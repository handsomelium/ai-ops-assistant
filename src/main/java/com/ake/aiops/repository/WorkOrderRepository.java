package com.ake.aiops.repository;

import com.ake.aiops.domain.Priority;
import com.ake.aiops.domain.WorkOrder;
import com.ake.aiops.domain.WorkOrderStatus;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/** 工单仓库（内存实现）。 */
@Repository
public class WorkOrderRepository {

    private final Map<String, WorkOrder> store = new LinkedHashMap<>();
    private final AtomicInteger seq = new AtomicInteger(0);

    public WorkOrderRepository() {
        seed();
    }

    private void seed() {
        save(new WorkOrder("WO-2026-0001", "B1低压配电柜柜内温度越限",
                "BA系统上报 B1 低压配电柜柜内温度 78℃，超过 70℃ 上限；同时 B 相电流 438A 偏高。需现场排查散热风机与母排接头。",
                "DEV-PDGB-01", Priority.HIGH, WorkOrderStatus.OPEN, "BA系统自动告警", null,
                LocalDateTime.now().minusHours(3)));

        save(new WorkOrder("WO-2026-0002", "2号办公楼空调机组风机过载停机",
                "2号办公楼空调机组报 E21 风机过载保护，机组已停机，影响 2 号楼供冷。",
                "DEV-AHU-02", Priority.HIGH, WorkOrderStatus.PROCESSING, "物业前台转报", "李伟",
                LocalDateTime.now().minusHours(6)));

        save(new WorkOrder("WO-2026-0003", "园区东门监控摄像头离线",
                "东门枪机自昨晚 22:41 起离线，PoE 供电疑似中断，影响出入监控取证。",
                "DEV-CCTV-03", Priority.MEDIUM, WorkOrderStatus.OPEN, "安保部 周队长", null,
                LocalDateTime.now().minusHours(11)));

        save(new WorkOrder("WO-2026-0004", "生活水泵2号通讯中断",
                "2号生活水泵与 BA 系统通讯中断，无法监测运行状态。当前 1 号泵单泵运行，存在供水风险。",
                "DEV-PUMP-02", Priority.MEDIUM, WorkOrderStatus.OPEN, "BA系统自动告警", null,
                LocalDateTime.now().minusHours(8)));

        save(new WorkOrder("WO-2026-0005", "A座2号客梯年度检验配合",
                "配合特检院完成 A 座 2 号客梯年度检验，检验有效期至 2026-11-30。",
                "DEV-ELV-02", Priority.LOW, WorkOrderStatus.PROCESSING, "设备部计划性任务", "王芳",
                LocalDateTime.now().minusDays(2)));

        save(new WorkOrder("WO-2026-0006", "地下车库C区照明部分不亮",
                "B1 车库 C 区照明回路 5 有 6 盏灯不亮，已更换驱动电源并恢复。",
                "DEV-LIGHT-05", Priority.LOW, WorkOrderStatus.CLOSED, "保洁部 反馈", "张建国",
                LocalDateTime.now().minusDays(3)));

        save(new WorkOrder("WO-2026-0007", "1号冷水机组能效下降并伴异响",
                "1号冷水机组实时 COP 3.4，较额定 4.6 下降 26%；现场巡检记录压缩机异响。需判断是蒸发器结垢还是冷媒泄漏。",
                "DEV-CHILL-01", Priority.HIGH, WorkOrderStatus.OPEN, "能源管理岗 巡检", null,
                LocalDateTime.now().minusHours(2)));

        WorkOrder closedDemo = store.get("WO-2026-0006");
        closedDemo.setResolution("更换 6 只 LED 驱动电源，回路恢复正常，亮灯率 100%。");
        // 构造时直接给 CLOSED 不会触发 setStatus 里的关单时间记录，这里回填一个历史时间，
        // 否则统计出来的"平均关单时长"会是 0
        closedDemo.setClosedAt(closedDemo.getCreatedAt().plusHours(5));
        closedDemo.trace("张建国 关闭工单：更换驱动电源后复测正常");
        store.get("WO-2026-0002").trace("派单给 李伟（暖通班），已到场排查");
        store.get("WO-2026-0005").trace("派单给 王芳（特种设备班），特检院预约 11-12 上午");
    }

    public void save(WorkOrder order) {
        store.put(order.getId(), order);
    }

    /** 生成下一个工单号，格式 WO-{年}-{4位序号}。 */
    public String nextId() {
        int year = LocalDateTime.now().getYear();
        int n;
        synchronized (seq) {
            // 从已有数据里推断当前最大序号，避免重启后重号
            int max = store.keySet().stream()
                    .filter(k -> k.startsWith("WO-" + year + "-"))
                    .map(k -> {
                        try {
                            return Integer.parseInt(k.substring(k.lastIndexOf('-') + 1));
                        } catch (NumberFormatException e) {
                            return 0;
                        }
                    })
                    .max(Integer::compareTo).orElse(0);
            n = Math.max(max, seq.get()) + 1;
            seq.set(n);
        }
        return String.format("WO-%d-%04d", year, n);
    }

    public Optional<WorkOrder> findById(String id) {
        if (id == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(store.get(id.trim().toUpperCase()));
    }

    public Collection<WorkOrder> findAll() {
        return store.values();
    }

    public List<WorkOrder> findByStatus(WorkOrderStatus status) {
        return store.values().stream().filter(o -> o.getStatus() == status).toList();
    }

    public List<WorkOrder> findByAssignee(String assignee) {
        return store.values().stream()
                .filter(o -> assignee != null && assignee.equals(o.getAssignee()))
                .filter(o -> o.getStatus() != WorkOrderStatus.CLOSED)
                .toList();
    }

    public List<WorkOrder> findByDeviceId(String deviceId) {
        return store.values().stream().filter(o -> deviceId != null && deviceId.equals(o.getDeviceId())).toList();
    }

    /** 组合查询：状态 / 优先级 / 关键字，任一为空即不过滤。 */
    public List<WorkOrder> search(WorkOrderStatus status, Priority priority, String keyword) {
        return store.values().stream()
                .filter(o -> status == null || o.getStatus() == status)
                .filter(o -> priority == null || o.getPriority() == priority)
                .filter(o -> isBlank(keyword) || matches(o, keyword.trim()))
                .sorted(Comparator
                        .comparing((WorkOrder o) -> o.getStatus() == WorkOrderStatus.CLOSED ? 1 : 0)
                        .thenComparing(o -> -o.getPriority().getWeight())
                        .thenComparing(WorkOrder::getCreatedAt, Comparator.reverseOrder()))
                .toList();
    }

    private boolean matches(WorkOrder o, String kw) {
        return contains(o.getId(), kw) || contains(o.getTitle(), kw)
                || contains(o.getDescription(), kw) || contains(o.getDeviceId(), kw)
                || contains(o.getAssignee(), kw) || contains(o.getReporter(), kw);
    }

    private boolean contains(String src, String kw) {
        return src != null && src.toLowerCase().contains(kw.toLowerCase());
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    public long countByStatus(WorkOrderStatus status) {
        return store.values().stream().filter(o -> o.getStatus() == status).count();
    }
}
