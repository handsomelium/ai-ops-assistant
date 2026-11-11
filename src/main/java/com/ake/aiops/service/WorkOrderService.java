package com.ake.aiops.service;

import com.ake.aiops.domain.Device;
import com.ake.aiops.domain.DeviceStatus;
import com.ake.aiops.domain.Engineer;
import com.ake.aiops.domain.Priority;
import com.ake.aiops.domain.WorkOrder;
import com.ake.aiops.domain.WorkOrderStatus;
import com.ake.aiops.domain.view.EngineerView;
import com.ake.aiops.domain.view.WorkOrderView;
import com.ake.aiops.repository.DeviceRepository;
import com.ake.aiops.repository.WorkOrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

/**
 * 工单业务服务。
 *
 * <p>这是整个 Demo 里最重要的一层：<b>AI 只能通过这里的方法改数据，
 * 而所有业务规则都写在这里</b>。模型可以自由决策"要不要派单、派给谁"，
 * 但"能不能这么派"由代码说了算。规则不通过时抛 {@link BusinessRuleException}，
 * 异常文本会被原样喂回模型，模型据此自我纠正。
 */
@Service
public class WorkOrderService {

    private static final Logger log = LoggerFactory.getLogger(WorkOrderService.class);
    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final WorkOrderRepository workOrderRepository;
    private final DeviceRepository deviceRepository;
    private final DeviceService deviceService;
    private final EngineerService engineerService;
    private final SkillRules skillRules;

    public WorkOrderService(WorkOrderRepository workOrderRepository,
                            DeviceRepository deviceRepository,
                            DeviceService deviceService,
                            EngineerService engineerService,
                            SkillRules skillRules) {
        this.workOrderRepository = workOrderRepository;
        this.deviceRepository = deviceRepository;
        this.deviceService = deviceService;
        this.engineerService = engineerService;
        this.skillRules = skillRules;
    }

    // ------------------------------------------------------------------
    //  查询
    // ------------------------------------------------------------------

    public List<WorkOrder> search(WorkOrderStatus status, Priority priority, String keyword) {
        return workOrderRepository.search(status, priority, keyword);
    }

    public Optional<WorkOrder> find(String id) {
        return workOrderRepository.findById(id);
    }

    public WorkOrder require(String id) {
        return workOrderRepository.findById(id).orElseThrow(() ->
                new BusinessRuleException("工单「" + id + "」不存在，请先调用 queryWorkOrders 工具确认工单号"));
    }

    public List<WorkOrder> openOrdersOfDevice(String deviceId) {
        return workOrderRepository.findByDeviceId(deviceId).stream()
                .filter(o -> o.getStatus() != WorkOrderStatus.CLOSED)
                .toList();
    }

    // ------------------------------------------------------------------
    //  写操作
    // ------------------------------------------------------------------

    /**
     * 创建工单。
     *
     * <p>业务规则：
     * <ol>
     *   <li>标题必填；</li>
     *   <li>指定了设备则设备必须存在；</li>
     *   <li>同一设备已有未关闭工单时视为重复报修，拒绝创建；</li>
     *   <li>设备处于「故障」状态时，优先级不得低于「中」——防止低优先级压单。</li>
     * </ol>
     */
    public WorkOrder create(String title, String description, String deviceId,
                            Priority priority, String reporter) {
        if (title == null || title.isBlank()) {
            throw new BusinessRuleException("工单标题(title)不能为空");
        }

        Device device = null;
        String normalizedDeviceId = null;
        if (deviceId != null && !deviceId.isBlank()) {
            device = deviceService.require(deviceId);
            normalizedDeviceId = device.getId();
        }

        if (device != null) {
            List<WorkOrder> dup = openOrdersOfDevice(device.getId());
            if (!dup.isEmpty()) {
                throw new BusinessRuleException(
                        "设备「" + device.getName() + "」已存在未关闭工单 "
                                + dup.stream().map(o -> o.getId() + "(" + o.getTitle() + ")")
                                .reduce((a, b) -> a + "、" + b).orElse("")
                                + "，属于重复报修。如需升级处理请改用 changeWorkOrderPriority 工具调整已有工单的优先级，不要重复开单");
            }
        }

        Priority effective = priority == null ? Priority.MEDIUM : priority;
        if (device != null && device.getStatus() == DeviceStatus.FAULT
                && effective == Priority.LOW) {
            throw new BusinessRuleException(
                    "设备「" + device.getName() + "」当前为故障状态，按运维规程工单优先级不得低于 MEDIUM，"
                            + "你提交的是 LOW，请调整后重试");
        }

        String id = workOrderRepository.nextId();
        WorkOrder order = new WorkOrder(id, title.trim(),
                description == null ? "" : description.trim(),
                normalizedDeviceId, effective, WorkOrderStatus.OPEN,
                reporter == null || reporter.isBlank() ? "AI助手代录" : reporter.trim(),
                null, LocalDateTime.now());
        order.trace("AI 创建工单，优先级 " + effective.getLabel());
        workOrderRepository.save(order);
        log.info("[业务写操作] 创建工单 {} - {}", id, title);
        return order;
    }

