package com.ake.aiops.service;

import com.ake.aiops.domain.DeviceStatus;
import com.ake.aiops.domain.Priority;
import com.ake.aiops.domain.WorkOrder;
import com.ake.aiops.domain.WorkOrderStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 业务规则测试。
 *
 * <p>这组测试<b>不需要 API Key、不联网</b>，是整个 Demo 的安全网：
 * 无论模型怎么发挥，这些规则都必须挡住。课堂上可以直接跑给学员看 ——
 * "AI 不是想改什么就能改什么"。
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class WorkOrderServiceTest {

    @Autowired
    private WorkOrderService workOrderService;

    @Autowired
    private DeviceService deviceService;

    @Test
    @DisplayName("同一设备已有未关闭工单时，拒绝重复开单")
    void createRejectsDuplicateDevice() {
        // DEV-PDGB-01 在种子数据里已有 WO-2026-0001（OPEN）
        BusinessRuleException e = assertThrows(BusinessRuleException.class, () ->
                workOrderService.create("配电柜又报警了", "重复报修", "DEV-PDGB-01",
                        Priority.HIGH, "测试"));
        assertTrue(e.getMessage().contains("重复报修"), "应提示重复报修，实际：" + e.getMessage());
        assertTrue(e.getMessage().contains("WO-2026-0001"), "应带上已存在的工单号，实际：" + e.getMessage());
        assertTrue(e.getMessage().contains("changeWorkOrderPriority"),
                "应告诉模型正确的替代动作，实际：" + e.getMessage());
    }

    @Test
    @DisplayName("工程师工种不匹配时，拒绝派单（安全合规硬要求）")
    void assignRejectsSkillMismatch() {
        // WO-2026-0003 关联 DEV-CCTV-03（安防摄像 -> 需要弱电），张建国是强电
        BusinessRuleException e = assertThrows(BusinessRuleException.class, () ->
                workOrderService.assign("WO-2026-0003", "张建国"));
        assertTrue(e.getMessage().contains("弱电"), "应说明需要弱电工种，实际：" + e.getMessage());
        assertTrue(e.getMessage().contains("matched=true"), "应引导模型去看 matched 字段，实际：" + e.getMessage());
    }

    @Test
    @DisplayName("工种匹配且负载未满时，派单成功并流转为处理中")
    void assignSucceedsWhenSkillMatches() {
        Snapshot before = snapshotOf("WO-2026-0003");
        try {
            WorkOrder after = workOrderService.assign("WO-2026-0003", "陈明");   // 陈明 = 弱电/安防/网络
            assertEquals("陈明", after.getAssignee());
            assertEquals(WorkOrderStatus.PROCESSING, after.getStatus());
            assertTrue(after.getTraces().stream().anyMatch(t -> t.contains("陈明")), "应留下派单痕迹");
        } finally {
            restore(before);
        }
    }

    @Test
    @DisplayName("engineerName 留空时，由系统按工种+负载自动推荐")
    void assignRecommendsWhenEngineerOmitted() {
        Snapshot before = snapshotOf("WO-2026-0004");   // DEV-PUMP-02 -> 给排水
        try {
            WorkOrder after = workOrderService.assign("WO-2026-0004", null);
            assertNotNull(after.getAssignee(), "应自动选出处理人");
            assertEquals(WorkOrderStatus.PROCESSING, after.getStatus());
            // 给排水工种只有刘洋
            assertEquals("刘洋", after.getAssignee());
        } finally {
            restore(before);
        }
    }

    @Test
    @DisplayName("工单没有处理人时，不允许关单")
    void closeRejectsWithoutAssignee() {
        BusinessRuleException e = assertThrows(BusinessRuleException.class, () ->
                workOrderService.close("WO-2026-0001", "已处理"));
        assertTrue(e.getMessage().contains("尚未派单"), "实际：" + e.getMessage());
    }

    @Test
    @DisplayName("关联设备仍处于故障状态时，不允许关单")
    void closeRejectsWhenDeviceStillFaulty() {
        // WO-2026-0002 关联 DEV-AHU-02（FAULT），已有处理人李伟
        BusinessRuleException e = assertThrows(BusinessRuleException.class, () ->
                workOrderService.close("WO-2026-0002", "已更换风机电机"));
        assertTrue(e.getMessage().contains("故障"), "实际：" + e.getMessage());
        assertTrue(e.getMessage().contains("updateDeviceStatus"), "应提示先恢复设备，实际：" + e.getMessage());
    }

    @Test
    @DisplayName("关单必须填写处理结果")
    void closeRejectsBlankResolution() {
        BusinessRuleException e = assertThrows(BusinessRuleException.class, () ->
                workOrderService.close("WO-2026-0002", "  "));
        assertTrue(e.getMessage().contains("处理结果"), "实际：" + e.getMessage());
    }

    @Test
    @DisplayName("设备从异常恢复为运行时，必须填写现场确认说明")
    void deviceRecoveryRequiresRemark() {
        assertThrows(BusinessRuleException.class, () ->
                deviceService.updateStatus("DEV-PUMP-02", DeviceStatus.RUNNING, null));
        assertThrows(BusinessRuleException.class, () ->
                deviceService.updateStatus("DEV-PUMP-02", DeviceStatus.RUNNING, "   "));
    }

    @Test
    @DisplayName("带确认说明时，设备状态恢复成功")
    void deviceRecoverySucceedsWithRemark() {
        DeviceStatus before = deviceService.require("DEV-PUMP-02").getStatus();
        try {
            assertEquals(DeviceStatus.RUNNING,
                    deviceService.updateStatus("DEV-PUMP-02", DeviceStatus.RUNNING,
                            "现场重启通讯模块，已恢复上报").getStatus());
        } finally {
            deviceService.require("DEV-PUMP-02").setStatus(before);
        }
    }

    @Test
    @DisplayName("优先级调整会留下痕迹")
    void changePriorityLeavesTrace() {
        Snapshot before = snapshotOf("WO-2026-0005");
        try {
            WorkOrder after = workOrderService.changePriority("WO-2026-0005", Priority.HIGH);
            assertEquals(Priority.HIGH, after.getPriority());
            assertTrue(after.getTraces().stream().anyMatch(t -> t.contains("优先级")), "应留下调级痕迹");
        } finally {
            restore(before);
        }
    }

    @Test
    @DisplayName("不存在的工单号/设备号会给出可操作的错误提示（点名下一步该调哪个工具）")
    void missingEntitiesGiveActionableMessage() {
        BusinessRuleException e1 = assertThrows(BusinessRuleException.class, () ->
                workOrderService.require("WO-9999-9999"));
        assertTrue(e1.getMessage().contains("queryWorkOrders"),
                "应点名 queryWorkOrders 工具，模型才知道下一步调什么，实际：" + e1.getMessage());

        BusinessRuleException e2 = assertThrows(BusinessRuleException.class, () ->
                deviceService.require("DEV-NOT-EXIST"));
        assertTrue(e2.getMessage().contains("listDevices"),
                "应点名 listDevices 工具，实际：" + e2.getMessage());
    }

    @Test
    @DisplayName("设备可以按名称模糊定位（模型经常给名称而不是编号）")
    void deviceLookupByNameTolerant() {
        assertEquals("DEV-PDGB-01", deviceService.require("B1低压配电柜").getId());
        assertEquals("DEV-PDGB-01", deviceService.require("dev-pdgb-01").getId());
    }

    /**
     * 工单快照。
     *
     * <p>注意必须先取出字段值：{@code require()} 返回的是仓库里的同一个实例，
     * 直接持有对象引用做"快照"是无效的 —— 改了之后快照也跟着变了。
     */
    private record Snapshot(String id, String assignee, WorkOrderStatus status, Priority priority) {
    }

    private Snapshot snapshotOf(String id) {
        WorkOrder o = workOrderService.require(id);
        return new Snapshot(o.getId(), o.getAssignee(), o.getStatus(), o.getPriority());
    }

    /** 把被测试改动过的工单恢复原状，避免用例之间互相影响。 */
    private void restore(Snapshot s) {
        WorkOrder live = workOrderService.require(s.id());
        live.setAssignee(s.assignee());
        // setStatus 会在 CLOSED 时写 closedAt，这里只涉及 OPEN/PROCESSING，无副作用
        live.setStatus(s.status());
        live.setPriority(s.priority());
    }
}
