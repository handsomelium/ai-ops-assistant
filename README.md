# AI 应用开发实战 · 园区智能运维工单助手

培训配套 Demo。演示如何把大模型能力**集成进一个 Spring Boot 业务系统**，让它承担真实业务逻辑 ——
而不是"用 AI 帮我写代码"。

一个工程、一套业务数据，三层能力递进：

| 层次 | 能做什么 | 入口 |
|---|---|---|
| ① 基础问答 | 提示词工程 + 会话记忆 + 长期记忆。只能聊，碰不到业务数据 | `POST /api/chat` |
| ② 单体 Agent | 挂 12 个业务工具，自主决定调哪个、调几次；能查能改，被规则拒绝后会自我纠正 | `POST /api/agent` |
| ③ 多智能体协同 | 调度中枢拆任务 → 3 位专家带各自工具并行执行 → 报告 Agent 汇总 | `POST /api/multi-agent` |

前端是同工程内的静态页面（`src/main/resources/static`），不需要单独构建。

**模型层是厂商无关的**：只依赖 `spring-ai-starter-model-openai`（标准 OpenAI Chat Completions 协议），
所有厂商配置都在**同一个 `application.yml`** 里，改两行就能切换，见 §2。

---

## 1. 环境要求

| 项 | 要求 | 备注 |
|---|---|---|
| JDK | **17 及以上**（开发验证用 21.0.10） | Spring AI 1.1 的硬性要求，JDK 8 跑不起来 |
| Maven | 3.6.3+ | |
| 网络 | 能访问所选模型网关 | 见 §2.3 |
| 数据库 | **不需要** | 数据是内存种子，重启即恢复，方便课堂反复演示 |

---

## 2. 快速开始

### 2.1 只改两行

打开 `src/main/resources/application.yml`，最上面这两行就是全部配置：

```yaml
spring:
  profiles:
    active: aliyun-token-plan          # ① 模型厂家
  ai:
    openai:
      api-key: 请替换为你的_APIKey     # ② API Key（直接填，不要加引号）
```

- **①** 可选 `aliyun-token-plan` / `aliyun-dashscope` / `deepseek` / `custom`
- **②** 填对应厂商的 Key，**换厂商必须同时换 Key**，否则 401

改完在 IDEA 里 **Rerun（重启）** 即可。不需要环境变量、不需要改 `run.bat`、没有额外配置文件。
启动自检会打印当前厂商、网关、模型、私有参数；前端右上角也会显示厂商名。

> 演示工程，Key 只在本机用。自己注意别把填了 Key 的 `application.yml` 提交出去。
> 改完配置必须重启：`ChatModel` 是启动时装配的 Bean，不支持热切换。

### 2.2 一个厂商一段配置

`application.yml` 里每个 `---` 分隔的文档块就是一个厂商的完整参数，
`spring.profiles.active` 填哪个名字，就激活哪一段：

| active | 厂商 | 网关 | 模型 | 私有参数 |
|---|---|---|---|---|
| `aliyun-token-plan` | 百炼 Token Plan | `token-plan.cn-beijing.maas.aliyuncs.com` | `qwen3.8-flash` | `{"enable_thinking":false}` |
| `aliyun-dashscope` | 百炼按量付费 | `dashscope.aliyuncs.com` | `qwen-plus` | `{"enable_thinking":false}` |
| `deepseek` | DeepSeek | `api.deepseek.com` | `deepseek-flash` | `{"thinking":{"type":"disabled"}}` |
| `custom` | 任意 OpenAI 兼容服务 | 自己填 | 自己填 | `{}` |

每段必填 5 项：`base-url`、`completions-path`、`model`（`spring.ai.openai.*` 下）、
`report-model`、`extra-body-json`（`aiops.vendor` 下）。

**加新厂商 = 复制一段 `---` 改这 5 项，再把顶部的 active 改成新名字，Java 零改动。**
`custom` 段落已备好 vLLM / Ollama 类的本地服务示例。

Spring AI 只认 `spring.ai.openai.*` 这个 namespace，所以连接参数写在 `spring:` 下；
协议之外的差异（私有参数、显示名、JSON 模式）写在 `aiops.vendor` 下，
由 `VendorBodyInjector` 统一合并进请求体。业务代码（Service / Agent / @Tool / Prompt）与厂商无关。

可选的全局开关（写在任意厂商段落里即可覆盖）：