    /**
     * 派单。
     *
     * <p>业务规则：工单未关闭；工程师在岗；工种必须匹配设备类别；
     * 在手工单数未超上限。engineerName 传空则按规则自动推荐最合适的工程师。
     */
    public WorkOrder assign(String workOrderId, String engineerName) {
        WorkOrder order = require(workOrderId);
        if (order.getStatus() == WorkOrderStatus.CLOSED) {
            throw new BusinessRuleException("工单 " + workOrderId + " 已关闭，不能再派单");
        }

        final String requiredSkill = requiredSkillOf(order);

        Engineer engineer;
        if (engineerName == null || engineerName.isBlank()) {
            EngineerView recommended = engineerService.recommend(requiredSkill).orElseThrow(() ->
                    new BusinessRuleException(
                            "当前没有满足条件的工程师（工种=" + requiredSkill + "、在岗、负载未满）。"
                                    + "请调用 listEngineers 工具查看名单，或先协调其他班组"));
            engineer = engineerService.require(recommended.name());
        } else {
            engineer = engineerService.require(engineerName);
        }

        if (!engineer.isOnDuty()) {
            throw new BusinessRuleException("工程师 " + engineer.getName() + " 当前不在岗，请改派他人");
        }
        if (requiredSkill != null && !engineer.hasSkill(requiredSkill)) {
            throw new BusinessRuleException(
                    "工程师 " + engineer.getName() + " 的技能是 " + engineer.getSkills()
                            + "，不具备该设备要求的「" + requiredSkill + "」工种资质，出于安全合规不能派单。"
                            + "请调用 listEngineers 工具，选择 matched=true 的人员");
        }
        int load = engineerService.activeOrdersOf(engineer.getName());
        if (load >= skillRules.maxConcurrentOrders()) {
            throw new BusinessRuleException(
                    "工程师 " + engineer.getName() + " 手上已有 " + load + " 个未关闭工单，已达上限 "
                            + skillRules.maxConcurrentOrders() + "，请改派负载更低的人员");
        }

        order.setAssignee(engineer.getName());
        order.setStatus(WorkOrderStatus.PROCESSING);
        order.trace("派单给 " + engineer.getName() + "（" + String.join("/", engineer.getSkills())
                + "，电话 " + engineer.getPhone() + "）");
        log.info("[业务写操作] 工单 {} 派给 {}", workOrderId, engineer.getName());
        return order;
    }

    /** 调整优先级（用于重复报修时的升级处理）。 */
    public WorkOrder changePriority(String workOrderId, Priority priority) {
        WorkOrder order = require(workOrderId);
        if (order.getStatus() == WorkOrderStatus.CLOSED) {
            throw new BusinessRuleException("工单 " + workOrderId + " 已关闭，不能调整优先级");
        }
        Priority old = order.getPriority();
        order.setPriority(priority);
        order.trace("优先级 " + old.getLabel() + " -> " + priority.getLabel() + "（AI 调整）");
        log.info("[业务写操作] 工单 {} 优先级 {} -> {}", workOrderId, old, priority);
        return order;
    }

    /**
     * 关单。
     *
     * <p>业务规则：必须已有处理人；必须填写处理结果；关联设备若仍为故障/离线，不允许关单。
     */
    public WorkOrder close(String workOrderId, String resolution) {
        WorkOrder order = require(workOrderId);
        if (order.getStatus() == WorkOrderStatus.CLOSED) {
            throw new BusinessRuleException("工单 " + workOrderId + " 已经是关闭状态");
        }
        if (order.getAssignee() == null || order.getAssignee().isBlank()) {
            throw new BusinessRuleException("工单 " + workOrderId + " 尚未派单，没有处理人，不能关单");
        }
        if (resolution == null || resolution.isBlank()) {
            throw new BusinessRuleException("关单必须填写处理结果(resolution)，这是运维台账要求");
        }
        if (order.getDeviceId() != null) {
            Optional<Device> device = deviceRepository.findById(order.getDeviceId());
            if (device.isPresent() && (device.get().getStatus() == DeviceStatus.FAULT
                    || device.get().getStatus() == DeviceStatus.OFFLINE)) {
                throw new BusinessRuleException(
                        "关联设备「" + device.get().getName() + "」仍处于"
                                + device.get().getStatus().getLabel()
                                + "状态，不能关单。请先调用 updateDeviceStatus 工具把设备 "
                                + device.get().getId() + " 恢复为 RUNNING（需附现场确认说明），再调用 closeWorkOrder");
            }
        }

        order.setResolution(resolution.trim());
        order.setStatus(WorkOrderStatus.CLOSED);
        order.trace(order.getAssignee() + " 关闭工单：" + resolution.trim());
        log.info("[业务写操作] 工单 {} 已关闭", workOrderId);
        return order;
    }

    // ------------------------------------------------------------------
    //  视图转换
    // ------------------------------------------------------------------

    /** 根据工单关联设备的类别，推导出该工单要求的工种。无关联设备时返回 null（不做强制校验）。 */
    private String requiredSkillOf(WorkOrder order) {
        if (order.getDeviceId() == null) {
            return null;
        }
        return deviceRepository.findById(order.getDeviceId())
                .map(d -> skillRules.requiredSkillOf(d.getCategory()))
                .orElse(null);
    }

    public WorkOrderView toView(WorkOrder o) {
        String deviceName = null;
        if (o.getDeviceId() != null) {
            deviceName = deviceRepository.findById(o.getDeviceId()).map(Device::getName).orElse(null);
        }
        return new WorkOrderView(o.getId(), o.getTitle(), o.getPriority().name(),
                o.getStatus().name(), o.getStatus().getLabel(), o.getDeviceId(), deviceName,
                o.getReporter(), o.getAssignee(), o.getCreatedAt().format(DT),
                o.getDescription(),
                o.getResolution() == null || o.getResolution().isBlank() ? null : o.getResolution(),
                List.copyOf(o.getTraces()));
    }
}
