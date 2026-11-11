package com.ake.aiops.ai.tool;

import com.ake.aiops.domain.DeviceStatus;
import com.ake.aiops.domain.Priority;
import com.ake.aiops.domain.WorkOrderStatus;

import java.util.Locale;

/**
 * 工具参数解析的公共逻辑。
 *
 * <p>大模型填枚举参数是不稳定的：可能给 {@code RUNNING}、也可能给 {@code running}、
 * {@code 运行中}、甚至 {@code P1}。工具入参一律用 String 接，再在这里做宽松归一化，
 * 比在 @ToolParam 里声明 enum 然后让调用直接失败要健壮得多。
 */
final class ToolParams {

    private ToolParams() {
    }

    static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /** 宽松解析设备状态：RUNNING / running / 运行中 都能认。无法识别返回 null。 */
    static DeviceStatus parseStatus(String raw) {
        if (isBlank(raw) || "ABNORMAL".equalsIgnoreCase(raw.trim())) {
            return null;
        }
        String s = raw.trim();
        for (DeviceStatus st : DeviceStatus.values()) {
            if (st.name().equalsIgnoreCase(s) || st.getLabel().equals(s)) {
                return st;
            }
        }
        return null;
    }

    /** 宽松解析工单状态。ACTIVE 表示"未关闭的全部"，由调用方单独处理，这里返回 null。 */
    static WorkOrderStatus parseWorkOrderStatus(String raw) {
        if (isBlank(raw) || "ACTIVE".equalsIgnoreCase(raw.trim())) {
            return null;
        }
        String s = raw.trim();
        for (WorkOrderStatus st : WorkOrderStatus.values()) {
            if (st.name().equalsIgnoreCase(s) || st.getLabel().equals(s)) {
                return st;
            }
        }
        return null;
    }

    /** 宽松解析优先级，兼容 P0/P1/紧急 这类口语写法。 */
    static Priority parsePriority(String raw) {
        if (isBlank(raw)) {
            return null;
        }
        String trimmed = raw.trim();
        for (Priority p : Priority.values()) {
            if (p.name().equalsIgnoreCase(trimmed) || p.getLabel().equals(trimmed)) {
                return p;
            }
        }
        return switch (trimmed.toUpperCase(Locale.ROOT)) {
            case "P0", "P1", "URGENT", "CRITICAL" -> Priority.HIGH;
            case "P2", "NORMAL", "MID" -> Priority.MEDIUM;
            case "P3", "TRIVIAL" -> Priority.LOW;
            default -> null;
        };
    }

    static Priority parsePriorityOr(String raw, Priority fallback) {
        Priority p = parsePriority(raw);
        return p == null ? fallback : p;
    }
}
