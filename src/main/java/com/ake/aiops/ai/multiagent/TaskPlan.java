package com.ake.aiops.ai.multiagent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * 调度中枢输出的任务计划。
 *
 * <p>这是多智能体协同的"契约"：调度 Agent 不直接干活，
 * 它只输出这个结构化计划，编排器（{@link SupervisorService}）按计划调度专家。
 *
 * <p>加 {@link JsonIgnoreProperties} 是必要的 ——
 * 模型经常会自作主张多返回几个字段，不加就会反序列化失败。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TaskPlan(
        String intent,
        String analysis,
        String directAnswer,
        boolean needReport,
        List<PlannedTask> tasks) {

    /** 单个子任务。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PlannedTask(String agentId, String instruction) {
    }

    public boolean isSimple() {
        return !"COMPLEX".equalsIgnoreCase(intent == null ? "" : intent.trim());
    }

    public List<PlannedTask> safeTasks() {
        return tasks == null ? List.of() : tasks;
    }
}
