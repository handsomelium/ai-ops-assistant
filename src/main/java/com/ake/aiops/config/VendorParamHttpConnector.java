package com.ake.aiops.config;

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
import java.util.function.Function;

/**
 * 给 <b>流式</b> 模型调用注入厂商私有参数的 HTTP 连接器。
 *
 * <p>Spring AI 的 OpenAiApi 有两条出网通道：{@code .call()} 走 RestClient，
 * {@code .stream()} 走 WebClient。两者都要经过 {@link VendorBodyInjector}，
 * 参数内容本身不在本类里，换厂商不用改这里。
 *
 * <p>做法是包一层 {@link ClientHttpConnector}，在请求体真正写出去之前把它聚合、
 * 改写 JSON、再交给底层连接器。这里显式用 JDK HttpClient 连接器，
 * 因为本工程没有引入 reactor-netty；如果以后加了 netty，把 delegate 换掉即可。
 */
public class VendorParamHttpConnector implements ClientHttpConnector {

    private static final Logger log = LoggerFactory.getLogger(VendorParamHttpConnector.class);

    private final ClientHttpConnector delegate;
    private final VendorBodyInjector injector;

    public VendorParamHttpConnector(ClientHttpConnector delegate, VendorBodyInjector injector) {
        this.delegate = delegate;
        this.injector = injector;
    }

    @Override
    public Mono<ClientHttpResponse> connect(HttpMethod method, URI uri,
                                            Function<? super ClientHttpRequest, Mono<Void>> requestCallback) {
        if (!injector.isEnabled() || uri == null || !injector.matches(uri.getPath())) {
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

                        byte[] patched = injector.patch(original);
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
}
