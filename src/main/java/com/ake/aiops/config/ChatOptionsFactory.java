package com.ake.aiops.config;

import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.ResponseFormat;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 统一构造 ChatOptions：模型、温度、厂商私有参数、JSON 输出开关。
 *
 * <p>集中一处的原因是：项目里有多处 ChatClient（基础问答、单体 Agent、
 * 三个专家、报告撰写），每处的模型和温度都可能不同，但"私有参数"这类
 * 全局约定必须一致，散在各处一定会漏。
 *
 * <p>私有参数来自 {@link VendorBodyInjector}（即 application.yml 当前厂商段落的
 * {@code aiops.vendor.extra-body-json}）。
 * 注意 Spring AI 1.1.0 组装请求时会把 {@code extraBody} 丢掉，真正兜住它的是
 * HTTP 层注入；这里仍设置一份，是为了表达意图，并且一旦框架修复，
 * 注入器会检测到 key 已存在而自动让路，不会出现两份不一致。
 */
@Component
public class ChatOptionsFactory {

    private final String defaultModel;
    private final VendorBodyInjector injector;

    public ChatOptionsFactory(
            @Value("${spring.ai.openai.chat.options.model:qwen3.8-flash}") String defaultModel,
            VendorBodyInjector injector) {
        this.defaultModel = defaultModel;
        this.injector = injector;
    }

    public String getDefaultModel() {
        return defaultModel;
    }

    /** 默认模型 + 常规对话参数。 */
    public OpenAiChatOptions chat() {
        return chat(defaultModel, 0.3);
    }

    public OpenAiChatOptions chat(String model) {
        return chat(model, 0.3);
    }

    public OpenAiChatOptions chat(String model, double temperature) {
        return base(model, temperature).build();
    }

    /**
     * 强制 JSON 输出。多智能体里的"调度 Agent"必须拿到结构化结果，
     * 编排器才能可靠解析任务计划。
     *
     * <p>少数兼容服务不支持 {@code response_format=json_object}，
     * 用 {@code aiops.vendor.json-mode=false} 关掉，此时退化为提示词约束
     * + {@code Json.extractObject} 容错解析 + 两次重试，链路不会崩。
     */
    public OpenAiChatOptions json(String model, double temperature) {
        OpenAiChatOptions.Builder builder = base(model, temperature);
        if (injector.isJsonMode()) {
            builder.responseFormat(ResponseFormat.builder().type(ResponseFormat.Type.JSON_OBJECT).build());
        }
        return builder.build();
    }

    public OpenAiChatOptions json() {
        return json(defaultModel, 0.1);
    }

    private OpenAiChatOptions.Builder base(String model, double temperature) {
        OpenAiChatOptions.Builder builder = OpenAiChatOptions.builder()
                .model(model == null || model.isBlank() ? defaultModel : model)
                .temperature(temperature);
        Map<String, Object> extra = injector.asMap();
        if (!extra.isEmpty()) {
            builder.extraBody(extra);
        }
        return builder;
    }
}
