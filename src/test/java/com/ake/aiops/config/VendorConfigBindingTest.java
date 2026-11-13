package com.ake.aiops.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 默认厂商段落（aliyun-token-plan）的装配验证。
 *
 * <p>重点确认 application.yml 里 {@code ---} 分隔的厂商段落真的被激活并绑定 ——
 * 只切一半（网关换了但私有参数没换，或反过来）在实际调用时才会暴露。
 */
@SpringBootTest
class VendorConfigBindingTest {

    @Autowired
    private AiOpsProperties properties;

    @Autowired
    private VendorBodyInjector injector;

    @Value("${spring.ai.openai.base-url}")
    private String baseUrl;

    @Value("${spring.ai.openai.chat.completions-path}")
    private String completionsPath;

    @Value("${spring.ai.openai.chat.options.model}")
    private String chatModel;

    @Test
    @DisplayName("默认仍是百炼 Token Plan，连接参数逐项保持不变")
    void defaultConnectionSettings() {
        assertEquals("https://token-plan.cn-beijing.maas.aliyuncs.com", baseUrl);
        assertEquals("/compatible-mode/v1/chat/completions", completionsPath);
        assertEquals("qwen3.8-flash", chatModel);
        assertEquals("qwen3.8-flash", properties.getMultiAgent().getReportModel());
    }

    @Test
    @DisplayName("厂商段落能绑定显示名与私有参数")
    void vendorBlockIsBound() {
        assertEquals("aliyun-token-plan", properties.getVendor().getActive());
        assertEquals("阿里云百炼 Token Plan", injector.getDisplayName());
        assertTrue(injector.isEnabled());
        assertTrue(injector.getFieldNames().contains("enable_thinking"));
        assertEquals(Boolean.FALSE, injector.asMap().get("enable_thinking"));
        assertTrue(injector.isJsonMode());
    }
}
