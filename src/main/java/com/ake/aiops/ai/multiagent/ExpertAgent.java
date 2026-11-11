package com.ake.aiops.ai.multiagent;

import com.ake.aiops.ai.support.SseStream;

/**
 * 专家 Agent 的统一契约。
 *
 * <p>多智能体系统能不能维护得住，关键在于<b>每个 Agent 的边界是否清晰</b>。
 * 这里用接口把三件事固定下来：我是谁（id/displayName）、我能干什么（capability）、
 * 我怎么执行（execute）。新增一个专家只要实现这个接口，
 * 调度中枢会自动把它纳入可调度范围，编排器不用改一行代码。
 */
public interface ExpertAgent {

    /** 唯一标识，调度 Agent 在计划里用它指定任务归属。 */
    String id();

    /** 展示名，给前端和日志用。 */
    String displayName();

    /**
     * 能力描述。会被拼进调度 Agent 的提示词，
     * 调度 Agent 完全靠这段文字决定"这件事该派给谁"。
     * 写得越具体，路由准确率越高。
     */
    String capability();

    /** 该专家被授权使用的工具名，用于前端展示与权限审计。 */
    String[] toolNames();

    /**
     * 非流式执行：结果返回给编排器，用于后续汇总。
     * 过程事件（工具调用等）仍会实时推给前端。
     */
    String execute(String instruction, SseStream stream);

    /**
     * 流式执行：结果 token 直接推给前端。
     * 用于"只有一个专家、不需要汇总报告"的场景，省掉一次模型调用。
     */
    String executeStreaming(String instruction, SseStream stream);
}
