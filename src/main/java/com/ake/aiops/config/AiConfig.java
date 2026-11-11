package com.ake.aiops.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * AI 相关的基础设施 Bean。
 *
 * <p>这里刻意只放"通用能力"（记忆、线程池、ChatClient 骨架），
 * 各个业务 Agent 自己的 ChatClient 在各自的 Service 里组装 ——
 * 因为它们的 system prompt、工具集、模型参数都不一样。
 */
@Configuration
public class AiConfig {

    /**
     * 会话记忆（短期记忆）。
     *
     * <p>{@link MessageWindowChatMemory} 是滑动窗口实现：只保留最近 N 条消息，
     * 超出就丢最老的。它解决的是"模型没有状态、每次调用都是全新的"这个问题。
     * 生产环境要换成 JDBC / Redis 版本的 ChatMemoryRepository，才能多实例共享。
     */
    @Bean
    public ChatMemory chatMemory(AiOpsProperties properties) {
        return MessageWindowChatMemory.builder()
                .maxMessages(properties.getMemory().getWindowSize())
                .build();
    }

    /**
     * AI 任务线程池。
     *
     * <p>大模型调用是典型的 IO 密集 + 长耗时（几秒到几十秒），
     * 用虚拟线程最合适：一个请求一个线程，阻塞在等模型返回时不占用平台线程。
     */
    @Bean(destroyMethod = "close")
    public ExecutorService aiExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    /**
     * 通用 ChatClient：不带工具、不带记忆，用于记忆抽取这类"一次性小任务"。
     *
     * <p>各个业务 Agent 不用这个 Bean，而是在自己的 Service 里
     * 基于 {@link ChatModel} 组装专属 ChatClient ——
     * 因为它们的 system prompt、工具集、模型参数都不一样。
     */
    @Bean
    public ChatClient chatClient(ChatModel chatModel) {
        return ChatClient.builder(chatModel).build();
    }
}
