package com.ake.aiops.domain.view;

import java.util.List;

/**
 * 工程师视图。
 *
 * @param activeOrders 当前手上未关闭的工单数，模型派单时要看这个做负载均衡
 * @param matched      是否匹配目标设备要求的工种
 */
public record EngineerView(
        String name,
        String phone,
        List<String> skills,
        boolean onDuty,
        int activeOrders,
        boolean matched) {
}
