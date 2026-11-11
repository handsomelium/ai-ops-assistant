package com.ake.aiops.ai.multiagent;

import com.ake.aiops.ai.prompt.PromptLoader;
import com.ake.aiops.ai.support.WriteClaimGuard;
import com.ake.aiops.ai.tool.DeviceQueryTools;
import com.ake.aiops.ai.tool.StatsTools;
import com.ake.aiops.ai.tool.WorkOrderQueryTools;
import com.ake.aiops.config.ChatOptionsFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

/**
 * 运营分析专家。
 *
 * <p><b>注意它的工具清单里没有任何写操作工具。</b>
 * 这就是最小权限原则在多智能体里的落地方式：
 * 分析岗只需要读数据，那就一个写工具都不给。
 * 即便模型产生幻觉想改数据，也根本没有对应的函数可调。
 */
@Component
public class AnalysisExpertAgent extends AbstractExpertAgent {

    public AnalysisExpertAgent(ChatModel chatModel,
                               ChatOptionsFactory optionsFactory,
                               PromptLoader prompts,
                               WriteClaimGuard writeClaimGuard,
                               StatsTools statsTools,
                               DeviceQueryTools deviceQueryTools,
                               WorkOrderQueryTools workOrderQueryTools) {
        super(chatModel, optionsFactory, prompts, writeClaimGuard,
                statsTools, deviceQueryTools, workOrderQueryTools);
    }

    @Override
    public String id() {
        return "analysis-expert";
    }

    @Override
    public String displayName() {
        return "运营分析专家";
    }

    @Override
    public String capability() {
        return "负责全局视角的数据分析：运维总览统计、工单积压与超时分析、"
                + "设备健康度评估、人力负载分析、风险优先级排序、日报周报的数据部分。"
                + "凡是涉及「整体情况怎么样、有多少、趋势如何、先处理哪个」的问题都归它。"
                + "它只读不写，不做单台设备的根因诊断，也不执行派单关单。";
    }

    @Override
    protected String promptName() {
        return "expert-analysis";
    }
}
