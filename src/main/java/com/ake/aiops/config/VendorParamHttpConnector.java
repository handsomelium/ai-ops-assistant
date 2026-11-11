package com.ake.aiops.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.reactive.ClientHttpConnector;
import org.springframework.http.client.reactive.ClientHttpRequest;
import org.springframework.http.client.reactive.ClientHttpRequestDecorator;
import org.springframework.http.client.reactive.ClientHttpResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.function.Function;

/**
 * 给 <b>流式</b> 模型调用注入厂商私有参数的 HTTP 连接器。
 *
 * <p><b>为什么 RestClient 拦截器不够？</b>
 * Spring AI 的 OpenAiApi 有两条出网通道：
 * <ul>
 *   <li>{@code .call()} 阻塞式 → 走 <b>RestClient</b>，能被 {@code RestClientCustomizer} 拦到</li>
 *   <li>{@code .stream()} 流式 → 走 <b>WebClient</b>，RestClient 那套完全管不着</li>
 * </ul>
 * 用本地回显服务抓包验证过：同一个应用里，非流式请求带上了 {@code enable_thinking}，
 * 流式请求却没有。所以两条通道都得处理，否则"关掉思考模式"只对一半调用生效 ——
 * 而恰恰是流式那条（用户能直接感知延迟的那条）没生效。
 *
 * <p>做法是包一层 {@link ClientHttpConnector}，在请求体真正写出去之前把它聚合、
 * 改写 JSON、再交给底层连接器。这里显式用 JDK HttpClient 连接器，
 * 因为本工程没有引入 reactor-netty；如果以后加了 netty，把 delegate 换掉即可。
 */
public class VendorParamHttpConnector implements ClientHttpConnector {

    private static final Logger log = LoggerFactory.getLogger(VendorParamHttpConnector.class);
    private static final String CHAT_PATH_MARKER = "/chat/completions";

    private final ClientHttpConnector delegate;
    private final ObjectMapper mapper;
    private final AiOpsProperties properties;

    public VendorParamHttpConnector(ClientHttpConnector delegate, ObjectMapper mapper,
                                    AiOpsProperties properties) {
        this.delegate = delegate;
        this.mapper = mapper;
        this.properties = properties;
    }

    @Override
    public Mono<ClientHttpResponse> connect(HttpMethod method, URI uri,
                                            Function<? super ClientHttpRequest, Mono<Void>> requestCallback) {
        if (!properties.isDisableThinking()
                || uri == null || uri.getPath() == null || !uri.getPath().contains(CHAT_PATH_MARKER)) {
            return delegate.connect(method, uri, requestCallback);
        }
        return delegate.connect(method, uri, request -> requestCallback.apply(new PatchingRequest(request)));
    }

    /** 包装单个请求：聚合请求体 -> 注入参数 -> 交给真正的请求对象。 */
    private final class PatchingRequest extends ClientHttpRequestDecorator {

        PatchingRequest(ClientHttpRequest delegate) {
            super(delegate);
        }

        @Override
        public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
            return DataBufferUtils.join(Flux.from(body))
                    .flatMap(joined -> {
                        byte[] original = new byte[joined.readableByteCount()];
                        joined.read(original);
                        DataBufferUtils.release(joined);

                        byte[] patched = inject(original);
                        if (patched.length != original.length) {
                            fixContentLength(patched.length);
                        }
                        return getDelegate().writeWith(Mono.just(bufferFactory().wrap(patched)));
                    })
                    // 请求体为空时不要吞掉，照原样走完
                    .switchIfEmpty(Mono.defer(() -> getDelegate().writeWith(Flux.empty())));
        }

        @Override
        public Mono<Void> writeAndFlushWith(Publisher<? extends Publisher<? extends DataBuffer>> body) {
            // 展平成分段流后统一走 writeWith，逻辑只写一份
            return writeWith(Flux.from(body).flatMapSequential(Flux::from));
        }

        private void fixContentLength(int length) {
            try {
                HttpHeaders headers = getHeaders();
                if (headers.containsKey(HttpHeaders.CONTENT_LENGTH)) {
                    headers.setContentLength(length);
                }
            } catch (Exception e) {
                log.debug("Content-Length 已只读，跳过修正（通常走 chunked，不影响）");
            }
        }
    }

    /** 往 JSON 请求体里补 enable_thinking=false；已存在或不是对象就原样返回。 */
    private byte[] inject(byte[] body) {
        if (body == null || body.length == 0) {
            return body == null ? new byte[0] : body;
        }
        try {
            JsonNode root = mapper.readTree(body);
            if (!(root instanceof ObjectNode obj) || obj.has("enable_thinking")) {
                return body;
            }
            obj.put("enable_thinking", false);
            return mapper.writeValueAsBytes(obj);
        } catch (Exception e) {
            // 解析失败就原样发出去，绝不能因为一个优化参数把请求搞挂
            log.debug("请求体不是可解析的 JSON，跳过注入：{}",
                    new String(body, 0, Math.min(body.length, 60), StandardCharsets.UTF_8));
            return body;
        }
    }
}
