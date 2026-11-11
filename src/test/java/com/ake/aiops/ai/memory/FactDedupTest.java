package com.ake.aiops.ai.memory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 长期记忆判重测试。
 *
 * <p>用例来自真实演示中踩到的问题：模型对同一个用户先后抽出了
 * "用户叫赵工，是园区暖通班班长，只负责制冷站。"、"用户姓赵，是园区暖通班班长"、
 * "用户负责制冷站区域" 等 9 条近义记忆，全等去重一条都合并不掉。
 */
class FactDedupTest {

    @Test
    @DisplayName("措辞不同的同一事实应判为相似")
    void mergesSameFactDifferentWording() {
        assertTrue(FactDedup.isSimilar("用户叫赵工，是园区暖通班班长，只负责制冷站。",
                "用户叫赵工，是园区暖通班班长。"));
        assertTrue(FactDedup.isSimilar("用户姓赵，是园区暖通班班长",
                "用户叫赵工，是园区暖通班班长"));
        assertTrue(FactDedup.isSimilar("用户希望回答控制在两句话内。",
                "用户要求回答控制在两句话内"));
        assertTrue(FactDedup.isSimilar("用户是设备部主管", "用户是设备部的主管"));
    }

    @Test
    @DisplayName("包含关系直接判为相似，不走相似度计算")
    void containmentIsSimilar() {
        assertTrue(FactDedup.isSimilar("用户负责制冷站", "用户负责制冷站区域"));
        assertTrue(FactDedup.isSimilar("用户负责制冷站区域", "用户负责制冷站"));
    }

    @Test
    @DisplayName("真正不同的事实不能被误合并")
    void keepsDistinctFactsApart() {
        assertFalse(FactDedup.isSimilar("用户是暖通班班长", "用户偏好简短回答"));
        assertFalse(FactDedup.isSimilar("用户负责A座和B座", "用户负责制冷站"));
        assertFalse(FactDedup.isSimilar("晚上10点后不派单", "周末不安排检修"));
        assertFalse(FactDedup.isSimilar("用户是设备部主管", "用户要求回答用表格"));
    }

    @Test
    @DisplayName("阈值经过标定：同义对最低分与异义对最高分之间留有余量")
    void thresholdIsCalibrated() {
        // 实测值，见 FactDedup.THRESHOLD 的注释
        double sameMin = FactDedup.similarity(
                FactDedup.normalize("用户姓赵，是园区暖通班班长"),
                FactDedup.normalize("用户叫赵工，是园区暖通班班长"));
        double diffMax = FactDedup.similarity(
                FactDedup.normalize("用户负责A座"),
                FactDedup.normalize("用户负责B座"));

        assertEquals(0.533, sameMin, 0.01);
        assertEquals(0.429, diffMax, 0.01);
        assertTrue(diffMax < FactDedup.THRESHOLD,
                "阈值必须高于异义对最高分 " + diffMax + "，当前 " + FactDedup.THRESHOLD);
        assertTrue(FactDedup.THRESHOLD < sameMin,
                "阈值必须低于同义对最低分 " + sameMin + "，当前 " + FactDedup.THRESHOLD);
    }

    @Test
    @DisplayName("归一化去掉标点与空白并转小写")
    void normalizeStripsPunctuation() {
        assertEquals("用户叫赵工是暖通班班长", FactDedup.normalize("用户叫赵工，是暖通班班长。"));
        assertEquals("abc", FactDedup.normalize(" A B C "));
        assertEquals("", FactDedup.normalize(null));
    }

    @Test
    @DisplayName("相似度取值范围与边界")
    void similarityRange() {
        assertEquals(1.0, FactDedup.similarity("完全相同", "完全相同"), 1e-9);
        assertEquals(0.0, FactDedup.similarity("", "任意"), 1e-9);
        double partial = FactDedup.similarity("用户负责制冷站", "用户负责配电房");
        assertEquals(0.333, partial, 0.01);
    }

    @Test
    @DisplayName("空值与单字符不会抛异常")
    void edgeCases() {
        assertFalse(FactDedup.isSimilar(null, "任意"));
        assertFalse(FactDedup.isSimilar("任意", ""));
        assertFalse(FactDedup.isSimilar(null, null));
        assertTrue(FactDedup.isSimilar("甲", "甲"));
    }
}
