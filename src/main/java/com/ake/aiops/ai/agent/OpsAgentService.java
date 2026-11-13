package com.ake.aiops.ai.agent;

import com.ake.aiops.ai.memory.LongTermMemoryService;
import com.ake.aiops.ai.prompt.PromptLoader;
import com.ake.aiops.ai.support.Events;
import com.ake.aiops.ai.support.Json;
import com.ake.aiops.ai.support.PromptVars;
import com.ake.aiops.ai.support.SseStream;
import com.ake.aiops.ai.support.WriteAudit;
import com.ake.aiops.ai.support.WriteClaimGuard;
import com.ake.aiops.ai.tool.DeviceCommandTools;
import com.ake.aiops.ai.tool.DeviceQueryTools;
import com.ake.aiops.ai.tool.ObservableToolCallback;
import com.ake.aiops.ai.tool.ObservableTools;
import com.ake.aiops.ai.tool.StatsTools;
import com.ake.aiops.ai.tool.WorkOrderCommandTools;
import com.ake.aiops.ai.tool.WorkOrderQueryTools;
import com.ake.aiops.config.ChatOptionsFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;

/**
 * 模块二：单体 Agent（工具调用 / Function Calling）。
 *
 * <p><b>这是"AI 集成进业务系统"的分水岭。</b>
 * 模块一只能聊天，模块二能真正干活 —— 因为模型拿到了调用业务接口的能力。
 *
 * <p>工作原理（一轮对话内部发生了什么）：
 * <pre>
 *   1. 我们把「工具清单（名称+描述+JSON Schema 参数）」和用户问题一起发给模型
 *   2. 模型不直接回答，而是返回 finish_reason=tool_calls，指明"我要调 queryWorkOrders，参数是 {...}"
 *   3. Spring AI 拦下这个响应，反射调用对应的 @Tool 方法，也就是调用我们真实的 Service
 *   4. 把方法返回值作为 role=tool 的消息塞回对话，再次请求模型
 *   5. 模型基于真实数据生成最终回答（也可能继续要求调别的工具，回到第 2 步）
 * </pre>
 * 第 2~5 步的循环由 Spring AI 内部完成，业务代码只负责声明工具。
 *
 * <p><b>关键设计：工具只是 Service 的一层薄封装。</b>
 * 所有业务规则（工种匹配、负载上限、状态流转）都还在 Service 里，
 * 页面手工操作和 AI 调用走的是同一套逻辑，不会出现"AI 绕过了风控"。
 */
@Service
public class OpsAgentService {

    private static final Logger log = LoggerFactory.getLogger(OpsAgentService.class);
    private static final Duration TIMEOUT = Duration.ofMinutes(5);
    private static final String AGENT_NAME = "运维Agent";

    private final ChatClient chatClient;
    private final ChatMemory chatMemory;
    private final LongTermMemoryService longTermMemory;
    private final ChatOptionsFactory optionsFactory;
    private final PromptLoader prompts;
    private final ExecutorService aiExecutor;
    private final ObjectMapper mapper;
    private final WriteClaimGuard writeClaimGuard;

    private ToolCallback[] tools;

    public OpsAgentService(ChatModel chatModel,
                           ChatMemory chatMemory,
                           LongTermMemoryService longTermMemory,
                           ChatOptionsFactory optionsFactory,
                           PromptLoader prompts,
                           ExecutorService aiExecutor,
                           ObjectMapper mapper,
                           WriteClaimGuard writeClaimGuard,
                           DeviceQueryTools deviceQueryTools,
                           DeviceCommandTools deviceCommandTools,
                           WorkOrderQueryTools workOrderQueryTools,
                           WorkOrderCommandTools workOrderCommandTools,
                           StatsTools statsTools) {
        this.chatMemory = chatMemory;
        this.longTermMemory = longTermMemory;
        this.optionsFactory = optionsFactory;
        this.prompts = prompts;
        this.aiExecutor = aiExecutor;
        this.mapper = mapper;
        this.writeClaimGuard = writeClaimGuard;
        this.chatClient = ChatClient.builder(chatModel)
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .build();
        // 单体 Agent 拿到全部工具（读 + 写）；多智能体里的各专家只拿到自己那一份
        this.tools = ObservableTools.of(deviceQueryTools, deviceCommandTools,
                workOrderQueryTools, workOrderCommandTools, statsTools);
    }

