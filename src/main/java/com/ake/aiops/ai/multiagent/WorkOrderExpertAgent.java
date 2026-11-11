package com.ake.aiops.ai.multiagent;

import com.ake.aiops.ai.prompt.PromptLoader;
import com.ake.aiops.ai.support.WriteClaimGuard;
import com.ake.aiops.ai.tool.DeviceQueryTools;
import com.ake.aiops.ai.tool.WorkOrderCommandTools;
import com.ake.aiops.ai.tool.WorkOrderQueryTools;
import com.ake.aiops.config.ChatOptionsFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

/**
 * 工单调度专家。
 *
 * <p>授权工具：工单读写 + 工程师查询 + 设备<b>只读</b>查询。
 * 给它设备只读权限是因为派单前需要知道设备属于哪个工种；
 * 但不给它 updateDeviceStatus —— 改设备状态是设备专家的职责。
 */
@Component
public class WorkOrderExpertAgent extends AbstractExpertAgent {

    public WorkOrderExpertAgent(ChatModel chatModel,
                                ChatOptionsFactory optionsFactory,
                                PromptLoader prompts,
                                WriteClaimGuard writeClaimGuard,
                                WorkOrderQueryTools workOrderQueryTools,
                                WorkOrderCommandTools workOrderCommandTools,
                                DeviceQueryTools deviceQueryTools) {
        super(chatModel, optionsFactory, prompts, writeClaimGuard,
                workOrderQueryTools, workOrderCommandTools, deviceQueryTools);
    }

    @Override
    public String id() {
        return "workorder-expert";
    }

    @Override
    public String displayName() {
        return "工单调度专家";
    }

    @Override
    public String capability() {
        return "负责工单流转与人力调配：查询工单、新建工单、派单给工程师、调整优先级、关单，"
                + "以及查询工程师名单/技能/当前负载。"
                + "凡是涉及「开单、派给谁、催办、关单、工单进展」的事项都归它。"
                + "它不做设备故障根因分析，也不做全局统计。";
    }

    @Override
    protected String promptName() {
        return "expert-workorder";
    }
}
