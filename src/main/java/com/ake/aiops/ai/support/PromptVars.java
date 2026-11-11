package com.ake.aiops.ai.support;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/** 提示词里注入的时间上下文。 */
public final class PromptVars {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final String[] WEEKDAYS = {"星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日"};

    private PromptVars() {
    }

    /**
     * 当前时间文本。
     *
     * <p>模型不知道"现在几点"，训练数据也有截止日期。凡是涉及
     * "今天""最近""超时多久"的判断，都必须把当前时间显式写进提示词，
     * 否则模型会按训练数据里的时间瞎猜。
     */
    public static String now() {
        LocalDateTime t = LocalDateTime.now();
        return t.format(FMT) + " " + WEEKDAYS[t.getDayOfWeek().getValue() - 1] + "（东八区）";
    }
}
