package com.ake.aiops.domain;

/** 工单状态流转：待处理 -> 处理中 -> 已关闭。 */
public enum WorkOrderStatus {

    OPEN("待处理"),
    PROCESSING("处理中"),
    CLOSED("已关闭");

    private final String label;

    WorkOrderStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
