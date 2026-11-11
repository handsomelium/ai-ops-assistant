package com.ake.aiops.ai.memory;

import com.ake.aiops.ai.support.Json;
import com.ake.aiops.config.AiOpsProperties;
import com.ake.aiops.config.ChatOptionsFactory;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 长期记忆服务。
 *
 * <p><b>和会话记忆的区别（这是培训里最容易混的一点）：</b>
 * <table border="1">
 *   <tr><th></th><th>会话记忆 ChatMemory</th><th>长期记忆 LongTermMemory</th></tr>
 *   <tr><td>存什么</td><td>原始对话消息</td><td>从对话里<b>抽取</b>出的结论/事实</td></tr>
 *   <tr><td>生命周期</td><td>一个会话，窗口滚动淘汰</td><td>跨会话持久化</td></tr>
 *   <tr><td>怎么进上下文</td><td>Advisor 自动拼进 messages</td><td>拼进 system prompt</td></tr>
 *   <tr><td>成本</td><td>免费，但越滚越长</td><td>每轮多一次抽取调用，但体积恒定</td></tr>
 * </table>
 *
 * <p>实现思路：每轮对话结束后，异步让模型做一次"记忆抽取"，
 * 把稳定可复用的事实（用户身份、偏好、约束）存下来；下次对话开始时
 * 注入 system prompt。Demo 落 JSON 文件，生产环境换向量库或数据库即可。
 */
@Service
public class LongTermMemoryService {

    private static final Logger log = LoggerFactory.getLogger(LongTermMemoryService.class);
    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    private final ChatClient chatClient;
    private final ChatOptionsFactory optionsFactory;
    private final AiOpsProperties properties;
    private final ObjectMapper mapper;
    private final Resource promptResource;

    private final Map<String, List<Fact>> store = new ConcurrentHashMap<>();
    private volatile String extractionPrompt = "";

    public LongTermMemoryService(ChatClient chatClient,
                                 ChatOptionsFactory optionsFactory,
                                 AiOpsProperties properties,
                                 ObjectMapper mapper,
                                 @Value("classpath:prompts/memory-extract.st") Resource promptResource) {
        this.chatClient = chatClient;
        this.optionsFactory = optionsFactory;
        this.properties = properties;
        this.mapper = mapper;
        this.promptResource = promptResource;
    }

    /** 一条长期记忆。 */
    public record Fact(String text, String learnedAt) {
    }

    @PostConstruct
    void init() {
        try {
            extractionPrompt = promptResource.getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("读取记忆抽取提示词失败，将使用内置兜底提示词", e);
            extractionPrompt = "从对话中抽取值得长期记住的用户事实，输出 {\"facts\":[\"...\"]}，没有则输出 {\"facts\":[]}";
        }
        load();
    }

    // ------------------------------------------------------------------
    //  读取
    // ------------------------------------------------------------------

    public List<String> factsOf(String userId) {
        return store.getOrDefault(key(userId), List.of())
                .stream().map(Fact::text).toList();
    }

