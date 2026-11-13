package com.ake.aiops.config;

import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.boot.web.reactive.function.client.WebClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.JdkClientHttpConnector;

/**
 * 在两条出网通道上挂请求体改写，把厂商私有参数补进真实发出的 HTTP 请求。
 *
 * <p><b>为什么需要这两个 Customizer？——一个真实踩到的框架坑。</b>
 * Spring AI 1.1.0 的 {@code OpenAiChatOptions} 明明提供了 {@code extraBody}，
 * {@code ChatCompletionRequest} 上也有对应字段和 {@code @JsonAnyGetter}，
 * 看起来设置一下就能传私有参数。但实测<b>不生效</b>：
 * {@code OpenAiChatModel} 组装请求时并没有把 extraBody 拷过去，参数被静默丢弃。
 * 验证方法：起一个本地回显服务，把 {@code spring.ai.openai.base-url} 指过去
 * 抓真实请求体，结果只有 {@code messages / model / stream / temperature}。
 *
 * <p><b>为什么私有参数很重要？</b>不少厂商默认开启思考模式：百炼 qwen3.7-plus
 * 实测回答一个 30 字的问题要 76 秒，DeepSeek 思考模式携带 tools 时还要求
 * 回传 {@code reasoning_content}（Spring AI 不会回传，直接 400）。
 * 关掉之后本 Demo 的多智能体全链路从 171s 降到 38s。
 *
 * <p><b>改动后的结构</b>：参数内容来自 application.yml 当前厂商段落的
 * {@code aiops.vendor.extra-body-json}，由 {@link VendorBodyInjector} 统一解析和合并；
 * 这里只负责把同一个注入器接到两条通道上，不再有任何厂商专属字段名。
 */
@Configuration
public class VendorParamConfig {

    /** 非流式（.call()）走 RestClient，能被 RestClientCustomizer 拦到。 */
    @Bean
    public RestClientCustomizer vendorParamCustomizer(VendorBodyInjector injector) {
        return builder -> builder.requestInterceptor((request, body, execution) -> {
            if (!injector.isEnabled() || body == null || body.length == 0
                    || !injector.matches(request.getURI().getPath())) {
                return execution.execute(request, body);
            }
            return execution.execute(request, injector.patch(body));
        });
    }

    /**
     * 流式（.stream()）走 WebClient，上面那个 RestClient 拦截器管不到，
     * 所以再挂一个 {@code WebClientCustomizer}，把连接器换成会改写请求体的包装版本。
     * 两条通道都必须处理，否则"关掉思考模式"只对一半调用生效 ——
     * 而恰恰是流式那条（用户能直接感知延迟的那条）没生效。
     *
     * <p>本工程没有引入 reactor-netty，WebClient 默认就用 JDK HttpClient，
     * 这里显式指定同一个实现，避免"包装后反而换了底层 HTTP 客户端"。
     */
    @Bean
    public WebClientCustomizer vendorParamWebClientCustomizer(VendorBodyInjector injector) {
        return builder -> builder.clientConnector(
                new VendorParamHttpConnector(new JdkClientHttpConnector(), injector));
    }
}
