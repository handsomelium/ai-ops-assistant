package com.ake.aiops.ai.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 动作幻觉校验测试。
 *
 * <p>用例里的"谎报"样本是<b>真实抓到的模型输出</b>，不是编的：
 * 那一轮模型只调用了 listEngineers 和 getWorkOrderDetail，
 * 从未调用 assignWorkOrder，却在回答里写了"已派给陈明（1380-0004）"。
 */
class WriteClaimGuardTest {

    private final WriteClaimGuard guard = new WriteClaimGuard();

    private WriteAudit auditWith(String... succeededWrites) {
        WriteAudit audit = new WriteAudit();
        for (String tool : succeededWrites) {
            audit.recordCall(tool);
            audit.recordSuccess(tool);
        }
        return audit;
    }

    @Test
    @DisplayName("真实事故样本：声称已派单但没有任何写操作成功 -> 必须纠正")
    void catchesRealHallucination() {
        String answer = "| 改派原因 | 张建国无弱电资质；陈明技能匹配 |\n| 结果 | 已派给陈明（1380-0004） |";
        WriteAudit audit = new WriteAudit();
        audit.recordCall("listEngineers");
        audit.recordCall("getWorkOrderDetail");

        Optional<String> correction = guard.verify(answer, audit);
        assertTrue(correction.isPresent(), "应检出动作幻觉");
        assertTrue(correction.get().contains("系统校验"), "纠正说明应标明来自系统");
        assertTrue(correction.get().contains("listEngineers"), "应列出实际调用过的工具");
    }

    @Test
    @DisplayName("确实执行成功了，就不该多嘴")
    void silentWhenWriteActuallySucceeded() {
        String answer = "已派给陈明，工单 WO-2026-0003 状态变为处理中。";
        assertTrue(guard.verify(answer, auditWith("assignWorkOrder")).isEmpty());
    }

    @Test
    @DisplayName("各种完成态表述都要能识别")
    void detectsVariousClaimForms() {
        WriteAudit empty = new WriteAudit();
        empty.recordCall("queryWorkOrders");

        assertTrue(guard.verify("已成功创建工单 WO-2026-0008。", empty).isPresent(), "已创建工单");
        assertTrue(guard.verify("已关闭工单 WO-2026-0006。", empty).isPresent(), "已关闭工单");
        assertTrue(guard.verify("已将工单优先级提级为 HIGH。", empty).isPresent(), "已提级");
        assertTrue(guard.verify("现已指派给李伟处理。", empty).isPresent(), "已指派");
        assertTrue(guard.verify("设备状态已变更为 RUNNING。", empty).isPresent(), "已变更状态");
    }

    @Test
    @DisplayName("转述业务规则的既有事实不算谎报（关键防误报用例）")
    void doesNotFlagQuotedBusinessRule() {
        WriteAudit empty = new WriteAudit();
        empty.recordCall("closeWorkOrder");
        empty.recordRejected("closeWorkOrder");

        // 这是工具返回的报错原文被模型转述，"已关闭"说的是既有状态，不是模型新做的动作
        String answer = "关单失败：工单 WO-2026-0002 已关闭，不能再派单。建议改为查询该工单的处理记录。";
        assertTrue(guard.verify(answer, empty).isEmpty(),
                "否定句中的既有事实不应被判为动作幻觉，实际：" + guard.verify(answer, empty));
    }

    @Test
    @DisplayName("建议句、条件句不算完成态声明")
    void doesNotFlagSuggestions() {
        WriteAudit empty = new WriteAudit();
        assertTrue(guard.verify("建议派单给陈明，他具备弱电资质且负载为 0。", empty).isEmpty());
        assertTrue(guard.verify("如需关单，请先恢复设备状态并补充处理结果。", empty).isEmpty());
        assertTrue(guard.verify("可以创建工单，但需要你确认优先级。", empty).isEmpty());
        assertTrue(guard.verify("张建国不具备弱电工种资质，无法派单给他。", empty).isEmpty());
    }

