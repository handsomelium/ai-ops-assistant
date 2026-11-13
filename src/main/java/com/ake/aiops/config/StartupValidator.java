package com.ake.aiops.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * 启动自检。
 *
 * <p>接大模型的项目最容易出的问题不是代码写错，而是 <b>Key 没配 / 配错网关 /
 * 模型名和网关不配套</b>，这类错误要等到第一次真正调用才暴露，报错还很晦涩。
 * 所以启动时把当前厂商、网关、模型、私有参数都打印一遍，省掉大量排查时间。
 */
@Component
public class StartupValidator implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupValidator.class);

    /** 占位符特征词：避免把模板里的假 Key 当成"已配置"。 */
    private static final String[] PLACEHOLDER_HINTS = {
            "请替换", "换成", "placeholder", "your_api_key", "your-api-key"
    };

    private final String apiKey;
    private final String baseUrl;
    private final String completionsPath;
    private final String model;
    private final int port;
    private final VendorBodyInjector injector;

    public StartupValidator(@Value("${spring.ai.openai.api-key:}") String apiKey,
                            @Value("${spring.ai.openai.base-url:}") String baseUrl,
                            @Value("${spring.ai.openai.chat.completions-path:}") String completionsPath,
                            @Value("${spring.ai.openai.chat.options.model:}") String model,
                            @Value("${server.port:8088}") int port,
                            VendorBodyInjector injector) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.completionsPath = completionsPath;
        this.model = model;
        this.port = port;
        this.injector = injector;
    }

    @Override
    public void run(ApplicationArguments args) {
        String masked = mask(apiKey);
        System.out.println();
        System.out.println("  ========================================================");
        System.out.println("   AI 应用开发实战 Demo —— 园区智能运维工单助手");
        System.out.println("  --------------------------------------------------------");
        System.out.println("   访问地址 : http://localhost:" + port + "/");
        System.out.println("   模型厂商 : " + injector.getDisplayName() + "  [" + injector.getActive() + "]");
        System.out.println("   模型网关 : " + baseUrl + completionsPath);
        System.out.println("   默认模型 : " + model);
        System.out.println("   私有参数 : " + (injector.isEnabled()
                ? injector.getFieldNames().toString() : "未注入"));
        System.out.println("   JSON 模式: " + (injector.isJsonMode() ? "开" : "关"));
        System.out.println("   API Key  : " + masked);
        System.out.println("  ========================================================");

        if (!isConfigured()) {
            System.out.println();
            log.warn("""

                    ┌──────────────────────────────────────────────────────────────┐
                    │  ⚠ API Key 尚未配置，AI 相关接口会返回 401                    │
                    │                                                              │
                    │  打开 src/main/resources/application.yml，改最上面两行：     │
                    │     spring.profiles.active     = 模型厂家                    │
                    │     spring.ai.openai.api-key   = 你的Key                     │
                    │                                                              │
                    │  Key 必须和厂商配套，配错会 401。改完重启。                 │
                    └──────────────────────────────────────────────────────────────┘
                    """);
        } else {
            log.info("API Key 已配置（{}），业务数据面板与 AI 接口均可用。", masked);
        }
        System.out.println();
    }

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank() && !looksLikePlaceholder(apiKey);
    }

    private String mask(String key) {
        if (key == null || key.isBlank()) {
            return "(未配置)";
        }
        if (looksLikePlaceholder(key)) {
            return "(占位符，未替换)";
        }
        if (key.length() <= 12) {
            return "****";
        }
        return key.substring(0, 8) + "****" + key.substring(key.length() - 4)
                + "  (长度 " + key.length() + ")";
    }

    private boolean looksLikePlaceholder(String key) {
        String lower = key.toLowerCase(Locale.ROOT);
        for (String hint : PLACEHOLDER_HINTS) {
            if (lower.contains(hint.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
