package com.ake.aiops.ai.multiagent;

import com.ake.aiops.ai.support.Events;
import com.ake.aiops.ai.support.Json;
import com.ake.aiops.ai.support.SseStream;
import com.ake.aiops.config.AiOpsProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/**
 * 多智能体编排器（Supervisor 模式的"总控"）。
 *
 * <p>完整链路：
 * <pre>
 *   用户请求
 *     ↓
 *   ① 调度中枢  —— 输出结构化任务计划 JSON（不干活）
 *     ↓
 *   ② 专家 Agent × N —— 各自带自己的工具去查数据 / 执行写操作（可并行）
 *     ↓
 *   ③ 报告撰写 Agent —— 汇总各专家结论，产出最终回复（无工具）
 *     ↓
 *   SSE 全程把每一步推给前端，链路完全可见
 * </pre>
 *
 * <p>编排器本身<b>不含任何 AI 逻辑</b>，它就是个普通的 Java 调度程序。
 * 这一点很重要：多智能体系统的可靠性来自编排层的确定性，
 * 而不是指望模型"自己会协调"。生产环境里这一层通常换成
 * 状态机 / DAG 引擎（如 Spring AI Alibaba Graph、LangGraph），
 * 以便支持条件分支、循环、人工审批节点和失败重试。
 */
@Service
public class SupervisorService {

    private static final Logger log = LoggerFactory.getLogger(SupervisorService.class);

    private final DispatcherAgent dispatcher;
    private final ReportWriterAgent reportWriter;
    private final Map<String, ExpertAgent> experts = new LinkedHashMap<>();
    private final ExecutorService aiExecutor;
    private final AiOpsProperties properties;
    private final ObjectMapper mapper;

    public SupervisorService(DispatcherAgent dispatcher,
                             ReportWriterAgent reportWriter,
                             List<ExpertAgent> expertList,
                             ExecutorService aiExecutor,
                             AiOpsProperties properties,
                             ObjectMapper mapper) {
        this.dispatcher = dispatcher;
        this.reportWriter = reportWriter;
        this.aiExecutor = aiExecutor;
        this.properties = properties;
        this.mapper = mapper;
        expertList.forEach(e -> experts.put(e.id(), e));
        log.info("[编排器] 已注册专家：{}", experts.keySet());
    }

    /** 前端展示用：当前有哪些专家、各自能干什么、有哪些工具。 */
    public List<ExpertInfo> roster() {
        return experts.values().stream()
                .map(e -> new ExpertInfo(e.id(), e.displayName(), e.capability(), List.of(e.toolNames())))
                .toList();
    }

    public record ExpertInfo(String id, String displayName, String capability, List<String> tools) {
    }

    /** 发起一次多智能体协同，立刻返回 SSE 流。 */
    public SseStream run(String message) {
        SseStream stream = SseStream.create(mapper);
        aiExecutor.submit(() -> orchestrate(stream, message));
        return stream;
    }

    private void orchestrate(SseStream stream, String message) {
        try {
            // ① 调度
            TaskPlan plan = dispatcher.plan(message, stream);
            dispatcher.publishPlan(plan, stream);

            // ② 简单问题：调度中枢自己就答了，不必惊动专家
            List<TaskPlan.PlannedTask> tasks = resolveTasks(plan, stream);
            if (tasks.isEmpty()) {
                String answer = plan.directAnswer() == null || plan.directAnswer().isBlank()
                        ? plan.analysis()
                        : plan.directAnswer();
                stream.delta(answer == null ? "" : answer);
                stream.emit("final", new Events.Final(answer == null ? "" : answer));
                stream.complete();
                return;
            }

            // ③ 单专家且不需要汇总报告：让它直接流式输出，省掉一次模型调用
            if (tasks.size() == 1 && !plan.needReport()) {
                ExpertOutcome only = runOne(tasks.get(0), stream, true);
                stream.emit("final", new Events.Final(only.output()));
                stream.complete();
                return;
            }

            // ④ 多专家协同
            List<ExpertOutcome> outcomes = runExperts(tasks, stream);

            // ⑤ 汇总
            String report = reportWriter.writeReport(message, outcomes, stream);
            stream.emit("final", new Events.Final(report));
            stream.complete();
        } catch (Exception e) {
            stream.fail(e);
        }
    }

    /** 过滤掉调度中枢编造出来的、并不存在的 agentId，并去重。 */
    private List<TaskPlan.PlannedTask> resolveTasks(TaskPlan plan, SseStream stream) {
        Set<String> seen = new LinkedHashSet<>();
        List<TaskPlan.PlannedTask> valid = new ArrayList<>();
        for (TaskPlan.PlannedTask t : plan.safeTasks()) {
            String id = t.agentId() == null ? "" : t.agentId().trim();
            if (!experts.containsKey(id)) {
                log.warn("[编排器] 调度中枢指派了不存在的专家 agentId={}，已忽略", id);
                stream.emit("warning", Map.of("message", "调度中枢指派了不存在的专家 " + id + "，该任务已忽略"));
                continue;
            }
            if (!seen.add(id)) {
                log.warn("[编排器] 专家 {} 被重复指派，已合并", id);
                continue;
            }
            valid.add(t);
        }
        return valid;
    }

    private List<ExpertOutcome> runExperts(List<TaskPlan.PlannedTask> tasks, SseStream stream) {
        boolean parallel = properties.getMultiAgent().isParallelExperts() && tasks.size() > 1;
        log.info("[编排器] 开始执行 {} 个专家任务，模式={}", tasks.size(), parallel ? "并行" : "串行");
        stream.emit("phase", Map.of("phase", "experts", "parallel", parallel, "count", tasks.size()));

        if (!parallel) {
            List<ExpertOutcome> outcomes = new ArrayList<>();
            for (TaskPlan.PlannedTask t : tasks) {
                outcomes.add(runOne(t, stream, false));
            }
            return outcomes;
        }
        // 并行执行：互不依赖的子任务同时跑，端到端时间约等于最慢的那个专家
        List<CompletableFuture<ExpertOutcome>> futures = tasks.stream()
                .map(t -> CompletableFuture.supplyAsync(() -> runOne(t, stream, false), aiExecutor))
                .toList();
        return futures.stream().map(CompletableFuture::join).toList();
    }

    private ExpertOutcome runOne(TaskPlan.PlannedTask task, SseStream stream, boolean streaming) {
        ExpertAgent expert = experts.get(task.agentId().trim());
        long t0 = System.currentTimeMillis();
        String output = streaming
                ? expert.executeStreaming(task.instruction(), stream)
                : expert.execute(task.instruction(), stream);
        long elapsed = System.currentTimeMillis() - t0;
        log.info("[编排器] {} 完成，耗时 {}ms，输出 {} 字",
                expert.displayName(), elapsed, output == null ? 0 : output.length());
        return new ExpertOutcome(expert.id(), expert.displayName(),
                task.instruction(), Json.truncate(output, 6000), elapsed);
    }
}
