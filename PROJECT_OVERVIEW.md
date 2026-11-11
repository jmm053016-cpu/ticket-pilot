# TicketPilot / AI Ticket Assistant — 项目全景文档

> 本文档是项目的**唯一实时全览**（架构 + 目录 + 链路 + 契约 + 现状 + 变更历史）。
> `README.md` 面向使用者（怎么跑、怎么配），本文档面向维护者（改哪里、别改坏什么）。

| 元信息 | 值 |
| --- | --- |
| 文档版本 | v5.7（2026-11-11 品类歧义列候选 + 最近一单解析 + Enter 发送） |
| 首次生成时间 | 2026-11-02 17:20 (Asia/Shanghai) |
| 最近更新时间 | 2026-11-11 21:10 (Asia/Shanghai) |
| 对应代码基线 | git `main` @ `8e7659f` + 本地未提交改动（变更 #2 ~ #52 全部未提交） |
| 工作区状态 | 有未提交改动：48 个 M / 6 个 D / 116 个新增（含 V7～V11 迁移文件与 scripts/，见 §6.1） |
| 代码规模 | 后端 229 个 Java 文件 / 约 10900 行（main）+ 30 个测试类 / **212 个用例**；前端 29 个 ts/tsx（**40 个前端用例**） |

---

## 0. 文档维护约定（重要）

**每次对项目做出任何更改后，必须同步更新本文档**，规则如下：

1. 改代码 → 更新对应章节（第 3/4/5/6/7/9/10 章中受影响的部分）。
2. 改配置/端口/依赖 → 更新第 2 章与第 8 章。
3. 新增/删除/重命名文件 → 更新第 4 章目录地图中的对应条目（含文件数量统计）。
4. 新增数据库迁移 → 更新第 6 章迁移清单与表清单。
5. 新增 SSE 事件类型 / Tool / 意图 → 更新第 7、9 章契约表。
6. 修掉或新增"已知限制" → 更新第 11 章。
7. **无论改动大小，都要在第 13 章"变更记录"追加一条**，**每条一行**（`# | 时间 | 一句话摘要 | 关键文件`）。
   详细推理不要写进变更记录 —— 写进对应章节（踩过的坑、维护红线、验证方式），变更记录只做"可追溯索引"。
   v2.0 之前的历史条目已按此规则压缩，内容并入正文。
8. 同步刷新顶部"元信息"表：文档版本、时间、代码基线 commit、代码规模。

> 变更记录只追加、不删除历史条目；被推翻的结论在正文改写，并在变更记录里注明"推翻 #N"。
> 章节里出现重复解释同一件事时，保留一处权威说法，其余改成一句话 + 指向该处。

---

## 1. 项目定位

企业客服 / 工单 / 订单场景的 **AI Native 后端架构示例**。重点不是训练模型，而是：

- 用 **Multi-Agent 编排**（Intent → Tool → Order/Knowledge）把 LLM 接进真实业务；
- 用 **Tool Calling 白名单 + 应用服务 + 领域模型** 划定"AI 只能提议、后端才能执行"的安全边界；
- 用 **SSE 分阶段事件** 把 Agent 决策过程实时可视化到前端；
- 用 **Flyway + JPA + MySQL** 保证业务数据可持久、可迁移。

一句话链路：`React(SSE) → AssistantStreamController → AssistantWorkflowService → IntentAgent → ToolDispatcher → 领域服务/JPA → MySQL → SSE 回推前端`。

---

## 2. 技术栈

### 后端
| 项 | 版本/说明 |
| --- | --- |
| Java | 17 |
| Spring Boot | 3.3.6（parent） |
| Web 层 | Spring WebFlux（`@RestController` + `Flux<ServerSentEvent>`） |
| 持久化 | Spring Data JPA + Hibernate（`ddl-auto: validate`） |
| 数据库 | MySQL 8.4（容器）/ 8.0.30（本地 dump 来源） |
| 迁移 | Flyway（`flyway-core` + `flyway-mysql`），脚本在 `classpath:db/migration` |
| 文档 | springdoc-openapi 2.6.0（Swagger UI） |
| AI 接入 | 无需厂商 SDK：DeepSeek / Qwen 都走 OpenAI 兼容 REST（`WebClient`），`fallback` 是纯规则 |
| 测试 | JUnit 5 / spring-boot-starter-test / reactor-test / H2（`@SpringBootTest` 冒烟已接入，见 §11.4）/ Testcontainers 1.20.4（依赖已声明，尚无用例） |
| 其他 | Lombok（optional）、Actuator（仅暴露 health/info） |

### 前端
| 项 | 版本/说明 |
| --- | --- |
| React | 18.3 + TypeScript 5.7 |
| 构建 | Vite 6（`tsc -b && vite build`） |
| 样式 | TailwindCSS 3.4（自定义玻璃拟态 `glass`、`shadow-glow`、暗色渐变背景） |
| 状态 | Zustand 5（单一 store：`assistantStore`） |
| 数据 | @tanstack/react-query（已在 `main.tsx` 挂 Provider，实际请求仍用原生 fetch/EventSource） |
| 图标 | lucide-react |
| 测试 | Vitest 2 + jsdom（2 个用例） |

### 部署
Docker Compose：`mysql` / `backend` / `frontend` 三服务（2026-11-04 删掉 `chromadb`：向量检索改为应用内本地计算，见 §6.5 ⑤）；前端 Nginx 静态托管。

---

## 3. 仓库结构（顶层）

```text
TicketPilot/
├── README.md                    # 使用者视角说明（运行/配置/示例问题）
├── PROJECT_OVERVIEW.md          # ← 本文档（维护者视角，随改动更新）
├── docker-compose.yml           # 4 服务编排
├── .env / .env.example          # AI Provider 密钥与前端 API 地址
├── .gitignore
├── LICENSE
├── backend/                     # Spring Boot（131 Java 文件）
├── db/ai_ticket_assistant.sql    # ⚠ 本地 mysqldump 快照，未被 git 跟踪
└── frontend/                    # React + Vite (19 ts/tsx)
```

### 需要注意的三个易错点
1. `db/ai_ticket_assistant.sql` 是**手工导出的数据库快照**（含 `flyway_schema_history`、20 张表），**不在 git 里**，也不是运行时输入。运行时建表的唯一事实来源是 `backend/src/main/resources/db/migration/`。两者若不一致，以 Flyway 为准。
2. `backend/target/`（90MB jar）与 `frontend/dist/` 是本地构建产物，已被 `.gitignore` 排除，不入库。
3. 部分源码文件在 Windows 终端用 `Get-Content` 直接读会显示乱码 —— 文件本身是 UTF-8，读取时需加 `-Encoding UTF8`（或使用 UTF-8 感知的读取工具），**不要因为看到乱码就去"修复"文件编码**。

---

## 4. 后端代码地图

根包：`com.example.aiticketassistant`。分层采用 **接口层 → 应用层 → 领域层 → 基础设施层**（DDD 轻量落地）。

### 4.1 interfaces（对外契约层）
| 文件 | 职责 |
| --- | --- |
| `interfaces/sse/AssistantStreamController.java` | `GET /assistant/stream`，唯一 AI 入口；把 `Flux<AssistantEvent>` 映射为 `ServerSentEvent`，设置 `Cache-Control: no-cache, no-transform`、`X-Accel-Buffering: no`、`Connection: keep-alive` |
| `interfaces/rest/ProductController.java` | `GET /products` 商品列表 |
| `interfaces/rest/KnowledgeController.java` | `GET /knowledge/search?query&limit` RAG 检索 |
| `interfaces/rest/OrderController.java` | `GET /orders?customerId`、`GET /orders/{orderNo}` |
| `interfaces/rest/OrderConfirmationController.java` | `POST /assistant/order-confirmations/{id}/confirm`、`.../cancel` |
| `interfaces/rest/TicketController.java` | `GET /tickets`（未关闭工单）、`GET /tickets/{id}` |
| `interfaces/rest/AssistantSessionController.java` | `GET /assistant/sessions`（会话列表）、`GET /assistant/sessions/{id}/messages`（历史轮次）；只读 |
| `interfaces/rest/GlobalExceptionHandler.java` | 统一异常映射；**兜底 500 会 `log.error` 打印堆栈**（2026-11-02 新增，此前 500 无任何日志） |
| `interfaces/dto/AssistantSseEvent.java` | SSE data 体：`{type, message, payload, timestamp}` |

### 4.2 application（应用层）
| 文件 | 职责 |
| --- | --- |
| `assistant/AssistantWorkflowService.java` | **核心编排器**：路由判定 → Intent → Tool → 上下文 Agent → 组装 → 切片流式输出。**路由不写任何关键词**，全部委托 `QuestionSignals`（见 §5.4.2） |
| `assistant/AssistantEvent` / `AssistantEventType` / `AssistantRequest` | 事件模型与输入模型 |
| `agent/IntentAgent.java` | 调 AI 出严格 JSON，解析失败/工具越权时走**启发式兜底**；购买意图强制覆盖为 `CREATE_ORDER`。启发式的每一类判定（订单列表/下单/商品问答/工单）同样只调 `QuestionSignals`，类内不再有关键词表 |
| `agent/OrderAgent.java` | 基于 `QUERY_ORDER` 结果做业务解读（订单列表走本地模板，非 LLM） |
| `agent/KnowledgeAgent.java` | 基于 RAG 片段生成回答；**精度优先**（问题含数字时优先保留命中该数字的片段）+ 只喂「标题：内容」的干净文本（见 §5.4）。三个入口：`streamAnswer`（真流式，工作流用）/ `synthesize`（整段，测试用）/ `retrieveKnowledge` + `sourcesBlock`（直答路径的前置检索，见 §5.5） |
| `agent/AiClientPort` / `AiRequest` / `AiResponse` / `AiStreamEvent` / `StreamChunks` / `IntentResult` / `IntentType` | AI 端口与 DTO。端口两个能力：`complete`（要解析 JSON 的调用，如意图识别）与 `stream`（正文增量）；`stream` 有默认实现 = 整段切句 90ms 一句，只有 DeepSeek 覆盖成真流式（见 §5.2） |
| `tool/ToolDispatcher.java` | **安全闸门**：白名单校验 → 查注册表 → 执行 → 记录 trace → 写回上下文 |
| `tool/ToolRegistry.java` / `ToolNames.java` / `ToolMetadata` / `ToolResult` / `ToolCall` | 工具注册与元数据 |
| `tool/tools/*Tool.java` | 5 个工具实现（见第 7 章）；`QueryOrderTool` 的"列表 vs 单订单"判定也走 `QuestionSignals.isOrderListRequest` |
| `order/CreateOrderConfirmationService.java` | 生成**待确认订单**（10 分钟 TTL，一单多明细），逐行校验商品/库存 + 地址/支付方式归属；`ItemRequest` 是下单请求行 |
| `order/ConfirmOrderService.java` | 用户确认后**事务内**扣库存 + 建单 + 写明细 + 置确认单状态；含过期/价格变动/归属校验 |
| `assembler/AssistantResponseAssembler.java` | 最终回答拼装，**面向用户**：结论优先、不暴露意图/置信度/Trace/score/内部 URI、空小节不输出（格式约定见 §5.4）。这是确定性字符串拼接，不是提示词，故意留在 Java 里 |
| `catalog/BudgetPlanner.java` / `PriceCatalogService.java` / `BudgetPlan.java` | 预算测算：价格表解析 + 确定性求解（见 §5.4.3） |
| `routing/QuestionSignals.java` | **全项目唯一的关键词表与判定口径**（纯函数）：品类词/检索线索/购物线索/下单动作/商品名→productId/订单列表/购物车/账户/订单生命周期/工单/订单号 + 商品与预算问答判定 + 汇总型问题 + 预算测算参数。路由、`IntentAgent` 启发式与护栏、`FallbackAiClientAdapter`、`QueryOrderTool`、`Bm25SearchAdapter` 全都只调它，见 §5.4.2。**不要在调用方另写 `contains("…")`** |
| `conversation/ConversationMemory.java` | 多轮会话记忆**端口**：`recentTurns` / `recordTurn` |
| `conversation/ConversationTurn.java` | 一轮问答记录 + `toPromptBlock()` 渲染（「用户: / 助手:」） |
| `conversation/ConversationHistory.java` | **历史注入的裁剪口径**（纯函数 + `Budget` 配置载体）：单条截断 → 总量预算（从最新往前装）→ 最新一轮必留；配错/缺省回落默认值。见 §4.6 |
| `prompt/PromptCatalog.java` | 提示词读取**端口**（接口）。应用层只依赖它，不依赖具体加载方式 |
| `prompt/PromptTemplate.java` | `{id, system, userTemplate}`；`user(vars)` 渲染 `{{var}}`，渲染后残留占位符直接抛 `IllegalStateException` |
| `prompt/PromptIds.java` | 提示词 ID 常量：`intent-agent` / `order-agent` / `knowledge-agent` / `direct-answer`，`ALL` 为启动期必检清单 |
| `workflow/WorkflowContext` / `WorkflowTrace` / `WorkflowStep` / `WorkflowStepStatus` | 单请求上下文 + 内存级 trace |
| `workflow/WorkflowTraceRecorder.java` | trace 落库**端口**：`record(context, status, completedAt)` |
| `workflow/WorkflowTraceStatus.java` | 落库状态：`COMPLETED` / `FAILED` / `CANCELLED` |
| `ticket/TicketEventRecorder.java` | 工单事件落库**端口**：`record(ticketId, eventType, payload)` |
| `ticket/TicketEventTypes.java` | 事件类型常量（`TICKET_CREATED` / `TICKET_RESOLVED` / `TICKET_ESCALATED` / `TICKET_WAITING_CUSTOMER` / `TICKET_UPDATED`） |

### 4.3 domain（领域层）
| 子包 | 内容 |
| --- | --- |
| `order/` | `Order`（含 `aiBusinessSummary()` 业务摘要、`canCreateSupportTicket()`）、`OrderItem`、`OrderConfirmation`（一单多明细，`totalAmount` 由明细算出）、`OrderConfirmationItem`（行明细，行小计派生）、枚举 `OrderStatus` / `PaymentStatus` / `OrderConfirmationStatus`、`OrderSupportPolicy`（优先级策略） |
| `ticket/` | `Ticket` 聚合（`open` / `resolve` / `escalate` / `markWaitingForCustomer` / `applyAiSuggestion`）、`TicketMessage`、枚举 `TicketStatus` / `TicketPriority` / `TicketCategory`、`TicketEscalationPolicy`（投诉/chargeback/legal/退款自动升级） |
| `catalog/` | `Product`、`ProductInventory`（`reserve()` 扣减） |
| `customer/` | `CustomerAddress`、`CustomerPaymentMethod`、「可信数据」仓储接口 |
| `knowledge/` | `KnowledgeDocument`、`KnowledgeChunk`、`KnowledgeSearchResult`（vectorScore/bm25Score/finalScore/matchReason） |
| `shared/` | `DomainException`、`NotFoundException` |

### 4.4 infrastructure（基础设施层）
| 子包 | 内容 |
| --- | --- |
| `ai/` | `FallbackAiClientAdapter`（默认，纯规则兜底；意图 JSON 的判定同样走 `QuestionSignals`，只保留"生成哪段 JSON/文案"的逻辑）、`deepseek/DeepSeekAiClientAdapter`（真流式）、`qwen/QwenAiClientAdapter`；均用 `@ConditionalOnProperty(assistant.ai.provider=…)` 单选装配。**2026-11-04 起移除 Anthropic 适配器与 `anthropic-java` 依赖**（项目不再使用该厂商模型） |
| `knowledge/bm25/Bm25SearchAdapter.java` | 中文 2~8 元 n-gram + 数字 token + 短语加权（+0.5）+ 目录汇总问题加权（+0.4） |
| `knowledge/HybridKnowledgeSearchRepository.java` | **混合检索**：向量 + BM25 双路召回 → 融合打分（§6.5 ⑤） |
| `knowledge/query/QueryRewriter.java` | 查询改写：中文数字归一 + 同义词扩展（只服务召回，与 `QuestionSignals` 互不相干） |
| `knowledge/embedding/HashingEmbeddingAdapter.java` | 默认向量实现：本地 TF-IDF 特征哈希（512 维），离线/零成本，**同时是语义适配器的降级兜底** |
| `knowledge/embedding/OpenAiCompatibleEmbeddingAdapter.java` | 真语义向量：任何 OpenAI 兼容 `/embeddings`（DashScope / SiliconFlow / Ollama / vLLM），批量预嵌入 + 缓存 + 失败整体降级（§6.5 ⑤） |
| `domain/knowledge/EmbeddingPort.java` | 向量化端口（`fit` / `embed` / `dimensions`）：默认本地实现，接真语义模型只需换实现 |
| `persistence/jpa/` | **19 个 JPA Entity + 19 个 Spring Data Repository**（含 `WorkflowTrace*` / `TicketEvent*` / `AssistantSession` / `AssistantMessage`） |
| `repository/` | 领域仓储实现 + 审计/记忆写入器：`JpaKnowledgeChunkSource`（知识片段来源端口实现）、`JpaWorkflowTraceRecorder`（trace+steps 一次事务写入）、`JpaTicketEventRecorder`（工单事件追加）、`JpaConversationMemory`（会话历史读写）；其余：`JpaOrderRepository`、`JpaTicketRepository`、`JpaProductRepository`、`JpaOrderConfirmationRepository`（主表 + 明细表，明细只在创建时写一次）、`JpaOrderItemRepository`、`JpaCustomerTrustRepository`、`JpaAgentRepository`、`JpaToolInvocationRecorder`（`@Primary`，静默吞异常写 `tool_invocations`） |
| `prompt/ClasspathPromptCatalog.java` | `PromptCatalog` 的实现：从外部目录或 `classpath:prompts/` 加载全部模板，**支持热加载**（文件指纹懒检查 + 手动 reload，运行时失败保留旧版，启动期缺文件仍直接失败）；UTF-8 读取、`CRLF→LF`、去尾部空白。见 §5.6 |
| `config/` | `AssistantProperties`（`@ConfigurationProperties(prefix="assistant")`，含 `ai` / `retrieval` / `cors` / `conversation` 四段（原 `chroma` 段已随 ChromaDB 一起删除；`retrieval` 管向量那一路））、`ConversationConfig`（把 `assistant.conversation.*` 映射成应用层的 `ConversationHistory.Budget`，配错回落默认值）、`WebConfig`（WebClient + CORS 白名单，**空/`*` 直接启动失败**）、`OpenApiConfig` |

### 4.5 resources
```text
resources/
├── application.yml               # 主配置（端口/数据源/JPA/Flyway/AI/CORS）
├── application-test.yml          # H2 + flyway 关闭
├── prompts/                      # ★ 运行时真实生效的提示词（启动期由 ClasspathPromptCatalog 加载）
│   ├── README.md                 # 命名规则、占位符语法、编辑约定
│   ├── intent-agent.system.md    # ↔ IntentAgent.analyze()
│   ├── intent-agent.user.md
│   ├── order-agent.system.md     # ↔ OrderAgent.analyze()
│   ├── order-agent.user.md
│   ├── knowledge-agent.system.md # ↔ KnowledgeAgent.synthesize()
│   ├── knowledge-agent.user.md
│   ├── direct-answer.system.md   # ↔ AssistantWorkflowService.runDirectQwen()
│   └── direct-answer.user.md
└── db/migration/                 # V1~V5 / V7 / V8（V6 曾用于时区视图，已撤销）
```

**提示词契约（2026-11-02 起）**
- 每个 ID 两个文件：`<id>.system.md`（原样发送，不做变量替换）+ `<id>.user.md`（渲染 `{{var}}`）。
- 占位符语法 `{{name}}`。渲染**只替换调用点传入的变量**，其余花括号原样保留，所以模板里可以直接写 JSON 示例（`{"tool_calls":[...]}`）。不要用 `%s` / `${...}`。
- 渲染后残留 `{{xxx}}` → 抛 `IllegalStateException`（不会把半成品发给模型）。
- 文件里**只放提示词原文**，不要加 Markdown 标题/注释（会一起发给模型）；人类说明写在 `prompts/README.md`。
- 编码必须 UTF-8；改完需重建/重启后端，**没有热加载**。

| 调用点 | 模板 ID | 变量 |
| --- | --- | --- |
| `IntentAgent.analyze()` | `intent-agent` | `userMessage`、`customerId`、`orderNo`（为空时渲染成 `null`，保持原行为）、`toolMetadata`、**`history`** |
| `OrderAgent.analyze()` | `order-agent` | `userMessage`、`orderToolResult`、**`history`** |
| `KnowledgeAgent.streamAnswer()` / `synthesize()` | `knowledge-agent` | `userMessage`、`sources`、`planResult`、**`history`** |
| `AssistantWorkflowService.runDirectQwen()` | `direct-answer` | `userMessage`、**`history`**、`sources`（直答前置检索结果，无命中时为空串，见 §5.5） |

---

### 4.6 多轮会话记忆（2026-11-03 新增）

**能力**：同一 `sessionId` 下的追问会被记住——历史以「用户: … / 助手: …」形式注入 4 个提示词的 `{{history}}`。

| 环节 | 实现 |
| --- | --- |
| 历史读取 | `ConversationMemory.recentTurns(sessionId, maxTurns)`，轮数由 `assistant.conversation.max-turns` 决定（默认 **6**），在 `boundedElastic` 上读，不阻塞 Netty |
| 历史裁剪 | `ConversationHistory.trim(turns, budget)`：单条截断 → 总量预算 → 最新一轮必留（见下方"长度与成本控制"） |
| 提示词注入 | `ConversationTurn.toPromptBlock()` 渲染成纯文本，填进 `intent-agent` / `order-agent` / `knowledge-agent` / `direct-answer` 的 `{{history}}` |
| 落库时机 | 一轮**正常完成且拿到最终回答**后写入 `assistant_messages` 两条（`user` + `assistant`），并 upsert `assistant_sessions`（刷新 `updated_at`） |
| 不记录的情况 | 失败（`FAILED`）、客户端取消（`CANCELLED`）、回答为空 → **不留半轮历史**，避免污染上下文 |
| 前端 | `sessionId` 由前端生成并存在 `localStorage`（`tp.assistant.sessionId`），每次请求都带上；页面右上角「新会话」按钮会换新 id 并清空界面 |

**为什么 sessionId 必须由前端固定**：后端在缺省时每次请求都会新生成 `session-UUID`（见 `AssistantStreamController`），
那样每轮都是全新会话，记忆永远为空。前端固定 + `localStorage` 复用，刷新页面也能续上。

#### 长度与成本控制（2026-11-03 补，原 §11.1 第 8 条）

**问题**：历史是按「用户: … / 助手: …」**整段**拼进 4 个提示词的，而助手的回答经常几百上千字（价格表、明细表）。
固定回看 6 轮 = 每个请求至少 2 次模型调用 × 全量历史 → 长会话下 prompt 被旧内容撑爆：
**又慢又贵**，而且模型盯着旧内容反而容易答偏。

**做法**：只在 `AssistantWorkflowService.loadHistory` 一处裁剪（不是每个 Agent 各写一遍），三条规则按优先级：

| 规则 | 默认值（可配） | 作用 |
| --- | --- | --- |
| 单条截断 | 用户 120 字 / 回答 300 字 | 保留**开头**（回答格式是"结论优先"，表格尾巴在历史里没用）+「…」；顺带把连续空白压成一个空格，表格换行不占预算 |
| 总量预算 | 1200 字 | **从最新一轮往前装**，装满即停（越近的对话越可能被追问） |
| 最新一轮必留 | — | 哪怕最新一轮自己就超预算，也要先把"用户刚才问的是什么"放进预算，剩下的额度再给回答 |

- 配置项：`assistant.conversation.max-turns / max-prompt-chars / max-user-chars / max-answer-chars`
  （环境变量 `CHAT_HISTORY_MAX_TURNS / CHAT_HISTORY_MAX_CHARS / CHAT_HISTORY_MAX_USER_CHARS / CHAT_HISTORY_MAX_ANSWER_CHARS`）。
- **配错或漏配一律回落默认值，绝不用 0**：用 0 会把历史静默清空、追问全部失效，比配错更难查（`Budget` 的 compact constructor 兜住）。
  轮数上限钉在 20（`JpaConversationMemory` 单次只取 40 条消息 = 20 轮，配再大也用不上）。