    @Test
    @DisplayName("空回答与无工具调用的纯问答不触发")
    void silentOnEmptyAndPureChat() {
        assertTrue(guard.verify(null, new WriteAudit()).isEmpty());
        assertTrue(guard.verify("", new WriteAudit()).isEmpty());
        assertTrue(guard.verify("MCP 是模型与外部工具之间的开放标准协议。", new WriteAudit()).isEmpty());
    }

    @Test
    @DisplayName("被拒绝的写操作会写进纠正说明，方便定位")
    void correctionMentionsRejectedWrites() {
        WriteAudit audit = new WriteAudit();
        audit.recordCall("assignWorkOrder");
        audit.recordRejected("assignWorkOrder");

        Optional<String> correction = guard.verify("已派给张建国。", audit);
        assertTrue(correction.isPresent());
        assertTrue(correction.get().contains("被业务规则拒绝"), "应说明有写操作被拒");
        assertFalse(correction.get().isBlank());
    }

    @Test
    @DisplayName("部分成功不能免检：建单成功后谎报已派单，必须检出")
    void catchesLieAfterAnotherWriteSucceeded() {
        WriteAudit audit = auditWith("createWorkOrder");
        String answer = "已创建工单 WO-2026-0008，并已派给陈明（1380-0004）。";

        Optional<String> correction = guard.verify(answer, audit);
        assertTrue(correction.isPresent(), "有一次写成功不等于所有动作声明都可信");
        String text = correction.get();
        assertTrue(text.contains("已派给"), "应点名未被证实的动作");
        assertTrue(text.contains("createWorkOrder"), "应列出本轮实际成功的写操作");
        assertFalse(text.contains("没有任何写操作成功执行"), "已有写成功时不能再说一次都没有");
    }

    @Test
    @DisplayName("声明的每一个动作都有对应成功记录，才保持静默")
    void silentWhenAllClaimedActionsAreBacked() {
        WriteAudit audit = auditWith("createWorkOrder", "assignWorkOrder");
        assertTrue(guard.verify("已创建工单 WO-2026-0008，并已派给陈明。", audit).isEmpty());
    }

    @Test
    @DisplayName("谎报设备状态变更，即使本轮派单真的成功也要检出")
    void catchesDeviceStatusLie() {
        WriteAudit audit = auditWith("assignWorkOrder");
        assertTrue(guard.verify("已派给陈明，设备状态已变更为 RUNNING。", audit).isPresent());
        assertTrue(guard.verify("已派给陈明，设备状态已恢复为运行。", audit).isPresent());
    }

    @Test
    @DisplayName("泛化的状态描述不误报：本轮确有写操作成功即可")
    void doesNotFlagGenericStatusWording() {
        WriteAudit audit = auditWith("assignWorkOrder");
        assertTrue(guard.verify("派单已完成，工单状态已改为处理中。", audit).isEmpty());
    }

    @Test
    @DisplayName("多个动作声明中只挑出没被证实的那些")
    void listsOnlyUnverifiedClaims() {
        WriteAudit audit = auditWith("assignWorkOrder");
        String answer = "已派给陈明，另外已关闭工单 WO-2026-0006，并已调整优先级为 HIGH。";

        Optional<String> correction = guard.verify(answer, audit);
        assertTrue(correction.isPresent());
        String text = correction.get();
        assertTrue(text.contains("已关闭工单"), "应点名关单声明");
        assertTrue(text.contains("已调整优先级"), "应点名调级声明");
        assertFalse(text.contains("未被证实的动作声明：已派给"), "派单已被证实，不该列为未证实");
    }

    @Test
    @DisplayName("复述同一个动作按类型核对，不按次数误报")
    void repeatingSameClaimIsNotFlagged() {
        WriteAudit audit = auditWith("assignWorkOrder");
        assertTrue(guard.verify("已派给陈明，确认已派给陈明（1380-0004）。", audit).isEmpty());
    }
}
