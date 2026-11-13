package com.ake.aiops.ai.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/**
 * 统一的 SSE 事件流封装。
 *
 * <p>三个模块（基础问答 / 单体 Agent / 多智能体）都用它往前端推事件，
 * 事件格式统一为 {@code data: {"type":"xxx", ...}}，前端只需按 type 分发。
 *
 * <p>为什么不用 {@code SseEmitter}？因为大模型的流式返回本身是 {@link Flux}，
 * 用 Reactor 的 {@link Sinks.Many} 可以把"模型输出的 token 流"和
 * "我们自己产生的过程事件（工具调用、Agent 切换）"合并成一条流，
 * 不需要额外的线程同步代码。
 *
 * <p>用法：
 * <pre>{@code
 * SseStream stream = SseStream.create(objectMapper);
 * executor.submit(() -> {
 *     try {
 *         stream.emit("agent_start", ...);
 *         ...
 *         stream.complete();
 *     } catch (Exception e) {
 *         stream.fail(e);
 *     }
 * });
 * return stream.flux();   // 立刻返回给 MVC，后续事件异步推送
 * }</pre>
 */
public final class SseStream {

    private static final Logger log = LoggerFactory.getLogger(SseStream.class);

    private final Sinks.Many<ServerSentEvent<String>> sink =
            Sinks.many().unicast().onBackpressureBuffer();
    private final ObjectMapper mapper;
    private final long startAt = System.currentTimeMillis();
    private volatile boolean finished = false;

    private SseStream(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public static SseStream create(ObjectMapper mapper) {
        return new SseStream(mapper);
    }

    public Flux<ServerSentEvent<String>> flux() {
        return sink.asFlux();
    }

    /**
     * 推送一个事件。payload 会被序列化成 JSON 对象，并自动补上 type 字段。
     *
     * <p>方法加了 {@code synchronized}：多智能体并行执行时，多个专家 Agent
     * 会在不同线程上同时推事件，而 Reactor 的 Sinks 不是线程安全的
     * （并发 tryEmitNext 会返回 FAIL_NON_SERIALIZED 并<b>静默丢事件</b>）。
     *
     * @param type    事件类型，前端按它分发
     * @param payload 事件内容，通常是 record
     */
    public synchronized void emit(String type, Object payload) {
        if (finished) {
            return;
        }
        doEmit(type, payload);
    }

    /**
     * 真正的发送逻辑，不检查 finished。
     *
     * <p>单独拆出来是因为 {@link #complete()} 和 {@link #fail()} 需要在置位
     * finished <b>之后</b>还能发出最后一帧（done / error）。
     * 如果直接在 complete() 里调 emit()，那一帧会被 finished 守卫静默吞掉 ——
     * 前端就永远等不到结束信号，界面会一直卡在"思考中"。
     */
    private void doEmit(String type, Object payload) {
        try {
            ObjectNode node;
            if (payload == null) {
                node = mapper.createObjectNode();
            } else if (payload instanceof CharSequence cs) {
                node = mapper.createObjectNode().put("content", cs.toString());
            } else {
                node = mapper.valueToTree(payload);
            }
            node.put("type", type);
            node.put("ts", System.currentTimeMillis() - startAt);
            sink.tryEmitNext(ServerSentEvent.<String>builder(mapper.writeValueAsString(node)).build());
        } catch (Exception e) {
            log.warn("SSE 事件序列化失败 type={}", type, e);
        }
    }

    /** 推送一个模型输出的文本增量。 */
    public void delta(String text) {
        if (text != null && !text.isEmpty()) {
            emit("delta", text);
        }
    }

    /** 正常结束。 */
    public synchronized void complete() {
        if (finished) {
            return;
        }
        finished = true;
        doEmit("done", new Events.Done(System.currentTimeMillis() - startAt));
        sink.tryEmitComplete();
    }

    /** 异常结束：先把错误告诉前端，再关流。 */
    public synchronized void fail(Throwable t) {
        if (finished) {
            return;
        }
        log.error("AI 流程执行失败", t);
        String msg = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
        if (msg.contains("401") || msg.toLowerCase().contains("invalid_api_key")
                || msg.contains("Incorrect API key")) {
            msg = "API Key 无效或未配置，或 Key 与当前厂商不配套。请检查 application.yml 顶部的 "
                    + "spring.profiles.active 与 spring.ai.openai.api-key，改完重启。原始错误：" + msg;
        }
        finished = true;
        doEmit("error", new Events.Error(msg));
        sink.tryEmitComplete();
    }

    public boolean isFinished() {
        return finished;
    }
}
