package com.ake.aiops.ai.tool;

import com.ake.aiops.domain.Priority;
import com.ake.aiops.domain.WorkOrder;
import com.ake.aiops.domain.WorkOrderStatus;
import com.ake.aiops.domain.view.EngineerView;
import com.ake.aiops.domain.view.WorkOrderView;
import com.ake.aiops.service.EngineerService;
import com.ake.aiops.service.WorkOrderService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

/** 工单域 —— 只读工具。 */
@Component
public class WorkOrderQueryTools {

    private final WorkOrderService workOrderService;
    private final EngineerService engineerService;

    public WorkOrderQueryTools(WorkOrderService workOrderService, EngineerService engineerService) {
        this.workOrderService = workOrderService;
        this.engineerService = engineerService;
    }

    @Tool(name = "queryWorkOrders",
            description = "查询运维工单列表。支持按状态、优先级、关键字组合过滤，结果已按"
                    + "「未关闭优先 + 高优先级优先 + 时间倒序」排好，并附带分类计数。"
                    + "回答「当前有多少工单」「哪些单子没人接」这类问题用它。")
    public WorkOrderList queryWorkOrders(
            @ToolParam(required = false,
                    description = "工单状态：OPEN(待处理)、PROCESSING(处理中)、CLOSED(已关闭)、ACTIVE(未关闭的全部)。不填查全部")
            String status,
            @ToolParam(required = false, description = "优先级：HIGH(高)、MEDIUM(中)、LOW(低)。不填表示不过滤")
            String priority,
            @ToolParam(required = false, description = "关键字，匹配工单号、标题、描述、设备、处理人、报修人")
            String keyword) {

        boolean activeOnly = status != null && "ACTIVE".equalsIgnoreCase(status.trim());
        WorkOrderStatus st = ToolParams.parseWorkOrderStatus(status);
        Priority pr = ToolParams.parsePriority(priority);

        List<WorkOrder> list = workOrderService.search(st, pr, keyword);
        if (activeOnly) {
            list = list.stream().filter(o -> o.getStatus() != WorkOrderStatus.CLOSED).toList();
        }
        List<WorkOrderView> views = list.stream().map(workOrderService::toView).toList();
        return new WorkOrderList(views.size(), countBy(views, "OPEN"), countBy(views, "PROCESSING"),
                countBy(views, "CLOSED"), views);
    }

    @Tool(name = "getWorkOrderDetail",
            description = "查询单个工单的完整详情，包含问题描述、处理结果和处理过程留痕(traces)。"
                    + "要跟进某个具体工单、或者关单前确认处理过程时用它。")
    public WorkOrderView getWorkOrderDetail(
            @ToolParam(description = "工单号，例如 WO-2026-0001") String workOrderId) {
        return workOrderService.toView(workOrderService.require(workOrderId));
    }

    @Tool(name = "listEngineers",
            description = "查询维修工程师名单及其当前负载。返回每人的技能标签、是否在岗、"
                    + "手上未关闭工单数(activeOrders)、以及是否匹配目标工种(matched)。"
                    + "派单前必须先调用它，不要凭空指定人名。")
    public List<EngineerView> listEngineers(
            @ToolParam(required = false,
                    description = "目标工种，用于标记 matched 字段。可选：暖通、强电、弱电、电梯、给排水、特种设备。不填则不做匹配标记")
            String requiredSkill) {
        return engineerService.candidates(ToolParams.isBlank(requiredSkill) ? null : requiredSkill.trim());
    }

    private long countBy(List<WorkOrderView> views, String status) {
        return views.stream().filter(v -> status.equals(v.status())).count();
    }

    /** 工单查询结果。带上分类计数，省得模型自己去数（模型数数不可靠）。 */
    public record WorkOrderList(int total, long openCount, long processingCount, long closedCount,
                                List<WorkOrderView> orders) {
    }
}
