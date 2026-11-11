package com.ake.aiops.ai.memory;

import java.util.HashSet;
import java.util.Set;

/**
 * 长期记忆的近似判重。
 *
 * <p>为什么不能用字符串全等：同一个事实，模型每轮的措辞都不一样 ——
 * "用户叫赵工，是暖通班班长" / "用户姓赵，是园区暖通班班长" / "用户负责制冷站"。
 * 全等匹配会留下一堆近义重复，注入 system prompt 时既浪费 token 又显得杂乱。
 *
 * <p>这里用<b>字符二元组（bigram）Jaccard 相似度</b>：
 * 把字符串切成相邻两字的片段集合，算交集/并集。
 * 对中文短句足够有效，而且不需要引入分词器或向量模型 —— 判重这一步
 * 本身不该比它要解决的问题更贵。
 *
 * <p>更讲究的做法是用 embedding 算余弦相似度，但那意味着每次抽取都要多一次
 * 向量调用；对一个"记住用户是谁"的场景，bigram 的性价比明显更高。
 */
final class FactDedup {

    /**
     * 相似度阈值。
     *
     * <p>不是拍脑袋定的，用真实数据标定过。取 11 组样本（5 组同义、6 组异义）实测：
     * <pre>
     *   同义对最低相似度 = 0.533   （"用户姓赵，是园区暖通班班长" vs "用户叫赵工，是园区暖通班班长"）
     *   异义对最高相似度 = 0.429   （"用户负责A座" vs "用户负责B座"）
     *   可用阈值区间 = (0.429, 0.533)
     * </pre>
     * 取区间中点 0.48，两侧都留出约 0.05 的余量。
     * 作为对照：0.72 这种"看起来挺严"的阈值只能认出 2/5 的同义对，等于没去重。
     *
     * <p><b>已知局限</b>：纯词面相似度分不清"负责A座"和"负责B座"这类只差一个关键实体的句子
     * （实测 0.429，刚好压在阈值下方）。对准确性要求更高时，应换成 embedding 余弦相似度，
     * 代价是每次判重多一次向量调用。
     */
    static final double THRESHOLD = 0.48;

    private FactDedup() {
    }

    /** 归一化：去掉标点与空白并转小写，只保留有信息量的字符。 */
    static String normalize(String s) {
        return s == null ? "" : s.replaceAll("[\\s，。,、；;：:！!？?\"'（）()【】\\[\\]]", "").toLowerCase();
    }

    /** 是否可视为同一条记忆。 */
    static boolean isSimilar(String a, String b) {
        String na = normalize(a);
        String nb = normalize(b);
        if (na.isEmpty() || nb.isEmpty()) {
            return false;
        }
        if (na.equals(nb) || na.contains(nb) || nb.contains(na)) {
            return true;
        }
        return similarity(na, nb) >= THRESHOLD;
    }

    /** 字符二元组 Jaccard 相似度，取值 0~1。 */
    static double similarity(String a, String b) {
        Set<String> sa = bigrams(a);
        Set<String> sb = bigrams(b);
        if (sa.isEmpty() || sb.isEmpty()) {
            return 0;
        }
        Set<String> intersection = new HashSet<>(sa);
        intersection.retainAll(sb);
        int union = sa.size() + sb.size() - intersection.size();
        return union == 0 ? 0 : (double) intersection.size() / union;
    }

    private static Set<String> bigrams(String s) {
        Set<String> set = new HashSet<>();
        if (s.length() == 1) {
            set.add(s);
            return set;
        }
        for (int i = 0; i + 1 < s.length(); i++) {
            set.add(s.substring(i, i + 2));
        }
        return set;
    }
}
