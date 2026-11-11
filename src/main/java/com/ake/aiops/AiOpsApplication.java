package com.ake.aiops;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * AI 应用开发实战培训 Demo —— 园区智能运维工单助手。
 *
 * <p>三个递进的能力层次：
 * <ol>
 *   <li>基础问答：ChatClient + 提示词工程 + 会话记忆 + 长期记忆</li>
 *   <li>单体 Agent：@Tool 把业务 Service 暴露给模型，模型自主决策调用</li>
 *   <li>多智能体协同：调度 Agent 拆解任务，专家 Agent 分工执行，报告 Agent 汇总</li>
 * </ol>
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class AiOpsApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiOpsApplication.class, args);
    }
}
