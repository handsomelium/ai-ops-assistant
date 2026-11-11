package com.ake.aiops.domain;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** 运维工单。 */
public class WorkOrder {

    private final String id;
    private String title;
    private String description;
    /** 关联设备，可为空（例如公共区域问题） */
    private String deviceId;
    private Priority priority;
    private WorkOrderStatus status;
    private String reporter;
    private String assignee;
    private final LocalDateTime createdAt;
    private LocalDateTime closedAt;
    private String resolution = "";
    /** 处理过程留痕，AI 每次写操作都会追加一条 */
    private final List<String> traces = new ArrayList<>();

    public WorkOrder(String id, String title, String description, String deviceId,
                     Priority priority, WorkOrderStatus status, String reporter,
                     String assignee, LocalDateTime createdAt) {
        this.id = id;
        this.title = title;
        this.description = description;
        this.deviceId = deviceId;
        this.priority = priority;
        this.status = status;
        this.reporter = reporter;
        this.assignee = assignee;
        this.createdAt = createdAt;
    }

    public void trace(String action) {
        this.traces.add(LocalDateTime.now().withNano(0) + " " + action);
    }

    public String getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(String deviceId) {
        this.deviceId = deviceId;
    }

    public Priority getPriority() {
        return priority;
    }

    public void setPriority(Priority priority) {
        this.priority = priority;
    }

    public WorkOrderStatus getStatus() {
        return status;
    }

    public void setStatus(WorkOrderStatus status) {
        this.status = status;
        if (status == WorkOrderStatus.CLOSED) {
            this.closedAt = LocalDateTime.now();
        }
    }

    public String getReporter() {
        return reporter;
    }

    public String getAssignee() {
        return assignee;
    }

    public void setAssignee(String assignee) {
        this.assignee = assignee;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getClosedAt() {
        return closedAt;
    }

    /** 仅供初始化种子数据时回填历史关单时间；正常流转由 setStatus(CLOSED) 自动记录。 */
    public void setClosedAt(LocalDateTime closedAt) {
        this.closedAt = closedAt;
    }

    public String getResolution() {
        return resolution;
    }

    public void setResolution(String resolution) {
        this.resolution = resolution;
    }

    public List<String> getTraces() {
        return traces;
    }
}