| 配置项 | 默认值 | 作用 |
|---|---|---|
| `aiops.vendor.inject-enabled` | `true` | `false` = 完全不改写请求体 |
| `aiops.vendor.path-marker` | `/chat/completions` | 只改写路径含此标记的请求 |
| `aiops.vendor.json-mode` | `true` | 服务端不支持 `response_format=json_object` 时设为 `false` |

### 2.3 ⚠ Key 和网关必须配套（最常见的坑）

配错网关直接 401，而且报错只说"Key 无效"，不会告诉你是网关错了。
`active` 决定用哪套网关，`api-key` 必须是该厂商的 Key：

| 厂商 / Key 前缀 | 配套 Base URL | completions-path |
|---|---|---|
| 百炼按量付费 `sk-` | `https://dashscope.aliyuncs.com` | `/compatible-mode/v1/chat/completions` |
| 百炼 Token Plan `sk-sp-` | `https://token-plan.cn-beijing.maas.aliyuncs.com` | `/compatible-mode/v1/chat/completions` |
| 百炼 Coding Plan `sk-ws-` | `https://coding.dashscope.aliyuncs.com` | 按网关文档 |
| DeepSeek | `https://api.deepseek.com` | `/chat/completions` |

DeepSeek 官方模型名是 `deepseek-flash` / `deepseek-v4-pro`；
`deepseek-chat`、`deepseek-reasoner` 已弃用。JSON Output 要求提示词里出现 `json` 字样，
本项目两个 JSON 提示词（`dispatcher-system.st`、`memory-extract.st`）都已满足。

另外实测确认：百炼 Token Plan 网关**只支持 OpenAI 兼容协议**，原生 DashScope 协议路径
（`/api/v1/services/aigc/text-generation/generation`）会返回 `400 url error`。
所以本项目用 `spring-ai-starter-model-openai`，**不能**用 `spring-ai-alibaba-starter-dashscope`。
反过来，非 OpenAI 兼容的协议（Anthropic 原生等）也不在本项目支持范围。

### 2.4 为什么换厂商必须管「私有参数」

不同厂商的开关字段名完全不同，而且不是可选项：

- 百炼 qwen3 默认开思考模式，实测 `qwen3.7-plus` 回答一个 30 字问题要 76 秒；
- DeepSeek 默认开思考模式，且思考模式下携带 `tools` 的请求**必须回传 `reasoning_content`**，
  否则返回 400，而 Spring AI 1.1 不会回传这个字段 —— 模块②单体 Agent、模块③专家 Agent 会直接跑不通；
  思考模式下 `temperature` 也会被忽略。

所以 DeepSeek 段落配的是 `{"thinking":{"type":"disabled"}}`，百炼段落配的是
`{"enable_thinking":false}`。接新厂商时若它也有类似开关，在 `extra-body-json` 里写对即可；
纯标准实现（OpenAI / vLLM / Ollama）填 `{}` 就行。

### 2.5 构建与启动

```bat
run.bat
```

或手动：

```bash
mvn clean package -DskipTests
java -jar target/ai-ops-assistant.jar
```

启动后访问 **http://localhost:8088**（端口在 `application.yml` 的 `server.port` 改）。
启动自检会打印：厂商、网关 + 端点路径、默认模型、注入的私有参数、JSON 模式、脱敏后的 Key。

---

## 3. 演示脚本（课堂建议顺序）

打开页面后有 4 个标签，每个标签下方都有可点击的示例问题。

**① 基础问答**
1. 先问 `什么是 MCP 协议？` —— 正常回答。
2. 再问 `现在园区里有哪些设备是异常的？` —— 它会**拒答**并提示切换模式。
   这就是 System Prompt 里"能力边界"在起作用，是防幻觉最便宜的一道闸。
3. 告诉它 `我叫张三，是设备部主管，负责 A 座和 B 座`，然后问 `我负责哪几座楼？`
   —— 会话记忆生效。右侧"记忆状态"面板能看到抽取出的长期记忆条目。
4. 点"清空当前会话"，再问一次 `我负责哪几座楼？` —— 会话记忆没了，但**长期记忆还在**。
   这个对比最能讲清两种记忆的区别。

