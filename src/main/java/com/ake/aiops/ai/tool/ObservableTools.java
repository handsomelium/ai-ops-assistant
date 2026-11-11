package com.ake.aiops.ai.tool;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

import java.util.Arrays;

/**
 * 把带 {@code @Tool} 注解的 Bean 转成"可观测"的 ToolCallback 数组。
 *
 * <p>{@code MethodToolCallbackProvider} 是 Spring AI 扫描 {@code @Tool} 注解、
 * 自动生成 JSON Schema 的入口 —— 这就是为什么工具方法只要写好注解和
 * {@code @ToolParam} 描述，模型那边就能拿到完整的函数签名。
 */
public final class ObservableTools {

    private ObservableTools() {
    }

    public static ToolCallback[] of(Object... toolObjects) {
        ToolCallback[] raw = MethodToolCallbackProvider.builder()
                .toolObjects(toolObjects)
                .build()
                .getToolCallbacks();
        return Arrays.stream(raw).map(ObservableToolCallback::new).toArray(ToolCallback[]::new);
    }

    public static String[] names(ToolCallback[] callbacks) {
        return Arrays.stream(callbacks).map(c -> c.getToolDefinition().name()).toArray(String[]::new);
    }
}
