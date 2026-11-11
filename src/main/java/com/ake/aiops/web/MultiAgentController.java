package com.ake.aiops.web;

import com.ake.aiops.ai.multiagent.SupervisorService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * 模块三：多智能体协同接口。
 *
 * <p>SSE 事件序列大致是：
 * <pre>
 *   agent_start(dispatcher) → agent_end(dispatcher) → plan
 *     → phase(experts)
 *     → agent_start(专家A) → tool_call → tool_result → agent_end(专家A)
 *     → agent_start(专家B) → ... → agent_end(专家B)
 *     → agent_start(report-writer) → delta×N → agent_end(report-writer)
 *     → final → done
 * </pre>
 * 前端照着这个序列就能把整个协同过程画成一条可视化的流水线。
 */
@RestController
@RequestMapping("/api/multi-agent")
public class MultiAgentController {

    private final SupervisorService supervisor;

    public MultiAgentController(SupervisorService supervisor) {
        this.supervisor = supervisor;
    }

    @PostMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> run(@Valid @RequestBody ChatRequest request) {
        return supervisor.run(request.message()).flux();
    }

    /** 专家名册：id、名称、职责、被授权的工具。 */
    @GetMapping("/roster")
    public List<SupervisorService.ExpertInfo> roster() {
        return supervisor.roster();
    }
}
