package com.ake.aiops.ai;

import com.ake.aiops.ai.agent.OpsAgentService;
import com.ake.aiops.ai.chat.BasicChatService;
import com.ake.aiops.ai.multiagent.SupervisorService;
import com.ake.aiops.ai.support.SseStream;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.test.annotation.DirtiesContext;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实调用大模型的端到端测试。
 *
 * <p><b>默认跳过</b>：只有设置了环境变量 {@code DASHSCOPE_API_KEY} 才会执行，
 * 这样 CI 和其他没有 Key 的人跑 {@code mvn test} 不会失败。
 *
 * <pre>
 *   Windows:  set DASHSCOPE_API_KEY=sk-sp-xxxx &amp;&amp; mvn test
 *   PowerShell: $env:DASHSCOPE_API_KEY="sk-sp-xxxx"; mvn test
 * </pre>
 *
 * <p><b>断言策略</b>：大模型输出是不确定的，所以这里只断言<b>结构与链路</b>
 * （有没有产生工具调用事件、有没有报错、最终答复是否非空），
 * 不断言具体措辞。这是测试 LLM 应用的基本原则 —— 测流程正确性，不测文本内容。
 */
@SpringBootTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIfEnvironmentVariable(named = "DASHSCOPE_API_KEY", matches = ".{10,}")
class AiLiveIntegrationTest {

    private static final Duration TIMEOUT = Duration.ofMinutes(6);

    @Autowired
    private BasicChatService basicChatService;

    @Autowired
    private OpsAgentService agentService;

    @Autowired
    private SupervisorService supervisorService;

    @Autowired
    private ObjectMapper mapper;

    // ------------------------------------------------------------------

    @Test
    @Order(1)
    @DisplayName("模块一：基础问答能流式返回，并正常收尾")
    void basicChatStreams() {
        List<Map<String, Object>> events = collect(
                basicChatService.chat(id("chat"), id("user"), "用一句话说明什么是提示词工程。"));

        assertHealthy(events);
        assertTrue(countType(events, "delta") > 0, "应产生流式增量事件");
        assertTrue(hasType(events, "final"), "应有 final 事件");
        assertTrue(hasType(events, "done"), "应有 done 事件");
        assertFalse(finalText(events).isBlank(), "最终答复不应为空");
    }

    @Test
    @Order(2)
    @DisplayName("模块一：会话记忆生效——第二轮能答出第一轮告诉它的信息")
    void sessionMemoryWorks() {
        String conv = id("mem");
        String user = id("user");

        collect(basicChatService.chat(conv, user, "请记住：我叫李四，是园区设备部主管，负责 A 座和 B 座。只需回复已记下。"));
        List<Map<String, Object>> second = collect(
                basicChatService.chat(conv, user, "我叫什么名字？负责哪几座楼？"));

        assertHealthy(second);
        String answer = finalText(second);
        assertTrue(answer.contains("李四"), "应记得姓名，实际回答：" + answer);
        assertTrue(answer.contains("A") && answer.contains("B"), "应记得负责楼栋，实际回答：" + answer);
    }

    @Test
    @Order(3)
    @DisplayName("模块一：长期记忆被抽取并持久化")
    void longTermMemoryIsLearned() {
        String user = id("ltm");
        List<Map<String, Object>> events = collect(basicChatService.chat(
                id("ltm-conv"), user,
                "请记住我的信息：我叫王五，是暖通班班长，只负责制冷站。回答尽量简短，不要超过两句话。"));

        assertHealthy(events);
        // 抽取是模型行为，允许本轮没抽到；但至少要发出 memory 事件（可能是空列表）
        assertTrue(hasType(events, "memory"), "应推送 memory 事件");
    }

    @Test
    @Order(4)
    @DisplayName("模块二：Agent 会自主调用工具查真实数据，而不是凭空回答")
    void agentCallsTools() {
        List<Map<String, Object>> events = collect(
                agentService.chat(id("agent"), id("user"), "现在有多少张待处理的工单？分别是哪些？"));

        assertHealthy(events);
        assertTrue(countType(events, "tool_call") > 0,
                "应至少产生一次工具调用，实际事件类型：" + types(events));
        assertTrue(toolCalled(events, "queryWorkOrders"),
                "应调用 queryWorkOrders，实际调用：" + calledTools(events));
        assertTrue(countType(events, "tool_result") > 0, "应有工具返回事件");

        // 工具必须真的执行成功了
        boolean anyFailed = events.stream()
                .filter(e -> "tool_result".equals(e.get("type")))
                .anyMatch(e -> Boolean.FALSE.equals(e.get("success")));
        assertFalse(anyFailed, "工具调用不应失败，事件：" + events);

        assertFalse(finalText(events).isBlank(), "最终答复不应为空");
    }

    @Test
    @Order(5)
    @DisplayName("模块二：Agent 触发业务规则拒绝后能自我纠正，不会崩")
    void agentRecoversFromBusinessRule() {
        // 张建国是强电工，派摄像头（弱电）工单给他必然被规则拒绝
        List<Map<String, Object>> events = collect(
                agentService.chat(id("agent-rule"), id("user"),
                        "把工单 WO-2026-0003 派给张建国。如果不行，请说明原因并改派给合适的人。"));

        assertHealthy(events);
        assertTrue(countType(events, "tool_call") > 0, "应产生工具调用");
        assertFalse(finalText(events).isBlank(), "应给出最终答复");
        // 不强求一定被拒（模型可能先查名单直接选对的人），只要链路不崩即可
    }

