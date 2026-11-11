package com.ake.aiops.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Demo 业务侧可调参数，对应 application.yml 里的 aiops.* 前缀。 */
@ConfigurationProperties(prefix = "aiops")
public class AiOpsProperties {

    /**
     * 是否关闭 qwen3 系列的思考模式。
     * qwen3.x 默认先输出一大段 reasoning 再给答案，实测单次可达 76 秒；
     * 多智能体串联时会放大数倍，课堂演示基本不可用，所以默认关闭。
     */
    private boolean disableThinking = true;

    private Memory memory = new Memory();
    private MultiAgent multiAgent = new MultiAgent();

    public boolean isDisableThinking() {
        return disableThinking;
    }

    public void setDisableThinking(boolean disableThinking) {
        this.disableThinking = disableThinking;
    }

    public Memory getMemory() {
        return memory;
    }

    public void setMemory(Memory memory) {
        this.memory = memory;
    }

    public MultiAgent getMultiAgent() {
        return multiAgent;
    }

    public void setMultiAgent(MultiAgent multiAgent) {
        this.multiAgent = multiAgent;
    }

    /** 记忆相关配置。 */
    public static class Memory {
        /** 会话记忆窗口大小（条） */
        private int windowSize = 20;
        /** 长期记忆持久化文件 */
        private String storeFile = System.getProperty("java.io.tmpdir") + "/aiops-long-term-memory.json";
        /** 每个用户最多保留多少条长期记忆 */
        private int maxFactsPerUser = 20;
        /** 是否在每轮对话后异步抽取长期记忆 */
        private boolean learningEnabled = true;

        public int getWindowSize() {
            return windowSize;
        }

        public void setWindowSize(int windowSize) {
            this.windowSize = windowSize;
        }

        public String getStoreFile() {
            return storeFile;
        }

        public void setStoreFile(String storeFile) {
            this.storeFile = storeFile;
        }

        public int getMaxFactsPerUser() {
            return maxFactsPerUser;
        }

        public void setMaxFactsPerUser(int maxFactsPerUser) {
            this.maxFactsPerUser = maxFactsPerUser;
        }

        public boolean isLearningEnabled() {
            return learningEnabled;
        }

        public void setLearningEnabled(boolean learningEnabled) {
            this.learningEnabled = learningEnabled;
        }
    }

    /** 多智能体编排配置。 */
    public static class MultiAgent {
        /** 报告撰写 Agent 使用的模型 */
        private String reportModel = "qwen3.8-flash";
        /** 专家 Agent 是否并行执行 */
        private boolean parallelExperts = true;

        public String getReportModel() {
            return reportModel;
        }

        public void setReportModel(String reportModel) {
            this.reportModel = reportModel;
        }

        public boolean isParallelExperts() {
            return parallelExperts;
        }

        public void setParallelExperts(boolean parallelExperts) {
            this.parallelExperts = parallelExperts;
        }
    }
}