- **裁剪只影响提示词，不影响界面**：`GET /assistant/sessions/{id}/messages` 读的是原始记录，
  前端「历史对话」依旧显示完整问答（真机验证：5 轮全会话，裁剪后注入 3 轮 960 字，接口仍返回 5 轮全文）。
- **可观测**：发生裁剪时打一行 INFO ——
  `会话 xxx 历史按预算裁剪：4 轮/1910 字 → 3 轮/960 字（预算 6 轮/1200 字）`，
  成本控制有没有生效看这一行即可（真机实测：单条 416 字的回答进历史后变 300 字）。

**测试覆盖**：
- `ConversationHistoryTest` —— 8 条：预算内保留最近几轮、最新一轮超预算也要留、单条截断保留开头、空白压缩、
  非法配置回落默认、轮数上限、渲染后总长不超预算。
- `AssistantWorkflowServiceTest.longHistoryIsTrimmedBeforeItReachesThePrompt` —— 6 轮 × 2000 字的历史，
  断言进入 prompt 的历史块 ≤ 预算、含最新一轮、不含最早一轮、长回答被截断。
- `AssistantWorkflowServiceTest.injectsConversationHistoryIntoDirectAnswerPrompt` —— 断言历史文本确实出现在发给 AI 的 user prompt 里（核心契约）
- 真机实测：同一 `sessionId` 连发两轮 → `assistant_sessions` 1 行、`assistant_messages` 4 行（user/assistant 交替，时间戳按轮次递增），第二轮 `updated_at` 被刷新

#### 多会话（2026-11-03 追加）

同一 `customerId` 下可以有任意多个并行会话，**历史严格按 `sessionId` 隔离**（`findTop40BySessionIdOrderByIdDesc`）。

实测隔离（A 会话两轮、B 会话一轮）：

| 请求 | 加载到的历史轮数 |
| --- | --- |
| A 第 1 轮 | 0 |
| A 第 2 轮 | 1 |
| B 第 1 轮 | **0**（没有串到 A 的历史） |
| A 第 3 轮 | 2 |

对外接口（只读，写仍走 `/assistant/stream`）：

| 接口 | 说明 |
| --- | --- |
| `GET /assistant/sessions?customerId=&limit=` | 会话列表，按最近活跃倒序；返回 `sessionId` / `preview`（首条用户消息截断 40 字）/ `messageCount` / `createdAt` / `updatedAt` |
| `DELETE /assistant/sessions/{sessionId}?customerId=` | 删除会话及其全部消息（会话侧栏的删除按钮）。校验归属（不是本客户的会话 → 400）；审计用的 `workflow_traces` **不删**。返回 `{sessionId, deleted}`，重复删除 `deleted=false`（幂等） |
| `GET /assistant/sessions/{sessionId}/messages?limit=` | 该会话的历史轮次（时间正序），返回 `turns[{userMessage, assistantAnswer}]` |

前端：
- 左侧新增「会话」侧栏（`SessionList.tsx`）：列出历史会话（摘要 + 消息数 + 相对时间），点击即切换；
- 切换后 `StreamingAnswer` 在回答区上方渲染「历史对话（N 轮）」；
- 顶部「新会话」按钮换新 `sessionId` 并清空界面，旧会话数据仍在库里、可随时从侧栏切回；
- 每轮结束（`streaming` 由 true → false）自动刷新侧栏。

**删除会话（2026-11-05）**：`DELETE /assistant/sessions/{id}?customerId=`，先按 `customerId` 校验归属（不是本客户的会话 → 400），
再在**一个事务**里删消息 + 删会话行（`assistant_messages` 没有外键，必须显式级联）。
`workflow_traces` 不删 —— 那是"发生过什么"的审计记录，不随对话消失。
前端在会话条目右上角加了删除按钮（`confirm` 二次确认，删掉当前会话时自动换新会话）。

> **踩到的坑（值得记住）**：这个删除方法最初写成 `@Transactional`，调用时 NPE `sessionRepository is null` ——
> CGLIB 代理实例是 Objenesis 建的（不跑构造器，字段全 null），这条路恰好走了"在代理实例上执行父类方法"，
> 而同类里原有的 `recordTurn` 没踩到。排查结论：**这个类干脆不用事务注解**，行内用 `TransactionTemplate`
> 显式开事务（`recordTurn` 也一起改了），代理随之消失，行为可预测。

**仍缺一项（未做）**：会话**读接口**的归属校验。`GET /assistant/sessions/{id}/messages` 只要知道 `sessionId` 就能读，
不校验该会话是否属于请求方；`customerId` 也是请求参数、无鉴权（**写操作删除已校验**）。演示环境可接受，生产化必须补。

---
## 5. 运行链路

### 5.1 路由判定（`AssistantWorkflowService.shouldUseAgentWorkflow`）
所有请求先过分流，**只有命中业务的才进 Agent 工作流**，其余直连 AI。
本类**不写关键词**：一行一类，判定全部来自 `QuestionSignals`（唯一关键词表，见 §5.4.2）：

| `shouldUseAgentWorkflow` 里的判定 | 输入特征 | 路径 |
| --- | --- | --- |
| `hasOrderNo` | 含 `ORD-xxx` 订单号 | Agent 工作流 |
| `isCatalogOrBudgetQuestion` | 商品目录事实问题（价格/多少/便宜/预算… **且** 衣服/服饰/T恤/卫衣…），或无品类词但"预算/搭配/几件" | Agent 工作流（→ RAG，必要时 → `PLAN_BUDGET`） |
| `purchaseProductId`（购买动作 **且** 认得出商品） | 购买动作（买一件/下单/来一件/买 2 件…，且**非**预算咨询）**并且**句子里能定位到具体商品 | Agent 工作流（→ 下单确认） |
| 只有购买动作、认不出商品（`hasPurchaseIntent` 为真但 `purchaseProductId` 为 null） | 「我想买个暖和的围脖」「我要买，选哪个好」 | **直答**（先检索再让模型反问要哪一件）—— 2026-11-05 修正，见 §5.4.2 |
| `isCartAction` / `isAddToCartRequest` / `isViewCartRequest` / `isCartCheckoutRequest` / `isOrderListRequest` / `isAccountQuery` | 购物车类措辞（含"加入购物车""我的购物车里有什么""把购物车里的都下单"）、我的订单/几个订单、我的账户/地址/支付方式/优惠券 | Agent 工作流（2026-11-09 起这些都有真工具，见 §5.4.6） |
| `isCancelOrderRequest` / `isRefundRequest` | 取消订单（**不含**取消订阅/取消关注）、退款**动作**（我要退款/申请退款；"怎么退款/退款要几天"算知识问法，**不**进工作流） | Agent 工作流（→ `CANCEL_ORDER` / `REQUEST_REFUND`，见 §5.4.6） |
| `isOrderLifecycleAction` / `isTicketCreateRequest` / `isTicketActionRequest` | 确认收货/修改地址（无对应工具的剩余生命周期动作）、创建+工单、更新/关闭/升级工单/我要投诉 | Agent 工作流（生命周期动作 2026-11-09 起**明确转人工**：`CREATE_TICKET` + `SEARCH_KNOWLEDGE`） |
| 以上都不命中（闲聊、推荐、通用售后） | — | `runDirectQwen()`：**先查一次知识库**（§5.5），命中就带来源作答，否则按通用知识 |

> 新增一类业务需求：先在 `QuestionSignals` 加语义方法（关键词也只加在那里），再在路由里排一行；
> `IntentAgent` 的启发式与护栏要用的同类判定同样从 `QuestionSignals` 取，保证"路由"和"意图"永远同口径。

### 5.2 Agent 工作流时序
```text
workflow_started
  └─ agent_started(IntentAgent)
     └─ IntentAgent.analyze()  ← AI 返回严格 JSON（可被启发式/hard rule 覆盖）
        └─ agent_completed(intent, title, priority, confidence)
           ├─ tool_call           (AI 提议的 tool_calls)
           ├─ agent_started       ("ToolDispatcher 正在执行工具")
           ├─ tool_result         (ToolDispatcher.dispatchAll 结果)
           │   ├─ ticket_created / ticket_updated
           │   └─ order_confirmation_required
           ├─ agent_started(OrderAgent / KnowledgeAgent)
           ├─ knowledge_sources   (RAG 片段：不等模型，先点亮右侧面板)
           ├─ final × N           (知识回答**逐 token**增量，边收边推；DeepSeek 真流式)
           ├─ agent_completed(orderAnalysis, knowledgeAnswer=已累加全文, streamed=true)
           ├─ final × M           (确定性小节：订单情况/需要你确认/工单/失败，按句 90ms)
           ├─ final(done=true)    (带 fullText，前端停光标；同时落进上下文供会话历史使用)
           └─ trace               (内存 trace 步骤)
```

> **并行性**：订单分析与知识生成同时跑。`orderAnalysis` 用 `Mono.cache()` + 一个只负责"提前订阅"的空分支
> （`orderKickoff`）启动 —— `Flux.concat` 是懒订阅，不这么做订单分析会排在知识流之后才开始（串行，慢一倍）。
> **顺序变化**（2026-11-04 真流式）：`agent_completed` 从"before 下发"改成"流结束后发"，因为回答是靠流产出的。
> 前端卡片语义不变（running → done），`knowledgeAnswer` 字段是已累加全文。

流终止后（正常完成 / 失败 / 客户端取消），`doFinally` 会把整条 trace 异步落库
（`workflow_traces` + `workflow_trace_steps`，boundedElastic 执行，失败只 warn）。
前端在 `trace` 事件里看到的步骤，和数据库里存的是同一份数据，但**数据库那份能长期回放**。

### 5.3 安全下单（两阶段）
```text
"我想要买米色帆布双肩包和藏青帆布鞋，帮我下单"
→ IntentAgent 命中 QuestionSignals.purchaseItems → 一个 CREATE_ORDER{items:[{productId,quantity}, …]}
→ CreateOrderTool → CreateOrderConfirmationService.create()
   ├─ 逐行校验 productId 非空（禁止 AI 编造商品）、quantity>0、商品 active、库存充足
   ├─ 任一行失败 → 整单失败（不会出现"下了一半"的确认单）
   ├─ 解析可信地址/支付方式（未指定则用客户默认）
   └─ 写入 order_confirmations（PENDING，10 分钟过期）+ N 行 order_confirmation_items → 返回 OCF-xxxxxxxx
→ SSE order_confirmation_required → 前端弹"高风险操作确认"卡片（一张卡、多行明细）
→ 用户点【确认下单】POST /assistant/order-confirmations/{id}/confirm（一次确认整单）
→ ConfirmOrderService.confirm() @Transactional：逐行复核价格/库存并预留 → 建 1 张 orders + N 行 order_items
   ├─ 校验归属（customerId + sessionId 必须一致）、状态必须 PENDING、未过期
   ├─ 逐行校验价格未变动（任一行变了 → confirmation=FAILED，要求重新确认）
   ├─ 逐行库存 reserve、地址/支付方式归属再校验
   ├─ 创建 1 张 orders(ORD-AI-xxxxxxxx) + N 行 order_items，扣库存
   └─ confirmation.status = CONFIRMED
   → paymentStatus 保持 UNPAID（**从不自动扣款**）
```

**一单多明细（V9，2026-11-05）**：`order_confirmations` 只留"单头"（客户/会话/地址/支付/合计/状态/过期时间），
商品明细在 `order_confirmation_items`（`confirmation_id` + `product_id` + `quantity` + `unit_price`）。
行小计 `quantity × unit_price` **不落库**，读取时算 —— 派生值存两份迟早对不上。
V9 迁移把历史单回填成一行明细，然后 drop 掉 `product_id/quantity/unit_price` 三列，
避免"单头也有商品、明细也有商品"的双写。
前端契约：`items[]` + `totalQuantity` + `totalAmount`，卡片按行渲染（`OrderConfirmationCard`）。

---

### 5.4 回答生成的四层防线

回答质量靠四层，**越靠前越根本**。每层都有一条"没它就会怎样"的实测教训，别退回去。

| 层 | 机制 | 没它会怎样（实测） |
| --- | --- | --- |
| ① 路由 | `QuestionSignals` 统一判定"是不是商品/预算问题" → 进工作流 vs 直答 | 换种说法就进不了工作流，模型没有商品数据，编"上装300、下装250" |
| ② 工具 | `IntentAgent` 护栏强制补 `SEARCH_KNOWLEDGE` / `PLAN_BUDGET` | 模型不主动检索；或自己算预算，同一问题跑出 934/954/964 三个合计 |
| ③ 提示词 | 正面输出契约 + 权威块照抄（不列举禁用词） | 先输出错误结论再"更正"，把草稿给用户看 |
| ④ 组装与渲染 | `AssistantResponseAssembler` + 前端表格渲染 | 意图/置信度/score/内部 URI 进正文；`\| a \| b \|` 显示成裸管道符 |

#### 5.4.1 组装器：面向用户的三条硬性约定

`AssistantResponseAssembler` 的职责是**只留下用户需要看的**：

1. **结论优先**：知识结论 / 订单分析直接放最前，不写"我已完成多 Agent 分析"这类开场废话。
2. **不暴露内部信息**：意图枚举、置信度、工具 Trace、检索 `score`、内部 URI（`internal://...`）一律不进正文 ——
   它们在 SSE 事件里，由前端工作流卡片 / 工具卡片 / RAG 面板 / Trace 时间线展示。
3. **空小节不输出**：没创建工单就不提工单，没调工具就不提工具。

小节只有这五种，互斥出现：

| 小节 | 出现条件 |
| --- | --- |
| （无标题，直接正文） | 有知识结论或订单分析时，作为第一段 |
| `**订单情况**` | 有 `OrderAgent` 分析 |
| `**需要你确认**` | 生成了待确认订单（`orderConfirmation`） |
| `**工单**` | 真的创建/更新了工单（带 ticketId / 状态 / 优先级） |
| `**有一步没能完成**` | 有工具执行失败（失败原因要给用户，这不是内部细节） |

#### 5.4.2 判定口径的唯一来源：`QuestionSignals`

**唯一关键词表**在 `application/routing/QuestionSignals`（纯函数，`QuestionSignalsTest` 覆盖）。
关键词与正则**只允许出现在这个类里**，调用方只能调语义方法。

以前同一批关键词在四处各写一遍、口径还不一致（品类词少几个、下单多一条正则、"一共"后面要不要跟"订单"），
结果是同一个需求换个说法就有两种行为，改 A 忘 B。现在的调用关系：

| 调用方 | 用到的判定 | 出错时的表现 |
| --- | --- | --- |
| 路由 `AssistantWorkflowService.shouldUseAgentWorkflow` | 订单号 / 商品预算问答 / 下单 / 购物车 / 订单列表 / 账户 / 订单生命周期 / 工单（§5.1） | 判成闲聊 → 不进工作流 → 不检索 |
| 意图 `IntentAgent.heuristicIntent / heuristicToolCalls` | 同一批（顺序：订单列表 > 下单 > 商品问答 > 工单 > 订单 > 知识） | 模型漏判时补错意图/工具 |
| 护栏 `IntentAgent.applyCatalogGuard` | `isCatalogOrBudgetQuestion` + `catalogQueryFor` | 进了工作流但没检索，同样没有商品数据 |
| 查询词 `catalogQueryFor` | 句子里没有品类词时补「服装 商品 价格」 | BM25 一条都检不到 → "知识库里没有相关资料" |
| 下单 `IntentAgent.purchaseToolCalls` | `purchaseProductId`（动作 + 商品可识别）+ `extractPurchaseQuantity` | 把"问价格"当"下单"，或数量算错 |
| 规则兜底 `FallbackAiClientAdapter` | 与上面同一套（订单列表/下单/商品/数量） | 没配 API Key 时行为与真模型分叉 |
| 工具 `QueryOrderTool` | `isOrderListRequest` | 该列全部订单时只回最近一单 |
| 检索加权 `Bm25SearchAdapter` | `isAggregateQuestion` | 汇总片段被单品片段压过去 |
| 预算 `PlanBudgetTool` | `extractBudgetAmount` / `repeatPreference` | 件数、合计算错 |

判定规则：品类词（衣服/卫衣/大衣…）**且**带检索线索（价格/多少钱/便宜…）或购物线索（推荐/搭配/几件/预算…）→ 商品问题；
没有品类词时，购物线索 + 钱/量词（预算/搭配/几件/元）也算。**明确下单**不在此列，避免干扰确认卡片；
「推荐一个退款流程」「支付失败怎么办」不会误判。

`applyCatalogGuard` 不补检索的四种情形：已检索过 / 有 `CREATE_ORDER` / 意图是订单或工单类 / 这句话不像在问商品。

**两个容易改坏的点**：
1. **下单 ≠ 句子里出现"买"**：「最多可以买几件衣服」里的"买"是选购；`hasPurchaseIntent` 先排除预算措辞，
   再要求"下单动词 + 商品可识别"同时成立（`purchaseProductId` 缺一返回 null）。
2. **意图口径比路由宽一档**：`mentionsTicket` 只要提到"工单"就算工单意图（工作流内补 `CREATE_TICKET` 更安全），
   而路由仍要求"动词 + 工单"（`isTicketCreateRequest`）。这是**有意保留的差异**，两条都有测试锁定。
3. **购买意图不单独决定路由**（2026-11-05）：路由第三路是 `purchaseProductId != null`，不是 `hasPurchaseIntent`。
   原因是一次真实的坏体验 —— 「我想买个暖和的围脖」进了工作流，但没有任何可下单的商品，工具一个都不会被调用，
   正文只剩空文案 → 用户看到"没能给出有效回答"。现在"只有买、认不出商品"走直答：先检索
   （语义向量能把「围脖」召回「围巾」），再让模型反问要哪一件。`hasPurchaseIntent` 仍留在表里，
   它服务于**意图**与兜底 JSON，只是不再单独决定路由 —— `QuestionSignalsImpactTest` 把这条差异写成断言。

**一句话买多件（2026-11-05 真实故障 → 已落地为一单多明细）**：用户说「我想要买米色帆布双肩包和藏青帆布鞋，帮我下单」，
结果只下成了**后一件**。四处都只处理第一个商品，缺一不可地都要修：

| 环节 | 原来的问题 | 现在 |
| --- | --- | --- |
| 信号 | `purchaseProductId` 只返回一个，且顺序取决于商品名表的**行序**（不是句子顺序） | 新增 `purchaseItems(message)` → 按**句子出现顺序**返回全部商品；同位置**长名优先**（否则「米色帆布双肩包」会被「双肩包」「包」抢走），重叠候选只留最长，同一 productId 去重 |
| 意图/兜底 | `IntentAgent.purchaseToolCalls` / `FallbackAiClientAdapter` 只生成一个商品参数 | **一次** `CREATE_ORDER`，商品放进 `items:[{productId,quantity}]`（工具同时兼容旧的 `productId`+`quantity` 单件形态） |
| 确认单 | 模型是"一单一商品"，多件只能拆成多张卡分别确认 | 升级为**一单多明细**（V9：`order_confirmation_items`），一张卡一次确认整单 |
| 事件与文案 | `toolEvents` 用 `findFirst()`（多张卡只推第一张）；文案只说"已生成待确认订单" | 一个确认单一个事件；文案列出「A × 1（¥x）、B × 1（¥y），共 N 件，合计 ¥Z」 |

数量口径（有意简化）：单个商品沿用「买 3 件」；多个商品只认「**各 2 件**」，没写就每个 1 件 ——
否则「买 2 件卫衣和裤子」会把裤子也算成 2 件。

回归用例：`QuestionSignalsTest`（5 条：句子顺序 / 长名优先 / 同商品去重 / 数量口径 / 非下单场景）、
`IntentAgentTest.multiProductPurchaseCreatesOneOrderCallWithAllItems`（一个调用、两行 items）、
`AssistantSmokeTest.multiProductPurchaseCreatesOneConfirmationWithAllItems`（端到端：一张卡两行明细 → 确认 → 1 张订单 + 2 行 order_items + 库存各预留 1）、
`OrderConfirmationCard.test.tsx`（前端逐行渲染与合计）。

> 另修过一个**真实模型才会踩的坑**：DeepSeek 会把工具名当意图返回（实测 `{"intent":"CREATE_ORDER"}`），
> 原 `IntentType.valueOf(...)` 直接抛异常，把整段 JSON（含 `title`/`confidence`/`tool_calls`）都丢掉。
> 现在 `parseIntent` 容错返回 null，再由 `heuristicIntent` 补判，模型给出的其余字段保留。

**改表的影响面怎么看得见**（2026-11-05，原 §11.1 第 5 条）：

之前"一张表九处复用"的唯一防线是"跑全量测试"—— 挂了才知道伤到谁，而且看不出**哪一路**变了。现在三层防线：

| 防线 | 做法 | 拦住什么 |
| --- | --- | --- |
| ① 登记对账 | `QuestionSignals.signalConsumers()`：每个 public 信号 → 具体消费者的清单；`QuestionSignalsImpactTest` 用**反射**比对 public 方法集合 | 新增信号却没想"这会被谁用"（测试直接失败） |
| ② 意图断言 | 每张表的**每个词条**都必须点亮它该点亮的信号（品类词→`hasCategoryWord`、下单词→`hasPurchaseIntent`+可路由…） | 加了个词但它其实是哑弹（永远不生效） |
| ③ 快照矩阵 | 全部词条 + 18 条真实问法 → 每条语料"点亮了哪些信号"，写进 `src/test/resources/question-signals-impact.txt`；比对按**语料标签**而不是行号 | 表改动带来的任何语义漂移；报告只列变化条目，例如加一个词只报一行：`＋ 新增语料「TABLE:ORDER_LIFECYCLE_CUES=退运费」=> isOrderLifecycleAction, needsAgentWorkflow` |

改表的正确姿势：改 → 跑 `QuestionSignalsImpactTest` 读 diff（每行都能对上 §5.4.2 的消费者表）→
确认无误后 `mvn -DsignalsSnapshot=true -Dtest=QuestionSignalsImpactTest test` 重生成快照 → **快照 diff 一起提交**。

顺带把**路由的九路组合**从 `AssistantWorkflowService` 搬进了 `QuestionSignals.needsAgentWorkflow`：
组合逻辑留在服务里时，"哪几个信号决定路由"只在那个文件可见，改表的人看不到全貌。
`routingIsExactlyTheOrOfItsNineSignals` 保证"路由 == 九路 OR"，防止只改一边。

#### 5.4.3 预算测算工具化：`PLAN_BUDGET`

**为什么必须工具化**：同一问题（1000 元、不重复）在**全新会话**连跑三次得到 6 件/¥934、6 件/¥954、6 件/¥964
（真值 **5 件/¥885**；它自己明细表里合计写的是 1144），同一段回答里"结论 6 件"与"明细 1144"互相矛盾。
**有历史的会话里之所以对，是因为上文恰好有正确答案被复述** —— 点「新会话」就必错。正确性不能建立在碰巧上。

| 组件 | 职责 |
| --- | --- |
| `domain/catalog/CatalogItem` | 商品条目，**价格以"分"存整数**，避免浮点误差 |
| `application/catalog/PriceCatalogService` | 从 `KnowledgeChunkSource`（全量片段）解析价格表，兼容单品/汇总两种写法，按 productId 去重、按价升序；**不依赖检索命中** |
| `application/catalog/BudgetPlanner` | 纯函数：允许重复 → 除法；不允许 → 从最便宜开始贪心，并给出"下一件最便宜是谁、为什么超预算" |
| `application/catalog/BudgetPlan` | 结果对象 + `toPromptBlock()`，渲染成给模型的权威测算块 |
| `application/tool/tools/PlanBudgetTool` | 工具实现，结果写入上下文 `budgetPlan` |

两道保险：

1. **确定性注入**：`IntentAgent.applyBudgetPlanGuard` 对"预算 + 几件"类问题强制追加 `PLAN_BUDGET`，
   并**覆盖模型传的参数** —— 模型会自己调这个工具但把 `allowRepeat` 传成 `false`（实测），把 10 件算成 5 件。
2. **权威块照抄**：`knowledge-agent.user.md` 新增 `{{planResult}}`，system prompt 第 0 条要求结论/明细照抄该块，
   禁止再自行加总、四舍五入或"复核"。

