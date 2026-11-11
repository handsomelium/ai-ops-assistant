package com.ake.aiops.ai.support;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 大模型输出的 JSON 容错解析。
 *
 * <p>即使已经开了 {@code response_format=json_object}，也依然可能拿到：
 * <ul>
 *   <li>被 ```json ... ``` 代码块包裹的结果</li>
 *   <li>JSON 前后夹带解释性文字</li>
 *   <li>空字符串或 null 字面量</li>
 * </ul>
 * 生产代码必须做这一层兜底，否则整个多智能体流程会因为一次解析失败就崩掉。
 */
public final class Json {

    private Json() {
    }

    /** 从模型输出里抠出第一个完整的 JSON 对象文本；找不到返回 null。 */
    public static String extractObject(String raw) {
        return extract(raw, '{', '}');
    }

    /** 从模型输出里抠出第一个完整的 JSON 数组文本；找不到返回 null。 */
    public static String extractArray(String raw) {
        return extract(raw, '[', ']');
    }

    private static String extract(String raw, char open, char close) {
        if (raw == null) {
            return null;
        }
        String s = stripCodeFence(raw).trim();
        int start = s.indexOf(open);
        if (start < 0) {
            return null;
        }
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == open) {
                depth++;
            } else if (c == close) {
                depth--;
                if (depth == 0) {
                    return s.substring(start, i + 1);
                }
            }
        }
        // 括号没闭合（多半是被 max_tokens 截断），退化成"从起始括号到结尾"
        return s.substring(start);
    }

    /** 去掉 ```json / ``` 包裹。 */
    public static String stripCodeFence(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim();
        if (s.startsWith("```")) {
            int firstNewline = s.indexOf('\n');
            if (firstNewline > 0) {
                s = s.substring(firstNewline + 1);
            }
            int fence = s.lastIndexOf("```");
            if (fence >= 0) {
                s = s.substring(0, fence);
            }
        }
        return s.trim();
    }

    /** 安全解析成目标类型，失败返回 null（调用方决定降级策略）。 */
    public static <T> T parse(String raw, Class<T> type, ObjectMapper mapper) {
        String json = extractObject(raw);
        if (json == null) {
            return null;
        }
        try {
            return mapper.readValue(json, type);
        } catch (Exception e) {
            return null;
        }
    }

    /** 把对象转成紧凑 JSON 字符串，失败时退回 toString。 */
    public static String write(Object value, ObjectMapper mapper) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    /** 截断过长的文本，避免把大段内容塞进上下文。 */
    public static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max) + "...(已截断)";
    }
}
