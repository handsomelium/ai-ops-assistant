package com.ake.aiops.ai.tool;

import com.ake.aiops.domain.Priority;
import com.ake.aiops.domain.WorkOrder;
import com.ake.aiops.domain.view.WorkOrderView;
import com.ake.aiops.service.BusinessRuleException;
import com.ake.aiops.service.WorkOrderService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 工单域 —— 写操作工具。
 *
 * <p>这几个工具是整个 Demo 里"AI 真正改业务数据"的地方。
 * 注意方法体极薄：只做参数归一化，然后直接调 Service。
 * <b>所有校验都在 Service 里</b>，所以页面手工操作和 AI 调用受同一套规则约束。
 *
 * <p>description 里必须写清业务约束，这不是废话 ——
 * 模型读到约束后会主动规避，能显著减少"调用被拒 → 重试"的往返次数，省时省钱。
 */
@Component
public class WorkOrderCommandTools {

    private final WorkOrderService workOrderService;

    public WorkOrderCommandTools(WorkOrderService workOrderService) {
        this.workOrderService = workOrderService;
    }

    @Tool(name = "createWorkOrder",
            description = "【写操作】新建运维工单。业务约束：①同一设备已有未关闭工单时会被判定为重复报修而拒绝，"
                    + "此时应改用 changeWorkOrderPriority 升级已有工单；"
                    + "②关联设备处于故障(FAULT)状态时，优先级不允许为 LOW。")
    public WorkOrderView createWorkOrder(
            @ToolParam(description = "工单标题，一句话概括问题，例如「B1配电柜柜内温度越限」") String title,
            @ToolParam(required = false, description = "问题详细描述，包含现象、发现时间、影响范围等") String description,
            @ToolParam(required = false, description = "关联设备编号或名称。不确定时先用 listDevices 查询") String deviceId,
            @ToolParam(required = false, description = "优先级：HIGH、MEDIUM、LOW。不填默认 MEDIUM") String priority,
            @ToolParam(required = false, description = "报修人/报修来源，不填默认为「AI助手代录」") String reporter) {

        WorkOrder created = workOrderService.create(title, description, deviceId,
                ToolParams.parsePriorityOr(priority, Priority.MEDIUM), reporter);
        return workOrderService.toView(created);
    }

    @Tool(name = "assignWorkOrder",
            description = "【写操作】把工单派给指定工程师。业务约束：①工单不能已关闭；②工程师必须在岗；"
                    + "③工程师必须具备该设备要求的工种资质（安全合规硬要求）；④在手工单数不能超过上限。"
                    + "engineerName 留空则由系统按「工种匹配 + 负载最低」自动推荐。"
                    + "被拒绝时返回信息里会写明原因，请据此调用 listEngineers 重新选人。")
    public WorkOrderView assignWorkOrder(
            @ToolParam(description = "工单号，例如 WO-2026-0001") String workOrderId,
            @ToolParam(required = false, description = "工程师姓名。留空表示让系统自动推荐最合适的人选")
            String engineerName) {
        return workOrderService.toView(workOrderService.assign(workOrderId, engineerName));
    }

    @Tool(name = "changeWorkOrderPriority",
            description = "【写操作】调整工单优先级。当发现某设备重复报修、或问题升级需要提级处理时使用。")
    public WorkOrderView changeWorkOrderPriority(
            @ToolParam(description = "工单号") String workOrderId,
            @ToolParam(description = "新的优先级：HIGH、MEDIUM、LOW") String priority) {
        Priority p = ToolParams.parsePriority(priority);
        if (p == null) {
            throw new BusinessRuleException(
                    "priority 参数无法识别：" + priority + "，合法值为 HIGH/MEDIUM/LOW");
        }
        return workOrderService.toView(workOrderService.changePriority(workOrderId, p));
    }

    @Tool(name = "closeWorkOrder",
            description = "【写操作】关闭工单。业务约束：①必须已派单（有处理人）；②必须填写处理结果 resolution；"
                    + "③关联设备若仍处于故障或离线状态，不允许关单，需先用 updateDeviceStatus 恢复设备。")
    public WorkOrderView closeWorkOrder(
            @ToolParam(description = "工单号") String workOrderId,
            @ToolParam(description = "处理结果说明，写清楚做了什么、当前状态如何。这是运维台账的必填项")
            String resolution) {
        return workOrderService.toView(workOrderService.close(workOrderId, resolution));
    }
}
