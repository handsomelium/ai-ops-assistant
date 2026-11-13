package com.ake.aiops.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把「厂商私有请求体参数」合并进真实发出的 HTTP 请求。
 *
 * <p><b>为什么要抽成单独一个类？</b>
 * 不同厂商的私有参数字段名完全不同：百炼要 {@code {"enable_thinking":false}}，
 * DeepSeek 要 {@code {"thinking":{"type":"disabled"}}}，标准 OpenAI / vLLM / Ollama
 * 则什么都不能加。Spring AI 的 {@code .call()} 走 RestClient、{@code .stream()}
 * 走 WebClient，两条通道都得处理，否则"关掉思考模式"只对一半调用生效 ——
 * 而恰恰是流式那条（用户能感知延迟的）没生效。现在两条通道共用这里。
 *
 * <p><b>为什么必须在 HTTP 层注入？</b>
 * Spring AI 1.1.0 的 {@code OpenAiChatOptions.extraBody} 实测会被静默丢弃：
 * 字段有、{@code @JsonAnyGetter} 也有，但 {@code OpenAiChatModel} 组装请求时
 * 没有把它拷过去。用本地回显服务抓真实请求体，只有
 * {@code messages / model / stream / temperature}。所以只能在这里兜底。
 *
 * <p><b>参数来源</b>：application.yml 里当前厂商段落的
 * {@code aiops.vendor.extra-body-json}，换厂商不用改 Java。
 *
 * <p><b>容错约定</b>：请求体不是 JSON 对象、配置写错、字段已存在（比如将来
 * Spring AI 修好了 extraBody）都原样放行 —— 绝不能因为一个优化参数把请求搞挂。
 */
@Component
public class VendorBodyInjector {

    private static final Logger log = LoggerFactory.getLogger(VendorBodyInjector.class);

    private final ObjectMapper mapper;
    private final ObjectNode extras;
    private final boolean enabled;
    private final String active;
    private final String displayName;
    private final String pathMarker;
    private final boolean jsonMode;
    private final List<String> fieldNames;

    public VendorBodyInjector(AiOpsProperties properties, ObjectMapper mapper) {
        this.mapper = mapper;
        AiOpsProperties.Vendor vendor = properties.getVendor();

        this.active = blankTo(vendor.getActive(), "default");
        this.displayName = blankTo(vendor.getDisplayName(), active);
        this.pathMarker = blankTo(vendor.getPathMarker(), "/chat/completions");
        this.jsonMode = vendor.isJsonMode();
        this.extras = parseObject(vendor.getExtraBodyJson());

        List<String> names = new ArrayList<>();
        this.extras.fieldNames().forEachRemaining(names::add);
        this.fieldNames = Collections.unmodifiableList(names);
        this.enabled = vendor.isInjectEnabled() && !this.fieldNames.isEmpty();

        log.info("模型厂商 [{}] {}，注入字段 {}，路径匹配 '{}'，JSON 模式 {}",
                active, displayName, fieldNames, pathMarker, jsonMode ? "开" : "关");
    }

    /** 当前是否真的会改写请求体。 */
    public boolean isEnabled() {
        return enabled;
    }

    public String getActive() {
        return active;
    }

    public String getDisplayName() {
        return displayName;
    }

    public boolean isJsonMode() {
        return jsonMode;
    }

    public List<String> getFieldNames() {
        return fieldNames;
    }

    /** 只改写路径里含这个标记的请求，避免影响应用里其他的 RestClient 调用。 */
    public boolean matches(String path) {
        return path != null && path.contains(pathMarker);
    }

    /** 同一份参数也喂给 OpenAiChatOptions.extraBody，防止将来框架修好后两处不一致。 */
    public Map<String, Object> asMap() {
        if (!enabled) {
            return Map.of();
        }
        Map<String, Object> map = mapper.convertValue(extras, new TypeReference<LinkedHashMap<String, Object>>() {
        });
        return map == null ? Map.of() : map;
    }

    /** 合并参数到请求体。已存在的 key 不覆盖；任何异常都原样返回。 */
    public byte[] patch(byte[] body) {
        if (!enabled || body == null || body.length == 0) {
            return body == null ? new byte[0] : body;
        }
        try {
            JsonNode root = mapper.readTree(body);
            if (!(root instanceof ObjectNode obj)) {
                return body;
            }
            boolean changed = false;
            var iterator = extras.fields();
            while (iterator.hasNext()) {
                Map.Entry<String, JsonNode> e = iterator.next();
                if (!obj.has(e.getKey())) {
                    obj.set(e.getKey(), e.getValue().deepCopy());
                    changed = true;
                }
            }
            if (!changed) {
                return body;
            }
            byte[] patched = mapper.writeValueAsBytes(obj);
            log.debug("已注入厂商参数 {}，请求体 {} -> {} 字节", fieldNames, body.length, patched.length);
            return patched;
        } catch (Exception e) {
            // 绝不能因为一个优化参数把请求搞挂
            log.debug("请求体不是可解析的 JSON，跳过注入：{}",
                    new String(body, 0, Math.min(body.length, 60), StandardCharsets.UTF_8));
            return body;
        }
    }

    private ObjectNode parseObject(String json) {
        if (json == null || json.isBlank()) {
            return mapper.createObjectNode();
        }
        try {
            JsonNode node = mapper.readTree(json);
            if (node instanceof ObjectNode obj) {
                return obj;
            }
            log.warn("aiops.vendor.extra-body-json 必须是 JSON 对象，当前值被忽略：{}", json);
        } catch (Exception e) {
            log.warn("aiops.vendor.extra-body-json 解析失败，已忽略：{} / {}", json, e.getMessage());
        }
        return mapper.createObjectNode();
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
