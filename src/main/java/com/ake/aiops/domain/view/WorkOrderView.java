package com.ake.aiops.domain.view;

import java.util.List;

/** 工单摘要视图。 */
public record WorkOrderView(
        String id,
        String title,
        String priority,
        String status,
        String statusLabel,
        String deviceId,
        String deviceName,
        String reporter,
        String assignee,
        String createdAt,
        String description,
        String resolution,
        List<String> traces) {
}
