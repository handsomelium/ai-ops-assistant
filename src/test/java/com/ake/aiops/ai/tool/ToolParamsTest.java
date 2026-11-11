package com.ake.aiops.ai.tool;

import com.ake.aiops.domain.DeviceStatus;
import com.ake.aiops.domain.Priority;
import com.ake.aiops.domain.WorkOrderStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 工具入参归一化测试。
 *
 * <p>模型填参数是不稳定的：大小写、中英文、P0/P1 这类别名都可能出现。
 * 工具入参一律用 String 接再宽松解析，比声明成 enum 让调用直接失败要健壮得多。
 */
class ToolParamsTest {

    @Test
    @DisplayName("设备状态：大小写、中文标签都要认")
    void parseDeviceStatus() {
        assertEquals(DeviceStatus.RUNNING, ToolParams.parseStatus("RUNNING"));
        assertEquals(DeviceStatus.RUNNING, ToolParams.parseStatus("running"));
        assertEquals(DeviceStatus.RUNNING, ToolParams.parseStatus(" 运行中 "));
        assertEquals(DeviceStatus.FAULT, ToolParams.parseStatus("故障"));
        assertEquals(DeviceStatus.MAINTENANCE, ToolParams.parseStatus("Maintenance"));
    }

    @Test
    @DisplayName("无法识别的状态返回 null（不抛异常，交由上层决定）")
    void parseDeviceStatusUnknown() {
        assertNull(ToolParams.parseStatus("BROKEN"));
        assertNull(ToolParams.parseStatus(null));
        assertNull(ToolParams.parseStatus("  "));
    }

    @Test
    @DisplayName("ABNORMAL 是查询语义而非枚举值，返回 null 由调用方特殊处理")
    void abnormalIsNotAStatus() {
        assertNull(ToolParams.parseStatus("ABNORMAL"));
    }

    @Test
    @DisplayName("工单状态解析，ACTIVE 同样返回 null")
    void parseWorkOrderStatus() {
        assertEquals(WorkOrderStatus.OPEN, ToolParams.parseWorkOrderStatus("OPEN"));
        assertEquals(WorkOrderStatus.OPEN, ToolParams.parseWorkOrderStatus("待处理"));
        assertEquals(WorkOrderStatus.CLOSED, ToolParams.parseWorkOrderStatus("closed"));
        assertNull(ToolParams.parseWorkOrderStatus("ACTIVE"));
    }

    @Test
    @DisplayName("优先级：兼容 P0/P1/紧急 等口语写法")
    void parsePriority() {
        assertEquals(Priority.HIGH, ToolParams.parsePriority("HIGH"));
        assertEquals(Priority.HIGH, ToolParams.parsePriority("high"));
        assertEquals(Priority.HIGH, ToolParams.parsePriority("高"));
        assertEquals(Priority.HIGH, ToolParams.parsePriority("P1"));
        assertEquals(Priority.HIGH, ToolParams.parsePriority("URGENT"));
        assertEquals(Priority.MEDIUM, ToolParams.parsePriority("中"));
        assertEquals(Priority.MEDIUM, ToolParams.parsePriority("P2"));
        assertEquals(Priority.LOW, ToolParams.parsePriority("LOW"));
        assertEquals(Priority.LOW, ToolParams.parsePriority("P3"));
        assertNull(ToolParams.parsePriority("VERY_HIGH"));
    }

    @Test
    @DisplayName("解析不出时用兜底值，而不是让工具调用失败")
    void parsePriorityWithFallback() {
        assertEquals(Priority.MEDIUM, ToolParams.parsePriorityOr(null, Priority.MEDIUM));
        assertEquals(Priority.MEDIUM, ToolParams.parsePriorityOr("不知道", Priority.MEDIUM));
        assertEquals(Priority.HIGH, ToolParams.parsePriorityOr("HIGH", Priority.MEDIUM));
    }

    @Test
    @DisplayName("空白判断")
    void blankCheck() {
        org.junit.jupiter.api.Assertions.assertTrue(ToolParams.isBlank(null));
        org.junit.jupiter.api.Assertions.assertTrue(ToolParams.isBlank("   "));
        org.junit.jupiter.api.Assertions.assertFalse(ToolParams.isBlank("a"));
    }
}
