package com.ake.aiops.ai.support;

import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 动作幻觉校验器：拿"模型说了什么"去对"代码实际执行了什么"。
 *
 * <p>触发条件：模型的回答里出现了"已完成某个写操作"的表述，
 * 但本轮 {@link WriteAudit} 里<b>一次成功的写操作都没有</b>。
 * 命中就在回答末尾追加一段系统校验说明，并通过 SSE 推一条 warning 事件。
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
     */
    private static final Pattern CLAIM = Pattern.compile(
            "(已经成功|已成功|已经|现已|已|成功|完成)"
                    + "[^。\\n；;！!？?不未没无待拒失]{0,8}"
                    + "(派单给|派给|指派给|改派给|"
                    + "创建了工单|创建工单|新建了工单|新建工单|开单成功|"
                    + "关闭了工单|关闭工单|关单成功|"
                    + "调整了优先级|调整优先级|提级为|提级至|"
                    + "状态变更为|状态改为|变更为|改为|恢复为运行|恢复运行|"
                    + "完成了派单|完成派单)");

    /**
     * @return 需要追加到回答末尾的纠正说明；没有发现问题时返回 empty
     */
    public Optional<String> verify(String answer, WriteAudit audit) {
        if (answer == null || answer.isBlank()) {
            return Optional.empty();
        }
        if (audit.anyWriteSucceeded()) {
            // 确实执行成功了，模型的"已完成"表述可信
            return Optional.empty();
        }
        Matcher m = CLAIM.matcher(answer);
        if (!m.find()) {
            return Optional.empty();
        }
        return Optional.of(buildCorrection(m.group(), audit));
    }

    private String buildCorrection(String matched, WriteAudit audit) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n\n---\n");
        sb.append("**⚠ 系统校验（自动追加，非模型输出）**\n\n");
        sb.append("上文「").append(matched).append("…」的表述与系统记录不符：");
        sb.append("本轮**没有任何写操作成功执行**，相关变更并未生效。\n\n");
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
