package com.ake.aiops.ai.multiagent;

/**
 * 一位专家的执行结果，交给报告 Agent 汇总。
 *
 * @param instruction 调度中枢下达的指令（一并给报告 Agent，
 *                    这样它能判断专家有没有答非所问）
 */
public record ExpertOutcome(String agentId, String displayName, String instruction,
                            String output, long elapsedMs) {
}
