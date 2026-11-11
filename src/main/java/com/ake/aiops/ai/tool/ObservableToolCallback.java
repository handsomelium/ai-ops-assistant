package com.ake.aiops.ai.tool;

import com.ake.aiops.ai.support.Events;
import com.ake.aiops.ai.support.Json;
import com.ake.aiops.ai.support.SseStream;
import com.ake.aiops.ai.support.WriteAudit;
import com.ake.aiops.service.BusinessRuleException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

/**
 * 工具调用的可观测装饰器。
 *
 * <p>包一层的目的有三个，都是生产环境必须解决的：
 * <ol>
 *   <li><b>可视化</b>：把每次工具调用的名称、入参、结果、耗时推给前端。
 *       没有这个，Agent 就是个黑盒，出了问题完全无法定位。</li>
 *   <li><b>业务异常降级</b>：{@link BusinessRuleException} 不能抛给框架变成 500，
 *       要转成一句模型看得懂的话返回去，模型读到后会自己换参数重试 ——
 *       这才是"把业务约束喂回模型"的正确姿势。</li>
 *   <li><b>结果裁剪</b>：工具返回内容过长时截断，防止一次查询把上下文撑爆。</li>
 * </ol>
 */
public class ObservableToolCallback implements ToolCallback {

    /** 放在 ToolContext 里的键：SSE 事件流 */
    public static final String CTX_STREAM = "aiops.sseStream";
    /** 放在 ToolContext 里的键：当前是哪个 Agent 在调用 */
    public static final String CTX_AGENT = "aiops.agentName";
    /** 放在 ToolContext 里的键：写操作审计，用于事后校验模型有没有谎报动作 */
    public static final String CTX_AUDIT = "aiops.writeAudit";

    private static final Logger log = LoggerFactory.getLogger(ObservableToolCallback.class);
    private static final int MAX_RESULT_CHARS = 4000;

    private final ToolCallback delegate;

    public ObservableToolCallback(ToolCallback delegate) {
        this.delegate = delegate;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    @Override
    public String call(String toolInput) {
        return call(toolInput, new ToolContext(java.util.Map.of()));
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        String name = delegate.getToolDefinition().name();
        SseStream stream = stream(toolContext);
        String agent = agent(toolContext);

        if (stream != null) {
            stream.emit("tool_call", new Events.ToolCall(name, Json.truncate(toolInput, 500), agent));
        }
        log.debug("[工具调用] agent={} tool={} args={}", agent, name, toolInput);

        long t0 = System.currentTimeMillis();
        String result;
        boolean success = true;
        try {
            result = delegate.call(toolInput, toolContext);
        } catch (Exception e) {
            Throwable cause = rootCause(e);
            success = false;
            if (cause instanceof BusinessRuleException) {
                // 关键：把业务规则的拒绝理由原样告诉模型，它会据此纠正
                result = "【业务规则拒绝】" + cause.getMessage();
                log.info("[业务规则拒绝] tool={} 原因={}", name, cause.getMessage());
            } else {
                result = "【工具执行失败】" + cause.getClass().getSimpleName() + ": " + cause.getMessage();
                log.error("[工具执行异常] tool={}", name, cause);
            }
        }
        long elapsed = System.currentTimeMillis() - t0;

        // 记账：这是后面校验"模型有没有谎报动作"的唯一事实来源
        WriteAudit audit = audit(toolContext);
        if (audit != null) {
            audit.recordCall(name);
            if (success) {
                audit.recordSuccess(name);
            } else {
                audit.recordRejected(name);
            }
        }

        result = Json.truncate(result, MAX_RESULT_CHARS);
        if (stream != null) {
            stream.emit("tool_result", new Events.ToolResult(name,
                    Json.truncate(result, 300), elapsed, success, agent));
        }
        log.debug("[工具返回] tool={} 耗时={}ms 成功={}", name, elapsed, success);
        return result;
    }

    private SseStream stream(ToolContext ctx) {
        Object o = ctx == null ? null : ctx.getContext().get(CTX_STREAM);
        return o instanceof SseStream s ? s : null;
    }

    private String agent(ToolContext ctx) {
        Object o = ctx == null ? null : ctx.getContext().get(CTX_AGENT);
        return o == null ? null : o.toString();
    }

    private WriteAudit audit(ToolContext ctx) {
        Object o = ctx == null ? null : ctx.getContext().get(CTX_AUDIT);
        return o instanceof WriteAudit a ? a : null;
    }

    private Throwable rootCause(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur;
    }
}