重复约束判定在 `QuestionSignals.repeatPreference`：明确禁止 → `false`；明确允许 → `true`；**没提 → null（按允许重复）**。
不能把"没提"当成"禁止"，否则数量最大化的问题会被少算。

验收（全新会话、无历史）：`不能重复` ×3 → 5 件/¥885/剩余 ¥115；`未提重复` ×2 → 10 件/¥990/剩余 ¥10。均完全一致。

> **目录每次扩容这些数字都会变，必须重标**（真机 + DeepSeek，全新会话）：
>
> | 目录 | `不能重复` | `未提重复` |
> | --- | --- | --- |
> | 10 件（V3） | 5 件 / ¥885 / 剩 ¥115 | 10 件 / ¥990 / 剩 ¥10 |
> | 30 件（V8） | 7 件 / ¥973 / 剩 ¥27 | 10 件 / ¥990 / 剩 ¥10 |
> | **60 件（V10，当前）** | **8 件 / ¥982 / 剩 ¥18** | **10 件 / ¥990 / 剩 ¥10** |
>
> 60 件下"不重复"的最优组合：¥99 + ¥109 + ¥119 + ¥119 + ¥129 + ¥129 + ¥139 + ¥139 = ¥982
> （再加一件最便宜的是 ¥149，¥982+¥149=¥1131 > ¥1000）。
> `未提重复` 不变是因为**最低价仍是 ¥99**（`CLOTH-TEE-001`）—— 这也是 V10 刻意不加 ¥99 以下商品的理由。
>
> 关键不是这几个数字，而是**它们由 `PLAN_BUDGET` 算出来、可复现**；改目录忘了重标，就会变成"文档说 7 件、实际算 8 件"。

#### 5.4.4 提示词：用"正面契约"，不要列举禁用词

**踩过的坑**：第一版写"禁止出现『更正：』『重新核算：』"，实测无效 —— **把禁用词写进提示词等于把它们喂给模型**，
它照旧吐「重新核算（以本表为准）」。改成规定输出结构后就干净了：

```
**结论**  一行定稿（数字/件数/合计都要对）
**明细**  被相加的数字 + 约束复核
**依据**  来源标题 / 缺失信息
```

再加两条纪律：① 先算完再落笔，正文要像成稿而非演算过程（自查：删掉某行不影响理解与信任就删掉）；
② 落笔前校验一致性 —— 合计 ≤ 预算、结论件数 = 明细行数。

> ⚠ 但要知道**提示词只能规整格式，救不了算错**：压掉草稿痕迹后，"自我更正"这个唯一的纠错机会也没了，
> 错误会变成"自信的错误答案"。所以算术必须走 §5.4.3 的工具。

#### 5.4.5 前端表格渲染

`markdownTable.ts`（纯函数）+ `StreamingAnswer`：识别表头 / `|---|---|` 分隔行 / 数据行，渲染成暗色表格；
短行自动补空单元格防错位；**流式输出中途缺分隔行时返回 null**、先按普通文本显示，补全后自动变表格。
`markdownTable.test.tsx` 6 例，其中一条专门断言"表格内容不得以裸管道符出现在正文"。

> ⚠ **维护红线**：`fallback` 规则引擎做关键词判断时，只能用 `AiRequest.structuredContext.userMessage`（或解析出的当前这句），
> **绝不能用整个 `userPrompt`** —— 提示词里含 `{{history}}` 与检索来源，历史里出现过的词会把当前问题劫持
> （实测：历史里一句含"退款"的回答，之后每次问商品都被答成退款，且错误回答又写回历史、自我加固）。
> 已由 `FallbackAiClientAdapterTest` 锁住。

---

#### 5.4.6 购物车 / 账户 / 售后：四类"真工具"（2026-11-09）

**问题**（实测于真机，DeepSeek）：路由里有 `isCartAction` / `isAccountQuery` / `isOrderLifecycleAction` 三路，
但工具清单里**没有任何对应工具**，`IntentAgent.heuristicToolCalls` 也没有分支 → 行为完全看模型心情：

| 用户说 | 修复前 | 问题 |
| --- | --- | --- |
| 帮我取消订单 | 工具 0 个 → "没能给出有效回答" | 直接哑火 |
| 确认收货 | 只调 `QUERY_ORDER` 回订单状态 | **最危险**：用户以为操作已成功 |
| 把我的订单取消掉 / 我想修改收货地址 | 只建工单，订单没动 | 语义与动作不符 |
| 把黑色连帽卫衣加入购物车 | 调 `CREATE_ORDER`，productId 被编成 `CLOTH-HOODIE-BLK` | 加购被当成下单 + 模型编 ID |
| 把基础纯棉白色T恤放进购物车 | 直接拒绝 | 同一意图两种结果 |

**做法**：新增 5 个工具 + 3 张表（V11），并把动作判定从"模型决定"改成"代码决定"：

```text
IntentAgent.afterSalesToolCalls(message, context, title)   ← 顺序即优先级
  加购  → ADD_TO_CART{items[]}          (productItemsIn 只认商品，不要求"购买动作")
  结算  → CREATE_ORDER{fromCart:true}   (整车转一张待确认单，成功后清车)
  查看车 → VIEW_CART
  取消  → CANCEL_ORDER{orderNo}
  退款  → REQUEST_REFUND{orderNo, reason}
  账户  → QUERY_ACCOUNT
  其余订单生命周期（确认收货 / 改地址）→ CREATE_TICKET + SEARCH_KNOWLEDGE（明确转人工）
```

**关键设计**（改之前先读）：

1. **覆盖模型输出**，不只是"模型漏了才补"：`parseOrFallback` 里买到商品 → 售后动作 → 才轮到模型的 `tool_calls`。
   否则 fallback 引擎对"取消订单"会给一个 `QUERY_ORDER`，护栏永远轮不到。
2. **`productItemsIn` 与 `purchaseItems` 分开**：「加入购物车」不含购买动词，但确实点名了商品 ——
   加购要商品实体、不要"下单"语义。`purchaseItems = hasPurchaseIntent ? productItemsIn : 空`。
3. **订单号现在会从消息里抽**（`QuestionSignals.orderNoIn`）：`WorkflowContext.orderNo` 原先**只来自请求参数**，
   用户把订单号写进句子（"帮我取消订单 ORD-AI-xxx"）时工作流根本拿不到。
4. **边界（有意为之）**：加购**不锁库存**（真实电商口径，只有创建待确认单才 reserve）；
   取消只允许 `CREATED + UNPAID` 并**释放预留库存**；退款**只落申请单不动钱**（同单可查、不可重复）；
   移除/清空购物车对话里**不支持**，`ViewCartTool` 会带回 `removeUnsupported` 让答案明说，而不是装作做完。
5. **答案渲染**在 `AssistantResponseAssembler`：新增 **购物车 / 账户信息 / 订单已取消 / 退款申请** 四小节，
   都是确定性文本（不依赖模型措辞）。前端不需要新组件 —— 这些小节随正文 Markdown 渲染。

**2026-11-09 补的两笔**（上一版上线的当天就被真机探出来了）：

1. **退款只能申请、不能查**：用户问「我的退款到哪了」得到"我这边无法查看你的退款状态" —— 能提交不能查比不能提交更伤信任。
   现在补 `QUERY_REFUND`（按订单号或最近 10 条）+ `AssistantResponseAssembler.refundQuerySection`（无记录时明确说"没查到 + 下一步怎么做"）；
   并且**退款申请落单的同时自动建 `REFUND` 工单**（HIGH），否则 `REQUESTED` 就是终点，没有人工会看到。
   时间戳统一按北京时间显示（`BEIJING_TIME` 格式化器），不要把 `Instant` 的 UTC 串直接丢给用户。
2. **"购物车"三个字一出现就被抢成查看命令**：实测「购物车里的东西会自动清空吗」被答成"购物车现在是空的"（答非所问）。
   `isViewCartRequest` 收紧为"必须含明确查看意图（`CART_VIEW_CUES`）或整句就是购物车"，规则/机制类问法交回知识检索；
   `isRemoveFromCartRequest` 仍走查看（先展示再明说"对话里不支持移除"），**空车时也补这句说明**。

**2026-11-09 第三笔：退货 ≠ 退款**。上一版只有退款，「我要退货」只能被拒绝（"我这边无法代办"）。现在
`REQUEST_RETURN`（落 `return_requests` + 建 `LOGISTICS` 工单，`order_no` 唯一防重复）+ `QUERY_RETURN`（查进度），
答案小节是 **退货申请 / 退货进度**，并明确写出"退货 = 先寄回验货，验货通过后原路退款"。未支付订单不能退货（应直接取消）。


**2026-11-11 第四笔：购物车优先于下单**。真机又踩了一次同类问题：用户说
「我想要买个切尔西短靴、藏青帆布鞋、深灰针织开衫。**先帮我加入购物车**」，
句子里既有购买词（"买个"）又能定位到商品，于是 `purchaseToolCalls` 先命中，**直接生成了待确认订单**（¥1057）。
现在 `cartToolCalls()` 会**抢在 `purchaseToolCalls` 之前**：只要句子里出现购物车这一路意图
（`isAddToCartRequest` / `isCartCheckoutRequest` / `isViewCartRequest`），动作就按购物车来，不升级成下单。
两处调用点都要改（`parseOrFallback` 覆盖模型输出 + `heuristicToolCalls`），漏一处就会在另一条路径上复发。
守卫：`AssistantSmokeTest.cartRequestWinsOverPurchaseIntent`。

另一笔独立加固：**工具参数白名单**（`ToolArgumentGuard`，见 §7）—— 之前 `ToolDispatcher` 只校验工具名，
`productId` 完全由模型给。现在的规则是"商品必须真实存在且在售 / 数量 1~20 / 与句子里点名的商品不能完全无关 / 订单号与动作取值合法"。

**踩坑（重要，别再踩）**：`JpaCustomerTrustRepository` 新加的 `findAddresses` 一调就
`NullPointerException: this.addressRepository is null`，而同一个类里**老方法 `findDefaultAddress` 正常**。
实测 `repository.getClass()` 是 `...JpaCustomerTrustRepository$$SpringCGLIB$$0` —— `@Repository` 会被
Spring 的 `PersistenceExceptionTranslationPostProcessor` 包 CGLIB 代理，新方法在代理实例上执行（字段全 null）。
规避方式：账户查询单独走**新端口 `AccountDirectory` + `@Component` 实现 `JpaAccountDirectory`**（纯读，不需要异常翻译）。
这与 §4.2 里 `JpaConversationMemory` 的 CGLIB 坑是同一类问题的两种表现，判断法相同：
**症状是"字段为 null / 代理实例执行方法"，先打印 `getClass()` 确认是不是代理。**

---


### 5.5 直答路径也查知识库（原 §11.1 第 3 条）

**问题**：`runDirectQwen`（闲聊 / 通用售后）以前**完全不碰知识库**，需要资料的问法只能靠模型自身知识 → 可能编
（"运费多少"会给出一个像样的数字）。

**做法**（2026-11-04）：直答前先跑一次本地 BM25 检索（无模型成本），命中就带资料作答：

```text
shouldUseAgentWorkflow == false
  └─ KnowledgeAgent.retrieveKnowledge(userMessage)        ← 前置检索（分值 ≥ 0.05 才留，最多 3 条）
     ├─ 命中 → 推 knowledge_sources 事件（右侧 RAG 面板点亮）
     │        → direct-answer 提示词带上 {{sources}} → 模型按资料答 + 末尾 `**依据**` 列标题
     └─ 未命中 → {{sources}} 为空 → 模型按通用售后常识答，且明确"以页面/人工客服确认为准"，不许编引用
```

要点：

| 项 | 值 / 理由 |
| --- | --- |
| 相关性下限 `MIN_DIRECT_ANSWER_SCORE` | **0.10**（混合检索的绝对融合分，真机标定）：噪声 0.041~0.081（你好/谢谢/发票/运费/写诗），真命中 0.122~0.670（支持哪些支付方式 0.122 / 订单状态 0.176 / 外套 0.259 / 七百块 0.367 / 帽衫 0.390 / 裙子 0.527 / 支付补偿 0.631）。分界落在 0.08~0.12 之间故取 0.10；**换语料要重标**。 |
| 条数 `DIRECT_ANSWER_SOURCES` | 3（再多是噪音，也白烧 token） |
| 检索失败 | 只 `log.warn`，按"无资料"继续 —— 检索是增强能力，不能让回答失败 |
| trace | 直答路径也记一步 `SearchKnowledge`（`stage=direct-answer`，输出 `hits`/`used`），落库后能从 `workflow_traces` 查到"这轮到底有没有查库" |
| 提示词口径 | 有资料：结论必须依据资料 + `**依据**` 列标题 + 资料没覆盖就说"资料里没有"；无资料：按常识但**不许编引用**；资料相关度不够：忽略它且不写 `**依据**` |

真机验证（DeepSeek）：

| 问法 | 改前 | 改后 |
| --- | --- | --- |
| 订单显示 FAILED 是什么意思 | 模型自己解释（可能不准） | 推 `knowledge_sources`，回答末尾 `**依据**` 引用「订单状态说明 / 支付成功但订单未生成处理规范」 |
| 支付成功但订单没生成怎么办 | 同上 | 引用「支付成功但订单未生成处理规范」，结论与文档一致 |
| 运费多少 / 谢谢，明白了 / 帮我写诗 | — | **无来源、无 `依据` 段**；运费明确回"没有可依据的资料，以页面/人工客服为准" |
| 支持哪些支付方式 | 检索把**服装价格表**顶到第一位（0.4 白拿的汇总加权） | 命中订单/支付类片段，并诚实回答"资料中没有说明" |

**同时修掉的检索 bug**：`summaryBoost` 原来只看"问题是不是汇总型"，不看"问题是不是商品/预算类" ——
于是任何含「哪些/有什么」的售后问题都会让整张价格表白拿 +0.4，把真实命中（0.036）压在下面。
现在两个条件缺一不可（见 §6.5 ②）。

**测试**：`KnowledgeAgentRetrievalTest`（阈值过滤 / 空消息不查 / 检索失败不炸 / 来源渲染）、
`AssistantWorkflowServiceTest.directAnswerCarriesKnowledgeSourcesWhenRetrieved` 与
`directAnswerSendsAnEmptyKnowledgeBlockWhenNothingIsRetrieved`、`Bm25SearchAdapterTest.nonCatalogQuestionGetsNoSummaryBoost`、
`ClasspathPromptCatalogTest.directAnswerTellsTheModelHowToTreatRetrievedKnowledge`。

---

### 5.6 提示词热加载（原 §11.1 第 8 条）

**问题**：`ClasspathPromptCatalog` 启动期一次性加载，改提示词必须重启 —— 调提示词是最频繁的活，
重启一次 6~10s，一天几十次。

**做法**：加载来源 + 两种重载方式 + 三条不变式。

| 项 | 说明 |
| --- | --- |
| 来源优先级 | **外部目录 `assistant.prompts.dir`（env `PROMPTS_DIR`）→ classpath**。外部目录里只要有 `<id>.system.md` 就当它是**权威来源**：缺文件算配置错误，不再偷偷回落 classpath（两个来源混读时，"改了没生效"极难排查） |
| 自动重载 | `get()` 时按 `check-interval`（默认 `PT1S`）**懒检查**文件指纹（mtime + 大小），变了才重读。用懒检查而不是监听线程：不起线程、不漏事件，一次 stat 远便宜于一次问答 |
| 手动重载 | `POST /admin/prompts/reload` 立即重读；`GET /admin/prompts` 看每个 prompt 的**实际文件路径**与最近一次错误 |
| 启动期 | 缺文件/内容为空 → **启动失败**（提示词是运行契约） |
| 运行期 | 重载失败（文件被删/清空）→ **保留上一版** + 记 error，对话继续；同一个坏状态只报一次，修好后自动恢复 |
| jar 内 | `ClassPathResource.getFile()` 不可用 → 自动退化为"启动加载一次"，并提示改用外部目录 |

**本地开发的标准姿势**（IDEA / Maven 都适用）：

```bash
PROMPTS_DIR=backend/src/main/resources/prompts   # 相对 backend/ 工作目录
```

改 `src/main/resources/prompts/*.md` 保存即生效，**不用重启、也不用 Build**（不配的话读的是
`target/classes`，得先 Build 一次才会更新）。

**两个真机踩到的坑**（都有测试盯着）：

1. **多个构造函数导致配置静默失效**：`ClasspathPromptCatalog` 同时有"无参（测试用）"和"带配置"两个构造函数，
   Spring 在都没标 `@Autowired` 时会**回落到无参构造函数** —— 于是 `PROMPTS_DIR` 设了也白设，
   `/admin/prompts` 里看到的还是 `target/classes`。修法：配置构造函数加 `@Autowired`，
   并且由 `SemanticEmbeddingWiringTest` 同款的 `PromptHotReloadWiringTest` 断言"配了外部目录 → 容器真的读它"。
2. **"改坏了不能把服务带崩"**：删除一个模板文件后 `reload()` 必须失败但保留旧版 ——
   否则一次手抖的保存会让正在进行的对话抛 `IllegalArgumentException`。

**验证**（真机）：追加一条"正文最后必须输出一行：热加载已生效"到 `direct-answer.system.md`，
进程不重启、2 秒后再问，回答末尾就出现了这一行；日志依次打出
`提示词外部目录已启用` / `提示词热加载已启用（PT0.5S）` / `提示词已重新加载（4 个）`。

**测试**：`PromptHotReloadTest`（外部目录优先、手动/自动重载、坏文件保留旧版、空文件拒绝、启动 fail-fast、
目录不存在回落）、`PromptHotReloadWiringTest`（`@SpringBootTest`：配置真的接到 Bean 上）、
`AssistantSmokeTest.promptAdminEndpointReportsSourcesAndReloadsWithoutRestart`（两个接口通）。

---
### 5.7 鉴权与数据归属（2026-11-09）

**问题**：全站原先无鉴权 —— `customerId` 由请求参数传入，把参数改成别人的 ID 就能读别人的会话历史
（实测 `customerId=CUST-9999` 能读到 CUST-1001 的对话原文）、查别人的订单。

**做法**：把"客户身份"从**请求参数**移到**令牌**上。

```text
POST /auth/login {customerId}                 → {token, customerId, expiresAt}   （演示级：只校验客户存在）
  └─ 令牌落 auth_tokens（24h，可注销）
GET  /assistant/** + Authorization: Bearer tk_xxx
  └─ AuthWebFilter：解析令牌 → 客户端身份放进 exchange 属性 → 控制器 AuthContext.resolveCustomer()
       ├─ 有令牌：以令牌里的客户为准，**请求参数里的 customerId 不再具备授权含义**
       ├─ 无令牌/过期/已注销：401
       └─ assistant.auth.enabled=false：整体放行（测试/演示模式）
```

**四个必须知道的细节**：

1. **SSE 的令牌只能走 URL**：`EventSource` 不能自定义请求头，所以 `/assistant/stream?...&token=tk_xxx`。
   过滤器同时接受 `Authorization` / `X-Customer-Token` / `?token=`。代价是令牌可能进访问日志，因此令牌是短期的（24h）且可注销。
2. **会话历史必须校验归属**：`GET /assistant/sessions/{id}/messages` 现在先 `ConversationMemory.requireOwnership`，
   越权与不存在**统一 404**（不透露"存在但无权"，否则等于给了存在性探针）。
3. **公开路径只有登录、商品目录与健康检查**：`/auth/**`、`/products`、`/health`、`/actuator`、`/v3/api-docs`、`/swagger-ui`。
   `/admin/prompts/**` 从这一版起**也要令牌**（历史遗留讨论见 §11.1 第 8 条）。
4. **边界（别当成真实登录）**：`login` 只校验客户 ID 存在，没有密码/验证码 —— 解决的是"越权访问"，
   不是"证明你是你"。真实项目要接统一登录 / OAuth / 短信验证后再发令牌。

**踩坑（第三次撞同一类代理问题，务必先看这里）**：给 `AssistantStreamController.stream` 加了 `ServerWebExchange`
参数后，所有 SSE 请求 500：`this.workflowService is null`。原因是该控制器上有类级 `@Validated`（方法参数校验），
Spring 用 **CGLIB 代理**它；新签名的方法在代理实例上执行时字段全 null。**修法是去掉 `@Validated`**，
把 `@NotBlank` 换成方法内显式判空。同类坑已经出现三次：
`JpaConversationMemory`（`@Transactional`）、`JpaCustomerTrustRepository`（`@Repository` 异常翻译）、
本次（`@Validated`）。**判断法：症状是"字段为 null / 方法体自己跑了"，先打印 `getClass()` 看是不是代理。**

### 5.8 数据保留与清理（2026-11-09）

`RetentionCleanupService.runOnce()` 清理四类"只增不减"的数据（**业务账不碰**：订单/工单/退款/退货/优惠券）：

| 对象 | 判定 | 默认保留 |
| --- | --- | --- |
| 待确认单（含明细行） | `status IN ('PENDING','EXPIRED')` 且 `created_at` 早于阈值 | 7 天 |
| 购物车行 | `updated_at` 早于阈值 | 30 天 |
| 会话（连消息） | `updated_at` 早于阈值 | 30 天 |
| 审计（trace / steps / tool_invocations） | `started_at`(trace) / `created_at`(tool) 早于阈值 | 14 天 |
| 登录令牌 | 过期或已注销超过 1 天 | — |

定时执行默认**关闭**（`assistant.retention.enabled=false`）—— 清理是破坏性操作，演示环境不该被后台任务动数据；
需要时打开（`@Scheduled(cron=...)`）或直接调 `runOnce()`。测试里不跑定时任务，由冒烟用例直接调 service 验证。

> ⚠ `workflow_traces` **没有 `created_at`**，是 `started_at`；`workflow_trace_steps` 关联的是 `trace_id → workflow_traces.id`。
> 写清理 SQL 时踩过一次（BadSqlGrammar），改之前先看 §6.1 的表结构。

---

### 5.9 售后审核台（2026-11-09）

**问题**：V11/V12 做到"用户提交退款/退货 + 自动建工单"，但申请单停在 `REQUESTED`，**审核只能人肉翻库**，
用户问"我的退款到哪了"永远只会看到 `REQUESTED`。

**做法**：申请单加审核字段（V13）+ 一套"两个资源 × 三个动作"的审核接口 + 一个审核台页面。

```text
GET  /admin/after-sales/summary                  → {pendingRefunds, pendingReturns, approvedRefunds, rejectedRefunds}
GET  /admin/after-sales/refunds?status=REQUESTED  （status: REQUESTED|APPROVED|REJECTED，缺省 REQUESTED）
GET  /admin/after-sales/returns?status=…
POST /admin/after-sales/{refunds|returns}/{id}/approve   {note?, reviewer?}
POST /admin/after-sales/{refunds|returns}/{id}/reject    {note 必填}
```

**状态机**（规则写在领域对象里，服务层不各写一遍）：

| 状态 | 含义（退款） | 含义（退货） |
| --- | --- | --- |
| `REQUESTED` | 待人工审核 | 待人工审核 |
| `APPROVED` | 已通过，走原路退款（本项目不动钱） | 已同意寄回，等收货验货 |
| `REJECTED` | 已驳回，**必须写原因** | 已驳回，**必须写原因** |

四条约定：

1. **只有 `REQUESTED` 能审**：重复审核 → **409**（`IllegalStateException` 已单独映射，不再兜底成 500）。
2. **驳回必须写原因**：空备注 → 400。原因会写进 `review_note`，**用户在对话里能看到**（`QUERY_REFUND`/`QUERY_RETURN` 的小节会显示备注）。
3. **审核即收口工单**：通过/驳回都会把关联工单 `resolve`（带结论）。V13 之前 `ticket_id` 没落库，读不出来 —— 现在存了，
   所以 `申请 → 工单 → 审核结果` 是一条可回放的链。
4. **没有角色体系**：`/admin/**` 只要求有效令牌，任何持令牌的调用方都能审核，`reviewedBy` 仅作审计
   （`X-Reviewer` 头或 body 里的 `reviewer`，缺省 `staff`）。引入员工身份/权限属独立议题，列在 §11.1。