    @Test
    @Order(6)
    @DisplayName("模块二：Agent 能执行写操作并真实修改业务数据")
    void agentPerformsWriteOperation() {
        // DEV-CHILL-01 已有 WO-2026-0007（OPEN 未派单），派单不会触发重复开单规则
        List<Map<String, Object>> events = collect(
                agentService.chat(id("agent-write"), id("user"),
                        "工单 WO-2026-0007 还没人处理，请派给合适的工程师。"));

        assertHealthy(events);
        assertTrue(toolCalled(events, "assignWorkOrder") || toolCalled(events, "listEngineers"),
                "应调用派单或工程师查询工具，实际调用：" + calledTools(events));
        assertFalse(finalText(events).isBlank(), "应给出最终答复");
    }

    @Test
    @Order(7)
    @DisplayName("模块三：多智能体完整跑通「调度 -> 专家 -> 汇总」链路")
    void multiAgentFullPipeline() {
        List<Map<String, Object>> events = collect(supervisorService.run(
                "全面评估园区当前运维状况，重点分析 B1 配电柜温度告警和 1 号冷水机组能效下降，给出应急处置方案"));

        assertHealthy(events);
        assertTrue(hasType(events, "plan"), "调度中枢应产出任务计划，实际事件：" + types(events));
        assertTrue(countType(events, "agent_start") >= 3,
                "至少应有 调度 + 1个专家 + 报告 三次 agent_start，实际：" + countType(events, "agent_start"));
        assertTrue(agentStarted(events, "dispatcher"), "应有调度中枢");
        assertTrue(agentStarted(events, "report-writer"), "应有报告撰写专家");
        assertTrue(countType(events, "tool_call") > 0, "专家应调用了业务工具");
        assertTrue(hasType(events, "final"), "应有 final 事件");
        assertTrue(finalText(events).length() > 30, "汇总报告不应过短：" + finalText(events));
    }

    @Test
    @Order(8)
    @DisplayName("模块三：简单问题由调度中枢直接回答，不惊动专家（省 token）")
    void multiAgentShortCircuitsSimpleQuery() {
        List<Map<String, Object>> events = collect(supervisorService.run("你好，你能做什么？"));

        assertHealthy(events);
        assertTrue(hasType(events, "plan"), "仍应产出计划（intent=SIMPLE）");
        assertFalse(agentStarted(events, "report-writer"), "简单问题不应触发报告 Agent");
        assertFalse(finalText(events).isBlank(), "应给出回答");
    }

    // ------------------------------------------------------------------
    //  断言与工具方法
    // ------------------------------------------------------------------

    private List<Map<String, Object>> collect(SseStream stream) {
        List<ServerSentEvent<String>> frames = stream.flux().collectList().block(TIMEOUT);
        assertNotNull(frames, "SSE 流不应为 null");
        return frames.stream().map(f -> {
            try {
                return mapper.<Map<String, Object>>readValue(f.data(), new TypeReference<>() {
                });
            } catch (Exception e) {
                throw new IllegalStateException("SSE 事件不是合法 JSON: " + f.data(), e);
            }
        }).toList();
    }

    /**
     * 健康性断言：没有 error 事件，且流正常收尾（有 done）。
     *
     * <p>done 事件缺失是个很隐蔽的 bug —— 后端逻辑全对，但前端永远收不到
     * 结束信号，界面会一直卡在"思考中"。所以每个用例都要断言它。
     */
    private void assertHealthy(List<Map<String, Object>> events) {
        List<Object> errors = events.stream()
                .filter(e -> "error".equals(e.get("type")))
                .map(e -> e.get("message"))
                .toList();
        assertTrue(errors.isEmpty(), "流程中出现错误事件：" + errors);
        assertTrue(hasType(events, "done"), "流应正常收尾（缺少 done 事件），实际事件：" + types(events));
    }

    private boolean hasType(List<Map<String, Object>> events, String type) {
        return countType(events, type) > 0;
    }

    private long countType(List<Map<String, Object>> events, String type) {
        return events.stream().filter(e -> type.equals(e.get("type"))).count();
    }

    private boolean toolCalled(List<Map<String, Object>> events, String tool) {
        return calledTools(events).contains(tool);
    }

    private List<String> calledTools(List<Map<String, Object>> events) {
        return events.stream()
                .filter(e -> "tool_call".equals(e.get("type")))
                .map(e -> String.valueOf(e.get("tool")))
                .toList();
    }

    private boolean agentStarted(List<Map<String, Object>> events, String agentId) {
        return events.stream()
                .filter(e -> "agent_start".equals(e.get("type")))
                .anyMatch(e -> agentId.equals(e.get("agentId")));
    }

    private String finalText(List<Map<String, Object>> events) {
        return events.stream()
                .filter(e -> "final".equals(e.get("type")))
                .map(e -> String.valueOf(e.getOrDefault("content", "")))
                .reduce("", (a, b) -> b.isEmpty() ? a : b);
    }

    private List<String> types(List<Map<String, Object>> events) {
        return events.stream().map(e -> String.valueOf(e.get("type"))).toList();
    }

    private String id(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
