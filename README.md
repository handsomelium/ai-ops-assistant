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

---

## 1. 环境要求

| 项 | 要求 | 备注 |
|---|---|---|
| JDK | **17 及以上**（开发验证用 21.0.10） | Spring AI 1.1 的硬性要求，JDK 8 跑不起来 |
| Maven | 3.6.3+ | |
| 网络 | 能访问模型网关 | 见下方"网关"说明 |
| 数据库 | **不需要** | 数据是内存种子，重启即恢复，方便课堂反复演示 |

---

## 2. 快速开始

### 2.1 配置 API Key

编辑 `src/main/resources/application.yml`，替换这一行的占位符：

```yaml
spring:
  ai:
    openai:
      api-key: ${DASHSCOPE_API_KEY:请替换为你的百炼TokenPlan_APIKey}
```

**更推荐用环境变量**，Key 不会进代码库：

```bat
:: Windows cmd
set DASHSCOPE_API_KEY=你的Key

:: PowerShell
$env:DASHSCOPE_API_KEY="你的Key"

:: Linux / macOS
export DASHSCOPE_API_KEY=你的Key
```

也可以直接改 `run.bat` 里那一行 `set "DASHSCOPE_API_KEY="`。

### 2.2 ⚠ Key 和网关必须配套（最常见的坑）

阿里云百炼有三套互不通用的 Key，**配错网关直接 401，而且报错只说"Key 无效"，不会告诉你是网关错了**：

| Key 前缀 | 计费模式 | 配套 Base URL |
|---|---|---|
| `sk-` | 按量付费 | `https://dashscope.aliyuncs.com` |
| `sk-sp-` | **Token Plan（团队版坐席）** | `https://token-plan.cn-beijing.maas.aliyuncs.com` |
| `sk-ws-` | Coding Plan | `https://coding.dashscope.aliyuncs.com` |

本项目默认配的是 **Token Plan 网关**。如果你用的是普通百炼 Key（`sk-` 开头），把 `application.yml` 里
`base-url` 改成 `https://dashscope.aliyuncs.com`、`model` 改成 `qwen-plus` 之类即可，**Java 代码一行都不用动**。

另外实测确认：Token Plan 网关**只支持 OpenAI 兼容协议**，原生 DashScope 协议路径
（`/api/v1/services/aigc/text-generation/generation`）会返回 `400 url error`。
所以本项目用的是 `spring-ai-starter-model-openai`，**不能**用 `spring-ai-alibaba-starter-dashscope`。

### 2.3 构建与启动

```bat
run.bat
```

或手动：

```bash
mvn clean package -DskipTests
java -jar target/ai-ops-assistant.jar
```

启动后访问 **http://localhost:8088**（端口在 `application.yml` 的 `server.port` 改）。

启动时控制台会打印自检信息，包括 Key 是否已配置（脱敏显示）、网关地址、默认模型。

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
    │   │   ├── ChatOptionsFactory.java     统一构造 ChatOptions（模型、温度、私有参数）
    │   │   ├── VendorParamConfig.java      ★ 注入 enable_thinking=false（含踩坑说明）
    │   │   ├── VendorParamHttpConnector.java  流式（WebClient）通道的请求体改写
    │   │   └── StartupValidator.java       启动自检
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
    │   ├── application.yml
    │   ├── prompts/*.st                    7 个提示词文件，改提示词不用动 Java
    │   └── static/                         前端页面（无构建步骤）
    └── test/java/com/ake/aiops/            54 个测试
```

---

## 5. 测试

```bash
# 只跑离线测试（不需要 Key，46 个）
mvn test -Dtest='!AiLiveIntegrationTest'

# 跑全部（含 8 个真实调用大模型的端到端测试，需要 Key）
set DASHSCOPE_API_KEY=你的Key
mvn test
```

`AiLiveIntegrationTest` 用 `@EnabledIfEnvironmentVariable` 门控：**没配 Key 会自动跳过，不会让构建失败**。

测试分两类：

- **离线**（46 个）：业务规则、参数归一化、JSON 容错解析、长期记忆判重、动作幻觉校验、Spring 装配与最小权限矩阵。
- **实时**（8 个）：真实调用百炼，验证三个模块的完整链路。

> 测 LLM 应用的原则：**断言结构与链路，不断言措辞**。
> 实时测试只检查"有没有产生工具调用事件""有没有 error 事件""流是否正常收尾"，
> 不检查模型具体说了什么 —— 否则测试会因为模型换个说法就红。

---

## 6. 几个刻意的设计决定

**① `enable_thinking=false` 必须在 HTTP 层注入**

qwen3 系列默认开思考模式，实测 `qwen3.7-plus` 回答一个 30 字的问题要 **76 秒**（4684 个 reasoning token），
多智能体串联直接不可用。关掉后 `qwen3.8-flash` 只要 **0.8 秒**。

但 Spring AI 1.1.0 的 `OpenAiChatOptions.extraBody` **不生效** —— 字段有、`@JsonAnyGetter` 也有，
`OpenAiChatModel` 组装请求时却没拷过去，参数被静默丢弃。

定位手段：起一个本地回显 HTTP 服务，把 `base-url` 指过去抓真实请求体。抓出来只有
`messages / model / stream / temperature`。

修复：`VendorParamConfig`（RestClient 拦截器，管非流式）+ `VendorParamHttpConnector`（WebClient 连接器包装，管流式）。
**两条通道都要拦**，只修一条的话，恰好是用户能感知延迟的流式那条没修好。

效果（同一份代码，只修这一个参数）：

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
| GET | `/api/system/status` | 配置与装配状态自检 |

SSE 事件统一为 `data: {"type":"...", ...}`，前端按 `type` 分发：
`delta` `final` `done` `error` `warning` `tool_call` `tool_result`
`agent_start` `agent_end` `plan` `phase` `memory` `session_memory`

> 注意是 **POST + SSE**。浏览器原生 `EventSource` 只支持 GET，
> 所以前端用 `fetch` + `ReadableStream` 手动解析（见 `static/js/app.js` 的 `streamSSE`）。

---

## 8. 常见问题

**启动报 401 / `invalid_api_key`**
Key 和网关不配套。见 §2.2 的对照表。

**回答特别慢（几十秒以上）**
`aiops.disable-thinking` 是不是被改成 `false` 了？或者换了默认开思考的模型。

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
