package com.ake.aiops.domain.view;

/**
 * 设备摘要视图。
 *
 * <p>为什么不直接把 Device 实体返回给模型？因为实体字段太多、还有内部备注，
 * 全塞进上下文既费 token 又容易让模型分心。<b>给模型看的 DTO 要专门设计</b>，
 * 这是 AI 集成里最容易被忽略、但收益最大的一步。
 */
public record DeviceView(
        String id,
        String name,
        String category,
        String location,
        String status,
        String statusLabel,
        String ownerTeam,
        String keyMetrics,
        String latestAlarm,
        String remark) {
}