    /**
     * 渲染成可直接拼进 system prompt 的文本。
     * 没有记忆时返回空串，调用方直接拼接即可。
     */
    public String renderForPrompt(String userId) {
        List<String> facts = factsOf(userId);
        if (facts.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("\n\n【你对该用户已有的长期记忆】\n");
        for (String f : facts) {
            sb.append("- ").append(f).append('\n');
        }
        sb.append("（以上由历史对话自动抽取，可能已过时。若与用户当前表述冲突，一律以当前表述为准，并更新记忆。）");
        return sb.toString();
    }

    public void clear(String userId) {
        store.remove(key(userId));
        persist();
    }

    private String key(String userId) {
        return userId == null || userId.isBlank() ? "anonymous" : userId.trim();
    }

    // ------------------------------------------------------------------
    //  学习
    // ------------------------------------------------------------------

    /**
     * 从一轮对话里抽取长期记忆。异步调用，不阻塞主流程。
     *
     * @return 本次新增的记忆条数
     */
    public int learn(String userId, String userMessage, String assistantReply) {
        if (!properties.getMemory().isLearningEnabled()) {
            return 0;
        }
        if (userMessage == null || userMessage.isBlank()) {
            return 0;
        }
        try {
            String conversation = "用户：" + Json.truncate(userMessage, 600)
                    + "\n助手：" + Json.truncate(assistantReply, 600);

            String raw = chatClient.prompt()
                    .options(optionsFactory.json())
                    .system(extractionPrompt)
                    .user(conversation)
                    .call()
                    .content();

            String json = Json.extractObject(raw);
            if (json == null) {
                return 0;
            }
            Map<String, Object> parsed = mapper.readValue(json, new TypeReference<>() {
            });
            Object facts = parsed.get("facts");
            if (!(facts instanceof List<?> list) || list.isEmpty()) {
                return 0;
            }
            return merge(key(userId), list.stream()
                    .filter(o -> o != null && !o.toString().isBlank())
                    .map(o -> o.toString().trim())
                    .toList());
        } catch (Exception e) {
            // 记忆抽取失败绝不能影响主对话，静默降级
            log.debug("长期记忆抽取失败，已跳过：{}", e.getMessage());
            return 0;
        }
    }

    /**
     * 合并去重，超上限时淘汰最旧的。返回新增条数。
     *
     * <p>去重不能只做字符串全等 —— 同一个事实模型每轮的措辞都不一样
     * （"用户叫赵工，是暖通班班长" / "用户姓赵，是园区暖通班班长"），
     * 全等匹配会留下一堆近义重复，既浪费上下文又让注入的提示词很难看。
     * 这里用<b>字符二元组 Jaccard 相似度</b>做近似判重，
     * 相似度够高就视为同一条，并保留信息量更大（更长）的那条。
     */
    private synchronized int merge(String userId, List<String> incoming) {
        List<Fact> facts = new ArrayList<>(store.computeIfAbsent(userId, k -> new ArrayList<>()));
        String now = LocalDateTime.now().format(DT);
        int added = 0;

        for (String text : incoming) {
            if (FactDedup.normalize(text).isEmpty()) {
                continue;
            }
            int similar = indexOfSimilar(facts, text);
            if (similar >= 0) {
                // 已有一条近义的：谁信息量大留谁（用长度近似衡量）
                if (text.length() > facts.get(similar).text().length()) {
                    facts.set(similar, new Fact(text, now));
                }
                continue;
            }
            facts.add(new Fact(text, now));
            added++;
        }

        if (added == 0) {
            // 即使没有新增，也可能发生了"替换成更详细的版本"，需要落盘
            store.put(userId, facts);
            persist();
            return 0;
        }
        int max = properties.getMemory().getMaxFactsPerUser();
        while (facts.size() > max) {
            facts.remove(0);   // 淘汰最旧的
        }
        store.put(userId, facts);
        persist();
        log.info("[长期记忆] 用户 {} 新增 {} 条，当前共 {} 条", userId, added, facts.size());
        return added;
    }

    /** 找出与给定文本近义的已有记忆下标，没有则返回 -1。判重算法见 {@link FactDedup}。 */
    private int indexOfSimilar(List<Fact> facts, String text) {
        for (int i = 0; i < facts.size(); i++) {
            if (FactDedup.isSimilar(facts.get(i).text(), text)) {
                return i;
            }
        }
        return -1;
    }

    // ------------------------------------------------------------------
    //  持久化（Demo 用 JSON 文件，生产建议换成数据库/向量库）
    // ------------------------------------------------------------------

    private void persist() {
        Path path = Path.of(properties.getMemory().getStoreFile());
        try {
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            Map<String, List<String>> dump = new LinkedHashMap<>();
            store.forEach((k, v) -> dump.put(k, v.stream()
                    .map(f -> f.text() + "\u0001" + f.learnedAt()).toList()));
            Files.writeString(path, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(dump));
        } catch (Exception e) {
            log.warn("长期记忆持久化失败（不影响运行）：{}", e.getMessage());
        }
    }

    private void load() {
        Path path = Path.of(properties.getMemory().getStoreFile());
        if (!Files.exists(path)) {
            return;
        }
        try {
            Map<String, List<String>> dump = mapper.readValue(Files.readString(path), new TypeReference<>() {
            });
            dump.forEach((k, v) -> {
                List<Fact> facts = new ArrayList<>();
                for (String s : v) {
                    int i = s.indexOf('\u0001');
                    facts.add(i > 0 ? new Fact(s.substring(0, i), s.substring(i + 1)) : new Fact(s, ""));
                }
                store.put(k, facts);
            });
            log.info("[长期记忆] 从 {} 载入 {} 个用户的记忆", path, store.size());
        } catch (Exception e) {
            log.warn("长期记忆载入失败，将以空记忆启动：{}", e.getMessage());
        }
    }
}
