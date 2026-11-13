package com.ake.aiops.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 厂商私有参数注入器测试。
 *
 * <p>这是多厂商兼容的关键边界：参数写错、写多、覆盖了框架已有字段，
 * 都可能让模型调用直接 400，而且很难排查。所以把合并规则、开关和容错全部钉死。
 */
class VendorBodyInjectorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String BODY = "{\"model\":\"demo\",\"messages\":[],\"stream\":false}";

    @Test
    @DisplayName("百炼参数：注入 enable_thinking=false，原有字段不丢")
    void aliyunPresetInjectsEnableThinking() throws Exception {
        VendorBodyInjector injector = new VendorBodyInjector(
                properties("{\"enable_thinking\":false}"), MAPPER);

        assertTrue(injector.isEnabled());
        assertTrue(injector.getFieldNames().contains("enable_thinking"));
        assertEquals("测试厂商", injector.getDisplayName());

        JsonNode patched = MAPPER.readTree(injector.patch(bytes(BODY)));
        assertEquals("demo", patched.get("model").asText());
        assertFalse(patched.get("enable_thinking").asBoolean());
        assertFalse(patched.get("stream").asBoolean());
        assertEquals(Boolean.FALSE, injector.asMap().get("enable_thinking"));
    }

    @Test
    @DisplayName("DeepSeek 参数：注入嵌套对象，且不带百炼字段")
    void deepseekInjectsNestedObject() throws Exception {
        VendorBodyInjector injector = new VendorBodyInjector(
                properties("{\"thinking\":{\"type\":\"disabled\"}}"), MAPPER);

        JsonNode patched = MAPPER.readTree(injector.patch(bytes(BODY)));
        assertEquals("disabled", patched.get("thinking").get("type").asText());
        assertNull(patched.get("enable_thinking"));

        Object thinking = injector.asMap().get("thinking");
        assertTrue(thinking instanceof Map);
        assertEquals("disabled", ((Map<?, ?>) thinking).get("type"));
    }

    @Test
    @DisplayName("请求体已有同名字段时不覆盖（框架修好 extraBody 会自动让路）")
    void existingFieldIsNotOverwritten() throws Exception {
        VendorBodyInjector injector = new VendorBodyInjector(
                properties("{\"enable_thinking\":false}"), MAPPER);
        byte[] body = bytes("{\"model\":\"demo\",\"enable_thinking\":true}");

        byte[] patched = injector.patch(body);
        assertSame(body, patched);
        assertTrue(MAPPER.readTree(patched).get("enable_thinking").asBoolean());
    }

    @Test
    @DisplayName("inject-enabled=false 时完全不改写，asMap 为空")
    void disabledByInjectSwitch() {
        AiOpsProperties p = properties("{\"enable_thinking\":false}");
        p.getVendor().setInjectEnabled(false);
        VendorBodyInjector injector = new VendorBodyInjector(p, MAPPER);
        byte[] body = bytes(BODY);

        assertFalse(injector.isEnabled());
        assertSame(body, injector.patch(body));
        assertTrue(injector.asMap().isEmpty());
    }

    @Test
    @DisplayName("extra-body-json 为空对象时不注入任何字段（custom 厂商）")
    void emptyExtraBodyDoesNotInject() {
        VendorBodyInjector injector = new VendorBodyInjector(properties("{}"), MAPPER);
        byte[] body = bytes(BODY);

        assertFalse(injector.isEnabled());
        assertTrue(injector.getFieldNames().isEmpty());
        assertSame(body, injector.patch(body));
    }

    @Test
    @DisplayName("请求体不是 JSON 时原样放行")
    void nonJsonBodyPassesThrough() {
        VendorBodyInjector injector = new VendorBodyInjector(
                properties("{\"enable_thinking\":false}"), MAPPER);
        byte[] body = bytes("not-a-json-body");

        assertSame(body, injector.patch(body));
        byte[] empty = new byte[0];
        assertSame(empty, injector.patch(empty));
    }

    @Test
    @DisplayName("配置里的 JSON 写错时降级为不注入，不能拖垮启动")
    void invalidConfigJsonDegradesGracefully() {
        VendorBodyInjector injector = new VendorBodyInjector(properties("{not json"), MAPPER);
        byte[] body = bytes(BODY);

        assertFalse(injector.isEnabled());
        assertSame(body, injector.patch(body));
        assertEquals("测试厂商", injector.getDisplayName());
    }

    @Test
    @DisplayName("路径标记：只匹配对话补全，不匹配其他接口")
    void pathMarkerMatches() {
        VendorBodyInjector injector = new VendorBodyInjector(
                properties("{\"thinking\":{\"type\":\"disabled\"}}"), MAPPER);

        assertTrue(injector.matches("/chat/completions"));
        assertTrue(injector.matches("/compatible-mode/v1/chat/completions"));
        assertFalse(injector.matches("/v1/models"));
        assertFalse(injector.matches(null));
    }

    @Test
    @DisplayName("json-mode 与厂商名能透传给自检和状态面板")
    void flagsAreExposed() {
        AiOpsProperties p = properties("{}");
        p.getVendor().setJsonMode(false);
        p.getVendor().setActive("custom");
        VendorBodyInjector injector = new VendorBodyInjector(p, MAPPER);

        assertFalse(injector.isJsonMode());
        assertEquals("custom", injector.getActive());
    }

    private static AiOpsProperties properties(String extraBodyJson) {
        AiOpsProperties p = new AiOpsProperties();
        p.getVendor().setActive("test");
        p.getVendor().setDisplayName("测试厂商");
        p.getVendor().setExtraBodyJson(extraBodyJson);
        return p;
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