**前端**：顶部导航切换「客服工作台 / 售后审核台」（项目没引路由库，用一个 `useState` 足够了）。
审核台支持"退款/退货 × 待审/已通过/已驳回"六种组合，行内可填审核备注、逐条通过或驳回，操作后自动刷新；
驳回未填原因时**前端先拦住**（不发请求），后端也会再挡一次。

---

### 5.10 决策模式：模型优先，关键词兜底（2026-11-11）

**为什么改**：此前是"关键词覆盖优先"—— `IntentAgent` 里 4 处代码覆盖（购物车 / 下单 / 售后 / 目录预算）
会在模型给出 `tool_calls` 之前抢先决定动作。结果模型即使判断对了也会被盖掉：
用户说「我想要买个 A、B、C。**先帮我加入购物车**」，代码按购买词判成下单，模型没有发言权。

**改法**：

```text
assistant.intent.mode = auto（默认）
   ├─ provider != fallback（真模型）→ MODEL_FIRST
   │    模型看完整工具清单 + 选择口径，自己给 tool_calls
   │      → enrichArguments() 补参数（商品 id / 订单号 / 预算 / 检索词，绝不覆盖动作）
   │      → 仍是 ToolArgumentGuard 兜底校验
   └─ provider == fallback（规则引擎，离线/测试）→ LEGACY
        关键词层照旧（规则引擎不是"理解"，让它选工具反而不如关键词确定）
```

- **补参的判据是"每条 item 都有非空 productId"，不是"items 非空"**。真机踩过（2026-11-11）：
  「我想要买军绿工装夹克、轻薄防风夹克、复古牛仔夹克，先帮我加入到购物车」模型正确选了 `ADD_TO_CART`，
  但给的是三条 `{productId:"", quantity:1}`（提示词要求"不知道就留空"，它照做了）；
  旧版把"items 非空"当成"已给明细"跳过补参 → 护栏报"商品编号不能为空"。
  现在整组按句子里点名的商品重建（模型给的数量按顺序保留），并把护栏话术改成
  「没能确定具体商品，请说得更具体一点」。
- 显式 `ASSISTANT_INTENT_MODE=model-first` / `legacy` 可强制任一模式，用于 A/B 对比与回滚。
- **模型没给任何 tool_calls 时**才走关键词兜底（购物车三态 > 下单 > 售后/账户 > 意图启发式）。
- 提示词不再硬编码工具枚举，改成"以用户消息里的 Tool metadata 为准"，并写明 7 条选择口径
  （购物车优先、退款/退货/取消之分、账户、预算、转人工等）与"不许编 productId/orderNo"。

**2026-11-11 第二笔：闸门本身也交给模型。** 上一版只把"调哪个工具"交给模型，但"要不要进工作流"仍是
`QuestionSignals.needsAgentWorkflow`（关键词）—— 于是「这个能退吗」「这单我不要了」「帮我查下我的单子」
这类自然说法**根本不进工作流**，模型完全没机会，统一得到"我无法直接操作"。实测 7 条口语里 6 条被挡在门外。

现在的入口逻辑（`AssistantWorkflowService.runWorkflow`）：

```text
纯闲聊？（短句 ≤20 字 + 寒暄白名单 + 无任何业务信号）→ 直答快路径（省一次模型调用）
  否则 → 先进工作流问 IntentAgent：
           有 tool_calls → 执行工具 + 上下文分析 + 组装回答
           无 tool_calls → 落回直答（先检索再作答），而不是留一句"没能给出有效回答"
```

同一批 6 条口语复测（修复后）：**6/6 全部进工作流**，其中「帮我查下我的单子」直接查到了订单、
「帮我看看还能退吗」走 `QUERY_RETURN`、「这单我不要了」走 `CANCEL_ORDER`、「东西不好用，想退」走 `REQUEST_RETURN`；
「这个能退吗」「我要取消」因信息不足走知识检索/直答（合理）。

> 副作用（已接受）：非闲聊请求都会多一次意图模型调用（约 0.5~1s）；`needsAgentWorkflow` 不再参与运行时路由，
> 只作为关键词覆盖度的**诊断口径**保留（单测与影响面快照仍在用）。
> 评测集里两条知识问法（「购物车会自动清空吗」「退款要几天到账」）的期望值同步改为 `SEARCH_KNOWLEDGE` ——
> 它们本来就是知识问题，模型主动检索是对的。

**A/B 数据**（32 条真实问法，DeepSeek，同一个库同一个 jar，只改 `ASSISTANT_INTENT_MODE`）：

| 模式 | 动作正确率 | 不一致项 |
| --- | --- | --- |
| `legacy`（关键词优先） | **28/31 = 90.3%** | 「1000元预算可以怎么搭配」漏 `PLAN_BUDGET`；「确认收货」多调检索；「我想修改收货地址」被 `isAccountQuery` 抢成 `QUERY_ACCOUNT`（应为转人工） |
| `model-first`（模型优先） | **32/32 = 100%**（补参修好后复跑） | — |

复跑方式：`.\scripts\intent-eval.ps1 -BaseUrl http://localhost:18080 -Label model-first`
语料：`backend/src/test/resources/intent-eval-corpus.tsv`（**线上判错的真实说法先加进语料，再改代码**）。

> ⚠ 评测用的是真模型，结果有随机性：改提示词或换模型后要重跑；`fallback` 引擎跑这份语料不代表线上表现。
> 另外关键词层仍是唯一进工作流的入口（粗路由），它认不出的说法依旧会掉到直答 —— 例如
> 「购物车里**还**剩什么」曾因为线索写的是"购物车里剩"而漏掉（已补）。

---

### 5.11 只说了品类 → 列候选；没给订单号 → 取最近一单（2026-11-11）

两处"替用户猜"的边界，现在都改成**不猜错、也不装懂**。

**① 品类歧义 → 列 2~3 个候选 + 反问**

以前用户说「我要买一件羽绒服」，系统会**替他挑一件**（"黑色短款羽绒服 ¥899"）直接生成待确认单。
确认卡片虽然能兜住，但等于替用户做了选品决定。

现在（`CategoryCandidateService` + `AssistantResponseAssembler.purchaseCandidatesText`）：

```text
购买/加购类请求 → 句子里出现品类词？
     该品类在目录里有 ≥2 个 SKU  → 列 2~3 个候选（按价格升序）+ 反问「想买哪一款？」
     该品类只有 1 个 SKU         → 照常下单（「帆布鞋」目录里只有一件，不算歧义）
     句子里已有完整商品名        → 当具体款处理，不问
```

真机实测「我要买一件夹克」：

```text
**帮你选一下**
你说的「夹克」有好几款，我不会替你挑，你选一个我再继续：
1. 复古牛仔夹克（CLOTH-JACKET-038，¥389.00）
2. 轻薄防风夹克（CLOTH-JACKET-006，¥399.00）
3. 军绿工装夹克（CLOTH-JACKET-021，¥459.00）
- 想买哪一款？直接回我名字或编号（例如「就第 1 个」）；也可以说预算，我帮你挑一件合适的。
```

> 判定"歧义与否"看的是**目录里有几个 SKU**，不是手写一张"品类词/具体名"表 —— 那是数据属性，
> 加商品就该自动生效（手写表迟早和目录脱节，这个项目已经踩过一次：工具加了提示词没写）。

**② 没给订单号 → 按动作取最近一笔（`OrderReferenceResolver`）**

模型选对了 `CANCEL_ORDER` / `REQUEST_RETURN`，但用户只说「这单我不要了」没写单号时，
以前只能回"请提供订单号"。现在按动作取其所需状态：

| 动作 | 取的订单 |
| --- | --- |
| `CANCEL_ORDER` | 最近一笔 **CREATED + 未支付** |
| `REQUEST_REFUND` / `QUERY_REFUND` / `REQUEST_RETURN` / `QUERY_RETURN` | 最近一笔 **已支付** |
| `QUERY_ORDER` | 最近一笔（不限状态） |

取不到就让工具照常要订单号 —— **不做跨状态兜底**（取消不会退而求其次去取消一张已支付订单），
也不按商品名反查订单（"那件夹克"指哪张单，容易猜错）。真机实测：

```text
「这单我不要了」→ CANCEL_ORDER，自动取 ORD-AI-1395FC61，已取消并释放预留库存
「我要退货」    → REQUEST_RETURN，自动取 ORD-1002（最近已支付），受理 RTN-… + 人工工单
```

---

## 6. 数据库

### 6.1 Flyway 迁移清单
| 版本 | 文件 | 内容 |
| --- | --- | --- |
| V1 | `V1__init_core_schema.sql` | tickets、ticket_messages、ticket_events、orders、order_items、support_agents、agent_capabilities、assistant_sessions、assistant_messages、workflow_traces、workflow_trace_steps、tool_invocations、knowledge_documents、knowledge_chunks（14 张） |
| V2 | `V2__seed_demo_data.sql` | 演示订单、客服人员、支付/订单知识库 |
| V3 | `V3__add_safe_order_creation.sql` | products、product_inventory、customer_addresses、customer_payment_methods、order_confirmations（5 张） |
| V4 | `V4__seed_demo_order_items.sql` | 演示订单明细 |
| V5 | `V5__seed_clothing_catalog_knowledge.sql` | 服装商品目录（CLOTH-*-001~010）+ 目录知识 chunk |
| V7 | `V7__clean_catalog_chunk_filler.sql` | 清理服装目录单品 chunk 的「价格刚好X元」填充语（见 §6.5） |
| V8 | `V8__expand_clothing_catalog_seed.sql` | **服装目录扩到 30 件**：新增 20 件商品（含库存、单品 chunk），重写汇总价格表片段与目录文档正文（见 §6.6） |
| V9 | `V9__order_confirmation_items.sql` | **待确认单升级为一单多明细**：新建 `order_confirmation_items`、回填历史单，再删掉 `order_confirmations` 上的 product_id/quantity/unit_price 冗余列（见 §5.3） |
| V10 | `V10__expand_clothing_catalog_to_60.sql` | **服装目录再扩 30 → 60 件**：新增 30 件（童装/家居服/运动/鞋/配饰等新品类），含库存与单品 chunk，重写汇总价格表（1917 字）与目录文档正文（见 §6.5 ⑥） |
| V11 | `V11__cart_account_and_after_sales.sql` | **购物车 / 优惠券 / 退款申请三张表**：`cart_items`（客户+商品唯一，重复加购合并）、`customer_coupons`（含 CUST-1001 三张券，一张故意过期）、`refund_requests`（退款申请单，状态 `REQUESTED`）（见 §5.4.5） |
| V12 | `V12__auth_tokens_and_returns.sql` | **登录令牌 + 退货申请**：`auth_tokens`（令牌 ↔ 客户，24h 有效可注销）、`return_requests`（退货申请单，`order_no` 唯一：同单不能并发重复提交）（见 §5.7 / §5.4.6） |
| V13 | `V13__after_sales_review.sql` | **售后审核字段**：退款/退货单各加 `ticket_id` / `review_note` / `reviewed_by` / `reviewed_at`（原来工单 id 只存在工具返回值里，审核端读不到），并按 `status` 建索引（见 §5.9） |

共 **24 张业务表**（+ `flyway_schema_history`）。

> ⚠ **两个迁移文件当前还没进 git**（V7、V8）：仓库根 `.git/info/exclude` 里原先写的是 `db/`，
> 它会连 `backend/src/main/resources/db/migration/` 一起忽略，新增的迁移文件会静默不进版本库。
> 已改成 `/db/`（只忽略根目录那份手工快照）；提交时记得把两个迁移 `git add` 进去 ——
> 否则别人拉到代码后库里少一批数据，而且 Flyway 不会报错。

### 6.2 表状态
| 状态 | 表 |
| --- | --- |
| **有 JPA 实体、实际读写** | `tickets`、`ticket_messages`、`orders`、`order_items`、`support_agents`、`agent_capabilities`、`cart_items`、`customer_coupons`、`refund_requests`、`return_requests`、`auth_tokens`、`products`、`product_inventory`、`customer_addresses`、`customer_payment_methods`、`order_confirmations`、`knowledge_documents`、`knowledge_chunks`、`tool_invocations`、`workflow_traces`、`workflow_trace_steps`、`ticket_events`、**`assistant_sessions`**、**`assistant_messages`** |
| **已建表但无 Java 读写** | 无（24 张表已全部接入；`agent_capabilities` / `support_agents` 只有实体与查询，无写入路径） |

> ### 审计类表的落库现状（2026-11-02 18:10 更新）
>
> 三张审计表已从"预留"转为真实读写，都是**追加式**写入，不参与业务判定：
>
> | 表 | 写入时机 | 写入者 | 粒度 |
> | --- | --- | --- | --- |
> | `workflow_traces` | 每个 `/assistant/stream` 请求**结束时**（含正常完成/失败/客户端取消） | `JpaWorkflowTraceRecorder` | 1 请求 = 1 行 |
> | `workflow_trace_steps` | 同上，随 trace 一起批量写 | `JpaWorkflowTraceRecorder` | 1 步骤 = 1 行 |
> | `ticket_events` | 工单创建/更新落库后 | `JpaTicketEventRecorder` | 1 次状态变更 = 1 行 |
> | `tool_invocations` | 每次工具调用后（早就有） | `JpaToolInvocationRecorder` | 1 次调用 = 1 行 |
>
> **三张表靠 traceId 串起来**，形成一条完整可回放的决策链：
> ```sql
> -- 回放某次请求：先看整体，再看每一步，最后看工具调用与工单变更
> SELECT * FROM workflow_traces WHERE id = 'trace-xxxxxxxx';
> SELECT step_name, step_kind, status, duration_millis, input_json, output_json
>   FROM workflow_trace_steps WHERE trace_id = 'trace-xxxxxxxx' ORDER BY id;
> SELECT tool_name, arguments_json, result_json, duration_millis
>   FROM tool_invocations WHERE trace_id = 'trace-xxxxxxxx';
> SELECT ticket_id, event_type, payload_json, created_at
>   FROM ticket_events ORDER BY created_at;         -- 工单时间线
> ```
> 附注：`workflow_traces` **没有 user_message 列**（V1 表结构如此，本次未改表）。用户原话保存在
> `workflow_trace_steps.input_json` 的 `IntentAgent` 步骤里（`{"message":"..."}`）；直答请求没有
> IntentAgent 步骤，因此**直答类请求的原始提问无法从这两张表还原**（只有 tool 侧和 steps 侧信息）。
> 若要补齐，需要加一版迁移给 `workflow_traces` 增列。
>
> ### 多轮会话表已接入（2026-11-03）
> `assistant_sessions` / `assistant_messages` 已由 `JpaConversationMemory` 真实读写，多轮记忆生效。
> 设计与实现细节见 **§4.6**；写入时机、不记录的边界（失败/取消不留半轮）也在那里。
>> **"未落库"的准确含义（保留，避免再次误解）**：表在库里真实存在（Flyway V1 建好，带索引），
> "未落库"指的是 `backend/src/main` 里**没有 Java 代码引用它**——没有实体、没有 Repository、没有 SQL，
> 因此运行期既不写也不读，Navicat 里手插的数据应用看不到，删表也不会让启动失败。
> 判断方法永远是看代码有没有写入口，而不是看表里有没有行。
### 6.3 关键领域不变量
- 订单号：种子数据为 `ORD-10xx`，AI 创建为 `ORD-AI-<8位大写>`；确认单号为 `OCF-<8位大写>`。
- `orders.order_no` 唯一约束；`order_confirmations` 有 `trace_id` / `session_id` / `customer_id` 用于归属校验（防越权确认）。
- V3 额外给既有表加列：`orders.source` / `orders.confirmation_id`（标记 AI 来源与来源确认单）、`order_items.product_id`（对齐商品目录）。新增订单字段时注意 V1 建表与 V3 加列两处。
- `Order.aiBusinessSummary()` 是**给 AI 看的业务摘要**，不是原始实体字段拼接 —— 新增状态时记得同步这里。
- Hibernate 为 `ddl-auto: validate`：**改实体但漏写迁移 → 启动直接失败**。

---

### 6.4 时区：表里直接显示北京时间，同时写入正确（2026-11-02 22:28 定稿）

**最终方案：让 MySQL 的"人工会话"是 +08:00，让"应用连接"强制 UTC。** 不需要视图，也不需要改实体。

| 角色 | 会话时区 | 来源 | 效果 |
| --- | --- | --- | --- |
| 人（Navicat / IDEA Database / 命令行） | **+08:00** | MySQL 全局 `time_zone='+08:00'`（compose `--default-time-zone=+08:00`） | `SELECT created_at` **直接就是北京时间**，无需 `CONVERT_TZ` |
| 应用（Spring Boot 的 JDBC 连接） | **+00:00** | JDBC URL `connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true` | 写入/读取的 `Instant` 与真实时刻一致 |

#### 为什么必须这么拆
`TIMESTAMP` 列**内部存的是 epoch**，显示时才按会话时区渲染。而 Hibernate 6 会把 `Instant` 归一化成
**「UTC 墙钟时间」**再绑定（绑参日志实测：`binding parameter (5:TIMESTAMP_UTC) <- [2026-11-02T13:35:08.069886Z]`），
也就是发给 MySQL 一个**不带时区**的 `13:35:08`。只有**该连接**的会话时区是 UTC 时，这个裸时间才被正确解释。

所以两件事必须同时成立：全局时区给人看（+08:00），应用自己的连接另钉成 UTC。
`forceConnectionTimeZoneToSession=true` 正是让驱动在连上后执行 `SET time_zone='+00:00'` 的开关。

#### 实测验证（22:27）
```
人工会话：@@global.time_zone=+08:00，NOW()=22:25:24          ← 北京时间
应用连接：session_tz=+00:00（探针对三种绑定方式偏差均为 0 秒）
写入校验：OCF-312A5CD8 的 epoch 偏差 −4 秒                    ← ✅ 正确
表里直读：created_at=2026-11-02 22:27:09，expires_at=22:37:09  ← ✅ 北京时间，+10 分钟正确
```

#### 曾经走过的弯路（别重蹈）
| 做法 | 结果 |
| --- | --- |
| 只把 MySQL 全局改成 `+08:00`（URL 仍 `serverTimezone=UTC`，不加 force） | ❌ 写入整体偏 **−8 小时**（3 次实测） |
| JDBC 改 `serverTimezone=Asia/Shanghai` | ❌ 仍偏 −8h |
| JDBC 改 `connectionTimeZone=Asia/Shanghai` | ❌ 仍偏 −8h |
| `hibernate.timezone.default_storage=NORMALIZE` | ❌ 仍偏 −8h |
| `hibernate.jdbc.time_zone=Asia/Shanghai` | ❌ 仍偏 −8h |
| 让 MySQL 保持 UTC + 北京时间视图 / `CONVERT_TZ` | ✅ 数据正确，但表里不是北京时间（已按需求撤掉 V6 视图） |

#### 维护红线
1. **URL 里这两个参数不能删、也不能把 `UTC` 改成 `Asia/Shanghai`**：
   `connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true`。删掉 → 写入偏 +8h；改 Asia/Shanghai → 偏 −8h。
2. **MySQL 全局时区保持 `+08:00`**（compose 已声明 `--default-time-zone=+08:00` + `TZ: Asia/Shanghai`）。
   被改回 UTC 也不会写错（应用连接自带 UTC），但表里又会显示 UTC。
3. **切回本机 MySQL（3306）时，URL 同样要带这两个参数**。本机 MySQL 的 `system_time_zone` 本来就是
   中国标准时间（+08:00），所以人工看是北京时间；但应用连接若不 force UTC，写入仍会偏 −8 小时。
4. 改完 `application.yml` 或 URL 后**必须重启后端**（连接池里的旧连接不会自动换时区）。
5. 临时单个查询想换显示时区：`SET time_zone='+08:00'`（只读会话可以；**别在写数据的会话里改**）。

#### 通用判断法
"是显示问题还是数据问题"永远看 epoch，别看格式化字符串：
```sql
SELECT created_at, UNIX_TIMESTAMP(created_at) FROM workflow_traces;
```
`UNIX_TIMESTAMP` 与真实发生时刻对得上 → 显示问题；对不上 → 写入问题。

---

### 6.5 知识库质量：数据、召回、评测集

**2026-11-04 起 RAG 不再"实为纯 BM25"**（原 §11.1 第 2 条）：查询改写（中文数字归一 + 同义扩展）
→ 向量召回（本地 TF-IDF 特征哈希）+ BM25 召回 → 融合打分；排序与阈值都用**绝对融合分**，RRF 只做并列裁决。
同时删掉了"恒返回空列表"的 `ChromaClient` 桩和 compose 里的 `chromadb` 服务 —— 它是"假混合"的根源，
留着只会继续误导文档。细节见 §6.5 ⑤ 与 §5.5。

在此之前，召回痛点的主因**并不只是缺向量**：数据脏 + 汇总片段被压 + 片段被截断，三项修完答案质量才明显变化。

#### ① 数据清理：V7 迁移

种子数据里每条单品 chunk 结尾都硬塞了「，价格刚好<自己的价格>元。」这种"为凑关键词加的话"，
于是问「价格刚好699的衣服是什么」时，259/229 的商品也含「价格刚好」被一起召回。

```sql
-- V7__clean_catalog_chunk_filler.sql
UPDATE knowledge_chunks
SET content = REGEXP_REPLACE(content, '，价格刚好[0-9.]+元。$', '。')
WHERE content REGEXP '，价格刚好[0-9.]+元。$';
```

> **必须用新迁移，不能改 V5** —— 改已应用的迁移会破坏 Flyway checksum，已有库直接启动失败。

#### ② 召回策略：汇总型问题要拿整张价格表

`Bm25SearchAdapter` 原本有 `catalogSummaryBoost`，但触发词只有「最便宜/哪些/低于…」，
**「预算/最多/几件/搭配」拿不到加权** → 328 字的整张价格表被 57 字的单品片段压过去 →
模型只看得到 3 个单品，算出"最低价 ¥229"（真实最低 ¥99）。现在：

- 汇总型问题判定统一走 `QuestionSignals.isAggregateQuestion`（预算/最多/几件/搭配/哪几种/便宜/推荐…）；
- 汇总型片段判定不再只认标题里的"完整价格表"，也支持**价格标记密度**（content 里 `¥xx` ≥ 5 个）。

> **2026-11-04 补的前提**：汇总加权还要满足 `QuestionSignals.isCatalogOrBudgetQuestion`（商品/预算类问题）。
> 只有"汇总型"一个条件时，售后问题里常见的"哪些/有什么"会让整张服装价格表白拿 +0.4
> （实测「支持哪些支付方式」→ 价格表 0.4 vs 真实命中 0.036），直答路径据此判断"有没有资料"会被彻底带偏。

#### ③ 片段不再被截断

`KnowledgeAgent` 原来把每条来源正文截断到 **120 字** —— 整张价格表 328 字被砍掉一半，
模型于是在答案里写"价格表被截断，无法确认是否还有更便宜的商品"。上限改为 **400 字**，
并注释写明**片段多大应由迁移/切分负责，这里只防超长**。

#### ④ 检索评测集（回归防线）

`RetrievalEvaluationTest`：语料**按迁移生成**（V3+V8 的 30 件商品 + 整张价格表 + 3 条售后 FAQ，共 34 个片段，不连数据库），
断言每类问法必须召回哪些片段。加商品只要改测试里那张 30 行表，评测集跟着长。

| 问法 | 断言 |
| --- | --- |
| 价格刚好699的衣服是什么 | top1 = `COAT-009`；第 2 名是整张价格表（它也含 699），第 3 名起必须是噪音级 |
| （30 件逐一）`<商品名>多少钱` | 必须召回**它自己**的片段（防同类商品互相压掉） |
| 1000元预算可以怎么搭配 / 最多可以买几件衣服 | 必须召回 `CHK-CATALOG-CLOTHING-ALL`（整张表） |
| 699 以内能买哪些衣服 / 最便宜的是哪件 | 必须召回 `...-ALL` |
| 支付成功但订单没生成怎么办 | 必须召回 `PAY-001-1` |
| 创建支付失败工单需要哪些信息 | 必须召回 `PAY-002-1` |
| **七百块的衣服**（中文数字） | 必须召回 `COAT-009` —— 归一成 700 + 数值邻近（±2%）匹配 ¥699 |
| **外套多少钱 / 帽衫多少钱 / 裙子有哪些**（同义改写） | 必须召回对应品类片段 —— 语料里根本没有"外套/帽衫"这两个词 |
| 想买件厚一点的外套过冬（与语料无字面交集） | 必须召回外套类，且 `matchReason` 里能看到 `vec#`（向量那一路真的参与了） |

