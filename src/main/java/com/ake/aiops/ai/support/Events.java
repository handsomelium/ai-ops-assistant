package com.ake.aiops.ai.support;

import java.util.List;

/** 前后端约定的 SSE 事件负载。全部用 record，Jackson 直接序列化。 */
public final class Events {

    private Events() {
    }

    /** 模型输出的文本增量（type=delta） */
    public record Delta(String content) {
    }

    /** Agent 决定调用某个工具（type=tool_call） */
    public record ToolCall(String tool, String args, String agent) {
    }

    /** 工具执行完成（type=tool_result） */
    public record ToolResult(String tool, String summary, long elapsedMs, boolean success, String agent) {
    }

    /** 某个 Agent 开始工作（type=agent_start） */
    public record AgentStart(String agentId, String agentName, String role, String input) {
    }

    /** 某个 Agent 工作结束（type=agent_end） */
    public record AgentEnd(String agentId, String agentName, String output, long elapsedMs) {
    }

    /** 调度 Agent 给出的任务拆解（type=plan） */
    public record Plan(String intent, String analysis, List<PlannedTask> tasks, boolean needReport) {
    }

    /** 计划中的单个子任务 */
    public record PlannedTask(String agentId, String agentName, String instruction) {
    }

    /** 最终答案（type=final），流式场景下也会在最后汇总一次 */
    public record Final(String content) {
    }

    /** 长期记忆变动（type=memory） */
    public record Memory(String userId, List<String> facts) {
    }

    /** 会话记忆快照（type=session_memory） */
    public record SessionMemory(String conversationId, int size, List<String> preview) {
    }

    /** 出错（type=error） */
    public record Error(String message) {
    }

    /** 结束（type=done） */
    public record Done(long totalMs) {
    }
}