**② Agent 工具调用**
1. `现在有多少张待处理工单？` —— 右侧"执行过程"会实时显示它调了 `queryWorkOrders`。
2. `B1 低压配电柜现在是什么情况？` —— 会调 `getDeviceDetail`，返回的温度、电流都是真实种子数据。
3. **重点演示**：`把工单 WO-2026-0003 派给张建国处理。如果他不行就换合适的人`
   - 张建国是强电工，摄像头属弱电，`assignWorkOrder` 会被业务规则**拒绝**
   - 拒绝原因（含"请调用 listEngineers 选择 matched=true 的人员"）被喂回模型
   - 模型改调 `listEngineers`，然后改派陈明成功
   - 切到"业务数据面板"点刷新，能看到工单状态和处理人真的变了
4. 切到 **④ 业务数据面板** 对照，证明 AI 的写操作是真实落地的。

**③ 多智能体协同**
1. `全面评估园区当前运维状况，重点分析 B1 配电柜温度告警和 1 号冷水机组能效下降，给出应急处置方案`
2. 右侧时间线会完整展示：调度中枢产出任务计划 → 专家并行执行（每步工具调用可见）→ 报告撰写 → 最终 Markdown 报告。
3. 再问一句 `你好，你能做什么？` —— 调度中枢判定为 SIMPLE，直接回答，**不惊动专家**，省 token。

---

## 4. 目录结构

```
ai-ops-assistant/
├── pom.xml
├── run.bat                      启动脚本（ASCII，避免 cmd 解析中文出问题）
├── build.bat                    构建 + 跑全部测试
└── src/
    ├── main/java/com/ake/aiops/
    │   ├── AiOpsApplication.java
    │   ├── config/
    │   │   ├── AiOpsProperties.java        aiops.* 配置
    │   │   ├── AiConfig.java               ChatMemory / 虚拟线程池 / 通用 ChatClient
    │   │   ├── ChatOptionsFactory.java     统一构造 ChatOptions（模型、温度、私有参数、JSON 模式）
    │   │   ├── VendorBodyInjector.java    ★ 统一解析并合并厂商私有参数
    │   │   ├── VendorParamConfig.java     ★ 两条出网通道的请求体改写（含踩坑说明）
    │   │   ├── VendorParamHttpConnector.java  流式（WebClient）通道的连接器包装
    │   │   └── StartupValidator.java       启动自检（打印当前厂商配置）
    │   ├── domain/                         Device / WorkOrder / Engineer / 枚举
    │   │   └── view/                       ★ 专门给模型看的精简 DTO
    │   ├── repository/                     内存实现 + 种子数据
    │   ├── service/                        ★ 所有业务规则都在这里
    │   │   ├── SkillRules.java             设备类别 → 工种映射、负载上限
    │   │   ├── DeviceService.java
    │   │   ├── WorkOrderService.java       开单/派单/关单/调级的校验
    │   │   ├── EngineerService.java
    │   │   ├── OpsStatsService.java        统计口径后端算，不让模型算数
    │   │   └── BusinessRuleException.java
    │   ├── ai/
    │   │   ├── prompt/PromptLoader.java    ★ 绕开 StringTemplate 的提示词加载
    │   │   ├── memory/                     长期记忆：抽取 + bigram 判重 + 落盘
    │   │   ├── support/
    │   │   │   ├── SseStream.java          统一 SSE 事件流（线程安全）
    │   │   │   ├── Events.java             前后端事件契约
    │   │   │   ├── Json.java               ★ 模型输出的 JSON 容错解析
    │   │   │   ├── WriteAudit.java         ★ 写操作审计
    │   │   │   └── WriteClaimGuard.java    ★ 动作幻觉校验
    │   │   ├── tool/                       @Tool 声明（读/写分离）+ 可观测装饰器
    │   │   ├── chat/BasicChatService.java  模块①
    │   │   ├── agent/OpsAgentService.java  模块②
    │   │   └── multiagent/                 模块③：调度 + 3 专家 + 报告 + 编排器
    │   └── web/                            Controller + 全局异常处理
    ├── main/resources/
    │   ├── application.yml                 ★ 唯一配置文件：顶部两行切厂商/填 Key + 各厂商段落
    │   ├── prompts/*.st                    7 个提示词文件，改提示词不用动 Java
    │   └── static/                         前端页面（无构建步骤）
    └── test/java/com/ake/aiops/            离线 + 实时两类测试
```

---

## 5. 测试