    @PostConstruct
    void logTools() {
        log.info("[Agent] 已注册 {} 个工具: {}", tools.length, Arrays.toString(ObservableTools.names(tools)));
    }

    /** 供前端展示"这个 Agent 有哪些能力"。 */
    public String[] toolNames() {
        return ObservableTools.names(tools);
    }

    public SseStream chat(String conversationId, String userId, String message) {
        SseStream stream = SseStream.create(mapper);
        aiExecutor.submit(() -> run(stream, conversationId, userId, message));
        return stream;
    }

    private void run(SseStream stream, String conversationId, String userId, String message) {
        try {
            Map<String, String> vars = new HashMap<>();
            vars.put("CURRENT_TIME", PromptVars.now());
            vars.put("LONG_TERM_MEMORY", longTermMemory.renderForPrompt(userId));

            stream.emit("agent_start", new Events.AgentStart(
                    "ops-agent", AGENT_NAME, "单体 Agent，具备 " + tools.length + " 个业务工具", message));
            long t0 = System.currentTimeMillis();

            StringBuilder answer = new StringBuilder();
            WriteAudit audit = new WriteAudit();
            chatClient.prompt()
                    .options(optionsFactory.chat())
                    .messages(new SystemMessage(prompts.render("agent-system", vars)))
                    .user(message)
                    .toolCallbacks(tools)
                    // 把 SSE 流和审计对象塞进 ToolContext，
                    // ObservableToolCallback 就能一边推事件一边记账
                    .toolContext(Map.of(
                            ObservableToolCallback.CTX_STREAM, stream,
                            ObservableToolCallback.CTX_AGENT, AGENT_NAME,
                            ObservableToolCallback.CTX_AUDIT, audit))
                    .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                    .stream()
                    .content()
                    .doOnNext(token -> {
                        answer.append(token);
                        stream.delta(token);
                    })
                    .blockLast(TIMEOUT);

            String full = answer.toString();

            // 动作幻觉校验：模型声称"已派单/已创建"，但审计里没有与之对应的成功写操作 -> 当场纠正。
            // 这一步不能省，也不能只靠提示词约束：实测中模型确实出现过
            // "已派给陈明（1380-0004）" 而 assignWorkOrder 从未被调用的情况。
            Optional<String> correction = writeClaimGuard.verify(full, audit);
            if (correction.isPresent()) {
                log.warn("[动作幻觉已纠正] 实际调用={} 成功的写操作={} 被拒的写操作={}",
                        audit.calledTools(), audit.succeededWrites(), audit.rejectedWrites());
                stream.emit("warning", Map.of(
                        "message", "检测到动作幻觉：模型声称已执行写操作，但审计记录中没有与这些动作对应的成功写操作",
                        "calledTools", audit.calledTools(),
                        "rejectedWrites", audit.rejectedWrites()));
                stream.delta(correction.get());
                full = full + correction.get();
            }

            stream.emit("final", new Events.Final(full));
            stream.emit("agent_end", new Events.AgentEnd("ops-agent", AGENT_NAME,
                    Json.truncate(full, 400),
                    System.currentTimeMillis() - t0));

            int added = longTermMemory.learn(userId, message, full);
            if (added > 0) {
                stream.emit("memory", new Events.Memory(userId, longTermMemory.factsOf(userId)));
            }
            stream.complete();
        } catch (Exception e) {
            stream.fail(e);
        }
    }
}
