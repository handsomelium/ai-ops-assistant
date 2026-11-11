package com.ake.aiops.ai.tool;

import com.ake.aiops.domain.Device;
import com.ake.aiops.domain.DeviceStatus;
import com.ake.aiops.domain.view.DeviceView;
import com.ake.aiops.service.BusinessRuleException;
import com.ake.aiops.service.DeviceService;
import com.ake.aiops.service.SkillRules;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/** 设备域 —— 写操作工具。只授权给需要执行处置动作的 Agent。 */
@Component
public class DeviceCommandTools {

    private final DeviceService deviceService;
    private final SkillRules skillRules;

    public DeviceCommandTools(DeviceService deviceService, SkillRules skillRules) {
        this.deviceService = deviceService;
        this.skillRules = skillRules;
    }

    @Tool(name = "updateDeviceStatus",
            description = "【写操作】修改设备运行状态，用于检修完成复电、现场确认恢复等场景。"
                    + "重要约束：把处于告警/故障/离线状态的设备恢复为 RUNNING 时，"
                    + "必须同时提供 remark 说明现场确认情况，否则会被业务规则拒绝。")
    public DeviceView updateDeviceStatus(
            @ToolParam(description = "设备编号，如 DEV-PUMP-02") String deviceId,
            @ToolParam(description = "目标状态，可选值：RUNNING、WARNING、FAULT、OFFLINE、MAINTENANCE")
            String status,
            @ToolParam(required = false,
                    description = "变更说明。从异常状态恢复为 RUNNING 时必填，需说明现场做了什么处理")
            String remark) {
        DeviceStatus target = ToolParams.parseStatus(status);
        if (target == null) {
            throw new BusinessRuleException(
                    "status 参数无法识别：" + status + "，合法值为 RUNNING/WARNING/FAULT/OFFLINE/MAINTENANCE");
        }
        Device d = deviceService.updateStatus(deviceId, target, remark);
        return deviceService.toView(d, skillRules.requiredSkillOf(d.getCategory()));
    }
}
