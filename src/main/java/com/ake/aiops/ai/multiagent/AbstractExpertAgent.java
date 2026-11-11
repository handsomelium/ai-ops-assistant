package com.ake.aiops.ai.multiagent;

import com.ake.aiops.ai.prompt.PromptLoader;
import com.ake.aiops.ai.support.Events;
import com.ake.aiops.ai.support.Json;
import com.ake.aiops.ai.support.PromptVars;
import com.ake.aiops.ai.support.SseStream;
import com.ake.aiops.ai.support.WriteAudit;
import com.ake.aiops.ai.support.WriteClaimGuard;
import com.ake.aiops.ai.tool.ObservableToolCallback;
import com.ake.aiops.ai.tool.ObservableTools;
import com.ake.aiops.config.ChatOptionsFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * 专家 Agent 的公共骨架。
 *
 * <p>三个设计决定值得注意：
 * <ol>
 *   <li><b>专家不挂 ChatMemory。</b>专家是"一次性任务执行者"，
 *       上下文由调度中枢在 instruction 里显式给全。
 *       给专家挂会话记忆反而会引入无关历史，干扰判断、白白消耗 token。</li>
 *   <li><b>工具集在构造期固定。</b>每个专家只拿到自己那份工具（最小权限），
 *       例如分析专家拿不到任何写操作工具，从根上杜绝越权。</li>
 *   <li><b>专家失败不炸整个流程。</b>execute 内部吞掉异常并返回错误说明，
 *       让报告 Agent 能在最终回复里如实交代"某一路调查失败了"。
 *       多智能体链路长，任何一环抛异常都会让用户看到一片空白。</li>
 * </ol>
 */
public abstract class AbstractExpertAgent implements ExpertAgent {

    private static final Logger log = LoggerFactory.getLogger(AbstractExpertAgent.class);
    private static final Duration TIMEOUT = Duration.ofMinutes(4);

    protected final ChatClient chatClient;
    protected final ChatOptionsFactory optionsFactory;
    protected final PromptLoader prompts;
    private final WriteClaimGuard writeClaimGuard;
    private final ToolCallback[] tools;

    protected AbstractExpertAgent(ChatModel chatModel,
                                  ChatOptionsFactory optionsFactory,
                                  PromptLoader prompts,
                                  WriteClaimGuard writeClaimGuard,
                                  Object... toolBeans) {
        this.chatClient = ChatClient.builder(chatModel).build();
        this.optionsFactory = optionsFactory;
        this.prompts = prompts;
        this.writeClaimGuard = writeClaimGuard;
        this.tools = ObservableTools.of(toolBeans);
    }

    /** 对应的提示词文件名（resources/prompts/{name}.st）。 */
    protected abstract String promptName();

    @Override
    public String[] toolNames() {
        return ObservableTools.names(tools);
    }

    protected String systemPrompt() {
        return prompts.render(promptName(), Map.of("CURRENT_TIME", PromptVars.now()));
    }

    @Override
    public String execute(String instruction, SseStream stream) {
        stream.emit("agent_start", new Events.AgentStart(id(), displayName(), capability(), instruction));
        long t0 = System.currentTimeMillis();
        WriteAudit audit = new WriteAudit();
        try {
            String output = chatClient.prompt()
                    .options(optionsFactory.chat())
                    .messages(new SystemMessage(systemPrompt()))
                    .user(instruction)
                    .toolCallbacks(tools)
                    .toolContext(Map.of(
                            ObservableToolCallback.CTX_STREAM, stream,
                            ObservableToolCallback.CTX_AGENT, displayName(),
                            ObservableToolCallback.CTX_AUDIT, audit))
                    .call()
                    .content();
            output = applyGuard(output, audit);
            long elapsed = System.currentTimeMillis() - t0;
            log.info("[{}] 执行完成，耗时 {}ms，结论 {} 字", displayName(), elapsed,
                    output == null ? 0 : output.length());
            stream.emit("agent_end", new Events.AgentEnd(id(), displayName(),
                    Json.truncate(output, 800), elapsed));
            return output == null ? "" : output;
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - t0;
            log.error("[{}] 执行失败", displayName(), e);
            String msg = "【该专家执行失败】" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            stream.emit("agent_end", new Events.AgentEnd(id(), displayName(), msg, elapsed));
            return msg;
        }
    }

    @Override
    public String executeStreaming(String instruction, SseStream stream) {
        stream.emit("agent_start", new Events.AgentStart(id(), displayName(), capability(), instruction));
        long t0 = System.currentTimeMillis();
        WriteAudit audit = new WriteAudit();
        StringBuilder sb = new StringBuilder();
        try {
            chatClient.prompt()
                    .options(optionsFactory.chat())
                    .messages(new SystemMessage(systemPrompt()))
                    .user(instruction)
                    .toolCallbacks(tools)
                    .toolContext(Map.of(
                            ObservableToolCallback.CTX_STREAM, stream,
                            ObservableToolCallback.CTX_AGENT, displayName(),
                            ObservableToolCallback.CTX_AUDIT, audit))
                    .stream()
                    .content()
                    .doOnNext(token -> {
                        sb.append(token);
                        stream.delta(token);
                    })
                    .blockLast(TIMEOUT);
        } catch (Exception e) {
            log.error("[{}] 流式执行失败", displayName(), e);
            String msg = "【该专家执行失败】" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            stream.delta(msg);
            sb.append(msg);
        }
        String output = applyGuard(sb.toString(), audit, stream);
        stream.emit("agent_end", new Events.AgentEnd(id(), displayName(),
                Json.truncate(output, 800), System.currentTimeMillis() - t0));
        return output;
    }

    /**
     * 动作幻觉校验：专家也一样会说"已派单"却其实没调工具。
     * 校验不通过就把纠正说明追加到结论里，让它一路带到报告 Agent，
     * 避免错误结论被汇总进最终报告。
     */
    private String applyGuard(String output, WriteAudit audit) {
        return applyGuard(output, audit, null);
    }

    private String applyGuard(String output, WriteAudit audit, SseStream stream) {
        if (output == null || output.isBlank()) {
            return output;
        }
        Optional<String> correction = writeClaimGuard.verify(output, audit);
        if (correction.isEmpty()) {
            return output;
        }
        log.warn("[{}] 动作幻觉已纠正：实际调用={} 成功写操作={}",
                displayName(), audit.calledTools(), audit.succeededWrites());
        if (stream != null) {
            stream.delta(correction.get());
            stream.emit("warning", Map.of(
                    "message", displayName() + " 声称已执行写操作，但审计记录显示没有成功的写操作",
                    "calledTools", audit.calledTools()));
        }
        return output + correction.get();
    }
}
