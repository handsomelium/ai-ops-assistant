package com.ake.aiops.config;

import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.ResponseFormat;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 统一构造 ChatOptions：模型、温度、以及厂商私有参数。
 *
 * <p>集中一处的原因是：项目里有多处 ChatClient（基础问答、单体 Agent、
 * 三个专家、报告撰写），每处的模型和温度都可能不同，但"关掉思考模式"
 * 这类全局约定必须一致，散在各处一定会漏。
 *
 * <p>关于私有参数 {@code enable_thinking}：
 * {@link OpenAiChatOptions} 提供了 {@code extraBody}，本意就是给非 OpenAI 官方
 * 服务（百炼、DeepSeek、Kimi…）传厂商参数的逃生舱口，但 <b>Spring AI 1.1.0
 * 实际会把它丢掉</b>，详见 {@link VendorParamConfig} 的说明。
 *
 * <p>实测数据（同一个问题，30 字以内回答）：
 * <pre>
 *   qwen3.8-flash  开思考：3.9s / 230 tokens（其中 reasoning 215）
 *   qwen3.8-flash  关思考：0.8s /  18 tokens
 *   qwen3.7-plus   开思考：76.2s / 4703 tokens  ← 多智能体串联会直接不可用
 * </pre>
 */
@Component
public class ChatOptionsFactory {

    private final String defaultModel;
    private final boolean disableThinking;

    public ChatOptionsFactory(
            @Value("${spring.ai.openai.chat.options.model:qwen3.8-flash}") String defaultModel,
            AiOpsProperties properties) {
        this.defaultModel = defaultModel;
        this.disableThinking = properties.isDisableThinking();
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
     * 强制 JSON 输出。多智能体里的"调度 Agent"必须用它 ——
     * 只有拿到严格结构化结果，编排器才能可靠地解析任务计划。
     */
    public OpenAiChatOptions json(String model, double temperature) {
        return base(model, temperature)
                .responseFormat(ResponseFormat.builder().type(ResponseFormat.Type.JSON_OBJECT).build())
                .build();
    }

    public OpenAiChatOptions json() {
        return json(defaultModel, 0.1);
    }

    private OpenAiChatOptions.Builder base(String model, double temperature) {
        OpenAiChatOptions.Builder builder = OpenAiChatOptions.builder()
                .model(model == null || model.isBlank() ? defaultModel : model)
                .temperature(temperature);
        if (disableThinking) {
            // 注意：Spring AI 1.1.0 组装请求时会把 extraBody 丢掉，这里设置实际不生效。
            // 真正兜住它的是 VendorParamConfig 里的 HTTP 拦截器。
            // 保留这行是为了表达意图，并且一旦框架修复，拦截器会检测到 key 已存在而自动让路。
            Map<String, Object> extra = new HashMap<>();
            extra.put("enable_thinking", false);
            builder.extraBody(extra);
        }
        return builder;
    }
}