为此检索数据源从 Spring Data 仓储抽成端口 `KnowledgeChunkSource`（JPA 实现 `JpaKnowledgeChunkSource`），
`Bm25SearchAdapter` 改用 domain 的 `KnowledgeChunk`。**改检索算法这组断言会立刻响**；新增问法只需加一条断言。

#### ⑤ 混合检索：向量 + BM25（2026-11-04 落地）

以前这里是"什么时候才需要向量"的规划，现在**已经接上了**，而且没上向量库：

| 环节 | 实现 | 关键点（都是踩过的坑） |
| --- | --- | --- |
| 查询改写 | `QueryRewriter`（`infrastructure/knowledge/query/`） | 中文数字归一（七百→700、一千五→1500、一万二→12000）+ 同义词扩展（外套→大衣/夹克/风衣/羽绒服/开衫）。**没有单位的「一/两」不许改**，否则「一共」会变成"1共"；「十分/千万」在停用词表里 |
| 向量召回 | `EmbeddingPort` 两个实现：默认 `HashingEmbeddingAdapter`（本地 TF-IDF 特征哈希，512 维）；`EMBEDDING_PROVIDER=openai-compatible` 时换成 `OpenAiCompatibleEmbeddingAdapter`（**真语义模型**，任何 OpenAI 兼容 `/embeddings`） | ①**数字必须单独成特征**（"价格刚好699" 里的 699）—— 只抽汉字会把数字全丢掉；②**必须按 IDF 加权**，否则「商品编号/服装商品」这类 ubiquitous n-gram 让余弦变成"谁跟谁都像"（实测无关片段拿到全场最高分）；③片段向量带缓存，语料指纹变化才重算 |
| 词法召回 | `Bm25SearchAdapter` | 原有命中率/短语/汇总加成，**新增数值邻近加成**（±2% 或 ±10 元，权重 0.15）：口语价格「七百」指的是 ¥699 |
| 融合 | `HybridKnowledgeSearchRepository` | `finalScore = 0.7×饱和(BM25) + 0.3×余弦`，**绝对量级**（不按"查询内最大值"归一，否则"整库只有一条弱命中"的查询也会得 1.0，阈值就失效了）；RRF 只做并列裁决（几十条语料时 RRF 值全落在 0.0296~0.0330，排不出差别） |

**为什么不干脆上向量库**：34 个片段 / 2634 字，内存全量扫描 + 缓存足够；而 `ChromaClient` 那个"恒返回空"的桩
已经证明"看起来接了、其实没接"比不接更糟 —— 所以连它和 compose 里的 chromadb 服务一起删了。

**真语义模型怎么开**（2026-11-04 补）：

```bash
EMBEDDING_PROVIDER=openai-compatible
EMBEDDING_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode/v1   # 或 SiliconFlow / Ollama / vLLM
EMBEDDING_MODEL=text-embedding-v3
EMBEDDING_API_KEY=sk-...        # 不填则复用 QWEN_API_KEY
EMBEDDING_DIMENSIONS=1024
```

**本机实测（2026-11-05，阿里云 `qwen3.7-text-embedding-flash`，1024 维）**：

- 语料预嵌入：34 条片段 / 4 批（`BATCH_SIZE=10`）**2.3s**，之后命中缓存；只有语料指纹变化才重算；
- 语义泛化确实生效：「装东西背的包有哪几种」→ 命中「米色帆布双肩包」0.278；
  「我想买个暖和的围脖」→ 命中「灰色羊毛围巾」0.144（字面哈希这两条都排不上）；
- **阈值必须重标**：语义向量把噪声分整体抬了 ~0.02，`MIN_DIRECT_ANSWER_SCORE` 从 0.10 抬到 **0.12**
  （噪声最高 0.105「讲个笑话」，真实问法最低 0.312「退款要几天」），详见 `KnowledgeAgent` 的注释；
- 测试必须离线：`.env` 打开语义向量后，`application-test.yml` 与 `AssistantSmokeTest` 的内联属性都强制回
  `hash`，否则 `@SpringBootTest` 会在 `fit()` 时真发 HTTP。

| 实现 | 能力 | 代价 |
| --- | --- | --- |
| `HashingEmbeddingAdapter`（默认） | 字面相近（词序/组合不同）能召回；同义与口语数字靠查询改写 | 0 成本、离线、确定性；**不懂语义** |
| `OpenAiCompatibleEmbeddingAdapter` | 语义相近即可召回（「冬天穿的那件长款」→ 大衣） | 一次语料预嵌入（34 片段 = 4 批、约 200ms）+ 每问 1 次往返 |

三个必须知道的实现细节：

1. **批量预嵌入 + 缓存**：`fit(corpus)` 在语料指纹变化时按 10 条一批全量算完，运行期查询只花 1 次往返；
   逐条打会慢到不可用（30+ 次请求）。
2. **失败整体降级**：没 key / 网络不通 / 5xx / 返回条数不符 → 打一条 warn，**永久**切回哈希向量并清空缓存
   （保证全语料落在同一个向量空间）。检索是增强能力，不能因为 embedding 服务抖动让回答失败。
3. **绝不能在 Netty 事件循环上 `block()`**：`/knowledge/search` 与直答路径都会调它，而 Reactor 在事件循环线程上
   直接禁止 `block()`（报 "block()/blockFirst() ... not supported"）。适配器内部用
   `Mono.fromCallable(...).subscribeOn(boundedElastic).toFuture().get(timeout)`；两处调用点也各自挪到了
   `boundedElastic`（那里还顺带跑了 JDBC）。**这个坑真机才暴露**：第一版上线后语义向量静默降级，检索退回哈希。

**代价与边界**：默认（哈希）下「七百」↔「699」靠数字归一 + 邻近加成，「外套」↔「大衣」靠同义词表；
开了语义模型后这类改写不再依赖词表，但**评测集必须两种 provider 都能跑**
（`RetrievalEvaluationTest` 用哈希，`HybridKnowledgeSearchRepositorySemanticTest` 用假语义端口锁管线）。
同义词表仍要跟语料一起维护（`QueryRewriterTest` 盯着）；语料大改后 §5.5 的 0.10 门槛要重标。

#### ⑥ 商品目录扩容（V8：10 → 30；V10：30 → 60 件）

为什么要扩：10 件样本太少 —— 同价商品几乎不存在、品类覆盖只有 8 类、检索"精确价格"与"同价噪音"根本测不出差别。

| 项 | 扩容后 |
| --- | --- |
| 商品 | **60 件**（V10 后）：上装 17 / 下装 8 / 外套 9 / 裙装 9 / 鞋 5 / 包与配饰 7 / 童装 1 / 家居服 2 / 运动 3，价格 ¥99 ~ ¥1299 |
| 知识片段 | 60 条单品片段 + 1 条汇总价格表（`CHK-CATALOG-CLOTHING-ALL`，**1917 字**）+ 3 条售后 FAQ = 64 |
| 截断上限 | `KnowledgeAgent.MAX_CONTENT_LENGTH` 1200 → **2600**（1917 字整表 ≈1.3k token，仍在预算内；超 ~4000 字才该分册） |
| 刻意保留 | 最低价仍是 ¥99（`CLOTH-TEE-001`）、¥699 仍唯一（`CLOTH-COAT-009`）、几个重复价位（169/199/259/299/399）用来测同价排序 |

扩容踩到的三个耦合点（**加商品时这三处必须一起改**）：

1. **`products` + `product_inventory` + 单品 chunk** —— 少了前两个：知识库查得到但下单报"商品不存在/无库存"；少了后面那个：检索找不到。
2. **汇总价格表片段** —— `PriceCatalogService`（预算测算）读的是它。忘改 → 预算结论少算商品，而错结论看起来仍然自洽。
3. **`QuestionSignals` 的商品名→productId 表** —— 忘改 → 「我要买一件XX」无法下单，只能反问用户。
   这张表**顺序即优先级**：60 条具体商品名在前，通用品类词（"卫衣""夹克"…）兜底在后，否则新品会被老品抢先命中。
   V10 还顺手补了品类词（靴/凉鞋/睡袍/泳衣/太阳镜/毛线帽…），否则问「靴子多少钱」不会进商品问答。

对应防线：

| 守卫 | 锁住什么 |
| --- | --- |
| `SeededProductCatalogConsistencyTest` | 直接读迁移 SQL：每个商品名必须解析到自己的 id；汇总价格表的商品集合必须与 `products` 一一对应 |
| `PriceCatalogServiceTest` | 用迁移里的真实片段测两种解析格式（单品 / 汇总），并验证去重后正好 60 件；迁移按**版本号数值**拼接（V10 字典序在 V8 前面，取"最后一次 UPDATE"必须按版本号） |
| `RetrievalEvaluationTest` | 语料按 V3+V8 生成；新增「每件商品问自己名字都必须被召回」的循环断言 |

另外**截断上限跟着调**：`KnowledgeAgent.MAX_CONTENT_LENGTH` 400 → 1200。整张价格表 971 字，
400 会把它砍掉一半，症状就是「表不全 → 结论错」（和当初 14 片段时代一模一样）。
目录继续长到整表 > 1200 字时，正确做法是**在迁移里把汇总片段分册**（按品类拆成几条），而不是继续抬数字。

---

## 7. Tool Calling 契约

白名单硬编码在 `ToolNames.AI_CALLABLE_TOOLS`（**14 个**）。不在白名单的工具即使模型返回也会被 `IntentAgent` 丢弃 + `ToolDispatcher` 拒绝。

| 工具 | 入参 | 出参要点 | 副作用 |
| --- | --- | --- | --- |
| `CREATE_TICKET` | customerId, title, description, priority | ticketId, title, status, priority, category | 写 `tickets` |
| `QUERY_ORDER` | orderNo, customerId | orderNo, orderStatus, paymentStatus, amount, items, itemNames, businessSummary（列表请求返回 totalOrders+orders） | 只读 |
| `UPDATE_TICKET` | ticketId, action(RESOLVE/ESCALATE/WAITING_CUSTOMER), note | ticketId, status, priority | 改 `tickets`；未给 ticketId 时回退用上下文里刚创建的工单 |
| `SEARCH_KNOWLEDGE` | query, limit(1~10，默认5) | sources[{id,title,source,content,score,matchReason}] | 只读，结果写入上下文 |
| `CREATE_ORDER` | productId, quantity, addressId?, paymentMethodId? | action=CONFIRM_REQUIRED, confirmationId, sessionId, 商品/金额/地址/支付方式/expiresAt | 写 `order_confirmations`（**仅待确认，不扣款**） |
| `PLAN_BUDGET` | budget, allowRepeat | maxCount, total, remaining, items[], nextCheapest | 只读；测算结果写入上下文 `budgetPlan`，供 KnowledgeAgent 引用 |
| `ADD_TO_CART` | items[{productId, quantity}] | lines[], totalQuantity, totalAmount | 写 `cart_items`（同商品合并数量）；**不锁库存、不建单** |
| `VIEW_CART` | 无 | lines[], totalQuantity, totalAmount, empty | 只读 |
| `QUERY_ACCOUNT` | 无 | addresses[], paymentMethods[], coupons[], expiredCouponCount | 只读；手机号脱敏、支付方式只给 `displayLabel` |
| `CANCEL_ORDER` | orderNo | orderNo, orderStatus, releasedItems[], message | 改 `orders.order_status=CANCELLED` + **释放预留库存**；仅 `CREATED+UNPAID` |
| `REQUEST_REFUND` | orderNo, reason? | refundId, orderNo, amount, status, **ticketId** | 写 `refund_requests`（`REQUESTED`）+ **自动建 `REFUND` 工单**（HIGH）；**不动钱**；仅已支付、同单不可重复 |
| `QUERY_REFUND` | orderNo?（可空 = 最近 10 条） | count, refunds[{refundId, orderNo, amount, status, reason, createdAt}] | 只读（2026-11-09 补：能申请不能查太伤信任） |
| `REQUEST_RETURN` | orderNo, reason? | returnId, orderNo, status, **ticketId** | 写 `return_requests`（`REQUESTED`，`order_no` 唯一）+ **自动建 `LOGISTICS` 工单**（HIGH）；退货 ≠ 退款，**不动钱** |
| `QUERY_RETURN` | orderNo? | count, returns[{returnId, orderNo, status, reason, createdAt}] | 只读 |

**参数白名单校验先于执行**（`ToolArgumentGuard`，2026-11-09）：`ToolDispatcher` 原来只校验"工具名在不在白名单"，参数完全不校验，
而 productId 直接来自模型输出（实测模型编过 `CLOTH-HOODIE-BLK`）。现在拒绝四类参数：

- 商品不存在/已下架（`productId` 必须能在 `products` 查到且 `active`）
- 数量不在 `1~20`（下单与加购都用这个上限）
- 句子里点名的商品与要操作的商品**完全没有交集**（防"说 A 下 B"；"就买这个"这种指代不算，因为句子里没有商品名）
- 订单号格式不是 `ORD-xxx`（取消 / 退款 / 退货 / 进度查询），工单动作不在枚举内，检索条数不在 `1~10`

拒绝原因作为工具失败结果回给用户（进 `tool_invocations` 的失败记录），不是静默失败。

每次调用都会经 `JpaToolInvocationRecorder` 落库到 `tool_invocations`（traceId/sessionId/toolName/arguments/result/success/durationMs）。

`CREATE_TICKET` / `UPDATE_TICKET` 成功落库后，还会经 `JpaTicketEventRecorder` 向 `ticket_events` **追加一条事件**
（`TICKET_CREATED` / `TICKET_RESOLVED` / `TICKET_ESCALATED` / `TICKET_WAITING_CUSTOMER` / `TICKET_UPDATED`），
payload 含 action、note、变更后的 status/priority、`source=AI_TOOL`、traceId、sessionId —— 工单从此有可回放的时间线。
这些审计写入失败只 `log.warn`，**不会**让工具调用失败。

---

## 8. 配置与环境

### 8.1 端口

| 服务 | Docker | 本地开发 |
| --- | --- | --- |
| Frontend | `http://localhost:15173`（Nginx 80） | `http://localhost:5173`（Vite） |
| Backend | `http://localhost:18080`（容器 8080） | `8080` |
| Swagger UI | `http://localhost:18080/swagger-ui.html` | `http://localhost:8080/swagger-ui.html` |
| MySQL | `localhost:13306`（容器内 3306） | **`localhost:13306`（默认）**；本机另有 MySQL80 占着 `3306` |


**本机存在两套互不相通的 MySQL**（库名相同、数据不同）：

| | 本机 MySQL | Docker MySQL |
| --- | --- | --- |
| 端口 / 版本 | `3306` / 8.0.30 | `13306`（容器内 3306）/ 8.4 |
| 用途 | 需显式 `SPRING_DATASOURCE_URL` 覆盖才会连 | **默认目标**（本地 IDEA 后端、Navicat、容器部署） |

两条最常踩的：

1. **IDEA 的 Database 工具窗口 ≠ 应用连接配置**。Database 面板只决定"能不能看表"；应用连哪个库只看 Run Configuration 的环境变量 / `application.yml`。
2. **两套库表结构完全相同**（Flyway 版本一致），只看表分不出连了哪套 —— 要看端口或数据差异。

自查"谁连了谁"（比猜配置可靠）：

```powershell
# ⚠ 不要用 Group-Object ProcessName —— 它会把 RemotePort 丢掉，切换前后输出完全一样
Get-NetTCPConnection -State Established | ? RemotePort -in 3306,13306 |
  % { $pr = Get-Process -Id $_.OwningProcess; "$($pr.ProcessName) -> $($_.RemotePort)" } | Group-Object
```

HikariCP 默认 `minimum-idle = maximum-pool-size = 10`，所以后端一启动就常驻 10 条连接（即使没人访问），不是连接泄漏。

schema 事实来源始终是 `backend/src/main/resources/db/migration/`；`db/ai_ticket_assistant.sql` 只是 3306 那套的手工快照（未入 git）。


### 8.2 AI Provider 选择

`assistant.ai.provider`（env `AI_PROVIDER`）单选，默认 `fallback`（`matchIfMissing = true`）：

| 值 | 生效 Adapter | 真调 LLM | 流式 | 备注 |
| --- | --- | --- | --- | --- |
| `fallback`（默认） | `FallbackAiClientAdapter` | 否 | 端口默认（整段切句） | 纯 if-else 规则兜底，覆盖订单/购买/工单/知识四类 |
| `deepseek` | `DeepSeekAiClientAdapter` | 是（OpenAI 兼容 REST） | **真流式（`stream=true`，逐 token）** | 无 key 时退回内置 `jsonFallback()`；回答走 `stream()`，意图识别仍走 `complete()` |
| `qwen` | `QwenAiClientAdapter` | 是 | 端口默认（整段切句） | DashScope compatible-mode；补真流式只需覆盖 `stream()` |

> Anthropic 已于 2026-11-04 移除（变更 #31）：适配器、`anthropic-java` 依赖、
> `ANTHROPIC_*` 配置项与 compose/env 变量全部删除。`assistant.ai.timeout`（env `AI_TIMEOUT`）保留，
> 由 DeepSeek 与 Qwen 共用。

**变量从哪来（最容易踩的地方）**：

| 启动方式 | 谁提供 `AI_PROVIDER` / `DEEPSEEK_API_KEY` | 真调模型 |
| --- | --- | --- |
| `docker compose up -d backend` | compose 读仓库根 `.env` | ✅ |
| IDEA / `java -jar` 本地跑 | `.env` 经 `spring.config.import` 被 Spring 读入 | ✅ |
| 两者都拿不到 | `application.yml` 的默认值 `fallback` | ❌ 规则引擎 |

Spring Boot **原生不读 `.env`**（只有 compose 读），必须显式接进来；`optional:` 保证容器部署（无 `.env`）不报错：

```yaml
spring:
  config:
    import:
      - optional:file:.env[.properties]      # 工作目录 = 仓库根
      - optional:file:../.env[.properties]   # 工作目录 = backend/（IDEA 默认）
      - optional:file:../../.env[.properties]
```

**IDEA 直接 Run 即可，不需要改任何 IDEA 配置**：Run Configuration 没设 `WORKING_DIRECTORY` → 工作目录是模块根 `backend/` → 第二条命中。
唯一要注意的是**改了 `application.yml` 必须重新 Build**（IDEA 跑的是 `target/classes` 里的副本）。

启动自检 `AiProviderDiagnostics` 会直接给出结论：

```
[AI] 生效适配器 = DeepSeekAiClientAdapter，配置 provider = deepseek，DeepSeek key = 已配置(长度 35)
[AI] 工作目录 = D:\...\TicketPilot\backend，命中的 .env = D:\...\backend\..\.env
[AI] 已接入真实大模型，回答耗时会明显长于规则兜底（每次问答至少 2 次调用）。
```

若第一行是 `FallbackAiClientAdapter`，日志会附三步排查清单（`.env` 内容 / 命中的路径 / 是否重新 Build）。
不看日志时也可以凭两个信号判断：规则引擎是「回显片段列表」且 ≈0.3s；真模型是「一句话结论 + 引用 + 说明资料不足」且 4~15s。
**注意 key 失效或网络不通时不会报错给用户**，只 `log.warn` 后退回 `jsonFallback()`。

> ⚠ `.env` 含真实密钥，已被 `.gitignore` 忽略且未被 git 跟踪，**文档中不记明文**。切 provider 后需重启（容器部署则重建 backend 容器）。

### 8.3 关键环境变量
| 变量 | 默认 | 说明 |
| --- | --- | --- |
| `SPRING_DATASOURCE_URL/USERNAME/PASSWORD` | **`localhost:13306/...`** / `app` / `app` | 默认连 Docker 的 MySQL；Compose 内覆盖为 `mysql:3306` |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:5173,http://127.0.0.1:5173` | **必须是显式白名单**，含 `*` 会抛 `IllegalStateException` 阻止启动；Compose 额外加了 `15173` 端口 |

| `CHAT_HISTORY_MAX_TURNS` / `CHAT_HISTORY_MAX_CHARS` | `6` / `1200` | 会话历史注入的轮数与**总字符预算**；裁剪规则见 §4.6 |
| `CHAT_HISTORY_MAX_USER_CHARS` / `CHAT_HISTORY_MAX_ANSWER_CHARS` | `120` / `300` | 单条用户消息 / 单条助手回答的长度上限（保留开头） |
| `AI_TIMEOUT` | `PT30S` | 单次模型调用超时（也用作流式相邻分片的空闲超时），DeepSeek 与 Qwen 共用 |
| `EMBEDDING_PROVIDER` | `hash` | 向量那一路的实现：`hash`（离线）/ `openai-compatible`（真语义模型，§6.5 ⑤） |
| `EMBEDDING_BASE_URL` / `EMBEDDING_MODEL` / `EMBEDDING_API_KEY` / `EMBEDDING_DIMENSIONS` | DashScope `text-embedding-v3` / 复用 `QWEN_API_KEY` / `1024`（本机实际用的是 `qwen3.7-text-embedding-flash`，同为 1024 维） | 仅 `openai-compatible` 生效；任何 OpenAI 兼容 `/embeddings` 都能接。换模型必须核对维度并重标阈值 |
| `PROMPTS_DIR` | 空（读 classpath） | 提示词外部目录：本地开发指向 `backend/src/main/resources/prompts`，改完即生效（§5.6） |
| `PROMPTS_HOT_RELOAD` / `PROMPTS_CHECK_INTERVAL` | `true` / `PT1S` | 是否按文件指纹自动重载、懒检查的节流间隔 |
| `VITE_API_BASE_URL` | 空（走 Vite 代理）/ Docker 构建参数 `http://localhost:18080` | **构建期注入**，改它必须重建前端
| `AUTH_ENABLED` | `true` | 鉴权开关：`false` 退回"参数即身份"的演示模式（测试用 `application-test.yml` 关闭，见 §5.7） |
| `AUTH_TOKEN_TTL_HOURS` | `24` | 登录令牌有效期（`auth_tokens.expires_at`） |
| `ASSISTANT_INTENT_MODE` | `auto` | 意图决策模式：`auto`（真模型 → 模型优先；fallback → 关键词层）/ `model-first` / `legacy`（§5.10） |
| `RETENTION_ENABLED` | `false` | 是否启用**定时**清理（默认关闭：清理是破坏性操作，见 §5.8） |
| `RETENTION_CRON` | `0 30 3 * * *` | 清理任务 cron（每天 03:30） |
| `RETENTION_CONFIRMATION_DAYS` / `CART_DAYS` / `SESSION_DAYS` / `TRACE_DAYS` | `7` / `30` / `30` / `14` | 各类数据保留天数（§5.8） | |

---

## 9. SSE 事件契约

> **鉴权（2026-11-09）**：`EventSource` 不能自定义请求头，所以 `/assistant/stream` 的令牌走 query
> （`?token=tk_xxx`）；`AuthWebFilter` 同时接受 `Authorization: Bearer` 与 `X-Customer-Token`。
> 无令牌/过期 → **401 JSON**（不是 SSE 帧），前端 `authorizedFetch` 会自动换一次令牌后重试。

后端 `AssistantEventType`（wireName）↔ 前端 `useAssistantStream` 监听名必须一一对应，改一处必须改两处。

| 事件 | payload 关键字段 | 前端消费 |
| --- | --- | --- |
| `workflow_started` | traceId, sessionId | Trace 时间线 |
| `agent_started` | agent / agents / toolCount | Agent 卡片置 running（按 message 里是否含 Agent 名或"并行"匹配） |
| `agent_completed` | intent, title, priority, confidence / orderAnalysis / knowledgeAnswer | Agent 卡片置 done |
| `tool_call` | toolCalls | Tool Calling 卡片（requested） |
| `tool_result` | results[] | Tool Calling 卡片（success） |
| `knowledge_sources` | sources[] | RAG Sources 面板（取前 4 条）。**工作流与直答两条路径都会发**（直答路径的前置检索见 §5.5） |
| `ticket_created` / `ticket_updated` | ticket | Ticket Status 面板 |
| `order_confirmation_required` | confirmation{…} | 订单确认卡片 |
| `order_created` | 后端枚举已定义，**当前无发射点** | 前端有监听，暂无数据 |
| `trace` | traceId, steps[] | Trace 时间线 |
| `final` | delta / done / fullText | 回答区累积；`done=true` 停止打字光标 |
| `error` | error | 停止流并将 running Agent 置 error |

