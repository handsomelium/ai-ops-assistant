package com.ake.aiops.domain;

/** 设备运行状态。 */
public enum DeviceStatus {

    /** 正常运行 */
    RUNNING("运行中"),
    /** 告警：仍在运行，但指标越限 */
    WARNING("告警"),
    /** 故障：已停机或功能失效 */
    FAULT("故障"),
    /** 离线：通讯中断，取不到数据 */
    OFFLINE("离线"),
    /** 保养中：计划性停用 */
    MAINTENANCE("保养中");

    private final String label;

    DeviceStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    /** 是否属于"需要人介入"的异常状态。 */
    public boolean isAbnormal() {
        return this == WARNING || this == FAULT || this == OFFLINE;
    }
}
