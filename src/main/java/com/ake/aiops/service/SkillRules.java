package com.ake.aiops.service;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 设备类别 -> 所需工种的映射规则。
 *
 * <p>这类"硬规则"必须留在代码里，不能交给大模型判断：
 * 模型可能把电梯维保派给强电工，而这是有安全合规风险的。
 * AI 负责"决定要派单"，规则引擎负责"这一单能不能这么派"。
 */
@Component
public class SkillRules {

    private static final Map<String, String> CATEGORY_TO_SKILL = new LinkedHashMap<>();

    static {
        CATEGORY_TO_SKILL.put("空调机组", "暖通");
        CATEGORY_TO_SKILL.put("冷水机组", "暖通");
        CATEGORY_TO_SKILL.put("电梯", "电梯");
        CATEGORY_TO_SKILL.put("配电柜", "强电");
        CATEGORY_TO_SKILL.put("照明回路", "强电");
        CATEGORY_TO_SKILL.put("水泵", "给排水");
        CATEGORY_TO_SKILL.put("安防摄像", "弱电");
    }

    /** 返回该设备类别要求的工种；未知类别返回 null 表示不做强制校验。 */
    public String requiredSkillOf(String deviceCategory) {
        if (deviceCategory == null) {
            return null;
        }
        return CATEGORY_TO_SKILL.get(deviceCategory.trim());
    }

    /** 单个工程师在手工单上限，超过即视为满载。 */
    public int maxConcurrentOrders() {
        return 3;
    }
}
