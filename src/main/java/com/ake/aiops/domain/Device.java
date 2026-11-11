package com.ake.aiops.domain;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 园区设备。
 *
 * <p>真实项目里这张表在数据库里、由采集网关实时刷新；Demo 用内存对象代替，
 * 但对 AI 来说没有区别 —— 它看到的只是工具方法返回的结构化数据。
 */
public class Device {

    private final String id;
    private final String name;
    /** 设备类别：空调机组 / 电梯 / 配电柜 / 水泵 / 摄像头 / 冷水机组 / 照明回路 */
    private final String category;
    private final String location;
    /** 责任人（班组） */
    private final String ownerTeam;

    private DeviceStatus status;
    /** 实时运行指标，例如 送风温度、柜内温度、COP、电流 */
    private final Map<String, String> metrics = new LinkedHashMap<>();
    /** 最近告警，倒序 */
    private final List<String> recentAlarms = new ArrayList<>();
    private LocalDateTime lastUpdate;
    private String remark = "";

    public Device(String id, String name, String category, String location,
                  String ownerTeam, DeviceStatus status) {
        this.id = id;
        this.name = name;
        this.category = category;
        this.location = location;
        this.ownerTeam = ownerTeam;
        this.status = status;
        this.lastUpdate = LocalDateTime.now();
    }

    public Device metric(String key, String value) {
        this.metrics.put(key, value);
        return this;
    }

    public Device alarm(String alarm) {
        this.recentAlarms.add(0, alarm);
        return this;
    }

    public Device remark(String remark) {
        this.remark = remark;
        return this;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getCategory() {
        return category;
    }

    public String getLocation() {
        return location;
    }

    public String getOwnerTeam() {
        return ownerTeam;
    }

    public DeviceStatus getStatus() {
        return status;
    }

    public void setStatus(DeviceStatus status) {
        this.status = status;
        this.lastUpdate = LocalDateTime.now();
    }

    public Map<String, String> getMetrics() {
        return metrics;
    }

    public List<String> getRecentAlarms() {
        return recentAlarms;
    }

    public LocalDateTime getLastUpdate() {
        return lastUpdate;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }
}
