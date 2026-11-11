package com.ake.aiops.ai.multiagent;

import com.ake.aiops.ai.prompt.PromptLoader;
import com.ake.aiops.ai.support.Events;
import com.ake.aiops.ai.support.Json;
import com.ake.aiops.ai.support.PromptVars;
import com.ake.aiops.ai.support.SseStream;
import com.ake.aiops.config.AiOpsProperties;
import com.ake.aiops.config.ChatOptionsFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 报告撰写 Agent（汇总节点）。
 *
 * <p>它是唯一一个<b>没有任何工具</b>的 Agent —— 只负责把各专家的结论整合成
 * 给最终用户看的回复。这样设计的好处：
 * <ul>
 *   <li>汇总环节不会再引入新的数据，杜绝"报告里冒出专家没查过的数字"</li>
 *   <li>可以给汇总节点单独配一个更擅长写作的模型（配置项 aiops.multi-agent.report-model）</li>
 *   <li>专家的输出格式可以很粗糙（给机器看的），报告格式统一由这一层保证</li>
 * </ul>
 */
@Component
public class ReportWriterAgent {

    public static final String ID = "report-writer";
    public static final String NAME = "报告撰写专家";
    private static final Logger log = LoggerFactory.getLogger(ReportWriterAgent.class);
    private static final Duration TIMEOUT = Duration.ofMinutes(4);

    private final ChatClient chatClient;
    private final ChatOptionsFactory optionsFactory;
    private final PromptLoader prompts;
    private final AiOpsProperties properties;

    public ReportWriterAgent(ChatModel chatModel,
                             ChatOptionsFactory optionsFactory,
                             PromptLoader prompts,
                             AiOpsProperties properties) {
        this.chatClient = ChatClient.builder(chatModel).build();
        this.optionsFactory = optionsFactory;
        this.prompts = prompts;
        this.properties = properties;
    }

    /**
     * 汇总各专家结论，流式输出最终报告。
     *
     * @return 完整报告文本
     */
    public String writeReport(String userQuestion, List<ExpertOutcome> outcomes, SseStream stream) {
        String material = buildMaterial(userQuestion, outcomes);
        stream.emit("agent_start", new Events.AgentStart(ID, NAME,
                "整合各专家结论，产出给最终用户的回复（无工具，不引入新数据）",
                "汇总 " + outcomes.size() + " 位专家的结论"));
        long t0 = System.currentTimeMillis();

        String system = prompts.render("reporter-system", Map.of("CURRENT_TIME", PromptVars.now()));
        StringBuilder sb = new StringBuilder();
        try {
            chatClient.prompt()
                    // 汇总节点可以单独配模型，写作用强模型、查询用快模型是很常见的成本优化
                    .options(optionsFactory.chat(properties.getMultiAgent().getReportModel(), 0.4))
                    .messages(new SystemMessage(system))
                    .user(material)
                    .stream()
                    .content()
                    .doOnNext(token -> {
                        sb.append(token);
                        stream.delta(token);
                    })
                    .blockLast(TIMEOUT);
        } catch (Exception e) {
            log.error("[报告撰写] 失败，降级为拼接专家原始结论", e);
            String fallback = "报告生成失败（" + e.getMessage() + "），以下是各专家的原始结论：\n\n" + material;
            stream.delta(fallback);
            sb.setLength(0);
            sb.append(fallback);
        }
        String report = sb.toString();
        stream.emit("agent_end", new Events.AgentEnd(ID, NAME,
                Json.truncate(report, 800), System.currentTimeMillis() - t0));
        return report;
    }

    /** 把用户问题和各专家结论拼成汇总 Agent 的输入材料。 */
    private String buildMaterial(String userQuestion, List<ExpertOutcome> outcomes) {
        StringBuilder sb = new StringBuilder();
        sb.append("【用户原始问题】\n").append(userQuestion).append("\n\n");
        sb.append("【各专家的工作结论】\n");
        for (ExpertOutcome o : outcomes) {
            sb.append("\n## ").append(o.displayName()).append("（").append(o.agentId()).append("）\n");
            sb.append("收到的指令：").append(o.instruction()).append('\n');
            sb.append("结论：\n").append(o.output() == null ? "（无输出）" : o.output()).append('\n');
            sb.append("耗时：").append(o.elapsedMs()).append("ms\n");
        }
        return sb.toString();
    }
}