> `final` 的粒度**取决于适配器**（前端只按 `delta` 累加，两种都能渲染）：
> - `deepseek`：**token 级**（上游 `stream=true`，一个分片一个事件；实测 188 字的回答 = 107 帧，首字 0.6~1.4s）；
> - `fallback` / `qwen`：端口默认实现，整段返回后**按句切片**（每片 90ms），与改造前观感一致。
>
> 两种模式下 `trace` 都是最后一帧，`done=true` 帧都带 `fullText`。

---

## 10. 前端结构

### 10.1 视图与页面（2026-11-09）

`App.tsx` 只有两个视图（项目没引路由库，用 `useState` 切换即可）：

| 视图 | 组件 | 说明 |
| --- | --- | --- |
| 客服工作台 | `features/assistant/AssistantPage` | 对话 + 会话侧栏 + 购物车面板 + 订单确认卡片 + 工具/trace 面板 |
| 售后审核台 | `features/review/ReviewConsole` | 退款/退货申请单的审核（列表、通过、驳回、看备注），见 §5.9 |

### 10.2 开发代理（`vite.config.ts`）与"登录失败"自查

前端在开发模式下走 Vite 代理（`API_BASE_URL` 为空 ⇒ 同源 5173 ⇒ 代理到 8080）。
**代理规则是按前缀白名单写的，新增后端接口前缀必须同步加规则**，否则浏览器侧的表现具有很强的误导性：

| 情况 | 浏览器看到 | 页面表现 |
| --- | --- | --- |
| 前缀漏配且后端无同路径 | `404` | 例如漏配 `/auth` ⇒ `POST /auth/login` 404 ⇒ 页面直接"登录失败" |
| 前缀漏配但 Vite 有 SPA fallback | `200 + text/html` | 更隐蔽：`/admin/**` 漏配时拿到 `index.html`，JSON 解析才炸 |

2026-11-10 就是这么坏的：加了 `/auth/**`、`/admin/**` 接口，忘了加代理规则，导致
"购物车和客服工作台都显示登录失败"。现在 `viteProxy.test.ts` 会断言代理覆盖
`/assistant`、`/auth`、`/admin`、`/tickets`、`/orders`、`/knowledge`、`/products`，改接口前缀时它会先报错。

> 改 `vite.config.ts` 必须**重启 Vite**（配置不热更）；`.env` 里的 `VITE_API_BASE_URL` 只在 Docker 构建时生效，
> 前端目录下没有 `.env`，dev 模式下 Vite 不会读仓库根那份。

### 10.3 鉴权与购物车面板（2026-11-09）

- **令牌**：`api/client.ts` 启动时 `ensureToken()` → `POST /auth/login`，令牌存 `localStorage`（`ticketpilot.token`）；
  所有请求经 `authorizedFetch` 自动带 `Authorization`，遇 401 自动换一次令牌重试；SSE 的令牌拼在 URL 上。
- **`CartPanel`**：右侧面板，显示每行商品/单价/小计与合计，支持 +/- 改数量（`PATCH /assistant/cart/items/{id}`）、
  移除（`DELETE …/items/{id}`）、清空（`DELETE /assistant/cart`）；每轮回答结束后自动刷新（用户可能在对话里刚加购/刚下单清车）。
  这是"对话里说去购物车页面操作"的落点 —— 页面必须真的能改，否则等于把人推到不存在的入口。

```text
frontend/src/
├── main.tsx                       # React18 + StrictMode + QueryClientProvider
├── app/App.tsx                    # 仅渲染 AssistantPage
├── index.css                      # 暗色渐变背景、glass 卡、typing-caret、traceIn/drift 动画
└── features/assistant/
    ├── AssistantPage.tsx           # 三栏布局：左(Composer/商品目录/Agent) 中(回答/Trace) 右(下单卡/工具/RAG/工单)
    ├── types.ts                    # 事件与卡片类型
    ├── api/client.ts               # API_BASE_URL、SSE URL 构造、确认/取消下单
    ├── hooks/useAssistantStream.ts # EventSource 订阅 13 个事件名
    ├── stores/assistantStore.ts    # Zustand：userMessage/lastAskedMessage/answer/events/agents/tools/sources/ticket/下单状态
    └── components/                 # 10 个展示组件（含 SessionList 会话侧栏）
```

- **输入框（Composer）的取值规则**：会话开始时为空（不预填示例问题，示例只放在 placeholder 里）；
  点发送后输入框**立刻清空**，同时把这句话记到 `lastAskedMessage`，回答区上方留一行「你：…」，
  否则清空后用户就看不到自己刚问的是什么了。这两条规则由 `store.consumeMessage()` 一处实现：
  它 trim 后返回本轮消息并清空输入框，空白输入返回 `null`（调用方直接 return，不发请求）；发送按钮在空白时置灰。
  默认 customerId：`CUST-1001`（前端硬编码，未做鉴权）。
- **`sessionId` 由前端生成并存在 `localStorage` 的 `tp.assistant.sessionId`**，每次 SSE 请求都带上（多轮记忆的锚点）；页面头部「新会话」按钮会换新 id 并清空界面（含输入框），旧上下文即断开。
- `agent_started` 的状态匹配依赖**中文文案**（`message.includes('并行')`）。改后端 Event message 文案会静默破坏前端 Agent 卡片状态 —— 属于隐式耦合，改动时需回归。
- Docker 下前端不经过 Nginx 反代后端，浏览器直连 `http://localhost:18080`，因此 **CORS 白名单必须包含 `15173`**。

---

## 11. 现状与已知问题（改动前必读）

### 11.1 未解决的功能性缺口

按"生产化必须补"的优先级排：

0. **售后动作的边界口径**（2026-11-09 起有真工具，但**故意只做一半**，改动前先对齐业务）：
   - 取消订单只允许 `CREATED + UNPAID`；已支付/已发货的直接拒绝并引导退款或人工。
   - 退款**只落申请单**（`refund_requests`，状态 `REQUESTED`），不调支付网关、不动钱；落单同时**自动建 `REFUND` 工单**，`QUERY_REFUND` 可查进度。审核在外部（本项目没有审核端界面）。
   - 购物车只支持"加 / 看 / 结算下单"，**移除与清空在对话里不支持**（`ViewCartTool` 会明说，不装作做完）。
   - 确认收货 / 修改地址**没有工具**，一律 `CREATE_TICKET` 转人工 —— 不要再让模型"回个订单状态就算办了"。
   - **退货 ≠ 退款**：退货落 `return_requests` + `LOGISTICS` 工单（先寄回验货，验货通过才退款），不会自动改订单状态。
   - **审核端没有角色体系**：`/admin/**` 只校验令牌，任何客户都能调审核接口（`reviewedBy` 仅作审计）。
     上线前要引入员工身份 + 权限（RBAC/统一登录），并区分"客服"与"审核员"。见 §5.9。
   - 数据清理只删"缓存性质"的数据（过期待确认单 / 闲置购物车 / 老会话 / 老 trace），**订单、工单、退款、退货、优惠券一律不删**（见 §5.8）。
   - 加购**不锁库存**、结算才 reserve：两人同时把最后一件加入购物车都可能成功，只有真正下单那一刻才判库存。
1. ~~**无鉴权、无租户隔离**~~ **（2026-11-09 已解决，见 §5.7）**：客户身份改由令牌决定（`POST /auth/login`），
   `customerId` 参数不再具备授权含义；`/assistant/**` 无令牌一律 401；会话历史读取校验归属（越权统一 404）。
   **剩下的真实缺口**：`login` 只校验客户存在、**没有密码/验证码**（演示级），上线要接统一登录/短信验证；
   令牌还能放 URL（SSE 限制），可能进访问日志 —— 24h 有效且可注销，属可接受的权衡。
2. **默认的向量那一路不是语义模型**（可以换成真模型）：出厂配置是本地 TF-IDF 特征哈希（`HashingEmbeddingAdapter`），
   只补"字面相近"的召回；同义靠 `QueryRewriter` 的同义词表、口语数字靠数字归一 + 数值邻近加成。
   要真语义就设 `EMBEDDING_PROVIDER=openai-compatible`（§6.5 ⑤），代价是预嵌入 + 每问一次往返，且要一个 embedding key
   （DeepSeek 没有 embeddings 接口，所以默认不启用）。
   **本仓库的环境没有 embedding key，语义模式只用本地桩服务验证过，未做真实厂商联调** —— 上线前请先跑一遍真机对比。
3. **直答路径的检索只用于"补资料"，不是完整 RAG**：直答前会先查一次知识库（见 §5.5），
   但它是**单轮、无工具、不分块推理**的 —— 多跳问题（"先查订单再比价"）仍然接不上，这类要走工作流。
   另外相关性门槛（0.10，真机标定见 `KnowledgeAgent.MIN_DIRECT_ANSWER_SCORE`）依赖语料规模，语料变化后要重标。
4. **只有 DeepSeek 是真流式**：`fallback` / `qwen` 走端口默认实现（整段返回后按句切片），
   首字延迟仍等于整段生成时间。Qwen 的 DashScope compatible-mode 有 SSE 接口，补法是覆盖 `stream()`，不需要再动工作流与前端。
5. **`QuestionSignals` 一张表九处复用**（四处各写一遍的问题已在 #25 解决，见 §5.4.2）。
   2026-11-05 起有三层防线（信号→消费者登记 + 每张表逐词断言 + 影响面快照矩阵，见 §5.4.2），
   改表时 `QuestionSignalsImpactTest` 会逐条列出"哪条语料、哪个信号变了"，不再只能靠"跑全量测试看挂没挂"。
   剩下的**真实风险**只有一条：快照 diff 需要人 review —— 如果无脑 `-DsignalsSnapshot=true` 重生成并提交，
   就等于把旧行为当成了新正确性。所以约定是"先读 diff 再重生成"，且 `QuestionSignalsTest`（人写的期望值）必须继续跑。
6. **`order_created` 事件无人发射**：枚举保留了，但确认下单走 REST 而非 SSE。
7. **知识库无管理接口**：加/改文档只能改 SQL 或写迁移（还有 Flyway checksum 约束），没有上传、重建索引的能力。
8. **提示词热加载的边界**（2026-11-05 评估后**决定保留现状**，见下方决策记录）：
   已支持外部目录 + 指纹懒检查（§5.6），但**胖 jar 内无法热加载** —— classpath 资源在 jar 里没有文件系统路径，
   `ClassPathResource.getFile()` 直接抛异常，所以"监听文件变化"这条路在 jar 内根本不成立。
   本项目 `backend/Dockerfile` 是 `java -jar app.jar`、`docker-compose.yml` **没有挂载 prompts**，
   于是**容器内改提示词 = 重建镜像**（几分钟）。不会静默出问题：日志会写"来自 jar 内 classpath，无法感知变化"，
   `GET /admin/prompts` 里 `hotReloadable=false` 也能查到。
   另外 `/admin/prompts/**` 没有鉴权：`POST /reload` 只能重读配置里那几个文件（目录来自配置、文件名来自 `PromptIds`，
   **没有路径注入**），代价是几次文件 IO，最多算刷日志/轻微 DoS；`GET` 会**泄露服务端绝对路径**（低-中危信息泄露）。
   这是"全站无鉴权"（第 1 条）的下游症状，单独给 `/admin` 加锁意义有限。

   > **决策记录（2026-11-05）**：评估过两条低成本修法 —— ① compose 挂载 `prompts` 卷 + `PROMPTS_DIR`（让容器也能热加载）；
   > ② 给 admin 端点加开关（默认关、本地开）。**本轮决定都不做，只保留文档**，理由：
   > ② 的收益被"全站无鉴权"吃掉（门没锁，窗户加锁只是错觉）；① 只有在"要在容器里现场调提示词"时才有价值，
   > 而本项目调提示词发生在本地开发（`PROMPTS_DIR` 已够用）。
   > **触发条件**：一旦出现"部署后需要改提示词"或"要把 `/admin/**` 暴露到内网以外"，
   > 就按上面的 ① ② 顺序补；再往前一步才是第 1 条的全站鉴权（Spring Security + 会话归属校验，会动 SSE/前端/测试）。
9. **关键词表仍是中文字面子串匹配**：`QuestionSignals` 只做 `contains`/正则，没有分词与同义词典，
   所以「七百」认不出「699」、「外套」认不出「大衣」。这类改写要靠检索侧的向量/同义词（§6.5 ⑤），不在关键词表里硬堆词。
10. **逐 token 事件的渲染成本**：真流式下一个回答 100~200+ 帧，前端每帧一次 `setState` + Markdown 重算。
    目前实测流畅（回答几百字），但长回答（上千字）或多路并发时应做 **30~50ms 合批**（把若干 delta 合并成一帧再推）。
    后端也可以按 token 积攒成小块再发 —— 现在选择"原样转发"，是为了让首字延迟最小。

> 已解决：原第 4 条"SSE 非 token 流"（2026-11-04，变更 #30，DeepSeek 真流式，见 §5.2/§9）；
> 原第 8 条"会话历史无长度与成本控制"（2026-11-03，变更 #28，见 **§4.6**）。

### 11.2 已解决，但请勿回归

| 曾经的坑 | 现在的约束 | 守卫 |
| --- | --- | --- |
| `prompts/*.md` 与代码内提示词脱钩 | 提示词全部外置为 `<id>.system.md` / `<id>.user.md` | `ClasspathPromptCatalogTest` |
| 5 张表预留不落库 | 22 张表全部有 JPA 实体与真实读写 | — |
| 多轮对话无记忆 | `ConversationMemory` 注入最近 6 轮，按 sessionId 隔离 | `AssistantWorkflowServiceTest` |
| 长回答把历史撑爆（prompt 又长又贵，还容易把模型带偏） | 注入前按预算裁剪：单条截断 + 总量预算 + 最新一轮必留（§4.6） | `ConversationHistoryTest`、`AssistantWorkflowServiceTest.longHistoryIsTrimmedBeforeItReachesThePrompt` |
| SSE 伪流式：整段生成完再按句吐，首字延迟 = 整个生成时间 | DeepSeek 走 `stream=true` 逐 token 推；其它适配器走端口默认实现（整段切句，观感不变）（§5.2/§9） | `AiClientPortTest`、`AssistantWorkflowServiceTest.directAnswerStreamsEachTokenAsItsOwnFinalEvent` / `knowledgeTokensStreamFirstThenDeterministicSectionsFollow`、`DeepSeekStreamParsingTest` |
| 回答把意图/置信度/Trace/score/URI 倒进正文 | 组装器只输出五种小节（§5.4.1） | — |
| fallback 关键词被历史劫持（问699答退款） | 只认 `structuredContext.userMessage` | `FallbackAiClientAdapterTest` |
| 预算类的三种问法时灵时不灵地检索 | 路由/护栏/查询词统一走 `QuestionSignals` | `QuestionSignalsTest`、`RetrievalEvaluationTest` |
| 「加入购物车」被当成下单，还编出不存在的 productId（`CLOTH-HOODIE-BLK`） | 加购走 `ADD_TO_CART` + `productItemsIn`（只认商品、不要求购买动作）；商品 ID 必须过商品表校验（§5.4.6） | `AssistantSmokeTest.cartAddViewAndCheckoutEndToEnd` |
| 「帮我取消订单」工具 0 个直接哑火；「确认收货」只回订单状态（用户以为办成了） | 售后动作由 `IntentAgent.afterSalesToolCalls` **确定性决定并覆盖模型输出**，没工具的转人工（§5.4.6） | `AssistantSmokeTest.cancelOrderReleasesStockAndIsIdempotent`、`refundRequestOnlyAcceptsPaidOrders` |
| 用户把订单号写进句子（"取消订单 ORD-AI-xxx"）时工作流拿不到订单号 | `WorkflowContext.orderNo` 兜底从消息里抽（`QuestionSignals.orderNoIn`） | `QuestionSignalsTest`、`AssistantSmokeTest` |
| 后端加了 `/auth`、`/admin` 接口但 Vite 代理没配 → 页面"登录失败"（`/auth` 404、`/admin` 拿到 index.html） | 代理前缀是白名单，新增接口前缀必须同步；`viteProxy.test.ts` 断言覆盖 | `viteProxy.test.ts` |
| 模型正确选了 `ADD_TO_CART` 但 `items:[{productId:""}]` → 平台没补参，护栏报"商品编号不能为空" | 补参判据看"每条 item 是否都有 productId"，缺失时整组按句子里点名的商品重建 | `IntentAgentTest.fillsProductIdsWhenModelLeavesThemEmpty` |
| 「我要买一件羽绒服」被系统替用户挑一件直接下水单 | 品类有多个 SKU 时列 2~3 个候选 + 反问；只有 1 个 SKU 才直接下单 | `CategoryCandidateServiceTest`、`AssistantSmokeTest.categoryOnlyPurchaseListsCandidatesInsteadOfPickingOne` |
| 「这单我不要了」因为没订单号只会回"请提供订单号" | 按动作取最近一笔合适状态的订单（取消要未支付、退款退货要已支付），取不到才要单号 | `OrderReferenceResolverTest` |
| 关键词闸门挡住自然说法（7 条口语 6 条进不了工作流 → 一律"我无法操作"） | 入口改为"纯闲聊快路径 + 其余交给意图模型"，模型说无工具再落直答 | `AssistantSmokeTest.colloquialBusinessPhrasesReachTheIntentAgent` |
| 令牌过期后 SSE 静默失败（`fetch` 有 401 重试，`EventSource` 没有） | `useAssistantStream` 断线时强制换一次令牌重连 | `useAssistantStream.test.ts`（断线重连用例） |
| 关键词覆盖抢在模型之前（模型判断对了也被盖掉），A/B 只有 90.3% | `assistant.intent.mode=auto`：真模型走模型优先，模型没给工具时关键词兜底（§5.10） | `IntentAgentTest.decisionModeFollowsProviderUnlessOverridden` + `scripts/intent-eval.ps1` |
| 提示词硬编码工具枚举，新增 9 个工具从没告诉模型 | 提示词以运行时 Tool metadata 为准 + 7 条选择口径；`ClasspathPromptCatalogTest` 断言关键契约 | `ClasspathPromptCatalogTest` |
| 「我想要买 A、B、C。先帮我加入购物车」被当成下单，直接生成待确认单（¥1057） | `cartToolCalls()` 抢在 `purchaseToolCalls` 之前：提到购物车就按购物车走 | `AssistantSmokeTest.cartRequestWinsOverPurchaseIntent` |
| 退款/退货申请单停在 `REQUESTED`，审核只能翻库，用户侧永远看到"待审" | 审核台（列表/通过/驳回）+ V13 审核字段 + 收口工单；用户侧能看到结论与备注 | `AssistantSmokeTest.reviewConsoleApprovesRefundAndClosesTicket` |
| 驳回不写原因（用户看不出为什么被拒） | 领域/服务/前端三层都要求驳回必须填原因 | `AssistantSmokeTest.reviewConsoleRejectsReturnAndRequiresReason`、`ReviewConsole.test.tsx` |
| 重复审核（点两次"通过"） | 只有 `REQUESTED` 能审，重复审 → 409 | 同上 |
| 全站无鉴权：改 `customerId` 参数就能读别人的会话与订单（实测 CUST-9999 读到 CUST-1001 的对话） | `/auth/login` 换令牌，`AuthWebFilter` 校验并把客户写进 exchange；参数不再具备授权含义 | `AuthSmokeTest` |
| `GET /assistant/sessions/{id}/messages` 只要知道 sessionId 就能读 | `ConversationMemory.requireOwnership`，越权与不存在统一 404 | `AuthSmokeTest.readingSomeoneElsesSessionIsNotFound` |
| 过期待确认单 / 闲置购物车 / 老会话 / 老 trace 只增不减 | `RetentionCleanupService`（默认不启用定时器，可手动跑） | `AssistantSmokeTest.retentionCleanupRemovesOnlyOldRows` |
| 「我要退货」只能被拒绝（只有退款，没有退货） | `REQUEST_RETURN` + `QUERY_RETURN` + `LOGISTICS` 工单，`order_no` 唯一防重复 | `AssistantSmokeTest.returnRequestCreatesLogisticsTicketAndCanBeQueried` |
| 对话里说"去购物车页面操作"，但页面没有购物车 | `CartPanel` + `/assistant/cart`（GET/PATCH/DELETE 单项/清空） | `CartPanel.test.tsx`、`AssistantSmokeTest.cartRestApiSupportsQuantityAndRemoval` |
| 控制器上加 `@Validated` → CGLIB 代理 → 方法体内字段全 null（SSE 全部 500） | 控制器不用类级 `@Validated`，参数显式判空（第三次撞同一类代理坑，见 §5.7） | `AssistantSmokeTest` 全量、`AssistantStreamControllerTest` |
| 退款能申请、不能查（"我的退款到哪了" → "我这边无法查看"）；申请单没有任何下游 | `QUERY_REFUND` 查进度 + 申请时自动建 `REFUND` 工单 | `AssistantSmokeTest.refundRequestOnlyAcceptsPaidOrders` |
| 「购物车里的东西会自动清空吗」被答成"购物车现在是空的"（含"购物车"就抢成查看命令） | `isViewCartRequest` 要求明确查看意图，或整句就是购物车 | `QuestionSignalsTest`、`AssistantSmokeTest.cartRuleQuestionIsNotHijackedByViewCart` |
| 工具参数不校验：模型编出 productId 就能下单/加购 | `ToolArgumentGuard` 在执行前拒绝不存在的商品、越界数量、与句子矛盾的商品 | `ToolArgumentGuardTest`、`ToolDispatcherTest` |
| `@Repository` 上新加的方法在 CGLIB 代理实例上执行 → 字段 null → NPE（老方法却正常） | 纯读路径改走 `@Component` 实现的新端口（`AccountDirectory`）；排查时先打印 `getClass()` 确认代理（§5.4.6） | `AssistantSmokeTest.accountQueryListsCouponsAndMasksPhone` |
| 关键词表在路由/意图/兜底/工具四处各写一遍、口径不同（改 A 忘 B） | 关键词与正则只存在于 `QuestionSignals`，调用方只调语义方法（§5.4.2） | `QuestionSignalsTest` 的 `businessRouteCuesAreAllCoveredByQuestionSignals`、`AssistantWorkflowServiceTest.routesBudgetAndQuantityQuestionsIntoAgentWorkflow` / `businessActionsStillRouteIntoAgentWorkflow` |
| 模型自己加总预算算错且不可复现 | 件数/合计由 `PLAN_BUDGET` 算（§5.4.3） | `BudgetPlannerTest`、`IntentAgentTest` |
| 商品数据三处副本（products / 汇总价格表 / 商品名表）漏改一处就静默失效 | 加商品三处必须同改；名称表"具体名在前、通用词在后"（§6.5 ⑥） | `SeededProductCatalogConsistencyTest`、`PriceCatalogServiceTest` |
| 汇总价格表被 400 字截断 → "表不全所以结论错" | 上限 1200（整表 971 字）；再长就分册，不抬数字 | `KnowledgeAgent` 注释 + 真机冒烟 |
| 直答路径不查知识库 → 售后问题靠模型自己编（"运费多少"能编出数字） | 直答前先检索（阈值 0.05），命中就带 `{{sources}}` 与 `**依据**`；没命中要求"说不准就以页面/人工客服为准"（§5.5） | `KnowledgeAgentRetrievalTest`、`AssistantWorkflowServiceTest.directAnswerCarriesKnowledgeSourcesWhenRetrieved` / `directAnswerSendsAnEmptyKnowledgeBlockWhenNothingIsRetrieved`、`ClasspathPromptCatalogTest` |
| 售后问题含"哪些/有什么"就白拿汇总加成，价格表被顶到第一位 | 汇总加权要求"汇总型 **且** 商品/预算类"（§6.5 ②） | `Bm25SearchAdapterTest.nonCatalogQuestionGetsNoSummaryBoost` |
| RAG 名为混合检索、实则 `finalScore = bm25Score`（「七百」「外套」检不到） | 查询改写 + 向量召回 + BM25 + 融合分；不许再出现"恒返回空"的假适配器（§6.5 ⑤） | `RetrievalEvaluationTest`（口语价/同义词/无字面交集三条）、`QueryRewriterTest`、`HashingEmbeddingAdapterTest` |
| 融合分按"查询内最大值"归一会让阈值失效（弱命中也能得 1.0） | 用绝对量级：0.7×BM25饱和 + 0.3×余弦；RRF 只做并列裁决 | `RetrievalEvaluationTest.resultsCarryScoresAndReason`、§5.5 的 0.10 门槛 |
| 语义 embedding 在 Netty 事件循环上 `block()` → 被 Reactor 拦下、静默降级（真机踩过） | 适配器内部 `subscribeOn(boundedElastic)+toFuture`；`/knowledge/search` 与直答路径也各自挪到 `boundedElastic`（那里还有 JDBC） | `SmokeTest` 与真机日志 `语义向量已就绪` / `已整体降级` |
| embedding 服务抖动把回答带崩 | 失败一次即**永久整体降级**到哈希向量并清缓存（同一向量空间）；只 warn 不抛 | `OpenAiCompatibleEmbeddingAdapterTest.serverFailureDegradesToLocalHashingVectors` |
| 上游 embedding 不按输入顺序返回 → 向量张冠李戴 | 按响应里的 `index` 还原顺序，条数不符也算失败 | 同上 `fitPreEmbedsTheCorpusInBatchesAndNormalizesVectors`（桩故意倒序） |
| 一个类有多个构造函数 → Spring 静默用无参构造，配置项全部失效（`PROMPTS_DIR` 白配） | 需要在容器里用的那个构造函数标 `@Autowired`；并用 `@SpringBootTest` 断言"配置真的接到 Bean 上" | `smoke.PromptHotReloadWiringTest` 的 `configuredExternalDirectoryIsWhatActuallyGetsRead` |
| 提示词改坏了（文件被删/清空）把运行中的对话带崩 | 重载失败保留上一版 + 记 error，只 warn 不抛；启动期缺文件仍然直接失败 | `PromptHotReloadTest.brokenEditKeepsLastGoodVersionAndReportsError` / `blankTemplateIsRejectedOnReload` / `startupStillFailsFastWhenFilesAreMissing` |
| 改一个关键词不知道波及哪些链路（一张表九处复用） | 信号→消费者必须登记；每张表逐词断言；影响面快照按语料标签比对，只报变化条目（§5.4.2） | `QuestionSignalsImpactTest` 四个用例（登记对账 / 逐词断言 / 路由==九路 OR / 快照矩阵） |
| 路由组合与 `QuestionSignals` 各写一份、只改一边 | 组合搬进 `QuestionSignals.needsAgentWorkflow`，服务只调一行；有等式断言 | `QuestionSignalsImpactTest.routingIsExactlyTheOrOfItsNineSignals` |
| 不存在的路径被兜底成 500「Unexpected server error」+ error 堆栈 | `GlobalExceptionHandler` 显式接 `NoResourceFoundException` → 404；冒烟用例锁住 | `AssistantSmokeTest.unknownPathReturns404NotServerError` |
| 「有购买意图但认不出商品」被路由进工作流 → 空转成"没能给出有效回答" | 路由改看 `purchaseProductId != null`，这类问法走直答（检索 + 模型反问）；影响面快照与路由等式断言同时锁住 | `QuestionSignalsTest.purchaseIntentWithoutIdentifiableProductStaysOnTheDirectPath`、`AssistantSmokeTest.purchaseIntentWithoutProductAnswersOnTheDirectPath`、`QuestionSignalsImpactTest` |
| 删会话只删一半（留下孤儿消息）或越权删除别人的会话 | 归属校验 + 单事务删消息与会话行（`TransactionTemplate`）、`workflow_traces` 保留作审计；端到端用例锁住"消息清零、另一会话不受影响、重复删幂等、越权 400" | `AssistantSmokeTest.deletingSessionRemovesItAndItsMessages` / `deletingSomeoneElsesSessionIsRejected`、`SessionList.test.tsx` |
| 一句话买多件时**静默丢单**（信号只返回一个 + `findFirst()` 只发一张卡） | `purchaseItems` 按句子顺序返回全部商品；**一个 `CREATE_ORDER` 带 `items[]`**、一张确认单多行明细（V9）、一次确认建 1 张订单 + N 行 order_items；文案列出各件与合计 | `QuestionSignalsTest`（多商品 5 例）、`IntentAgentTest.multiProductPurchaseCreatesOneOrderCallWithAllItems`、`AssistantSmokeTest.multiProductPurchaseCreatesOneConfirmationWithAllItems`、`OrderConfirmationCard.test.tsx` |
| 时区：表里显示 UTC 或写入偏 8 小时 | 人工会话 `+08:00` / 应用连接钉 UTC，**参数不可改**（§6.4） | 维护红线 |
| 表格显示成裸管道符 | 前端 `markdownTable` 解析后渲染 | `markdownTable.test.tsx` |

