package com.ake.aiops.ai.multiagent;

import com.ake.aiops.ai.prompt.PromptLoader;
import com.ake.aiops.ai.support.WriteClaimGuard;
import com.ake.aiops.ai.tool.DeviceCommandTools;
import com.ake.aiops.ai.tool.DeviceQueryTools;
import com.ake.aiops.config.ChatOptionsFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

/**
 * 设备诊断专家。
 *
 * <p>授权工具：设备只读查询 + 设备状态变更。
 * 拿不到任何工单工具 —— 它不能开单、不能派单，那是工单专家的职责。
 */
@Component
public class DeviceExpertAgent extends AbstractExpertAgent {

    public DeviceExpertAgent(ChatModel chatModel,
                             ChatOptionsFactory optionsFactory,
                             PromptLoader prompts,
                             WriteClaimGuard writeClaimGuard,
                             DeviceQueryTools deviceQueryTools,
                             DeviceCommandTools deviceCommandTools) {
        super(chatModel, optionsFactory, prompts, writeClaimGuard,
                deviceQueryTools, deviceCommandTools);
    }

    @Override
    public String id() {
        return "device-expert";
    }

    @Override
    public String displayName() {
        return "设备诊断专家";
    }

    @Override
    public String capability() {
        return "负责设备侧的一切：设备台账与实时指标查询、告警时间线梳理、故障根因分析、"
                + "设备健康度判断、设备状态变更（如检修后恢复运行）。"
                + "涉及具体某台设备「为什么坏、坏到什么程度、要不要停机」的问题都归它。"
                + "它不做派单、不做整体统计、不写最终报告。";
    }

    @Override
    protected String promptName() {
        return "expert-device";
    }
}
