package com.ake.aiops.ai.support;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 动作幻觉校验器：拿"模型说了什么"去对"代码实际执行了什么"。
 *
 * <p>触发条件：模型的回答里出现了"已完成某个写操作"的表述，
 * 但本轮 {@link WriteAudit} 里<b>没有与之对应的成功写操作记录</b>。
 * 命中就在回答末尾追加一段系统校验说明，并通过 SSE 推一条 warning 事件。
 *
 * <p><b>为什么不能只判断「本轮有没有任意一次写操作成功」：</b>
 * 早期版本用的是 {@code if (audit.anyWriteSucceeded()) return empty;}，
 * 这会被「部分成功 + 谎报其它动作」绕过 —— 例如模型真的建了一张工单，
 * 却接着写"已派给陈明""已关闭工单"，此时审计里已有成功记录，校验会整段跳过，
 * 而工单的 assignee 仍然为 null。所以必须把每一句完成态声明单独映射到它依赖的写工具，
 * 逐条核对。<b>每一句动作声明，都要有对应写工具的成功记录。</b>
 *
 * <p>核对的是「动作类型有没有对应成功记录」，不比较次数：模型把同一个动作复述两遍
 * 是常见的表达方式，按次数判定会造成误报，这里刻意按类型匹配。
 *
 * <p>正则设计上有两个坑要避开：
 * <ol>
 *   <li><b>不能只匹配"已 + 动词"</b>。工具返回的报错原文里常有
 *       "工单 WO-x 已关闭，不能再派单" —— 这是在陈述既有事实，不是模型谎报动作。
 *       所以中间的间隔字符里排除了 不/未/没/无/待/拒 等否定词。</li>
 *   <li><b>动词要带完成态助词</b>。"派给""创建工单""调整优先级"这类才算动作声明，
 *       单纯的"派单""关单"可能出现在建议句里（"建议派单给陈明"），不能误判。</li>
 * </ol>
 */
@Component
public class WriteClaimGuard {

    /**
     * 已/已经/已成功/现已/成功/完成 + 最多 8 个非否定、非句读字符 + 写操作动词。
     * 否定字符集用来排除"已关闭，不能再派单"这种转述业务规则的句子。
     * 第 2 捕获组是动作动词，用于回查它依赖的写工具。
     *
     * <p>间隔必须用<b>非贪婪</b> {0,8}?：否则"已派给陈明，另外已关闭工单"会被
     * 一口气匹配成一句"关单"声明，把前面已证实的派单也吞进去，逐条核对就失去意义。
     */
    private static final Pattern CLAIM = Pattern.compile(
            "(已经成功|已成功|已经|现已|已|成功|完成)"
                    + "[^。\\n；;！!？?不未没无待拒失]{0,8}?"
                    + "(派单给|派给|指派给|改派给|"
                    + "创建了工单|创建工单|新建了工单|新建工单|开单成功|"
                    + "关闭了工单|关闭工单|关单成功|"
                    + "调整了优先级|调整优先级|提级为|提级至|"
                    + "状态变更为|状态改为|变更为|改为|恢复为运行|恢复运行|"
                    + "完成了派单|完成派单)");

    /** 完成态动词 -> 能支撑这个声明的写工具。一个声明只需命中其中任意一个工具。 */
    private static final Map<String, Set<String>> ACTION_TOOLS = new LinkedHashMap<>();

    static {
        Set<String> assign = Set.of("assignWorkOrder");
        Set<String> create = Set.of("createWorkOrder");
        Set<String> close = Set.of("closeWorkOrder");
        Set<String> priority = Set.of("changeWorkOrderPriority");
        Set<String> deviceStatus = Set.of("updateDeviceStatus");

        for (String verb : new String[]{"派单给", "派给", "指派给", "改派给", "完成了派单", "完成派单"}) {
            ACTION_TOOLS.put(verb, assign);
        }
        for (String verb : new String[]{"创建了工单", "创建工单", "新建了工单", "新建工单", "开单成功"}) {
            ACTION_TOOLS.put(verb, create);
        }
        for (String verb : new String[]{"关闭了工单", "关闭工单", "关单成功"}) {
            ACTION_TOOLS.put(verb, close);
        }
        for (String verb : new String[]{"调整了优先级", "调整优先级", "提级为", "提级至"}) {
            ACTION_TOOLS.put(verb, priority);
        }
        for (String verb : new String[]{"状态变更为", "状态改为", "恢复为运行", "恢复运行"}) {
            ACTION_TOOLS.put(verb, deviceStatus);
        }
        // "变更为""改为"这类泛化措辞没说明是设备状态还是工单状态，无法从文字判断对应的工具。
        // 未登记的动作一律按"本轮有任何写操作成功即不误报"处理，避免把正常回答打断。
    }

    /**
     * @return 需要追加到回答末尾的纠正说明；每一句完成态声明都有对应成功记录时返回 empty
     */
    public Optional<String> verify(String answer, WriteAudit audit) {
        if (answer == null || answer.isBlank()) {
            return Optional.empty();
        }
        List<String> succeeded = audit.succeededWrites();
        List<String> unsupported = new ArrayList<>();

        Matcher m = CLAIM.matcher(answer);
        while (m.find()) {
            String claim = m.group();
            Set<String> expected = expectedTools(answer, m);
            // 按动作类型核对、不比较次数：复述同一个动作不应被判成幻觉。
            boolean backed = expected.stream().anyMatch(succeeded::contains);
            if (!backed && !unsupported.contains(claim)) {
                unsupported.add(claim);
            }
        }
        if (unsupported.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(buildCorrection(unsupported, audit));
    }

    /** 从匹配到的句子里推断「这句话依赖哪个写工具」。 */
    private Set<String> expectedTools(String answer, Matcher m) {
        // "设备状态已变更为 RUNNING" 这类句子，动词组只捕到泛化的"变更为"，
        // 但"已"字前面紧邻的"设备"足以确定这是设备状态声明，必须按 updateDeviceStatus 精确核对。
        String before = answer.substring(Math.max(0, m.start() - 6), m.start());
        if (before.contains("设备")) {
            return Set.of("updateDeviceStatus");
        }
        return ACTION_TOOLS.getOrDefault(m.group(2), WriteAudit.WRITE_TOOLS);
    }

    private String buildCorrection(List<String> unsupported, WriteAudit audit) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n\n---\n");
        sb.append("**⚠ 系统校验（自动追加，非模型输出）**\n\n");
        sb.append("上文「").append(String.join("」「", unsupported)).append("」的表述与系统记录不符：");
        if (audit.anyWriteSucceeded()) {
            sb.append("本轮虽有写操作成功，但**没有与上述动作对应的成功记录**，这些变更并未生效。\n\n");
        } else {
            sb.append("本轮**没有任何写操作成功执行**，相关变更并未生效。\n\n");
        }
        sb.append("- 未被证实的动作声明：").append(String.join("、", unsupported)).append('\n');
        if (audit.anyWriteSucceeded()) {
            sb.append("- 本轮实际成功的写操作：")
                    .append(String.join("、", audit.succeededWrites())).append('\n');
        }
        sb.append("- 实际调用的工具：")
                .append(audit.calledTools().isEmpty() ? "（无）" : String.join("、", audit.calledTools()))
                .append('\n');
        if (!audit.rejectedWrites().isEmpty()) {
            sb.append("- 被业务规则拒绝的写操作：")
                    .append(String.join("、", audit.rejectedWrites()))
                    .append("（拒绝原因见上方执行过程）\n");
        }
        sb.append("\n请人工确认后再执行，或明确要求我重新操作一次。");
        return sb.toString();
    }
}