### 11.3 工程性限制

1. **测试以单元测试为主 + 1 个真容器冒烟**：后端 28 个测试类 / 177 用例 + 前端 19 用例（其中 3 个是 `Composer` 的交互测试，靠 jsdom + stub `EventSource`）。
   `AssistantSmokeTest` 是唯一的 `@SpringBootTest`（H2 + 规则兜底，见 §11.4），**Bean 装配错误现在会在 `mvn test` 阶段炸**；
   但它跑在 H2 上，**MySQL 专有行为（时区、REGEXP、真实 schema 漂移）仍只能靠真机启动验证**，也仍无 Testcontainers（依赖已声明未使用）。
2. **`tool_invocations` 写入静默失败**：`JpaToolInvocationRecorder` 里 `catch (Exception ignored)`，审计会静默丢数据。
   新增的三个 recorder 都改成 `log.warn` 带堆栈，但老代码未动。
3. **无 API 级幂等/限流**：重复点"确认下单"靠状态机（PENDING）兜底，并发靠事务。
4. **`db/ai_ticket_assistant.sql` 快照与迁移可能漂移**：手工 dump 未纳入 git，不能作为 schema 事实来源。
5. **Windows 本地构建依赖 `project.build.sourceEncoding=UTF-8`**：源码与提示词都是中文，Maven 默认平台编码（GBK）会静默改坏中文字面量。
6. **前端状态集中在一个 store**：`assistantStore.ts` 聚合了流式、会话、历史、订单确认等状态；`SessionList` 依赖
   `streaming` 由 true→false 来刷新列表，属隐式耦合。
7. **前端 Agent 卡片状态依赖后端中文文案**：`message.includes('并行')` 之类的匹配，改 Event message 会静默破坏 UI。

### 11.4 测试地图（改什么该跑什么）

```bash
cd backend
$env:JAVA_HOME='D:\Java\jdk-17.0.18'
mvn -s settings.xml -o test                                         # 全量（含真容器冒烟，约 10s）
mvn -s settings.xml -o -Dtest=AssistantSmokeTest test               # 只想确认"容器还能起来"
```

