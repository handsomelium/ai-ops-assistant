package com.ake.aiops.ai.chat;

import com.ake.aiops.ai.memory.LongTermMemoryService;
import com.ake.aiops.ai.prompt.PromptLoader;
import com.ake.aiops.ai.support.Events;
import com.ake.aiops.ai.support.Json;
import com.ake.aiops.ai.support.PromptVars;
import com.ake.aiops.ai.support.SseStream;
import com.ake.aiops.config.ChatOptionsFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 * 模块一：基础问答。
 *
 * <p>这是 AI 集成到业务系统里的<b>最小可用形态</b>，涉及四个知识点：
 * <ol>
 *   <li><b>提示词工程</b>：system prompt 从外部文件加载，明确角色、能力边界、回答风格。
 *       特别注意里面写了"不能编造业务数据"——这是没有工具时最重要的护栏。</li>
 *   <li><b>会话记忆</b>：{@link MessageChatMemoryAdvisor} 自动把历史消息拼进 prompt，
 *       用 conversationId 隔离不同会话。模型本身是无状态的，"记得上文"完全靠这个。</li>
 *   <li><b>长期记忆</b>：每轮结束后异步抽取稳定事实，下一轮注入 system prompt。
 *       跨会话生效，且体积恒定，不像会话记忆会越滚越长。</li>
 *   <li><b>流式输出</b>：SSE 把 token 一个个推给前端，首字延迟从几秒降到几百毫秒。</li>
 * </ol>
 */
@Service
public class BasicChatService {

    private static final Logger log = LoggerFactory.getLogger(BasicChatService.class);
    private static final Duration TIMEOUT = Duration.ofMinutes(3);

    private final ChatClient chatClient;
    private final ChatMemory chatMemory;
    private final LongTermMemoryService longTermMemory;
    private final ChatOptionsFactory optionsFactory;
    private final PromptLoader prompts;
    private final ExecutorService aiExecutor;
    private final ObjectMapper mapper;

    public BasicChatService(ChatModel chatModel,
                            ChatMemory chatMemory,
                            LongTermMemoryService longTermMemory,
                            ChatOptionsFactory optionsFactory,
                            PromptLoader prompts,
                            ExecutorService aiExecutor,
                            ObjectMapper mapper) {
        this.chatMemory = chatMemory;
        this.longTermMemory = longTermMemory;
        this.optionsFactory = optionsFactory;
        this.prompts = prompts;
        this.aiExecutor = aiExecutor;
        this.mapper = mapper;
        // 记忆能力通过 Advisor 挂载，业务代码里完全不用手动拼历史消息
        this.chatClient = ChatClient.builder(chatModel)
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .build();
    }

    /**
     * 发起一次流式问答。方法立刻返回 SSE 流，实际推理在虚拟线程里异步跑。
     *
     * @param conversationId 会话 ID，决定用哪一份会话记忆
     * @param userId         用户 ID，决定用哪一份长期记忆
     */
    public SseStream chat(String conversationId, String userId, String message) {
        SseStream stream = SseStream.create(mapper);
        aiExecutor.submit(() -> run(stream, conversationId, userId, message));
        return stream;
    }

    private void run(SseStream stream, String conversationId, String userId, String message) {
        try {
            // 教学可视化：把本轮开始前的记忆状态推给前端，让"记忆"这件事看得见
            emitMemorySnapshot(stream, conversationId, userId);

            Map<String, String> vars = new HashMap<>();
            vars.put("CURRENT_TIME", PromptVars.now());
            vars.put("LONG_TERM_MEMORY", longTermMemory.renderForPrompt(userId));

            StringBuilder answer = new StringBuilder();
            chatClient.prompt()
                    .options(optionsFactory.chat())
                    // 用 SystemMessage 而不是 .system(String)，避开 StringTemplate 对花括号的解析
                    .messages(new SystemMessage(prompts.render("basic-system", vars)))
                    .user(message)
                    .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                    .stream()
                    .content()
                    .doOnNext(token -> {
                        answer.append(token);
                        stream.delta(token);
                    })
                    .blockLast(TIMEOUT);

            String full = answer.toString();
            stream.emit("final", new Events.Final(full));

            // 长期记忆学习：同步做，这样前端能立刻看到"记住了什么"。
            // 生产环境建议丢到消息队列异步处理，别拖慢响应。
            int added = longTermMemory.learn(userId, message, full);
            if (added > 0) {
                stream.emit("memory", new Events.Memory(userId, longTermMemory.factsOf(userId)));
            }
            stream.complete();
        } catch (Exception e) {
            stream.fail(e);
        }
    }

    private void emitMemorySnapshot(SseStream stream, String conversationId, String userId) {
        List<Message> history = chatMemory.get(conversationId);
        List<String> preview = history.stream()
                .map(m -> roleOf(m) + ": " + Json.truncate(m.getText(), 60))
                .toList();
        stream.emit("session_memory", new Events.SessionMemory(conversationId, history.size(), preview));
        stream.emit("memory", new Events.Memory(userId, longTermMemory.factsOf(userId)));
    }

    private String roleOf(Message m) {
        MessageType type = m.getMessageType();
        return switch (type) {
            case USER -> "用户";
            case ASSISTANT -> "助手";
            case SYSTEM -> "系统";
            case TOOL -> "工具";
        };
    }

    /** 清空某个会话的会话记忆（长期记忆单独清）。 */
    public void clearConversation(String conversationId) {
        chatMemory.clear(conversationId);
        log.info("已清空会话记忆 {}", conversationId);
    }

    public List<String> sessionPreview(String conversationId) {
        return chatMemory.get(conversationId).stream()
                .map(m -> roleOf(m) + ": " + Json.truncate(m.getText(), 80))
                .toList();
    }
}
