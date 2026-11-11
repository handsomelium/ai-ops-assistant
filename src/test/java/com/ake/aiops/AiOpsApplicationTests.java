package com.ake.aiops;

import com.ake.aiops.ai.agent.OpsAgentService;
import com.ake.aiops.ai.multiagent.SupervisorService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 上下文装配测试：不联网、不需要 API Key。
 *
 * <p>它验证的是"整个 Spring AI 装配是通的"——ChatModel、ChatMemory、
 * 各工具 Bean、三个专家 Agent、编排器都能正确注入。
 * 接大模型的项目里，一半以上的启动期问题都能被这一个测试提前暴露。
 */
@SpringBootTest
class AiOpsApplicationTests {

    @Autowired
    private OpsAgentService agentService;

    @Autowired
    private SupervisorService supervisorService;

    @Test
    @DisplayName("应用上下文正常启动")
    void contextLoads() {
    }

    @Test
    @DisplayName("单体 Agent 装配到了全部业务工具（读 + 写）")
    void agentHasAllTools() {
        var tools = java.util.List.of(agentService.toolNames());
        assertFalse(tools.isEmpty(), "Agent 不应没有工具");
        // 读
        assertTrue(tools.contains("listDevices"), "缺少 listDevices：" + tools);
        assertTrue(tools.contains("queryWorkOrders"), "缺少 queryWorkOrders：" + tools);
        assertTrue(tools.contains("getOpsOverview"), "缺少 getOpsOverview：" + tools);
        // 写
        assertTrue(tools.contains("createWorkOrder"), "缺少 createWorkOrder：" + tools);
        assertTrue(tools.contains("assignWorkOrder"), "缺少 assignWorkOrder：" + tools);
        assertTrue(tools.contains("updateDeviceStatus"), "缺少 updateDeviceStatus：" + tools);
    }

    @Test
    @DisplayName("多智能体：三位专家全部注册，且分析专家没有任何写权限（最小权限）")
    void expertsRegisteredWithLeastPrivilege() {
        var roster = supervisorService.roster();
        assertEquals(3, roster.size(), "应注册 3 位专家，实际：" + roster);

        var ids = roster.stream().map(SupervisorService.ExpertInfo::id).toList();
        assertTrue(ids.contains("device-expert"), "缺少设备专家：" + ids);
        assertTrue(ids.contains("workorder-expert"), "缺少工单专家：" + ids);
        assertTrue(ids.contains("analysis-expert"), "缺少分析专家：" + ids);

        var analysis = roster.stream().filter(e -> e.id().equals("analysis-expert")).findFirst().orElseThrow();
        assertFalse(analysis.tools().contains("createWorkOrder"), "分析专家不应有开单权限");
        assertFalse(analysis.tools().contains("assignWorkOrder"), "分析专家不应有派单权限");
        assertFalse(analysis.tools().contains("closeWorkOrder"), "分析专家不应有关单权限");
        assertFalse(analysis.tools().contains("updateDeviceStatus"), "分析专家不应有改设备状态权限");

        var workorder = roster.stream().filter(e -> e.id().equals("workorder-expert")).findFirst().orElseThrow();
        assertTrue(workorder.tools().contains("assignWorkOrder"), "工单专家应有派单权限");
        assertFalse(workorder.tools().contains("updateDeviceStatus"), "工单专家不应能改设备状态");
    }
}
