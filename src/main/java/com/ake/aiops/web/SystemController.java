package com.ake.aiops.web;

import com.ake.aiops.ai.agent.OpsAgentService;
import com.ake.aiops.ai.multiagent.SupervisorService;
import com.ake.aiops.config.AiOpsProperties;
import com.ake.aiops.config.ChatOptionsFactory;
import com.ake.aiops.config.StartupValidator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 系统状态接口：前端启动时拉一次，用来渲染配置面板和错误提示。 */
@RestController
@RequestMapping("/api/system")
public class SystemController {

    private final StartupValidator validator;
    private final ChatOptionsFactory optionsFactory;
    private final OpsAgentService agentService;
    private final SupervisorService supervisor;
    private final AiOpsProperties properties;
    private final String baseUrl;

    public SystemController(StartupValidator validator,
                            ChatOptionsFactory optionsFactory,
                            OpsAgentService agentService,
                            SupervisorService supervisor,
                            AiOpsProperties properties,
                            @Value("${spring.ai.openai.base-url:}") String baseUrl) {
        this.validator = validator;
        this.optionsFactory = optionsFactory;
        this.agentService = agentService;
        this.supervisor = supervisor;
        this.properties = properties;
        this.baseUrl = baseUrl;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("apiKeyConfigured", validator.isConfigured());
        m.put("baseUrl", baseUrl);
        m.put("chatModel", optionsFactory.getDefaultModel());
        m.put("reportModel", properties.getMultiAgent().getReportModel());
        m.put("thinkingDisabled", properties.isDisableThinking());
        m.put("parallelExperts", properties.getMultiAgent().isParallelExperts());
        m.put("memoryWindow", properties.getMemory().getWindowSize());
        m.put("longTermMemoryEnabled", properties.getMemory().isLearningEnabled());
        m.put("agentTools", List.of(agentService.toolNames()));
        m.put("experts", supervisor.roster());
        return m;
    }
}
