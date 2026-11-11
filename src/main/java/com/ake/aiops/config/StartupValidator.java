package com.ake.aiops.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 启动自检。
 *
 * <p>接大模型的项目最容易出的问题不是代码写错，而是 <b>Key 没配 / 配错网关</b>，
 * 而这类错误要等到第一次真正调用才暴露，报错信息还很晦涩（401 invalid_api_key）。
 * 所以启动时就检查一遍，把话说清楚，省掉大量排查时间。
 */
@Component
public class StartupValidator implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupValidator.class);
    private static final String PLACEHOLDER = "请替换为你的百炼TokenPlan_APIKey";

    private final String apiKey;
    private final String baseUrl;
    private final String model;
    private final int port;

    public StartupValidator(@Value("${spring.ai.openai.api-key:}") String apiKey,
                            @Value("${spring.ai.openai.base-url:}") String baseUrl,
                            @Value("${spring.ai.openai.chat.options.model:}") String model,
                            @Value("${server.port:8088}") int port) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.model = model;
        this.port = port;
    }

    @Override
    public void run(ApplicationArguments args) {
        String masked = mask(apiKey);
        System.out.println();
        System.out.println("  ========================================================");
        System.out.println("   AI 应用开发实战 Demo —— 园区智能运维工单助手");
        System.out.println("  --------------------------------------------------------");
        System.out.println("   访问地址 : http://localhost:" + port + "/");
        System.out.println("   模型网关 : " + baseUrl);
        System.out.println("   默认模型 : " + model);
        System.out.println("   API Key  : " + masked);
        System.out.println("  ========================================================");

        if (!isConfigured()) {
            System.out.println();
            log.warn("""

                    ┌──────────────────────────────────────────────────────────────┐
                    │  ⚠ API Key 尚未配置，AI 相关接口会返回 401                    │
                    │                                                              │
                    │  两种配置方式，任选其一：                                     │
                    │  1) 改 application.yml：                                     │
                    │     spring.ai.openai.api-key: 你的Key                        │
                    │  2) 设环境变量（推荐，Key 不进代码库）：                      │
                    │     set DASHSCOPE_API_KEY=你的Key        (Windows cmd)        │
                    │     $env:DASHSCOPE_API_KEY="你的Key"     (PowerShell)         │
                    │                                                              │
                    │  注意：Token Plan（sk-sp- 开头）必须配 token-plan 网关；      │
                    │        普通百炼 Key（sk- 开头）必须配 dashscope 网关。        │
                    │        两者不通用，配错会直接 401。                           │
                    └──────────────────────────────────────────────────────────────┘
                    """);
        } else {
            log.info("API Key 已配置（{}），业务数据面板与 AI 接口均可用。", masked);
        }
        System.out.println();
    }

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank() && !apiKey.contains(PLACEHOLDER);
    }

    private String mask(String key) {
        if (key == null || key.isBlank()) {
            return "(未配置)";
        }
        if (key.contains(PLACEHOLDER)) {
            return "(占位符，未替换)";
        }
        if (key.length() <= 12) {
            return "****";
        }
        return key.substring(0, 8) + "****" + key.substring(key.length() - 4)
                + "  (长度 " + key.length() + ")";
    }
}
