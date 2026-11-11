package com.ake.aiops.repository;

import com.ake.aiops.domain.Device;
import com.ake.aiops.domain.DeviceStatus;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 设备仓库（内存实现）。
 *
 * <p>培训 Demo 不连数据库，重启即恢复初始数据 —— 这样课堂上可以反复演示
 * "AI 改坏了也不怕"。生产环境把这一层换成 MyBatis / JPA 即可，
 * 上层的 Service 与 AI 工具代码完全不用动。
 */
@Repository
public class DeviceRepository {

    private final Map<String, Device> store = new LinkedHashMap<>();

    public DeviceRepository() {
        seed();
    }

    private void seed() {
        save(new Device("DEV-AHU-01", "1号办公楼空调机组", "空调机组", "1号办公楼 屋顶机房", "暖通班", DeviceStatus.RUNNING)
                .metric("送风温度", "18.5℃").metric("回风温度", "24.2℃").metric("风机电流", "32A")
                .metric("过滤网压差", "180Pa").remark("运行平稳"));

        save(new Device("DEV-AHU-02", "2号办公楼空调机组", "空调机组", "2号办公楼 屋顶机房", "暖通班", DeviceStatus.FAULT)
                .metric("送风温度", "--").metric("风机电流", "0A").metric("故障代码", "E21")
                .alarm("2026-11-10 08:12 风机过载保护动作(E21)，机组停机")
                .alarm("2026-11-09 17:40 风机电流异常波动 45A→52A")
                .remark("已停机，待检修"));

        save(new Device("DEV-ELV-01", "A座客梯1号", "电梯", "A座 1号井道", "特种设备班", DeviceStatus.RUNNING)
                .metric("运行次数(今日)", "1268").metric("轿厢温度", "23℃").remark("正常"));

        save(new Device("DEV-ELV-02", "A座客梯2号", "电梯", "A座 2号井道", "特种设备班", DeviceStatus.MAINTENANCE)
                .metric("年检有效期至", "2026-11-30").remark("年度检验中，暂停使用"));

        save(new Device("DEV-PDGB-01", "B1低压配电柜", "配电柜", "B1 地下配电房", "强电班", DeviceStatus.WARNING)
                .metric("柜内温度", "78℃").metric("温度阈值", "70℃").metric("A相电流", "412A")
                .metric("B相电流", "438A").metric("C相电流", "405A").metric("母排温度", "81℃")
                .alarm("2026-11-10 09:05 柜内温度越上限 78℃ > 70℃")
                .alarm("2026-11-10 09:02 B相电流偏高 438A（额定 400A）")
                .remark("疑似散热风机故障或母排接触不良，需现场排查"));

        save(new Device("DEV-PUMP-01", "生活水泵1号", "水泵", "B1 生活泵房", "给排水班", DeviceStatus.RUNNING)
                .metric("出口压力", "0.42MPa").metric("运行频率", "45Hz").remark("正常"));

        save(new Device("DEV-PUMP-02", "生活水泵2号", "水泵", "B1 生活泵房", "给排水班", DeviceStatus.OFFLINE)
                .metric("出口压力", "--").metric("通讯状态", "中断")
                .alarm("2026-11-10 07:33 与BA系统通讯中断，最后上报压力 0.00MPa")
                .remark("备用泵，通讯中断原因待查"));

        save(new Device("DEV-CCTV-03", "园区东门枪机", "安防摄像", "园区东门 立杆", "弱电班", DeviceStatus.OFFLINE)
                .metric("在线状态", "离线").metric("最后心跳", "2026-11-09 22:41")
                .alarm("2026-11-09 22:41 设备离线，PoE 供电疑似中断")
                .remark("影响东门出入监控取证"));

        save(new Device("DEV-CHILL-01", "1号冷水机组", "冷水机组", "制冷站", "暖通班", DeviceStatus.WARNING)
                .metric("冷冻水出水温度", "7.8℃").metric("冷凝压力", "1.62MPa").metric("实时COP", "3.4")
                .metric("额定COP", "4.6").metric("蒸发器压差", "偏高")
                .alarm("2026-11-10 10:20 实时COP 3.4 低于额定值 30% 以上")
                .alarm("2026-11-10 06:15 压缩机异响（现场巡检记录）")
                .remark("能效明显下降，怀疑蒸发器结垢或冷媒不足"));

        save(new Device("DEV-LIGHT-05", "地下车库照明回路5", "照明回路", "B1 车库 C区", "强电班", DeviceStatus.RUNNING)
                .metric("回路电流", "12A").metric("亮灯率", "100%").remark("已修复"));
    }

    public void save(Device device) {
        store.put(device.getId(), device);
    }

    public Optional<Device> findById(String id) {
        if (id == null) {
            return Optional.empty();
        }
        Device hit = store.get(id.trim().toUpperCase());
        if (hit != null) {
            return Optional.of(hit);
        }
        // 容错：模型经常给的是设备名称而不是编号
        String key = id.trim();
        return store.values().stream()
                .filter(d -> d.getName().contains(key) || d.getLocation().contains(key))
                .findFirst();
    }

    public Collection<Device> findAll() {
        return store.values();
    }

    public List<Device> findAbnormal() {
        return store.values().stream()
                .filter(d -> d.getStatus().isAbnormal())
                .sorted(Comparator.comparing(d -> d.getStatus().name()))
                .toList();
    }

    public List<Device> findByStatus(DeviceStatus status) {
        return store.values().stream().filter(d -> d.getStatus() == status).toList();
    }

    public long count() {
        return store.size();
    }

    public long countByStatus(DeviceStatus status) {
        return store.values().stream().filter(d -> d.getStatus() == status).count();
    }
}
