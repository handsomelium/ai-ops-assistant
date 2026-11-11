package com.ake.aiops.ai.tool;

import com.ake.aiops.service.OpsStatsService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

/**
 * 统计分析工具集。
 *
 * <p>注意这个工具<b>没有参数</b>——聚合口径由后端固定。
 * 这是一个重要的实践：涉及统计口径、财务数字、KPI 的计算，
 * 一定要在后端算好再把结果给模型，<b>绝不能让模型自己"心算"</b>。
 * 大模型做算术是不可靠的，让它算必然出错。
 */
@Component
public class StatsTools {

    private final OpsStatsService statsService;

    public StatsTools(OpsStatsService statsService) {
        this.statsService = statsService;
    }

    @Tool(name = "getOpsOverview",
            description = "获取园区运维总览统计数据，全部由后端实时聚合计算（不要让模型自己算数）。"
                    + "包含：工单总数及按状态分类计数、高优先级未关闭数、平均关单时长、超时未处理工单清单、"
                    + "设备总数与异常数、设备状态分布、异常设备清单、各工程师当前负载。"
                    + "写日报/周报、做整体健康度评估、回答「情况怎么样」这类概览问题时用它。")
    public OpsStatsService.Overview getOpsOverview() {
        return statsService.overview();
    }
}
