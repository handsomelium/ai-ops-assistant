package com.ake.aiops.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 切到 deepseek profile 后的装配验证：连接四参数和私有参数必须同时切过去。
 *
 * <p>这是"profile 一键切换"最核心的回归测试 —— 只切一半（比如网关换了但
 * completions-path 没换、或者思考模式没关）在实际调用时才会暴露。
 */
@SpringBootTest
@ActiveProfiles("deepseek")
class DeepSeekProfileBindingTest {

    @Autowired
    private VendorBodyInjector injector;

    @Autowired
    private AiOpsProperties properties;

    @Value("${spring.ai.openai.base-url}")
    private String baseUrl;

    @Value("${spring.ai.openai.chat.completions-path}")
    private String completionsPath;

    @Value("${spring.ai.openai.chat.options.model}")
    private String chatModel;

    @Test
    @DisplayName("profile 切好网关、端点、两个模型名")
    void connectionSettingsAreSwitched() {
        assertEquals("https://api.deepseek.com", baseUrl);
        assertEquals("/chat/completions", completionsPath);
        assertEquals("deepseek-flash", chatModel);
        assertEquals("deepseek-flash", properties.getMultiAgent().getReportModel());
    }

    @Test
    @DisplayName("DeepSeek 段落生效，关闭思考模式且不带百炼字段")
    void vendorBlockIsSwitched() {
        assertEquals("deepseek", injector.getActive());
        assertEquals("DeepSeek", injector.getDisplayName());
        assertTrue(injector.isEnabled());
        assertTrue(injector.getFieldNames().contains("thinking"));
        assertNull(injector.asMap().get("enable_thinking"));

        Object thinking = injector.asMap().get("thinking");
        assertTrue(thinking instanceof Map);
        assertEquals("disabled", ((Map<?, ?>) thinking).get("type"));
    }
}
