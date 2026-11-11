package com.ake.aiops.service;

import com.ake.aiops.domain.Device;
import com.ake.aiops.domain.DeviceStatus;
import com.ake.aiops.domain.view.DeviceView;
import com.ake.aiops.repository.DeviceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/** 设备业务服务。 */
@Service
public class DeviceService {

    private static final Logger log = LoggerFactory.getLogger(DeviceService.class);

    private final DeviceRepository repository;

    public DeviceService(DeviceRepository repository) {
        this.repository = repository;
    }

    public List<Device> listAll() {
        return List.copyOf(repository.findAll());
    }

    public List<Device> listAbnormal() {
        return repository.findAbnormal();
    }

    public Optional<Device> find(String idOrName) {
        return repository.findById(idOrName);
    }

    public Device require(String idOrName) {
        return repository.findById(idOrName).orElseThrow(() ->
                new BusinessRuleException("找不到设备「" + idOrName + "」，请先调用 listDevices 工具确认设备编号或名称"));
    }

    /**
     * 修改设备状态。
     *
     * <p>业务规则：故障/离线状态不允许直接跳回"运行中"，必须先经过人工确认
     * （Demo 里用 remark 非空来代表"已现场确认"）。这条规则故意写得严格一点，
     * 方便课堂上演示"模型被业务规则挡回来之后如何自我纠正"。
     */
    public Device updateStatus(String idOrName, DeviceStatus target, String remark) {
        Device device = require(idOrName);
        DeviceStatus old = device.getStatus();

        if (old.isAbnormal() && target == DeviceStatus.RUNNING
                && (remark == null || remark.isBlank())) {
            throw new BusinessRuleException(
                    "设备「" + device.getName() + "」当前为" + old.getLabel()
                            + "，恢复到运行中必须填写现场确认说明(remark)，这是安全合规要求");
        }
        device.setStatus(target);
        if (remark != null && !remark.isBlank()) {
            device.setRemark(remark.trim());
        }
        device.alarm("状态变更：" + old.getLabel() + " -> " + target.getLabel()
                + (remark == null || remark.isBlank() ? "" : "（" + remark.trim() + "）"));
        log.info("[业务写操作] 设备 {} 状态 {} -> {}", device.getId(), old, target);
        return device;
    }

    /** 状态分布统计。 */
    public Map<String, Long> statusDistribution() {
        return repository.findAll().stream()
                .collect(Collectors.groupingBy(d -> d.getStatus().getLabel(), Collectors.counting()));
    }

    public DeviceView toView(Device d) {
        return toView(d, null);
    }

    /**
     * @param highlightSkill 若非空，会在摘要里标注该设备所需工种，方便派单场景使用
     */
    public DeviceView toView(Device d, String highlightSkill) {
        String metrics = d.getMetrics().entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining("; "));
        String alarm = d.getRecentAlarms().isEmpty() ? null : d.getRecentAlarms().get(0);
        String remark = d.getRemark();
        if (highlightSkill != null) {
            remark = (remark == null || remark.isBlank() ? "" : remark + " | ") + "所需工种=" + highlightSkill;
        }
        return new DeviceView(d.getId(), d.getName(), d.getCategory(), d.getLocation(),
                d.getStatus().name(), d.getStatus().getLabel(), d.getOwnerTeam(),
                metrics.isEmpty() ? null : metrics, alarm, remark);
    }
}
