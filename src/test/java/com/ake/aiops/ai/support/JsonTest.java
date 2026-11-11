package com.ake.aiops.ai.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JSON 容错解析测试。
 *
 * <p>多智能体的调度环节完全依赖"模型输出能被解析成 TaskPlan"。
 * 这一步不稳，整条链路就断了，所以必须用测试把各种畸形输出都覆盖到。
 */
class JsonTest {

    @Test
    @DisplayName("纯 JSON 直接返回")
    void plainJson() {
        assertEquals("{\"a\":1}", Json.extractObject("{\"a\":1}"));
    }

    @Test
    @DisplayName("被 ```json 代码块包裹时能剥掉")
    void codeFence() {
        String raw = "```json\n{\"intent\":\"COMPLEX\",\"tasks\":[]}\n```";
        assertEquals("{\"intent\":\"COMPLEX\",\"tasks\":[]}", Json.extractObject(raw));
    }

    @Test
    @DisplayName("被 ``` 包裹但没有语言标记")
    void codeFenceWithoutLang() {
        assertEquals("{\"a\":1}", Json.extractObject("```\n{\"a\":1}\n```"));
    }

    @Test
    @DisplayName("JSON 前后夹带解释文字时能抠出来")
    void surroundingText() {
        String raw = "好的，我的判断如下：\n{\"intent\":\"SIMPLE\",\"analysis\":\"寒暄\"}\n以上就是拆解结果。";
        assertEquals("{\"intent\":\"SIMPLE\",\"analysis\":\"寒暄\"}", Json.extractObject(raw));
    }

    @Test
    @DisplayName("嵌套对象与数组能正确配平括号")
    void nested() {
        String raw = "{\"tasks\":[{\"agentId\":\"a\",\"instruction\":\"检查 {占位} 情况\"}],\"needReport\":true}";
        assertEquals(raw, Json.extractObject(raw));
    }

    @Test
    @DisplayName("字符串里的花括号和转义引号不会干扰配平")
    void bracesInsideString() {
        String raw = "{\"q\":\"他说 \\\"{x}\\\" 然后走了\",\"n\":1}";
        assertEquals(raw, Json.extractObject(raw));
    }

    @Test
    @DisplayName("括号未闭合（被 max_tokens 截断）时退化为返回剩余全部")
    void truncated() {
        String raw = "{\"intent\":\"COMPLEX\",\"tasks\":[{\"agentId\":\"a\"";
        String out = Json.extractObject(raw);
        assertNotNull(out);
        assertTrue(out.startsWith("{\"intent\""));
    }

    @Test
    @DisplayName("完全没有 JSON 时返回 null，由调用方降级")
    void noJson() {
        assertNull(Json.extractObject("今天天气不错"));
        assertNull(Json.extractObject(null));
        assertNull(Json.extractObject(""));
    }

    @Test
    @DisplayName("数组提取")
    void arrayExtraction() {
        assertEquals("[1,2,3]", Json.extractArray("结果是 [1,2,3] 共三个"));
        assertNull(Json.extractArray("{\"a\":1}"));
    }

    @Test
    @DisplayName("超长文本截断，避免把上下文撑爆")
    void truncate() {
        assertEquals("abc", Json.truncate("abc", 10));
        String long_ = "x".repeat(50);
        String cut = Json.truncate(long_, 10);
        assertTrue(cut.startsWith("xxxxxxxxxx"));
        assertTrue(cut.contains("已截断"));
        assertNull(Json.truncate(null, 10));
    }
}
