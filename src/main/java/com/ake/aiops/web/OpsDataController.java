package com.ake.aiops.web;

import com.ake.aiops.domain.view.DeviceView;
import com.ake.aiops.domain.view.EngineerView;
import com.ake.aiops.domain.view.WorkOrderView;
import com.ake.aiops.service.DeviceService;
import com.ake.aiops.service.EngineerService;
import com.ake.aiops.service.OpsStatsService;
import com.ake.aiops.service.WorkOrderService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 业务数据面板接口（和 AI 无关的普通 REST 接口）。
 *
 * <p>它的存在是为了在课堂上<b>证明一件事</b>：AI 做的写操作是真的落到了业务层。
 * 左边让 AI 派个单，右边这个面板刷新一下就能看到工单状态和处理人变了 ——
 * 说明 AI 走的是和人工操作完全相同的 Service 通道，而不是自说自话。
 */
@RestController
@RequestMapping("/api/ops")
public class OpsDataController {

    private final DeviceService deviceService;
    private final WorkOrderService workOrderService;
    private final EngineerService engineerService;
    private final OpsStatsService statsService;

    public OpsDataController(DeviceService deviceService,
                             WorkOrderService workOrderService,
                             EngineerService engineerService,
                             OpsStatsService statsService) {
        this.deviceService = deviceService;
        this.workOrderService = workOrderService;
        this.engineerService = engineerService;
        this.statsService = statsService;
    }

    @GetMapping("/devices")
    public List<DeviceView> devices() {
        return deviceService.listAll().stream().map(deviceService::toView).toList();
    }

    @GetMapping("/work-orders")
    public List<WorkOrderView> workOrders() {
        return workOrderService.search(null, null, null).stream()
                .map(workOrderService::toView).toList();
    }

    @GetMapping("/engineers")
    public List<EngineerView> engineers() {
        return engineerService.candidates(null);
    }

    @GetMapping("/overview")
    public OpsStatsService.Overview overview() {
        return statsService.overview();
    }
}
