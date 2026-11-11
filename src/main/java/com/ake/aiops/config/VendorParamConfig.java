package com.ake.aiops.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.boot.web.reactive.function.client.WebClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.reactive.JdkClientHttpConnector;

/**
 * 把厂商私有参数 {@code enable_thinking=false} 注入到实际发出的 HTTP 请求体里。
 *
 * <p><b>为什么需要这个类？——一个真实踩到的框架坑。</b>
 *
 * <p>Spring AI 1.1.0 的 {@code OpenAiChatOptions} 明明提供了 {@code extraBody}，
 * {@code ChatCompletionRequest} 上也有对应字段和 {@code @JsonAnyGetter}，
 * 看起来设置一下就能传私有参数。但实测<b>不生效</b>：
 * {@code OpenAiChatModel} 在把 options 组装成请求时并没有把 extraBody 拷过去，
 * 参数被静默丢弃 —— 不报错、不警告，你只会发现"模型怎么这么慢"。
 *
 * <p>验证方法（也是排查这类问题的通用手段）：起一个本地回显 HTTP 服务，
 * 把 {@code spring.ai.openai.base-url} 指过去，抓一份真实请求体出来看。
 * 抓到的结果确实只有 {@code messages / model / stream / temperature}。
 *
 * <p><b>为什么这个参数很重要？</b>实测同一个问题（3000 字材料 → 1000 字报告）：
 * <pre>
 *   enable_thinking=true  ：12.4s，reasoning 805 字，completion 997 tokens
 *   enable_thinking=false ： 7.7s，reasoning   0 字，completion 649 tokens
 * </pre>
 * 而小问题上的差距更夸张：qwen3.8-flash 开思考 3.9s / 关思考 0.8s，
 * qwen3.7-plus 开思考 <b>76.2s</b>（4684 个 reasoning token）。
 * 多智能体串联会把这个问题放大好几倍 —— 本 Demo 实测汇总节点因此从 ~10s 涨到 109s。
 *
 * <p>所以这里用 {@link RestClientCustomizer} 挂一个拦截器，在请求真正发出前
 * 把参数补进 JSON 体。Spring Boot 会把所有 {@code RestClientCustomizer} Bean
 * 应用到自动装配的 {@code RestClient.Builder} 上，而 Spring AI 正是用这个 Builder
 * 建 OpenAiApi 的，因此能覆盖到全部模型调用。
 */
@Configuration
public class VendorParamConfig {

    private static final Logger log = LoggerFactory.getLogger(VendorParamConfig.class);

    /** 只处理对话补全接口，避免影响应用里其他的 RestClient 调用。 */
    private static final String CHAT_PATH_MARKER = "/chat/completions";

    @Bean
    public RestClientCustomizer vendorParamCustomizer(AiOpsProperties properties, ObjectMapper mapper) {
        return builder -> builder.requestInterceptor(thinkingInterceptor(properties, mapper));
    }

    /**
     * 流式调用走的是 WebClient，上面那个 RestClient 拦截器管不到，
     * 所以再挂一个 {@code WebClientCustomizer}，把连接器换成会改写请求体的包装版本。
     *
     * <p>本工程没有引入 reactor-netty，WebClient 默认就会用 JDK HttpClient，
     * 这里显式指定同一个实现，避免"包装后反而换了底层 HTTP 客户端"。
     */
    @Bean
    public WebClientCustomizer vendorParamWebClientCustomizer(AiOpsProperties properties, ObjectMapper mapper) {
        return builder -> builder.clientConnector(
                new VendorParamHttpConnector(new JdkClientHttpConnector(), mapper, properties));
    }

    private ClientHttpRequestInterceptor thinkingInterceptor(AiOpsProperties properties, ObjectMapper mapper) {
        return (request, body, execution) -> {
            if (!properties.isDisableThinking() || body == null || body.length == 0) {
                return execution.execute(request, body);
            }
            String path = request.getURI().getPath();
            if (path == null || !path.contains(CHAT_PATH_MARKER)) {
                return execution.execute(request, body);
            }
            try {
                JsonNode root = mapper.readTree(body);
                if (!(root instanceof ObjectNode obj)) {
                    return execution.execute(request, body);
                }
                // 已经带了就不覆盖：万一将来 Spring AI 修好了 extraBody，这里自动让路
                if (obj.has("enable_thinking")) {
                    return execution.execute(request, body);
                }
                obj.put("enable_thinking", false);
                byte[] patched = mapper.writeValueAsBytes(obj);
                log.debug("已注入 enable_thinking=false，请求体 {} -> {} 字节", body.length, patched.length);
                return execution.execute(request, patched);
            } catch (Exception e) {
                // 解析失败就原样放行，绝不能因为一个优化参数把请求搞挂
                log.warn("注入 enable_thinking 失败，按原请求发送：{}", e.getMessage());
                return execution.execute(request, body);
            }
        };
    }
}
