package com.ake.aiops.ai.multiagent;

import com.ake.aiops.ai.prompt.PromptLoader;
import com.ake.aiops.ai.support.Events;
import com.ake.aiops.ai.support.Json;
import com.ake.aiops.ai.support.PromptVars;
import com.ake.aiops.ai.support.SseStream;
import com.ake.aiops.config.ChatOptionsFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 调度中枢（Supervisor / Router）。
 *
 * <p>多智能体协同的第一种主流范式：<b>中心式调度</b>。
 * 一个"不干活的" Agent 负责理解意图、拆解任务、指派专家；
 * 专家各自干活；最后由汇总 Agent 出报告。
 *
 * <p>为什么要有这一层，而不是让一个大 Agent 挂上所有工具？
 * <ul>
 *   <li><b>上下文更短</b>：每个专家只看到自己那份指令和自己的工具，
 *       不像单 Agent 要把 11 个工具的 schema 全塞进 prompt。</li>
 *   <li><b>权限可隔离</b>：分析专家拿不到写工具，这是硬隔离不是提示词约束。</li>
 *   <li><b>可并行</b>：互不依赖的子任务能同时跑，端到端时间明显下降。</li>
 *   <li><b>提示词更专注</b>：每个专家的人设、纪律、输出格式都能写细，
 *       不会互相打架。</li>
 * </ul>
 *
 * <p>代价也要讲清楚：链路变长、调用次数变多（token 和延迟都涨）、
 * 调试更难（所以必须做全程可观测）。<b>能用单 Agent 解决的，不要上多智能体。</b>
 */
@Component
public class DispatcherAgent {

    public static final String ID = "dispatcher";
    public static final String NAME = "调度中枢";
    private static final Logger log = LoggerFactory.getLogger(DispatcherAgent.class);
    private static final int MAX_PARSE_RETRY = 2;

    private final ChatClient chatClient;
    private final ChatOptionsFactory optionsFactory;
    private final PromptLoader prompts;
    private final ObjectMapper mapper;
    private final String roster;

    /** Spring 会把所有 {@link ExpertAgent} 实现自动收集成 List 注入进来。 */
    public DispatcherAgent(ChatModel chatModel,
                           ChatOptionsFactory optionsFactory,
                           PromptLoader prompts,
                           ObjectMapper mapper,
                           List<ExpertAgent> experts) {
        this.chatClient = ChatClient.builder(chatModel).build();
        this.optionsFactory = optionsFactory;
        this.prompts = prompts;
        this.mapper = mapper;
        this.roster = buildRoster(experts);
        log.info("[调度中枢] 可调度专家 {} 位：{}", experts.size(),
                experts.stream().map(ExpertAgent::id).toList());
    }

    /** 生成专家名册，拼进调度提示词。新增专家会自动出现在这里。 */
    private String buildRoster(List<ExpertAgent> experts) {
        StringBuilder sb = new StringBuilder();
        for (ExpertAgent e : experts) {
            sb.append("- agentId=").append(e.id())
                    .append("｜").append(e.displayName())
                    .append("\n  职责：").append(e.capability())
                    .append("\n  可用工具：").append(String.join(", ", e.toolNames()))
                    .append('\n');
        }
        return sb.toString();
    }

    /**
     * 把用户请求拆解成任务计划。
     *
     * <p>用 {@code response_format=json_object} 强制 JSON 输出，
     * 再加一层容错解析和重试 —— 结构化输出这一步一旦不稳，
     * 后面整条编排链就全断了，所以必须做兜底。
     */
    public TaskPlan plan(String userMessage, SseStream stream) {
        stream.emit("agent_start", new Events.AgentStart(ID, NAME, "理解意图并拆解任务，不直接干活", userMessage));
        long t0 = System.currentTimeMillis();

        String system = prompts.render("dispatcher-system", Map.of(
                "CURRENT_TIME", PromptVars.now(),
                "EXPERT_ROSTER", roster));

        String raw = null;
        TaskPlan plan = null;
        for (int attempt = 1; attempt <= MAX_PARSE_RETRY && plan == null; attempt++) {
            try {
                raw = chatClient.prompt()
                        .options(optionsFactory.json())
                        .messages(new SystemMessage(system))
                        .user(userMessage)
                        .call()
                        .content();
                plan = parse(raw);
                if (plan == null) {
                    log.warn("[调度中枢] 第 {} 次输出无法解析，原文：{}", attempt, Json.truncate(raw, 300));
                }
            } catch (Exception e) {
                log.error("[调度中枢] 第 {} 次调用失败", attempt, e);
                raw = "调用失败：" + e.getMessage();
            }
        }

        long elapsed = System.currentTimeMillis() - t0;
        if (plan == null) {
            // 兜底：解析不出计划时，把模型原文直接展示给用户，绝不让流程崩在这里
            log.error("[调度中枢] 任务计划解析失败，降级为直接展示模型输出");
            plan = new TaskPlan("SIMPLE", "任务计划解析失败，已降级处理",
                    "【调度解析失败，以下是模型原始输出】\n" + Json.truncate(raw, 800),
                    false, List.of());
        }
        stream.emit("agent_end", new Events.AgentEnd(ID, NAME,
                "intent=" + plan.intent() + "，任务数=" + plan.safeTasks().size(), elapsed));
        return plan;
    }

    private TaskPlan parse(String raw) {
        String json = Json.extractObject(raw);
        if (json == null) {
            return null;
        }
        try {
            return mapper.readValue(json, TaskPlan.class);
        } catch (Exception e) {
            log.debug("计划 JSON 反序列化失败：{}", e.getMessage());
            return null;
        }
    }

    /** 把计划转成前端要展示的事件。 */
    public void publishPlan(TaskPlan plan, SseStream stream) {
        List<Events.PlannedTask> tasks = plan.safeTasks().stream()
                .map(t -> new Events.PlannedTask(t.agentId(), nameOf(t.agentId()), t.instruction()))
                .toList();
        stream.emit("plan", new Events.Plan(plan.intent(), plan.analysis(), tasks, plan.needReport()));
    }

    private String nameOf(String agentId) {
        return switch (agentId == null ? "" : agentId) {
            case "device-expert" -> "设备诊断专家";
            case "workorder-expert" -> "工单调度专家";
            case "analysis-expert" -> "运营分析专家";
            default -> agentId;
        };
    }
}