| 测试类 | 覆盖什么 | 什么时候一定要跑 |
| --- | --- | --- |
| **`smoke/AssistantSmokeTest`**（唯一 `@SpringBootTest`） | ① 装配：三个 Agent / 工具白名单 14 个 / 提示词 4 个 / `ConversationHistory.Budget` Bean 都在，且 AI 端口**必须是规则兜底**（不联网）；② 端到端：走真实 HTTP + SSE 跑通「安全下单」（含落库断言：只有待确认单、不建 orders）、「知识检索」、「闲聊直答」、**「多轮记忆落库」** | 改 Bean/构造器签名、改 SSE 事件、改路由、改工具白名单 |
| `application.routing.QuestionSignalsTest`、`SeededProductCatalogConsistencyTest` | 关键词表与商品名映射（含"迁移里的每件商品都能被名字解析"） | 改任何关键词、加商品 |
| `application.routing.QuestionSignalsImpactTest` | **影响面矩阵**：信号→消费者登记对账、每张表逐词断言、路由==九路 OR、快照比对（改表必跑，见 §5.4.2） | 改任何关键词/表/信号 |
| `application.conversation.ConversationHistoryTest` | 历史裁剪三条规则（单条截断 / 总量预算 / 最新一轮必留） | 改历史注入或预算 |
| `application.routing.QuestionSignalsTest`（多商品部分） | `purchaseItems`：句子顺序、长名优先（双肩包/连帽卫衣不被短名抢走）、同商品去重、"各 2 件"口径、非下单场景为空 | 改商品名表或下单识别 |
| `application.routing.QuestionSignalsTest`（路由口径） | 「只有买、认不出商品」不进工作流；能定位商品/预算搭配仍进（防这次改动把下单路带偏） | 改路由组合 |
| `smoke/AssistantSmokeTest`（2026-11-09 四条售后台账） | 购物车全链路（加购→合并→查看→结算成确认单→清车）、取消订单（状态 CANCELLED + **库存释放** + 二次取消被拒）、退款申请（未支付被拒 / 已支付落 `refund_requests` / 重复提交被拒）、账户查询（可用券 / 过期券不计 / 手机号脱敏） | 改这些工具、表结构或售后护栏 |
| `features/assistant/__tests__/OrderConfirmationCard.test.tsx` | 确认卡片的**多行渲染**：两件商品都显示、行小计用后端 `lineAmount`、合计件数与金额、无单据时不渲染 | 改确认单前端契约（items/totalQuantity/totalAmount） |
| `features/assistant/__tests__/SessionList.test.tsx` | 会话删除：确认后发 `DELETE`、列表刷新、删当前会话时换新会话；取消确认时不发请求 | 改会话侧栏或删除接口 |
| **`smoke/AuthSmokeTest`**（唯一打开 `auth.enabled=true` 的用例类） | 无令牌 401、登录换令牌、令牌身份压过参数、越权读会话 404、注销后失效、未知客户不能登录 | 改鉴权/过滤器/会话归属 |
| `features/review/__tests__/ReviewConsole.test.tsx` | 审核台：列出待审、通过发 POST approve、驳回未填原因**不发请求**、填了原因才发 reject | 改审核台 |
| `AssistantSmokeTest`（review 两条） | 审核全链路：申请 → 审核台列表 → 通过 → 申请单 APPROVED + 工单 RESOLVED + 用户侧看到备注；驳回必须写原因；重复审核 409 | 改审核接口/状态机 |
| `features/assistant/__tests__/CartPanel.test.tsx` | 购物车面板：渲染行与合计、+/- 发 PATCH、移除发 DELETE、空车不显示清空 | 改购物车页面 |
| `application.catalog.CategoryCandidateServiceTest`、`application.order.OrderReferenceResolverTest` | 品类歧义判定（多 SKU→候选 / 单 SKU→下单 / 具体名→不问）与"这单指哪一单"（取消取未支付、退款退货取已支付、取不到返回空） | 改选品或订单解析口径 |
| `scripts/intent-eval.ps1` + `src/test/resources/intent-eval-corpus.tsv` | **动作正确率评测**（31 条真实问法）：`-Label model-first` / `legacy` 各跑一遍对比；改提示词、换模型、改路由后都要跑 | 改意图/提示词/工具选择 |
| `application.agent.IntentAgentTest`（模式用例） | `auto`/`model-first`/`legacy` 的解析：真模型→模型优先、fallback→关键词层 | 改决策模式 |
| `application.tool.ToolArgumentGuardTest`、`ToolDispatcherTest` | 工具参数白名单：不存在的商品、越界数量、与句子矛盾的商品、订单号格式 / 工单动作 / 检索条数；以及"护栏先于执行" | 改工具参数或校验规则 |
| `application.agent.IntentAgentTest`、`assistant.AssistantWorkflowServiceTest` | 意图启发式与护栏、路由分流、历史注入、trace 状态映射、**流式推送（逐 token / 尾巴追加 / 收尾帧）** | 改路由/意图/组装/**流式** |
| `application.agent.AiClientPortTest` | 端口 `stream()` 默认实现：整段切句、**惰性执行**（不能在事件循环上跑阻塞调用）、空回答不出帧 | 改端口或加适配器 |
| `infrastructure.ai.deepseek.DeepSeekStreamParsingTest` | 上游 SSE 分片解析：哪些该留（content delta）、哪些该丢（`[DONE]`/role-only/空/坏 JSON） | 改 DeepSeek 适配器 |
| `application.catalog.*` | 预算测算（确定性）与价格表解析（两种片段格式） | 改预算或知识片段格式 |
| `infrastructure.knowledge.*` | 检索评测集（真实语料）、BM25 加成（含"非商品问题不给汇总加成"）、中文数字/同义改写的召回回归 | 改检索算法、同义词表或语料 |
| `infrastructure.knowledge.query.QueryRewriterTest` | 中文数字归一（七百→700、一千五→1500、一万二→12000）、**不误伤「一共」「两件」**、同义扩展不吞原文 | 改查询改写或同义词表 |
| `infrastructure.knowledge.embedding.HashingEmbeddingAdapterTest` | 数字单独成特征、IDF 让 ubiquitous 词变轻、同文本余弦为 1 | 改向量实现 |
| `infrastructure.knowledge.embedding.OpenAiCompatibleEmbeddingAdapterTest` | 本地桩 `HttpServer` 真发 HTTP：批量预嵌入、按 `index` 还原顺序（桩故意倒序）、查询缓存、服务 500 时整体降级 | 改语义适配器 |
| `infrastructure.knowledge.HybridKnowledgeSearchRepositorySemanticTest` | 有语义向量时"字面无交集"也能召回（`vec#`）、哈希向量做不到（对比断言） | 改融合逻辑 |
| `smoke.SemanticEmbeddingWiringTest`（`@SpringBootTest`） | 开关打开后容器里注入的确实是语义适配器，且检索链路真的走到了桩服务 | 改 Bean 条件/开关 |
| `application.agent.KnowledgeAgentRetrievalTest` | 直答前置检索：阈值过滤 / 空消息不查 / 检索失败不炸 / 来源渲染（§5.5） | 改直答路径或相关性门槛 |
| `infrastructure.ai.FallbackAiClientAdapterTest` | 规则兜底"只认当前这句话"，不被历史劫持 | 改兜底规则 |
| `interfaces.sse.AssistantStreamControllerTest`、`infrastructure.prompt.ClasspathPromptCatalogTest` | SSE 入口与提示词加载契约（文件存在、变量名匹配、渲染严格性、JSON 花括号不被误伤） | 改入口/提示词 |
| `infrastructure.prompt.PromptHotReloadTest` | 外部目录优先 / 权威性、手动与自动重载、坏文件保留旧版、空文件拒绝、启动 fail-fast、目录不存在回落 | 改热加载 |
| `smoke.PromptHotReloadWiringTest`（`@SpringBootTest`） | `assistant.prompts.dir` **真的**接到 Bean 上（曾经因多构造函数静默失效）、改文件后不重启即生效 | 改 Bean 装配/配置项 |
| 前端 `Composer.test.tsx` / `useAssistantStream.test.ts` / `assistantStore.test.ts` / `markdownTable.test.tsx` | 输入框行为、发送与清空、会话状态、表格渲染 | 改前端交互 |

**冒烟测试的边界（别误用）**：H2 内存库 + `ddl-auto=create-drop`，迁移是 MySQL 专有语法所以**不跑 Flyway**。
它证明的是"装配与链路通"，不是"数据正确"—— 数据侧靠 `SeededProductCatalogConsistencyTest`（直接读迁移 SQL）与真机冒烟。

---


## 12. 常用命令速查

```bash
# 全量启动 / 单服务重建
docker compose up -d --build
docker compose up -d --build backend
docker compose up -d --build frontend
docker compose ps
docker compose logs -f backend

# 后端（本地，需本机 MySQL）
cd backend
./mvnw -s settings.xml test
./mvnw -s settings.xml spring-boot:run

# 前端（本地，走 Vite 代理到 8080）
cd frontend
npm install
npm run dev
npm run build
npm test

# 时间列直接就是北京时间（人工会话默认 +08:00，无需任何转换函数）
#   docker exec ai-ticket-mysql mysql -uapp -papp -D ai_ticket_assistant -e "SELECT id,status,started_at FROM workflow_traces ORDER BY started_at DESC LIMIT 5"

# 快速验证
curl -N "http://localhost:18080/assistant/stream?message=我的衣服比699便宜的有哪几种&customerId=CUST-1001"
curl "http://localhost:18080/knowledge/search?query=价格刚好699的衣服是什么&limit=5"
curl "http://localhost:18080/products"          # V8 后应为 30 件

# 目录扩容后的两道验收（数字必须与 §5.4.3 一致）
curl -N "http://localhost:18080/assistant/stream?message=我有1000元，最多可以买几件衣服，衣服不能重复&customerId=CUST-1001"  # 7 件 / ¥973 / 剩 ¥27
curl -N "http://localhost:18080/assistant/stream?message=我要买一件驼色双面呢大衣&customerId=CUST-1001"                      # CLOTH-COAT-024 待确认单

# Windows 本地跑测试（本机 mvn 3.9.10 + JDK17；无 mvnw.cmd，直接用 mvn）
$env:JAVA_HOME='D:\Java\jdk-17.0.18'
cd backend; mvn -s settings.xml -o test                       # 全量（含冒烟，实测约 21~30s）
cd backend; mvn -s settings.xml -o -Dtest=AssistantSmokeTest test   # 只跑真容器冒烟（H2，不需要 MySQL/Docker）

# POST 带 JSON body 的接口在 PowerShell 里不要用 -d '...'（引号会被吃掉变非法 JSON）
# 用 --data-binary "@body.json" 传文件

# 本地"一键起站"（脚本会等后端真的返回 200 再打开浏览器，见 §12.3）
.\scripts\dev-up.ps1              # 后端(jar) + 前端(vite)
.\scripts\dev-up.ps1 -Rebuild     # 改过 Java 代码后强制重新打包
.\scripts\dev-down.ps1            # 停掉脚本起的两端
```

> PowerShell 传 JSON 给 `curl.exe` 时双引号会被丢弃（`{"a":"b"}` 变成 `{a:b}`），
> 表现为 500 + `JsonParseException: Unexpected character`。这是终端问题，不是接口问题。

### 12.3 启动耗时实测（2026-11-05）

"每次进入网站都很慢"的实测归因：**不是页面慢，是后端冷启动的 10~23 秒**。这段时间浏览器打开站点，
所有接口都连不上 → 面板空白/报错，观感就是卡住。

| 启动方式 | 到「首个 200 响应」 | 说明 |
| --- | --- | --- |
| `mvn spring-boot:run` | **23.5s** | Maven 自己 ~17s（解析 + 编译），Spring 6.3s |
| `java -jar target/*.jar` | **9~10s** | 省掉 Maven；脚本默认走这条 |
| `jar` + `spring.main.lazy-initialization` | 10.5s | 无稳定收益 |
| `jar` + `-XX:TieredStopAtLevel=1 -XX:+UseSerialGC` | 11.1s | 无收益（甚至更慢） |
| `jar` + Spring AOT（`-Dspring.aot.enabled=true`） | 10.3s | 构建代价 +24s，收益 0 |
| `jar` + AOT + 懒加载 + 关 springdoc | 9.4s | 组合收益 ~1s，不稳定，**未启用** |

Spring 自己的 6.3s 花在哪（mvn 启动日志的时间戳）：JVM+框架 0→2.0s、Hikari 连接池 1.73→2.0s、
Hibernate 实体扫描+校验 2.29→4.03s（+1.1s）、提示词加载 3.94s、WebFlux 路由/actuator 装配 4.42→6.48s（+2.05s）、
Netty 绑定 6.48→7.60s。

修不了的别硬修：这 10s 主要是 JVM 类加载 + Hibernate + Netty 的固有成本。真正的解法是**少重启** ——
改前端走 HMR、改提示词走热加载（§5.6）、只有改 Java 才重启；要找人就先 `. \scripts\dev-up.ps1`
（等到 200 才开浏览器，用户不会撞到空窗期）。

其它已排除的猜测（都测过，正常）：前端首屏 32 个模块 / 2.6MB / 串行 157ms；Vite dev 启动 405ms；
`lucide-react` 27MB 但已被预打包成 1 个请求；两个首屏接口 17ms / 63ms；数据库体量（88 条消息）无影响。

---

## 13. 变更记录

> 追加式。早期条目在这里只留一行摘要，详细推理已并入对应章节。格式：`# | 时间 | 摘要 | 关键文件`

| # | 时间 | 摘要 | 关键文件 |
| --- | --- | --- | --- |
| 1 | 11-02 17:20 | 首次通读全项目并创建本文档（基线 `8e7659f`） | 新增 `PROJECT_OVERVIEW.md` |
| 2 | 11-02 17:36 | **提示词外置**：`PromptCatalog` 端口 + 8 个 md 模板，Java 内不再有提示词字面量；删 `final-response.md`；补 UTF-8 编码 | `application/prompt/*`、`infrastructure/prompt/*`、`resources/prompts/*`、`pom.xml` |
| 3 | 11-02 17:44 | 澄清"未落库"≠"表没建"；发现宿主机 3306 与 Docker 13306 是两套库且已分歧 | 文档 §6.2/§8.1 |
| 4 | 11-02 17:55 | 纠正上条误判：Navicat 连 13306、IDEA 后端连 3306（IDEA Database 面板不影响应用连接） | 文档 §8.1 |
| 5 | 11-02 18:10 | **审计落库**：`workflow_traces` / `workflow_trace_steps` / `ticket_events` 转真实读写；`traceStatusFor` 抽成纯函数 | `WorkflowTraceRecorder`、`TicketEventRecorder`、3 实体 + 3 仓储 |
| 6 | 11-02 21:00 | 补"该连 3306 还是 13306"的三步切换清单 | 文档 §8.1 |
| 7 | 11-02 21:12 | 数据源默认改指 Docker `13306` + 注释如何覆盖回本机 | `application.yml`、`README.md` |
| 8 | 11-02 21:16 | 修正会误导人的自查命令（`Group-Object ProcessName` 会丢掉 RemotePort） | 文档 §8.1 |
| 9 | 11-02 21:22 | 首次暴露时区问题：容器 UTC，写入 epoch 正确但显示为 UTC。补"判断显示/数据要看 `UNIX_TIMESTAMP`" | 文档 §6.4 |
| 10 | 11-02 21:25 | 补 `SET PERSIST` 权限说明（1227 需 root / SYSTEM_VARIABLES_ADMIN） | 文档 §6.4 |
| 11 | 11-02 21:28 | 澄清是 Windows 与官方镜像的默认值差异，不是 Docker bug；两库 `app` 权限相同 | 文档 §6.4 |
| 12 | 11-02 21:42 | **"统一 +08:00"尝试失败并完整回退**：写入偏 −28800s，定位为 Hibernate 6 的 UTC 墙钟绑定；四种修法全无效 | `application.yml`、`docker-compose.yml`、文档 §6.4 |
| 13 | 11-02 22:20 | 曾落地 CONVERT_TZ 视图方案（V6 + 7 个 `v_*_cst` 视图） | `V6__beijing_time_views.sql`（后撤销） |
| 14 | 11-02 22:28 | **改"双会话时区"并撤销 V6**：人工会话 `+08:00` 直接看北京时间，应用连接钉 UTC 保证写入正确 | `application.yml`、`docker-compose.yml`、删 V6、文档 §6.4 |
| 15 | 11-03 00:25 | **多轮会话记忆**：`ConversationMemory` 端口 + `assistant_sessions`/`assistant_messages` 真实读写 + 前端固定 sessionId | `application/conversation/*`、`JpaConversationMemory`、前端 3 文件 |
| 16 | 11-03 00:50 | **多会话**：会话列表 / 切换 / 历史回看（2 个只读接口 + 侧栏），历史按 sessionId 严格隔离 | `AssistantSessionController`、`SessionList.tsx` |
| 17 | 11-03 01:00 | **重写回答格式**：结论优先、不暴露意图/置信度/Trace/score/内部 URI、空小节不输出 | `AssistantResponseAssembler`、`KnowledgeAgent` |
| 18 | 11-03 01:08 | **修 fallback 关键词被历史劫持**（问699答退款）：只认 `structuredContext.userMessage` | `FallbackAiClientAdapter` + 新测试 |
| 19 | 11-03 16:22 | **`.env` 接入 Spring**（`spring.config.import`），本地 IDEA 启动也能用 DeepSeek，密钥不进仓库 | `application.yml`、`README.md` |
| 20 | 11-03 16:33 | **启动自检** `AiProviderDiagnostics`：打印生效适配器 / 命中的 `.env` / 工作目录，配错时给出排查清单 | `AiProviderDiagnostics` |
| 21 | 11-03 19:55 | **修"时灵时不灵地检索"三连 bug**（路由 / 护栏 / 查询词），判定口径统一到 `QuestionSignals`；意图枚举容错 | `QuestionSignals`、`AssistantWorkflowService`、`IntentAgent` |
| 22 | 11-03 20:15 | **知识库整改**：V7 清填充语、汇总片段加权、取消片段截断、建检索评测集 | `V7`、`Bm25SearchAdapter`、`KnowledgeChunkSource`、`RetrievalEvaluationTest` |
| 23 | 11-03 20:45 | **前端表格渲染 + 提示词正面契约**；发现"压掉草稿会把自我纠错一起消掉" | `markdownTable.ts`、`prompts/*.system.md` |
| 24 | 11-03 21:10 | **预算测算工具化 `PLAN_BUDGET`**：件数/合计由代码算，全新会话连跑三次结果一致 | `domain/catalog/*`、`application/catalog/*`、`PlanBudgetTool` |
| 25 | 11-03 21:44 | **关键词表统一**：路由 / `IntentAgent` 启发式与护栏 / `FallbackAiClientAdapter` / `QueryOrderTool` 的判定与商品名表全部并入 `QuestionSignals`，调用方不再有 `contains("…")`；顺带修「下单 2 件」只有意图认、路由不认 | `QuestionSignals`、`AssistantWorkflowService`、`IntentAgent`、`FallbackAiClientAdapter`、`QueryOrderTool` + 11 个新用例 |
| 26 | 11-03 22:22 | **服装目录扩容 10 → 30 件（V8）**：products/inventory/单品 chunk/汇总价格表/商品名表五处同步；`MAX_CONTENT_LENGTH` 400→1200（整表 971 字）；评测集改为按迁移生成并逐件断言召回；修 `.git/info/exclude` 的 `db/`（V7、V8 之前静默不进 git） | `V8__expand_clothing_catalog_seed.sql`、`QuestionSignals`、`KnowledgeAgent`、`RetrievalEvaluationTest`、`PriceCatalogServiceTest`、`SeededProductCatalogConsistencyTest`、`.git/info/exclude` |
| 27 | 11-03 23:30 | **输入框行为**：会话开始为空（去掉预填的示例问题）、发送后立即清空、空白输入不发请求（按钮置灰）；回答区回显「你：…」以免清空后看不到本轮提问；`newSession`/`openSession` 同步清空 | `Composer.tsx`、`useAssistantStream.ts`、`assistantStore.ts`（`consumeMessage`/`lastAskedMessage`）、`StreamingAnswer.tsx` + 10 个前端用例 |
| 28 | 11-03 23:56 | **会话历史长度与成本控制**（原 §11.1 第 8 条）：注入提示词前在 `loadHistory` 一处裁剪 —— 单条截断（用户 120 / 回答 300）→ 总量预算 1200 字（从最新往前装）→ 最新一轮必留；`assistant.conversation.*` 可配、配错回落默认值；裁剪只影响提示词，界面历史仍完整 | `ConversationHistory`、`AssistantWorkflowService`、`AssistantProperties`、`ConversationConfig`、`application.yml`、`ConversationHistoryTest` + 1 个 workflow 用例 |
| 29 | 11-04 00:21 | **补真容器冒烟 `AssistantSmokeTest`**（项目首个 `@SpringBootTest`）：H2 + 规则兜底，覆盖 Agent/工具/提示词/预算 Bean 装配 + 4 条端到端（安全下单含落库断言、知识检索、闲聊直答、多轮记忆落库）；新增 §11.4 测试地图 | `smoke/AssistantSmokeTest`、文档 §11.3/§11.4 |
| 30 | 11-04 00:47 | **真流式 SSE**（原 §11.1 第 4 条）：`AiClientPort.stream()` 端口 + DeepSeek `stream=true` 逐 token 解析；工作流改成"边收边推"（知识流→确定性小节→收尾帧），订单分析用 `cache()`+空分支保持并行；顺带修「`Flux.just` 装配期求值导致收尾帧丢尾巴」与工作流错误无日志 | `AiClientPort`、`AiStreamEvent`、`StreamChunks`、`DeepSeekAiClientAdapter`、`KnowledgeAgent`、`AssistantWorkflowService`、`AssistantResponseAssembler` + 8 个新用例 |
| 31 | 11-04 10:50 | **移除 Anthropic**：删适配器与 `anthropic-java` 依赖；`AssistantProperties.Ai` 去掉 `anthropicApiKey` / `model` / `maxTokens` / `enableRealClient` 与 `hasApiKey()`；`ANTHROPIC_*` 从 application.yml / docker-compose / .env.example 清除；`timeout` 改由 `AI_TIMEOUT` 控制（DeepSeek 与 Qwen 共用）；`.gitignore` 去掉 `.claude/`；provider 只剩 `deepseek` / `qwen` / `fallback` | `AnthropicClaudeClientAdapter`（删除）、`pom.xml`、`AssistantProperties`、`application.yml`、`docker-compose.yml`、`.env.example`、`DeepSeekStreamParsingTest`（构造参数）、文档 |
| 32 | 11-04 20:45 | **直答路径接知识库**（原 §11.1 第 3 条）：`runDirectQwen` 先跑本地混合检索（阈值 0.05、最多 3 条，#33 重标为 0.10），命中就推 `knowledge_sources` 并把 `{{sources}}` 喂给模型（要求按资料作答 + `**依据**` 列标题，没命中则明确"以页面/人工客服为准、不许编引用"）；顺带修 `summaryBoost` 误触发（售后问题含"哪些"白拿 +0.4 把价格表顶到第一） | `AssistantWorkflowService`、`KnowledgeAgent`（retrieveKnowledge/sourcesBlock）、`Bm25SearchAdapter`、`prompts/direct-answer.*`、`KnowledgeAgentRetrievalTest` + 4 个新用例 |
| 33 | 11-04 21:06 | **混合检索落地**（原 §11.1 第 2 条）：`QueryRewriter`（中文数字归一 + 同义扩展）→ `EmbeddingPort`/`HashingEmbeddingAdapter`（TF-IDF 特征哈希）+ `Bm25SearchAdapter`（新增数值邻近加成）→ `HybridKnowledgeSearchRepository` 融合打分（0.7×饱和BM25 + 0.3×余弦，RRF 仅裁决并列）；删掉恒返回空的 `ChromaClient` 与 compose 的 chromadb 服务；直答门槛重标 0.05 → 0.10 | `HybridKnowledgeSearchRepository`、`QueryRewriter`、`EmbeddingPort`、`HashingEmbeddingAdapter`、`Bm25SearchAdapter`、`KnowledgeAgent`、`application.yml`、`docker-compose.yml`、`RetrievalEvaluationTest` + 11 个新用例 |
| 34 | 11-04 22:11 | **向量那一路支持真语义模型**（原 §11.1 第 2 条的边界）：`EmbeddingPort` 增加 `fit` 钩子，新增 `OpenAiCompatibleEmbeddingAdapter`（任何 OpenAI 兼容 `/embeddings`：DashScope/SiliconFlow/Ollama），批量预嵌入 + 缓存 + 失败整体降级；`EMBEDDING_*` 五个配置项，默认仍是本地哈希；顺带修「事件循环上 block() 导致语义向量静默降级」与 `/knowledge/search`、直答路径的事件循环阻塞 | `OpenAiCompatibleEmbeddingAdapter`、`EmbeddingPort`、`HashingEmbeddingAdapter`、`AssistantProperties`、`application.yml`、`KnowledgeController`、`AssistantWorkflowService` + 8 个新用例 |
| 35 | 11-04 23:08 | **提示词热加载**（原 §11.1 第 8 条）：`PROMPTS_DIR` 外部目录（权威来源）+ 文件指纹懒检查（默认 1s）+ `POST /admin/prompts/reload` 与 `GET /admin/prompts` 诊断；运行时重载失败保留旧版、启动期缺文件仍 fail-fast；修「多构造函数让 Spring 静默用无参构造、配置项失效」 | `ClasspathPromptCatalog`、`PromptCatalog`（reload/info）、`PromptReloadResult`、`PromptSourceInfo`、`PromptAdminController`、`AssistantProperties`、`application.yml` + 11 个新用例 |
| 36 | 11-05 00:40 | **`QuestionSignals` 影响面矩阵**（原 §11.1 第 5 条）：新增 `signalConsumers()` 登记「信号→消费者」、`cueTables()` 让测试不必复制关键词；`QuestionSignalsImpactTest` 四道防线（登记对账用反射 / 每张表逐词断言 / 路由==九路 OR / 按语料标签比对的快照矩阵，只报变化条目）；路由的九路组合搬进 `QuestionSignals.needsAgentWorkflow` | `QuestionSignals`、`AssistantWorkflowService`、`QuestionSignalsImpactTest`、`src/test/resources/question-signals-impact.txt` |
| 37 | 11-05 09:54 | **一句话买多件**（用户反馈的真实故障：只下成后一件）：新增 `QuestionSignals.purchaseItems`（按句子顺序 + 长名优先 + 同商品去重 + 「各 N 件」口径）；`IntentAgent`/`FallbackAiClientAdapter` 一个商品一个 `CREATE_ORDER`；`toolEvents` 每个确认单都发事件（原 `findFirst()`）；「需要你确认」列出全部商品与张数 | `QuestionSignals`、`PurchaseItem`、`IntentAgent`、`FallbackAiClientAdapter`、`AssistantWorkflowService`、`CreateOrderTool`、`AssistantResponseAssembler` + 7 个新用例 |
| 38 | 11-05 10:25 | **待确认单升级为一单多明细（V9）**：新增 `order_confirmation_items` 表/实体/仓储（`OrderConfirmationItem`，行小计派生不落库），回填历史单后 drop 掉 `order_confirmations` 的 product_id/quantity/unit_price；`CreateOrderConfirmationService` 逐行校验、整单成败；`ConfirmOrderService` 一次事务建 1 张订单 + N 行 order_items；`CREATE_ORDER` 参数改为 `items[]`（兼容旧单件形态）；前端卡片多行渲染 + 下单结果列明细 | `V9__order_confirmation_items.sql`、`OrderConfirmation(Item)`、`OrderConfirmationItemJpaEntity`、`SpringDataOrderConfirmationItemJpaRepository`、`JpaOrderConfirmationRepository`、`CreateOrderConfirmationService`、`ConfirmOrderService`、`OrderConfirmationView`、`CreateOrderTool`、`IntentAgent`、`FallbackAiClientAdapter`、`AssistantResponseAssembler`、`OrderConfirmationCard.tsx`、`types.ts` |
| 39 | 11-05 11:06 | **"进入网站很慢"归因并给出方案**：实测 `mvn spring-boot:run` 23.5s vs `java -jar` 9~10s，且懒加载/AOT/Tiered1/关 springdoc 均无稳定收益（§12.3）；新增 `scripts/dev-up.ps1`（自动打包 + 等后端返回 200 后再开浏览器）与 `dev-down.ps1`（按进程树停止）；顺手修 `NoResourceFoundException` 被兜底成 500 → 404 | `scripts/dev-up.ps1`、`scripts/dev-down.ps1`、`GlobalExceptionHandler`、`AssistantSmokeTest`、`README.md`、`.gitignore` |
| 40 | 11-05 14:50 | **接上真语义向量（阿里云 qwen3.7-text-embedding-flash）**：`.env` 配 `EMBEDDING_PROVIDER=openai-compatible` + key/model/dimensions；实测语料预嵌入 34 片/4 批 2.3s、语义泛化生效（围脖→围巾 0.144）；因噪声分被整体抬高（0.081→0.105），`MIN_DIRECT_ANSWER_SCORE` 0.10 → **0.12** 并重标注释；测试侧强制离线（`application-test.yml` + 冒烟用例内联 `embedding-provider=hash`） | `.env`、`.env.example`、`KnowledgeAgent`、`application-test.yml`、`AssistantSmokeTest`、`PROJECT_OVERVIEW.md` §6.5 ⑤ / §8.3 |
| 41 | 11-05 15:55 | **修「有购买意图但认不出商品」的空回答**：路由第三路从 `hasPurchaseIntent` 改为 `purchaseProductId != null`，这类问法改走直答（检索 + 模型反问）；影响面快照重生成（28 处变化全是"裸下单词不再路由"，真实问法 0 变化）；实测「我想买个暖和的围脖」→ 命中「灰色羊毛围巾 ¥109」并给出结论/依据 | `QuestionSignals.needsAgentWorkflow`、`QuestionSignalsImpactTest`（路由等式 + 语料）、`QuestionSignalsTest`、`AssistantSmokeTest`、`question-signals-impact.txt` |
| 44 | 11-09 16:59 | **收掉上一版的三处毛边**：① 退款补 `QUERY_REFUND`（工具 11 → 12）+ 申请落单**自动建 REFUND 工单**（此前 `REQUESTED` 是终点、没人处理），进度里的时间戳改北京时间；② `isViewCartRequest` 收紧为"明确查看意图或整句就是购物车"，修掉「购物车里的东西会自动清空吗 → 购物车现在是空的」这类答非所问，空车问"删掉"也补一句"对话里不支持移除"；③ 新增 `ToolArgumentGuard`：`ToolDispatcher` 执行前校验 productId 真实存在且在售、数量 1~20、与句子里点名的商品不能完全无关、订单号/工单动作/检索条数合法（此前参数完全不校验，模型编个存在的编号就能下单）。后端 197 用例全绿，真机逐条复验：进度查询（无记录→引导 / 有记录→RFD+状态+北京时间）、规则问句走直答、退款同时落 REFUND 工单（TCK-…/HIGH）。 |
| 45 | 11-09 22:00 | **鉴权 / 会话归属 / 数据清理 / 退货流程 / 购物车面板（一次做五件）**：① 新增 `auth_tokens`（V12）+ `AuthService` + `AuthWebFilter` + `/auth/login|logout|whoami`，客户身份改由令牌决定、`/assistant/**` 无令牌 401、`/products` 等公开；② `GET /assistant/sessions/{id}/messages` 补 `requireOwnership`（越权统一 404）；③ `RetentionCleanupService`（过期确认单 7d / 闲置购物车 30d / 老会话 30d / 老 trace 14d / 过期令牌；定时器默认关闭）；④ 退货 ≠ 退款：`return_requests`（V12，`order_no` 唯一）+ `REQUEST_RETURN` / `QUERY_RETURN`（工具 12 → 14）+ `LOGISTICS` 工单 + 两个答案小节；⑤ 前端 `CartPanel` + `/assistant/cart` REST + 令牌自动携带（`authorizedFetch`、SSE 走 `?token=`）。后端 206 用例、前端 28 用例全绿；真机（MySQL 3306 + DeepSeek）验证：无令牌 401 / 登录后 200 / 加购-PATCH-移除-清空 / 退货落 RTN + LOGISTICS 工单 / 越权读会话 404 / 注销后 401。**踩坑**：控制器类上的 `@Validated` 会让 Spring 用 CGLIB 代理它，方法体内字段全 null（SSE 全部 500）—— 这是第三次撞同类代理坑，见 §5.7。 |
| 46 | 11-09 22:25 | **售后审核台**：V13 给 `refund_requests` / `return_requests` 补 `ticket_id` / `review_note` / `reviewed_by` / `reviewed_at` + 状态索引（此前工单 id 只在工具返回值里，审核端读不出来）；领域对象补状态机（`REQUESTED → APPROVED/REJECTED`，只有待审能审、驳回必须有原因）；新增 `AfterSalesReviewService` + `/admin/after-sales/**`（summary / 列表 / approve / reject，通过或驳回都会把关联工单 resolve 并写上结论）；`IllegalStateException` 单独映射 409，重复审核不再兜底成 500；用户侧 `QUERY_REFUND`/`QUERY_RETURN` 现在能看到 APPROVED/REJECTED 与审核备注；前端新增「售后审核台」视图（退款/退货 × 待审/已通过/已驳回，行内填备注、逐条审核，驳回未填原因前端先拦）。后端 208 用例、前端 32 用例全绿；真机（MySQL 3306 + DeepSeek）走通：提交退款 → 审核台列表 → 通过 → 申请单 APPROVED + 工单 RESOLVED → 用户侧读到"APPROVED + 核实无误，同意退款"；退货驳回空原因 400、填原因后 REJECTED、重复审核 409。 |
| 47 | 11-10 15:27 | **修"登录失败"**：根因是 `vite.config.ts` 的代理前缀白名单没跟上新接口 —— `POST /auth/login` 在 5173 上是 **404**（`/admin/**` 更隐蔽：拿到 `index.html` 的 200），前端 `ensureToken()` 直接抛错，于是购物车面板与客服工作台同时"登录失败"。补 `/auth`、`/admin` 两条代理规则并重启 Vite；新增 `viteProxy.test.ts`（7 个前缀 + 目标 8080）把"后端前缀"和"代理覆盖"钉在一起；文档补 §10.2 自查表。真机验证：`5173/auth/login` 200 + token、`5173/assistant/sessions` 200、`5173/assistant/cart` 200、`5173/admin/after-sales/summary` 返回 JSON。 |
| 48 | 11-11 10:10 | **修"要加购却生成了订单"**：`purchaseToolCalls`（购买意图+能定位商品）抢在售后工具之前，导致「我想要买个 A、B、C。先帮我加入购物车」直接生成待确认单（¥1057）。新增 `IntentAgent.cartToolCalls()`，只要命中购物车三态（加购/结算/查看）就**优先于下单**，并在 `parseOrFallback`（覆盖模型输出）与 `heuristicToolCalls` 两处都插入；`AssistantSmokeTest.cartRequestWinsOverPurchaseIntent` 锁住（3 件入车、无确认单事件、无订单行）。反例同时验证：不带"购物车"仍走下单、结算走 fromCart、纯查看走 VIEW_CART。后端 209 用例全绿；真机复验原句：工具 `ADD_TO_CART`、购物车 3 件 ¥1057、0 个待确认单。 |
| 49 | 11-11 11:10 | **决策模式改为"模型优先、关键词兜底"**（用户提议"先让模型理解再调功能"）：① 提示词去掉硬编码工具枚举，改为以运行时 Tool metadata 为准，并补 7 条选择口径（购物车优先 / 退款退货取消之分 / 账户 / 预算 / 转人工 / 禁编 id）；② `IntentAgent` 加 `assistant.intent.mode`（auto/model-first/legacy），真模型默认模型优先，模型没给 tool_calls 时才走关键词兜底；③ 新增 `enrichArguments()` 只补参数不覆盖动作（商品 id/订单号/预算/检索词）；④ 新增评测脚本 `scripts/intent-eval.ps1` + 31 条语料 `intent-eval-corpus.tsv`。**A/B（DeepSeek 实跑）：legacy 28/31 = 90.3%（漏 PLAN_BUDGET、修改地址被账户查询抢走），model-first 31/31 = 100%**。顺带修「购物车里还剩什么」的路由线索（写的是"购物车里剩"，中间多了"还"认不出）。后端 210 用例全绿。 |
| 50 | 11-11 14:52 | **修"加购报商品编号不能为空"**：模型正确选了 `ADD_TO_CART`，但按提示词"不知道就留空"给了三条 `{productId:"", quantity:1}`；`fillCartItems` 把"items 非空"当成"已给明细"直接跳过补参 → 护栏拒绝。改为"每条 item 都必须有非空 productId，否则整组按句子里点名的商品重建（模型给的数量按顺序保留）"，护栏话术也改成人话。新增 2 条单测（补空 id / 保留模型给的 id）并把这条真实说法加进评测语料；`model-first` 复跑 **32/32 = 100%**。顺带修一处异步落库 5s 超时的用例抖动（放宽到 20s）。后端 212 用例全绿。 |
| 51 | 11-11 16:40 | **路由也交给模型（收官"模型优先"）**：去掉"关键词闸门决定要不要进工作流"（实测 7 条口语 6 条被挡在门外、一律"我无法操作"）。新入口 = 纯闲聊快路径（`QuestionSignals.isPureChitchat`：短句 ≤20 字 + 寒暄白名单 + 无业务信号）→ 其余一律先进工作流问 IntentAgent → 有 tool_calls 执行工具、无 tool_calls 落回直答（带前置检索）。复测 6 条口语 6/6 进工作流，其中「帮我查下我的单子」直接查到订单。**前端补 SSE 断线重连**：EventSource 拿不到状态码，断线时强制换一次令牌重连（令牌过期不再"点了没反应"）。**补 model-first 离线回归**：空 productId 已覆盖，另加"CREATE_ORDER 无明细补参""一次多个工具全保留"。后端 215 用例、前端 41 用例全绿；评测 32/32 = 100%（两条知识问法期望值同步为 SEARCH_KNOWLEDGE）。 |
| 52 | 11-11 21:10 | **两处"替用户猜"的边界改成不猜错**：① 品类歧义 → 新增 `CategoryCandidateService`：只说了品类且目录里该品类有 ≥2 个 SKU 时**列 2~3 个候选 + 反问**（以前会替用户挑一件直接下水单），单 SKU 或句子里已有完整商品名则照常下单；判定依据是"目录里有几个 SKU"（数据属性），不是手写词表。② 新增 `OrderReferenceResolver`：`CANCEL_ORDER` 取最近一笔"已创建未支付"、退款/退货取最近一笔"已支付"、查订单取最近任意，取不到才要订单号（不跨状态兜底）。③ 前端输入框 **Enter 发送、Shift+Enter 换行**（输入法组词时不抢 Enter）。真机验证：品类候选正确列出 3 款夹克；「这单我不要了」自动取消 ORD-AI-1395FC61 并释放库存；「我要退货」自动取 ORD-1002 受理。后端 224 用例、前端 42 用例全绿。 |
| 43 | 11-09 16:35 | **售后四类真工具（P0 修复）**：新增 `ADD_TO_CART` / `VIEW_CART` / `QUERY_ACCOUNT` / `CANCEL_ORDER` / `REQUEST_REFUND`（工具白名单 6 → 11）+ V11（`cart_items` / `customer_coupons` / `refund_requests`）。根因：路由里有购物车/账户/订单生命周期三路信号，却**没有任何对应工具** → 实测「加入购物车」被当成下单（productId 被编成不存在的 `CLOTH-HOODIE-BLK`）、「帮我取消订单」工具 0 个直接哑火、「确认收货」只回订单状态（用户以为办成了）。做法：`IntentAgent.afterSalesToolCalls` **确定性判定并覆盖模型输出**；`productItemsIn` 与 `purchaseItems` 拆开（加购不要求购买动作）；`QuestionSignals.orderNoIn` 让订单号能从句子里的抽；组装器新增购物车/账户/已取消/退款四个小节。取消只允许未支付并释放预留库存，退款只落申请单不动钱，移除/清空购物车明说"不支持"而不是装作做完。踩坑：`@Repository` 上新增方法在 CGLIB 代理实例执行 → 字段 null → NPE（同一类里老方法正常），账户查询改走新端口 `AccountDirectory` + `@Component`（§5.4.6）。后端 188 用例、前端 23 用例全绿；真机（MySQL 3306 + DeepSeek）逐条验证加购/合并/结算/取消/退款/账户。 |
| 42 | 11-05 16:47 | **删除会话 + 目录 30 → 60 件**：新增 `DELETE /assistant/sessions/{id}`（归属校验 + 单事务删消息与会话，审计 trace 保留；前端侧栏删除按钮 + 二次确认）；V10 迁移扩 30 件新品类（童装/家居服/运动/鞋/配饰），同步 products/库存/单品 chunk/汇总价格表/商品名表/品类词；`MAX_CONTENT_LENGTH` 1200 → 2600（整表 1917 字）；预算验收重标「不重复 8 件 / ¥982 / 剩 ¥18」 | `AssistantSessionController`、`ConversationMemory`、`JpaConversationMemory`、`SessionList.tsx`、`V10__expand_clothing_catalog_to_60.sql`、`QuestionSignals`、`KnowledgeAgent`、`PriceCatalogServiceTest`、`question-signals-impact.txt` |
