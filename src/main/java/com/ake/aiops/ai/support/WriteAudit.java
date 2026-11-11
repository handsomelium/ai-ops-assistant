package com.ake.aiops.ai.support;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 写操作审计。
 *
 * <p><b>这个类是为了修一个真实发生的事故而存在的。</b>
 *
 * <p>演示中让 Agent 把工单 WO-2026-0003 派给张建国（强电工，不该派弱电活）。
 * 期望的行为是：调用 assignWorkOrder → 被业务规则拒绝 → 查 listEngineers → 改派陈明。
 * 前几次确实如此。但有一次模型只调了 {@code listEngineers} 和 {@code getWorkOrderDetail}，
 * <b>从未调用 assignWorkOrder</b>，却在最终回答里写道：
 *
 * <pre>
 *   | 结果 | 已派给陈明（1380-0004） |
 * </pre>
 *
 * 而数据库里 assignee 仍然是 null，工单仍是"待处理"。
 *
 * <p>这就是<b>动作幻觉（action hallucination）</b>：模型把"我认为应该这么做"
 * 说成了"我已经这么做了"。它比答错一个数字危险得多 —— 用户会以为事情办完了。
 *
 * <p>结论：<b>模型说了什么不算数，代码执行了什么才算数。</b>
 * 工具层把真实成功的写操作记在这里，回答生成完之后拿它去核对模型的措辞，
 * 对不上就当场纠正。提示词里当然也要写"不许谎报"，但那只是降低概率，
 * 真正兜底的必须是这种确定性校验。
 */
public class WriteAudit {

    /** 会改变业务数据的工具集合。只有这些工具的成功执行才算"真的做了事"。 */
    public static final Set<String> WRITE_TOOLS = Set.of(
            "createWorkOrder", "assignWorkOrder", "closeWorkOrder",
            "changeWorkOrderPriority", "updateDeviceStatus");

    private final List<String> succeeded = new CopyOnWriteArrayList<>();
    private final List<String> rejected = new CopyOnWriteArrayList<>();
    private final List<String> allCalled = new CopyOnWriteArrayList<>();

    /** 记录一次工具调用（不论读写、不论成败），用于事后向用户交代"实际做了什么"。 */
    public void recordCall(String tool) {
        allCalled.add(tool);
    }

    public void recordSuccess(String tool) {
        if (WRITE_TOOLS.contains(tool)) {
            succeeded.add(tool);
        }
    }

    public void recordRejected(String tool) {
        if (WRITE_TOOLS.contains(tool)) {
            rejected.add(tool);
        }
    }

    public boolean anyWriteSucceeded() {
        return !succeeded.isEmpty();
    }

    public List<String> succeededWrites() {
        return List.copyOf(succeeded);
    }

    public List<String> rejectedWrites() {
        return List.copyOf(rejected);
    }

    public List<String> calledTools() {
        return List.copyOf(allCalled);
    }

    public boolean isEmpty() {
        return succeeded.isEmpty() && rejected.isEmpty();
    }
}
