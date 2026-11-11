package com.ake.aiops.ai.tool;

import com.ake.aiops.domain.Device;
import com.ake.aiops.domain.DeviceStatus;
import com.ake.aiops.domain.view.DeviceView;
import com.ake.aiops.service.DeviceService;
import com.ake.aiops.service.SkillRules;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 设备域 —— 只读工具。
 *
 * <p><b>为什么把查询和写操作拆成两个类？</b>
 * 因为多智能体场景下要做<b>最小权限控制</b>：
 * "运营分析专家"只需要读数据，就不该拿到能改设备状态的工具。
 * 工具集是按 Agent 逐个装配的，拆分之后才能精细授权。
 * 这也是防止"模型自作主张改了生产数据"最有效的一道闸。
 *
 * <p>另外注意每个 description —— 它们不是注释，而是<b>直接进模型上下文的函数说明书</b>，
 * 模型完全靠这些文字决定调哪个工具、参数怎么填。写含糊了模型就会乱调。
 */
@Component
public class DeviceQueryTools {

    private final DeviceService deviceService;
    private final SkillRules skillRules;

    public DeviceQueryTools(DeviceService deviceService, SkillRules skillRules) {
        this.deviceService = deviceService;
        this.skillRules = skillRules;
    }

    @Tool(name = "listDevices",
            description = "查询园区设备台账。可按运行状态过滤，也可按名称/位置/类别关键字模糊搜索。"
                    + "返回设备编号、名称、类别、位置、状态、责任班组、关键指标摘要。"
                    + "不确定设备编号时先用这个工具查。")
    public List<DeviceView> listDevices(
            @ToolParam(required = false,
                    description = "设备状态过滤，可选值：RUNNING(运行中)、WARNING(告警)、FAULT(故障)、"
                            + "OFFLINE(离线)、MAINTENANCE(保养中)、ABNORMAL(所有异常状态)。不填表示查全部")
            String status,
            @ToolParam(required = false,
                    description = "关键字，匹配设备名称、位置或类别，例如 电梯、B1、空调。不填表示不过滤")
            String keyword) {

        DeviceStatus parsed = ToolParams.parseStatus(status);
        boolean abnormalOnly = status != null && "ABNORMAL".equalsIgnoreCase(status.trim());
        String kw = ToolParams.isBlank(keyword) ? null : keyword.trim();

        return deviceService.listAll().stream()
                .filter(d -> {
                    if (abnormalOnly) {
                        return d.getStatus().isAbnormal();
                    }
                    return parsed == null || d.getStatus() == parsed;
                })
                .filter(d -> kw == null
                        || d.getName().contains(kw)
                        || d.getLocation().contains(kw)
                        || d.getCategory().contains(kw)
                        || d.getId().equalsIgnoreCase(kw))
                .map(this::view)
                .toList();
    }

    @Tool(name = "getDeviceDetail",
            description = "查询单台设备的完整详情，包含全部实时指标和最近告警记录（按时间倒序）。"
                    + "做故障诊断、根因分析时必须先调这个工具拿到原始数据，不要凭设备名称猜测。")
    public DeviceDetail getDeviceDetail(
            @ToolParam(description = "设备编号（如 DEV-PDGB-01）或设备名称（如 B1低压配电柜）")
            String deviceId) {
        Device d = deviceService.require(deviceId);
        String requiredSkill = skillRules.requiredSkillOf(d.getCategory());
        List<String> alarms = d.getRecentAlarms().isEmpty()
                ? List.of("无告警记录")
                : List.copyOf(d.getRecentAlarms());
        return new DeviceDetail(view(d), alarms, d.getLastUpdate().toString(), requiredSkill);
    }

    @Tool(name = "listAbnormalDevices",
            description = "列出当前所有异常设备（状态为告警、故障或离线）。"
                    + "巡检、日报、健康度评估类问题优先用这个工具，比 listDevices 更省 token。")
    public AbnormalSummary listAbnormalDevices() {
        List<DeviceView> views = deviceService.listAbnormal().stream().map(this::view).toList();
        return new AbnormalSummary(views.size(), (int) deviceService.listAll().size(), views);
    }

    private DeviceView view(Device d) {
        return deviceService.toView(d, skillRules.requiredSkillOf(d.getCategory()));
    }

    /** 设备详情。 */
    public record DeviceDetail(DeviceView device, List<String> recentAlarms,
                               String lastUpdate, String requiredSkill) {
    }

    /** 异常设备汇总。 */
    public record AbnormalSummary(int abnormalCount, int totalCount, List<DeviceView> devices) {
    }
}
