package com.ake.aiops.ai.prompt;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 提示词加载器：把提示词从 Java 代码里挪到 resources/prompts/*.st。
 *
 * <p><b>为什么不用 Spring AI 的 {@code .system(Resource)} 或 {@code .system(String)}？</b>
 * 因为它们会经过 {@code StTemplateRenderer}（StringTemplate）渲染，
 * 而 StringTemplate 把 {@code { }} 当作变量定界符。
 * 我们的调度提示词里写了 JSON 输出示例，满屏都是花括号，
 * 走模板渲染要么报错、要么把示例吃掉 —— 这是接 Spring AI 时非常常见的一个坑。
 *
 * <p>所以这里自己读文件、自己用 {@code {{VAR}}} 占位符做替换，
 * 完全可控，也方便非开发人员直接改提示词文件而不用动 Java 代码。
 */
@Component
public class PromptLoader {

    private static final Logger log = LoggerFactory.getLogger(PromptLoader.class);

    private final Map<String, String> cache = new ConcurrentHashMap<>();

    /** 加载提示词原文。 */
    public String load(String name) {
        return cache.computeIfAbsent(name, this::read);
    }

    /** 加载并替换 {{KEY}} 占位符。 */
    public String render(String name, Map<String, String> vars) {
        String text = load(name);
        for (Map.Entry<String, String> e : vars.entrySet()) {
            text = text.replace("{{" + e.getKey() + "}}", e.getValue() == null ? "" : e.getValue());
        }
        return text;
    }

    private String read(String name) {
        String path = "prompts/" + name + ".st";
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("提示词文件 {} 读取失败", path, e);
            throw new IllegalStateException("提示词文件缺失: " + path, e);
        }
    }
}