```bash
# 只跑离线测试（不需要 Key）
mvn test -Dtest='!AiLiveIntegrationTest'

# 跑全部（含真实调用模型的端到端测试，需要填好 yml 里的 Key）
mvn test -Daiops.live=true
```

`AiLiveIntegrationTest` 用 `-Daiops.live=true` 门控：**默认跳过，不会让构建失败**。
厂商和 Key 都读 `application.yml`，所以改完两行再跑同一个测试类，就能验证另一个厂商的链路。

测试分两类：

- **离线**：业务规则、参数归一化、JSON 容错解析、长期记忆判重、动作幻觉校验、
  `VendorBodyInjector` 的参数合并/不覆盖/开关/非法 JSON 容错、
  默认厂商段落与 DeepSeek 段落的装配验证、Spring 装配与最小权限矩阵。
- **实时**：真实调用模型，验证三个模块的完整链路。

> 测 LLM 应用的原则：**断言结构与链路，不断言措辞**。
> 实时测试只检查"有没有产生工具调用事件""有没有 error 事件""流是否正常收尾"，
> 不检查模型具体说了什么 —— 否则测试会因为模型换个说法就红。

---

## 6. 几个刻意的设计决定

**① 厂商私有参数必须在 HTTP 层注入，且只能有一份实现**

Spring AI 1.1.0 的 `OpenAiChatOptions.extraBody` **不生效** —— 字段有、`@JsonAnyGetter` 也有，
但 `OpenAiChatModel` 组装请求时没拷过去，参数被静默丢弃。
定位手段：起一个本地回显 HTTP 服务，把 `base-url` 指过去抓真实请求体，
抓出来只有 `messages / model / stream / temperature`。

而且 `OpenAiApi` 有两条出网通道：`.call()` 走 RestClient，`.stream()` 走 WebClient。
**两条都得拦**，只修一条的话，恰好是用户能感知延迟的流式那条没修好。
现在 `VendorParamConfig`（RestClient 拦截器）+ `VendorParamHttpConnector`（WebClient 连接器包装）
共用同一个 `VendorBodyInjector`，参数来自当前厂商段落的 `extra-body-json`，
不存在"改了一半"的风险：注入器对已存在的字段不覆盖（将来框架修好 extraBody 会自动让路），
解析失败原样放行。

效果（百炼上同一份代码，只修这一个参数）：

| 场景 | 修复前 | 修复后 |
|---|---|---|
| 基础问答首轮 | 9.6s | **2.6s** |
| Agent 工具查询 | 15.7s | **3.8s** |
| 多智能体全链路 | 171.0s | **38.1s** |
| 报告撰写节点 | 109.6s | **9.2s** |

**② 动作幻觉必须用代码兜底**

实测抓到过：让 Agent 派单，它只调了 `listEngineers` 和 `getWorkOrderDetail`，
**从未调用 `assignWorkOrder`**，却在回答里写"已派给陈明（1380-0004）"，而数据库里 `assignee` 还是 `null`。

提示词里写了"禁止谎报"也只能降低概率。真正的兜底是 `WriteAudit` + `WriteClaimGuard`：
工具层记录真实成功的写操作，回答生成完用正则比对模型的完成态措辞，对不上就追加系统校验说明并推 `warning` 事件。

正则有两个坑（`WriteClaimGuardTest` 里有对应用例）：
- 不能只匹配"已 + 动词"：工具报错原文常有"工单已关闭，不能再派单"，那是转述既有事实。所以间隔字符排除了 `不/未/没/无/待/拒`。
- 动词要带完成态：`派给`、`创建工单`、`关闭工单` 算；单独的 `派单`、`关单` 可能出现在建议句里，会误报。

**③ 业务规则一行都不写在 AI 层**

`@Tool` 方法体极薄，只做参数归一化然后调 Service。这样页面手工操作和 AI 调用走同一套逻辑，
不会出现"AI 绕过了风控"，规则也能单独写单元测试。

规则拒绝时抛 `BusinessRuleException`，由 `ObservableToolCallback` 转成
`【业务规则拒绝】+ 原因 + 下一步该调哪个工具` 返回给模型 —— **错误信息里点名工具**，
模型才知道该怎么纠正，否则会原地打转。

**④ 多智能体的最小权限是硬隔离**

