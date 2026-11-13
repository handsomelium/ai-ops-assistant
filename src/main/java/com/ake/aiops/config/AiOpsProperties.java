package com.ake.aiops.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Demo 业务侧可调参数，对应 application.yml 里的 aiops.* 前缀。 */
@ConfigurationProperties(prefix = "aiops")
public class AiOpsProperties {

    private Memory memory = new Memory();
    private MultiAgent multiAgent = new MultiAgent();
    private Vendor vendor = new Vendor();

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

    public Vendor getVendor() {
        return vendor;
    }

    public void setVendor(Vendor vendor) {
        this.vendor = vendor;
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
        /** 报告撰写 Agent 使用的模型（各厂商段落里会覆盖） */
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

    /**
     * 厂商差异配置 —— 全部写在 application.yml 对应厂商的 --- 段落里。
     *
     * <p>连接参数（网关 / 路径 / 模型名）属于 {@code spring.ai.openai.*}，
     * Spring AI 只认那个 namespace；这里只管协议之外的东西：私有请求体参数、
     * 显示名、路径过滤、JSON 模式开关。
     */
    public static class Vendor {
        /** 当前厂商名（显示在自检和状态面板） */
        private String active = "default";
        /** 显示名 */
        private String displayName = "自定义厂商";
        /** 要合并进请求体的 JSON 对象，例如 {"enable_thinking":false} */
        private String extraBodyJson = "{}";
        /** 总开关：false = 完全不改写请求体 */
        private boolean injectEnabled = true;
        /** 只改写路径里含这个标记的请求 */
        private String pathMarker = "/chat/completions";
        /** 服务端是否支持 response_format=json_object */
        private boolean jsonMode = true;

        public String getActive() {
            return active;
        }

        public void setActive(String active) {
            this.active = active;
        }

        public String getDisplayName() {
            return displayName;
        }

        public void setDisplayName(String displayName) {
            this.displayName = displayName;
        }

        public String getExtraBodyJson() {
            return extraBodyJson;
        }

        public void setExtraBodyJson(String extraBodyJson) {
            this.extraBodyJson = extraBodyJson;
        }

        public boolean isInjectEnabled() {
            return injectEnabled;
        }

        public void setInjectEnabled(boolean injectEnabled) {
            this.injectEnabled = injectEnabled;
        }

        public String getPathMarker() {
            return pathMarker;
        }

        public void setPathMarker(String pathMarker) {
            this.pathMarker = pathMarker;
        }

        public boolean isJsonMode() {
            return jsonMode;
        }

        public void setJsonMode(boolean jsonMode) {
            this.jsonMode = jsonMode;
        }
    }
}
