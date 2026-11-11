package com.ake.aiops.web;

import com.ake.aiops.ai.agent.OpsAgentService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.Map;

/** 模块二：单体 Agent（工具调用）接口。 */
@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private final OpsAgentService agentService;

    public AgentController(OpsAgentService agentService) {
        this.agentService = agentService;
    }

    /**
     * 流式对话。和基础问答接口的签名几乎一样，
     * 差别全在服务端：这个 Agent 挂了业务工具，模型会自己去调。
     * 前端通过 SSE 里的 tool_call / tool_result 事件把调用过程画出来。
     */
    @PostMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> chat(@Valid @RequestBody ChatRequest request) {
        return agentService.chat(
                "agent-" + request.conversationIdOr("default"),
                request.userIdOr("demo-user"),
                request.message()).flux();
    }

    /** 这个 Agent 被授权了哪些工具。 */
    @GetMapping("/tools")
    public Map<String, Object> tools() {
        String[] names = agentService.toolNames();
        return Map.of("count", names.length, "tools", names);
    }
}