工具在构造期按角色装配：运营分析专家一个写工具都拿不到，报告撰写 Agent 零工具。
不是靠提示词说"你不要改数据"，而是根本没有对应的函数可调。

**⑤ 统计一律后端算**

`getOpsOverview` 把所有计数、时长、分布算好再给模型。大模型做算术不可靠，让它数数必然出错。

---

## 7. 接口清单

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/chat` | 基础问答，SSE 流式 |
| GET/DELETE | `/api/chat/session-memory` | 查看 / 清空会话记忆 |
| GET/DELETE | `/api/chat/long-term-memory` | 查看 / 清空长期记忆 |
| POST | `/api/agent` | 单体 Agent，SSE 流式 |
| GET | `/api/agent/tools` | Agent 被授权的工具清单 |
| POST | `/api/multi-agent` | 多智能体协同，SSE 流式 |
| GET | `/api/multi-agent/roster` | 专家名册与各自的工具授权 |
| GET | `/api/ops/devices` `/work-orders` `/engineers` `/overview` | 业务数据面板 |
| GET | `/api/system/status` | 配置与装配状态自检（含 `vendor` / `vendorDisplayName` / `vendorFields` / `jsonMode`） |

SSE 事件统一为 `data: {"type":"...", ...}`，前端按 `type` 分发：
`delta` `final` `done` `error` `warning` `tool_call` `tool_result`
`agent_start` `agent_end` `plan` `phase` `memory` `session_memory`

> 注意是 **POST + SSE**。浏览器原生 `EventSource` 只支持 GET，
> 所以前端用 `fetch` + `ReadableStream` 手动解析（见 `static/js/app.js` 的 `streamSSE`）。

---

## 8. 常见问题

**启动报 401 / `invalid_api_key`**
`api-key` 没填，或 Key 和 `spring.profiles.active` 选的厂商不配套。见 §2.3 对照表；
启动自检会打印当前厂商和网关。

**换厂商后报 404**
新厂商段落里的 `completions-path` 不对。DeepSeek 是 `/chat/completions`，
百炼是 `/compatible-mode/v1/chat/completions`。

**多智能体报 400 / 任务计划一直解析失败**
多半是该厂商段落的 `extra-body-json` 没配对，模型还开着思考模式。
检查启动自检里的"私有参数"一行。服务不支持 `response_format=json_object` 时，
在该段落加一行 `json-mode: false`，链路会退化为提示词约束 + 容错解析。

**回答特别慢（几十秒以上）**
厂商段落里的 `extra-body-json` 是不是漏了关闭思考模式的参数？
或者 `inject-enabled` 被设成了 `false`。

**`Could not reserve enough space for object heap`**
设 `set MAVEN_OPTS=-Xmx512m` 再构建。

**端口 8088 被占用**
改 `application.yml` 的 `server.port`，或启动时加 `--server.port=9090`。

**Maven 拉不到依赖**
项目依赖都在 Maven Central。如果公司配了内网镜像且不可达，
临时用 `mvn -s 你自己的settings.xml` 指定一个可用的镜像。

**长期记忆越攒越多**
默认每个用户上限 20 条，超出淘汰最旧的。判重用字符二元组 Jaccard 相似度，
阈值 0.48（用 11 组样本标定，可用区间 0.429~0.533）。
纯词面相似度分不清"负责A座"和"负责B座"，要求更高就换 embedding 余弦相似度。

---

## 9. 上生产前还要补的

Demo 刻意没做这些，但真实项目一件都不能少：

1. **Key 管理** —— 配置中心 / KMS，绝不进代码库
2. **超时与重试** —— 读超时、指数退避、降级到规则或人工
3. **成本与限流** —— 按用户配额、按 token 监控
4. **审计留痕** —— 谁在何时让 AI 做了什么，可追溯
5. **评估集** —— 固定问答集 + 期望断言，改提示词前后都跑一遍
6. **写操作幂等** —— AI 会重试，不幂等就会重复开单
7. **人工审批节点** —— 高风险写操作插入人工确认
8. **全链路可观测** —— trace 打通到每次模型调用与工具调用

另外，Demo 的会话记忆是 `MessageWindowChatMemory`（单机内存），多实例部署要换成
JDBC / Redis 版本的 `ChatMemoryRepository`；编排器是手写调度，
需要条件分支、循环、人工审批节点时换成状态机 / DAG 引擎
（如 Spring AI Alibaba Graph、LangGraph）。
