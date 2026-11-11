package com.ake.aiops.service;

import com.ake.aiops.domain.Priority;
import com.ake.aiops.domain.WorkOrder;
import com.ake.aiops.domain.WorkOrderStatus;
import com.ake.aiops.domain.view.EngineerView;
import com.ake.aiops.repository.DeviceRepository;
import com.ake.aiops.repository.WorkOrderRepository;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** 运维统计服务：给"报告类"工具提供聚合数据。 */
@Service
public class OpsStatsService {

    private final WorkOrderRepository workOrderRepository;
    private final DeviceRepository deviceRepository;
    private final DeviceService deviceService;
    private final EngineerService engineerService;

    public OpsStatsService(WorkOrderRepository workOrderRepository,
                           DeviceRepository deviceRepository,
                           DeviceService deviceService,
                           EngineerService engineerService) {
        this.workOrderRepository = workOrderRepository;
        this.deviceRepository = deviceRepository;
        this.deviceService = deviceService;
        this.engineerService = engineerService;
    }

    public Overview overview() {
        List<WorkOrder> all = List.copyOf(workOrderRepository.findAll());
        long open = all.stream().filter(o -> o.getStatus() == WorkOrderStatus.OPEN).count();
        long processing = all.stream().filter(o -> o.getStatus() == WorkOrderStatus.PROCESSING).count();
        long closed = all.stream().filter(o -> o.getStatus() == WorkOrderStatus.CLOSED).count();
        long highActive = all.stream()
                .filter(o -> o.getStatus() != WorkOrderStatus.CLOSED)
                .filter(o -> o.getPriority() == Priority.HIGH)
                .count();

        List<WorkOrder> overdue = all.stream()
                .filter(o -> o.getStatus() == WorkOrderStatus.OPEN)
                .filter(o -> Duration.between(o.getCreatedAt(), LocalDateTime.now()).toHours() >= 6)
                .toList();

        double avgCloseHours = all.stream()
                .filter(o -> o.getClosedAt() != null)
                .mapToLong(o -> Duration.between(o.getCreatedAt(), o.getClosedAt()).toMinutes())
                .average().orElse(0) / 60.0;

        Map<String, Long> deviceDist = deviceService.statusDistribution();
        List<String> abnormalDevices = deviceRepository.findAbnormal().stream()
                .map(d -> d.getId() + " " + d.getName() + "(" + d.getStatus().getLabel() + ")")
                .toList();
        List<EngineerView> loads = engineerService.candidates(null);

        return new Overview(all.size(), open, processing, closed, highActive,
                round1(avgCloseHours),
                overdue.stream().map(o -> o.getId() + " " + o.getTitle()
                        + "（已挂起 " + Duration.between(o.getCreatedAt(), LocalDateTime.now()).toHours() + " 小时）").toList(),
                deviceRepository.count(), deviceRepository.findAbnormal().size(),
                deviceDist, abnormalDevices,
                loads.stream().map(v -> v.name() + " 在手工单=" + v.activeOrders() + " 技能=" + v.skills()).toList());
    }

    private double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }

    /**
     * @param total          工单总数
     * @param avgCloseHours  平均关单时长（小时）
     * @param overdue        超时未处理工单（OPEN 且超过 6 小时）
     */
    public record Overview(long total, long open, long processing, long closed, long highActive,
                           double avgCloseHours, List<String> overdue,
                           long deviceTotal, long deviceAbnormal,
                           Map<String, Long> deviceStatusDistribution,
                           List<String> abnormalDevices,
                           List<String> engineerLoads) {
    }
}
