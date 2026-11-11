package com.ake.aiops.web;

import com.ake.aiops.ai.chat.BasicChatService;
import com.ake.aiops.ai.memory.LongTermMemoryService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

/**
 * 模块一：基础问答接口。
 *
 * <p>注意返回类型是 {@code Flux<ServerSentEvent<String>>} 而不是 {@code SseEmitter}。
 * Spring MVC 在检测到 classpath 上有 Reactor 时，可以直接把 Flux 当 SSE 流输出，
 * 这样就能和大模型的流式返回无缝对接，不用自己写线程和队列。
 *
 * <p>另外这里用的是 <b>POST + SSE</b>。浏览器原生的 {@code EventSource} 只支持 GET，
 * 所以前端要用 {@code fetch} + {@code ReadableStream} 手动解析（见 static/js/app.js）。
 * 这是 AI 应用里非常常见的组合，因为对话内容通常要放在请求体里。
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final BasicChatService chatService;
    private final LongTermMemoryService longTermMemory;

    public ChatController(BasicChatService chatService, LongTermMemoryService longTermMemory) {
        this.chatService = chatService;
        this.longTermMemory = longTermMemory;
    }

    /** 流式问答。 */
    @PostMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> chat(@Valid @RequestBody ChatRequest request) {
        return chatService.chat(
                request.conversationIdOr("default"),
                request.userIdOr("demo-user"),
                request.message()).flux();
    }

    /** 查看某个会话当前的会话记忆（教学可视化用）。 */
    @GetMapping("/session-memory")
    public Map<String, Object> sessionMemory(@RequestParam(defaultValue = "default") String conversationId) {
        List<String> preview = chatService.sessionPreview(conversationId);
        return Map.of("conversationId", conversationId, "size", preview.size(), "messages", preview);
    }

    /** 清空会话记忆。 */
    @DeleteMapping("/session-memory")
    public Map<String, Object> clearSession(@RequestParam(defaultValue = "default") String conversationId) {
        chatService.clearConversation(conversationId);
        return Map.of("cleared", conversationId);
    }

    /** 查看某个用户的长期记忆。 */
    @GetMapping("/long-term-memory")
    public Map<String, Object> longTerm(@RequestParam(defaultValue = "demo-user") String userId) {
        return Map.of("userId", userId, "facts", longTermMemory.factsOf(userId));
    }

    /** 清空长期记忆。 */
    @DeleteMapping("/long-term-memory")
    public Map<String, Object> clearLongTerm(@RequestParam(defaultValue = "demo-user") String userId) {
        longTermMemory.clear(userId);
        return Map.of("cleared", userId);
    }
}
