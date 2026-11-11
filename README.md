# 企业级 Multi-Agent AI 智能工单协同系统（AI Ticket Assistant）

AI Ticket Assistant 是一个面向企业客服/工单/订单场景的 AI Native 示例项目。项目重点不在“训练模型”，而在后端架构与业务集成：通过 Spring Boot 后端编排 Intent Agent、Tool Calling、RAG 检索、订单确认、JPA 持久化和 SSE 分阶段事件推送，前端以 React 展示多 Agent 工作流、工具调用、RAG 来源和订单确认卡片。

## 核心功能

- **普通问答 / 商品推理**：普通咨询、简单计算、商品目录问答可直接回答或走 RAG 检索。
- **直答也会查知识库**：闲聊/通用售后在直连模型之前先检索一次知识库，命中就按资料回答并列出「依据」，没命中才用通用知识（并明确"以页面/人工客服为准"，不许编引用）。
- **商品目录 RAG（混合检索）**：服装商品编号、名称、价格已写入知识库；查询改写（中文数字 + 同义词）+ 本地向量 + BM25 融合，所以「七百块的衣服」「外套多少钱」这类口语问法也能命中。
- **多 Agent 并行编排**：Intent Agent 识别意图，Order Agent 与 Knowledge Agent 通过 `Mono.zip` 并行分析，最后统一组装回答。
- **Tool Calling 安全边界**：AI 只返回工具调用 JSON，真实业务动作由后端 ToolDispatcher 和领域服务执行。
- **安全下单确认**：购买类语义先创建待确认订单，不自动扣款；用户确认后才创建真实订单。一句话买多件时是**一张确认单多行商品**（确认一次整单下单，不会漏掉其中一件）。
- **工单与订单查询**：支持创建/更新工单、查询用户订单、结合知识库解释订单/支付问题。
- **购物车与售后（2026-11-09）**：`ADD_TO_CART` / `VIEW_CART` 是真购物车（`cart_items`，同商品合并数量、不锁库存），「把购物车里的都下单」整车转成一张待确认单；`CANCEL_ORDER` 只取消未支付订单并**释放预留库存**；`REQUEST_REFUND` 只落退款申请单（`refund_requests`），**不动钱**；`QUERY_ACCOUNT` 查地址/支付方式/可用优惠券（脱敏）。退款提交后**自动建 REFUND 工单**并可随时用 `QUERY_REFUND` 查进度（`REQUESTED` = 待人工审核）；工具参数有白名单校验（商品必须真实存在、数量 1~20、不能与句子里点名的商品矛盾），模型编不出商品来下单。确认收货、修改地址这类没有对应工具的动作**一律转人工建工单**，不会再"回个订单状态就算办完了"。对话里移除/清空购物车不支持，会明确告知。 退货走 `REQUEST_RETURN`（货寄回 + 物流工单），与退款是两条流程。
- **多轮会话记忆**：同一 sessionId 下记住最近 6 轮问答（落 assistant_sessions / assistant_messages），追问「那再帮我算算 2000 元」能接上上文；前端用 localStorage 固定 sessionId，页面有「新会话」按钮；左侧会话栏支持**删除会话**（消息一起删，审计 trace 保留）。
- **提示词热加载**：把 `PROMPTS_DIR` 指向 `backend/src/main/resources/prompts`，改完保存即生效（不用重启、不用 Build）；
  `GET /admin/prompts` 看每个提示词实际读的是哪个文件，`POST /admin/prompts/reload` 立即重载。改坏了会保留上一版并报错，不会把对话带崩。
- **历史长度与成本控制**：注入提示词前会裁剪历史（单条回答保留开头 300 字，整块历史 1200 字，从最新一轮往前装，最新一轮必留），避免长会话把 prompt 撑大；上限可用 `CHAT_HISTORY_MAX_TURNS / CHAT_HISTORY_MAX_CHARS / CHAT_HISTORY_MAX_USER_CHARS / CHAT_HISTORY_MAX_ANSWER_CHARS` 调整。界面上的「历史对话」仍是完整原文。
- **SSE 分阶段事件推送**：后端以 Server-Sent Events 推送 workflow、agent、tool、RAG source、final answer、trace。
- **真流式回答**：DeepSeek 走 `stream=true`，回答边生成边推（逐 token 一个 `final` 增量帧），首字延迟 0.6~1.4s（改造前 = 整段生成完才出第一帧）；规则兜底 / Qwen 仍整段返回后按句切片。
- **数据库迁移与种子数据**：使用 Flyway 管理 MySQL 表结构和演示数据。

## 技术栈

### Backend

- Java 17
- Spring Boot 3.3.6
- Spring WebFlux
- Spring Data JPA
- MySQL 8.4
- Flyway
- Reactor
- OpenAPI / Swagger UI
- Testcontainers / H2 / JUnit

### AI / Agent

- `AiClientPort` 统一 AI 调用端口（`complete` 整段 / `stream` 流式）
- DeepSeek Adapter（真流式）
- Qwen Adapter
- Fallback Adapter
- Intent Agent
- Order Agent
- Knowledge Agent
- ToolDispatcher
- RAG：混合检索（查询改写 + 向量 + BM25 融合，无需外部向量库）；向量默认本地哈希，可一键切真语义模型

### Frontend

- React 18
- TypeScript
- Vite
- TailwindCSS
- Zustand
- React Query
- EventSource / SSE
- lucide-react

### Deployment

- Docker Compose
- Backend Dockerfile
- Frontend Nginx 静态部署
- MySQL 容器
- 检索：应用内向量计算（默认 TF-IDF 特征哈希；可切 OpenAI 兼容 embedding 做真语义召回）

## 项目结构

```text
ai-ticket-assistant/
├── README.md
├── docker-compose.yml
├── backend/
│   ├── Dockerfile
│   ├── pom.xml
│   ├── settings.xml
│   └── src/
│       ├── main/java/com/example/aiticketassistant/
│       │   ├── interfaces/
│       │   │   ├── rest/          # REST API：商品、知识库、订单确认等
│       │   │   ├── sse/           # SSE 入口 AssistantStreamController
│       │   │   └── dto/           # 前后端传输 DTO
│       │   ├── application/
│       │   │   ├── assistant/     # AssistantWorkflowService 编排工作流
│       │   │   ├── agent/         # IntentAgent / OrderAgent / KnowledgeAgent / AI Port
│       │   │   ├── tool/          # ToolDispatcher、ToolRegistry、Tool 定义
│       │   │   ├── order/         # 下单确认、确认下单应用服务
│       │   │   ├── query/         # 查询用例
│       │   │   ├── assembler/     # 最终回答组装
│       │   │   └── workflow/      # WorkflowContext / Trace
│       │   ├── domain/
│       │   │   ├── order/         # 订单/确认单领域模型
│       │   │   ├── catalog/       # 商品目录领域模型
│       │   │   ├── customer/      # 地址/支付方式领域模型
│       │   │   ├── ticket/        # 工单领域模型
│       │   │   ├── knowledge/     # 知识库领域模型
│       │   │   └── shared/        # 通用异常/值对象
│       │   └── infrastructure/
│       │       ├── ai/            # DeepSeek / Qwen / Fallback AI Adapter
│       │       ├── knowledge/     # 混合检索（查询改写 / 本地向量 / BM25）
│       │       ├── persistence/   # JPA Entity / Spring Data Repository
│       │       └── repository/    # 领域 Repository 实现
│       └── main/resources/
│           ├── application.yml
│           └── db/migration/      # Flyway 建表和种子数据
└── frontend/
    ├── Dockerfile
    ├── nginx.conf
    ├── package.json
    └── src/
        ├── app/                   # React App 入口
        ├── features/assistant/
        │   ├── api/               # API URL / 下单确认请求
        │   ├── components/        # 页面组件、RAG、Trace、订单卡片
        │   ├── hooks/             # useAssistantStream SSE Hook
        │   └── stores/            # Zustand 状态
        ├── index.css
        └── main.tsx
```

## 后端运行链路

以用户输入：

```text
我要买一件黑色卫衣
```

为例：

```text
React 前端
  -> EventSource GET /assistant/stream
  -> AssistantStreamController
  -> AssistantWorkflowService
  -> IntentAgent 调用 AI Adapter
  -> 返回 CREATE_ORDER ToolCall
  -> ToolDispatcher 校验工具白名单
  -> CreateOrderTool
  -> CreateOrderConfirmationService
  -> JPA Repository
  -> MySQL 写入 order_confirmations
  -> SSE 推送 order_confirmation_required
  -> 前端展示订单确认卡片
  -> 用户点击确认
  -> POST /assistant/order-confirmations/{id}/confirm
  -> ConfirmOrderService 事务内创建 orders / order_items
```

核心原则：

```text
AI 负责理解意图和提取参数。
ToolDispatcher 负责限制 AI 能调用哪些工具。
应用服务负责业务规则和事务。
JPA Repository 负责数据库交互。
MySQL 负责持久化。
SSE 负责把每一步实时推给前端。
```

## 路由策略

判定口径只有一份：`application/routing/QuestionSignals`（唯一关键词表，纯函数）。
`AssistantWorkflowService` 不再自带关键词，只负责按它的判定分流（连"九路 OR 组合"也在 `QuestionSignals.needsAgentWorkflow` 里）：

- 普通问答、简单数学、非业务动作：直连 AI。**但直连之前会先查一次知识库**：命中相关资料就按资料作答并在末尾列
  `**依据**`，没命中才按通用知识回答（涉及价格/时效/政策说不准就明确让用户以页面或人工客服为准）。
- 商品目录事实查询：走 RAG / `SEARCH_KNOWLEDGE`。
- 购买、下单、购物车、订单查询、退款、工单动作：走 Agent/Tool。

> 另外一条容易踩的口径：**"买"本身不决定路由**。只有"购买动作 + 能定位到具体商品"才进工作流下单；
> 只说"我想买个暖和的围脖"这种认不出商品的，走直答（先检索再让模型反问要哪一件），
> 否则工作流里没有可下单的东西，只会回一句"没能给出有效回答"。
>
> 要加一类新需求或调整关键词，只改 `QuestionSignals`（关键词也只写在那里）。**改完必须跑
> `QuestionSignalsImpactTest`**：它会把"哪条语料、哪个信号变了"逐条列出来（按语料标签比对，不受行号位移干扰）。
> 确认 diff 都是你想要的影响面后，用 `mvn -DsignalsSnapshot=true -Dtest=QuestionSignalsImpactTest test`
> 重新生成影响面快照，并把快照 diff 一起提交。详见 `PROJECT_OVERVIEW.md` §5.4.2。
> 改完必须跑后端全量测试，因为同一张表被路由、意图、规则兜底、工具与检索加权共用。

示例：

| 用户输入 | 路由 |
| --- | --- |
| `1000 最多可以买几件衣服` | 普通预算/商品推理 |
| `价格刚好699的衣服是什么` | RAG 商品目录检索 |
| `我的衣服比699便宜的有哪几种` | RAG 商品目录检索 |
| `我要买一件黑色卫衣` | Agent/Tool，下单确认 |
| `帮我查一下我的订单` | Agent/Tool，订单查询 |
| `支付成功但订单没生成怎么办` | 普通知识/售后问答或 RAG |

## Tool Calling 设计

AI 不能直接调用 Java Service 或直接写数据库。AI 只能返回受控工具调用：

```json
{
  "tool_calls": [
    {
      "tool": "CREATE_ORDER",
      "arguments": {
        "productId": "CLOTH-HOODIE-004",
        "quantity": 1
      }
    }
  ]
}
```

后端通过 `ToolDispatcher` 统一处理：

1. 校验工具是否在 AI 白名单内。
2. 从 `ToolRegistry` 找到对应 `ToolExecutor`。
3. 执行业务工具。
4. 记录工具调用 Trace。
5. 将结果写入 `WorkflowContext` 并通过 SSE 返回前端。

当前 AI 可调用工具：

- `CREATE_TICKET`
- `QUERY_ORDER`
- `UPDATE_TICKET`
- `SEARCH_KNOWLEDGE`
- `PLAN_BUDGET`
- `ADD_TO_CART`
- `VIEW_CART`
- `QUERY_ACCOUNT`（地址 / 支付方式 / 可用优惠券，脱敏只读）
- `CANCEL_ORDER`（仅未支付订单，取消后释放预留库存）
- `REQUEST_REFUND`（只落退款申请单，不动钱；同时自动建 REFUND 工单）
- `QUERY_REFUND`（查退款进度，按订单号或最近 10 条）
- `REQUEST_RETURN`（退货申请：货寄回，自动建物流工单，不动钱）
- `QUERY_RETURN`（退货进度）
- `CREATE_ORDER`（支持 `fromCart:true`：把购物车整车转成一张待确认单）

## 鉴权（2026-11-09，演示级）

后端**只信令牌里的客户**，不再信请求参数里的 `customerId`：

```bash
# 1) 换令牌（演示环境只校验客户存在，没有密码）
curl -X POST http://localhost:18080/auth/login -H "Content-Type: application/json" -d '{"customerId":"CUST-1001"}'
# → {"token":"tk_...","customerId":"CUST-1001","expiresAt":"..."}

# 2) 业务接口带令牌（无令牌一律 401）
curl -H "Authorization: Bearer tk_..." "http://localhost:18080/assistant/sessions?customerId=CUST-1001"
```

- 公开路径：`/auth/**`、`/products`、`/health`、`/actuator`、`/v3/api-docs`、`/swagger-ui`。`/admin/prompts/**` 现在也要令牌。
- **SSE 的令牌走 URL**：`EventSource` 不能自定义请求头，所以 `/assistant/stream?...&token=tk_...`。
- 会话历史**校验归属**：读别人的会话一律 404（不透露"存在但无权"）。
- 前端自动处理：`ensureToken()` 登录一次存 `localStorage`，`authorizedFetch` 自动带令牌、遇 401 换一次重试。
- 关闭方式：`AUTH_ENABLED=false`（测试就是这么跑的）。
- **边界**：这是"防越权"不是"证明你是谁" —— 没有密码/验证码，上线请接统一登录或短信验证（详见 `PROJECT_OVERVIEW.md` §5.7）。

## 交互与「不替用户猜」的边界（2026-11-11）

- **输入框**：`Enter` 发送、`Shift+Enter` 换行（中文输入法选词时不会误发）。
- **只说了品类**：例如「我要买一件夹克」→ 不替你挑，列 2~3 个候选 + 反问；该品类在目录里只有一件时直接下单。
  （判定看**目录里有几个 SKU**，不是手写词表；句子里已有完整商品名时按具体款处理。）
- **没给订单号**：例如「这单我不要了」「我要退货」→ 按动作取**最近一笔合适状态的订单**
  （取消要"已创建未支付"，退款/退货要"已支付"）；取不到才要求你给订单号。不跨状态兜底、不按商品名反查。

## 意图决策模式与评测（2026-11-11）

**由模型决定调哪个工具，代码只做兜底**（`assistant.intent.mode`，默认 `auto`）：

| 模式 | 行为 |
| --- | --- |
| `auto`（默认） | 真模型（deepseek/qwen）→ 模型优先：模型看完整工具清单自己选工具，关键词只在模型没给工具时兜底；`fallback` 规则引擎 → 沿用关键词层 |

**路由也交给模型（2026-11-11）**：以前"要不要进工作流"是关键词闸门，导致「这个能退吗」「这单我不要了」
这类自然说法根本进不来（实测 7 条口语 6 条被挡）。现在是：纯闲聊（`你好`/`谢谢`/`你是谁` 这类短句）
走直答快路径，**其余一律先问意图模型**——有工具就执行，没有就落回直答（先检索再作答）。
| `model-first` | 强制模型优先（即使 provider=fallback） |
| `legacy` | 旧的"关键词覆盖优先"，仅用于 A/B 与回滚 |

实测对比（31 条真实问法，DeepSeek）：

| 模式 | 动作正确率 |
| --- | --- |
| `legacy` | 28/31 = 90.3% |
| `model-first` | **31/31 = 100%** |

改提示词 / 换模型 / 改路由后请重跑评测：

```powershell
# 后端跑起来之后（18080 或 8080 都行）
.\scripts\intent-eval.ps1 -BaseUrl http://localhost:8080 -Label model-first -ShowMismatch
```

语料在 `backend/src/test/resources/intent-eval-corpus.tsv`（格式：`原句<TAB>期望工具,工具`，`NONE` = 不该调工具）。
**线上发现判错的真实说法，先加进语料再改代码** —— 这样回归有据可依，不用凭感觉调关键词。

> 注意：评测走真模型，有随机性；`fallback` 引擎跑这份语料不代表线上表现（它没有语义理解能力）。

## 售后审核台（2026-11-09）

页面顶部导航切到「售后审核台」即可（退款/退货 × 待审/已通过/已驳回），行内填备注、逐条通过或驳回。
后端接口（都要令牌）：

```bash
GET  /admin/after-sales/summary                       # 待审/已通过/已驳回计数
GET  /admin/after-sales/refunds?status=REQUESTED      # 也可 returns
POST /admin/after-sales/refunds/{id}/approve  {"note":"核实无误","reviewer":"alice"}
POST /admin/after-sales/returns/{id}/reject   {"note":"已超过七天无理由期限"}   # 驳回必须填原因
```

规则：只有 `REQUESTED` 能审（重复审 → 409）；驳回必须写原因（空原因 → 400，用户会看到这句话）；
通过/驳回都会把关联工单 `resolve` 并写上结论，用户侧用 `QUERY_REFUND` / `QUERY_RETURN` 就能看到结果。
**边界**：本项目不动钱 —— "通过"只代表同意退款/同意寄回；也**没有角色体系**，任何持令牌的调用方都能审核，
`reviewedBy` 仅作审计（详见 `PROJECT_OVERVIEW.md` §5.9 / §11.1）。

## 页面报"登录失败"怎么查（2026-11-10）

开发模式下前端走 Vite 代理（同源 5173 → 8080）。**代理是按前缀白名单配的**，后端新增接口前缀必须同步改
`frontend/vite.config.ts`，否则浏览器侧会以很误导的方式失败：

```powershell
# 直接打 Vite，看它有没有把请求转给后端
curl -i -X POST http://localhost:5173/auth/login -H "Content-Type: application/json" -d '{"customerId":"CUST-1001"}'
# 404（空 body）      → 代理漏配 /auth
# 200 + text/html     → 代理漏配，拿到了 SPA 的 index.html
# 200 + application/json + token → 正常
```

常见三种：

| 症状 | 原因 | 处理 |
| --- | --- | --- |
| 页面所有请求都"登录失败" | `/auth` 没进代理 | 加规则并**重启 Vite**（配置不热更） |
| 审核台报 JSON 解析错误 | `/admin` 没进代理（拿到 index.html） | 同上 |
| 接口通了但 401 | 后端开了鉴权而前端没带令牌 | 清掉 `localStorage` 里的 `ticketpilot.token` 再刷新（会自动重新登录） |

> `.env` 里的 `VITE_API_BASE_URL` 只在 **Docker 构建**时生效；`frontend/` 下没有 `.env`，dev 模式不读仓库根那份。

## 数据清理（2026-11-09）

`RetentionCleanupService` 清理"缓存性质"的数据：过期待确认单（7 天）、闲置购物车（30 天）、老会话（30 天）、
老 trace / tool_invocations（14 天）、过期令牌。**订单、工单、退款、退货、优惠券一律不删。**
定时任务默认关闭，需要时 `RETENTION_ENABLED=true`（cron 默认每天 03:30），或直接调 `runOnce()`。
详见 `PROJECT_OVERVIEW.md` §5.8。

## AI Adapter 设计

## AI Adapter 设计

后端通过 `AiClientPort` 解耦外部 AI 服务：

```text
Application Layer
  -> AiClientPort
      -> DeepSeekAiClientAdapter      # 真流式（stream=true）
      -> QwenAiClientAdapter
      -> FallbackAiClientAdapter      # 规则兜底（默认）
```

> 2026-11-04 起不再支持 Anthropic：适配器、`anthropic-java` 依赖、`ANTHROPIC_*` 配置项均已删除。

配置项位于 `backend/src/main/resources/application.yml`：

```yaml
assistant:
  ai:
    provider: ${AI_PROVIDER:fallback}
    deepseek-api-key: ${DEEPSEEK_API_KEY:}
    deepseek-model: ${DEEPSEEK_MODEL:deepseek-chat}
    deepseek-base-url: ${DEEPSEEK_BASE_URL:https://api.deepseek.com/v1/chat/completions}
    deepseek-max-tokens: ${DEEPSEEK_MAX_TOKENS:2000}
    qwen-api-key: ${QWEN_API_KEY:}
    qwen-model: ${QWEN_MODEL:qwen-plus}
    timeout: ${AI_TIMEOUT:PT30S}
```

Docker Compose 默认：

```yaml
AI_PROVIDER: ${AI_PROVIDER:-fallback}
DEEPSEEK_API_KEY: ${DEEPSEEK_API_KEY:-}
DEEPSEEK_MODEL: ${DEEPSEEK_MODEL:-deepseek-chat}
DEEPSEEK_BASE_URL: ${DEEPSEEK_BASE_URL:-https://api.deepseek.com/v1/chat/completions}
QWEN_API_KEY: ${QWEN_API_KEY:-}
QWEN_MODEL: ${QWEN_MODEL:-qwen-plus}
```

未配置真实 Key 时自动走 `FallbackAiClientAdapter` 的规则兜底，此时回答由本地 if-else 规则产出，不经过 LLM。

### 使用 DeepSeek

项目根目录 `.env`：

```env
AI_PROVIDER=deepseek
DEEPSEEK_API_KEY=sk-你的Key
DEEPSEEK_MODEL=deepseek-chat
DEEPSEEK_BASE_URL=https://api.deepseek.com/v1/chat/completions
DEEPSEEK_MAX_TOKENS=2000
```

重建后端使配置生效：

```bash
docker compose up -d --build backend
```

检查容器内配置：

```bash
docker compose exec backend printenv AI_PROVIDER DEEPSEEK_MODEL
```

**本地 IDE / `java -jar` 跑怎么办？** Spring Boot 原生不读 `.env`，所以 `application.yml` 里加了显式导入：

```yaml
spring:
  config:
    import:
      - optional:file:.env[.properties]      # 工作目录 = 仓库根
      - optional:file:../.env[.properties]   # 工作目录 = backend/（IDEA 常见）
```

因此只要 `.env` 在仓库根目录，本地启动也能读到 `AI_PROVIDER` / `DEEPSEEK_API_KEY`，密钥不必进仓库。
判断是否真的调了模型：回答是"一句话结论 + 引用来源"且耗时 4~15s（规则引擎约 0.3s，会原样回显片段）。
若日志出现 `DeepSeek API call failed`，说明 key 或网络有问题，此时会静默退回规则兜底。

### 用 IDEA 启动后端（本机开发）

**不需要改任何 IDEA 配置**：IDEA 的 Spring Boot 运行配置没设工作目录时默认用模块根 `backend/`，
`application.yml` 里的 `optional:file:../.env[.properties]` 正好命中仓库根的 `.env`（已实测：`backend/` 与仓库根两种目录都能生效）。

启动后看日志里的 `[AI]` 自检三行，就知道有没有真的接上大模型：

```
[AI] 生效适配器 = DeepSeekAiClientAdapter，配置 provider = deepseek，DeepSeek key = 已配置(长度 35)
[AI] 工作目录 = ...\TicketPilot\backend，命中的 .env = ...\TicketPilot\backend\..\.env
[AI] 已接入真实大模型，回答耗时会明显长于规则兜底（每次问答至少 2 次调用）。
```

若显示 `FallbackAiClientAdapter`，说明在走规则兜底：按告警里的三步排查（`.env` 内容 / 命中的路径 / 是否重新 Build）。
注意 IDEA 跑的是 `target/classes`，改了 `application.yml` 必须重新 Build 才生效。

## RAG / 知识库

知识库使用 MySQL 表保存文档和 chunk：

- `knowledge_documents`
- `knowledge_chunks`

检索是混合的（`HybridKnowledgeSearchRepository`）：

- `QueryRewriter`：中文数字归一（七百→700）+ 同义词扩展（外套→大衣/夹克/风衣…）
- `EmbeddingPort` / `HashingEmbeddingAdapter`：本地确定性向量（TF-IDF 特征哈希，512 维），余弦相似度
- `Bm25SearchAdapter`：命中率 + 短语 + 汇总片段 + 数值邻近（±2%）
- 融合：`finalScore = 0.7×饱和(BM25) + 0.3×余弦`（绝对量级，可直接当相关性门槛）

以前这里是 `ChromaClient` 桩（恒返回空列表、打分实际等于 BM25）。2026-11-04 起删掉了它和 compose 里的
`chromadb` 服务：本地 34 个片段的规模不需要向量库，而"看起来接了、其实没接"比不接更容易误导。

**要真语义召回**（同义不用写词表）：

```bash
EMBEDDING_PROVIDER=openai-compatible
EMBEDDING_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode/v1
EMBEDDING_MODEL=text-embedding-v3
EMBEDDING_API_KEY=sk-...        # 不填则复用 QWEN_API_KEY
EMBEDDING_DIMENSIONS=1024
```

语料会在启动后首次检索时批量预嵌入（34 片段 ≈ 4 批、约 200ms），之后每问一次往返；
服务不可用会自动整体降级回本地哈希向量，不会让回答失败。

检索质量有三条约定（详见 `PROJECT_OVERVIEW.md` §6.5）：

- **汇总型问题**（预算/最多/几件/搭配/哪几种）会给"整张价格表"片段加权，否则模型只看得到少数单品、算出错误结论；
- **片段正文不再截断**（上限 2600 字；60 件商品的整张价格表 1917 字，截断会让模型答"表不全"）；
- **`RetrievalEvaluationTest` 是检索回归防线**：语料按迁移生成，断言"某问法必须召回某片段"（含 60 件商品逐一按名字召回），改检索算法时会立刻报错。

商品目录知识在 Flyway 迁移中写入：

- `V5__seed_clothing_catalog_knowledge.sql`（初版 10 件：单品片段 + 整张价格表）
- `V7__clean_catalog_chunk_filler.sql`（清理种子数据里为凑关键词加的「价格刚好X元」填充语）
- `V8__expand_clothing_catalog_seed.sql`（扩到 30 件：上装/下装/外套/裙装/鞋/包配饰，¥99 ~ ¥1299）
- `V10__expand_clothing_catalog_to_60.sql`（再扩到 **60 件**：新增童装/家居服/运动/靴鞋/配饰等品类，¥99 ~ ¥1299）
- `V11__cart_account_and_after_sales.sql`（购物车 / 优惠券 / 退款申请三张表，含 CUST-1001 演示券）
- `V12__auth_tokens_and_returns.sql`（登录令牌 + 退货申请；退货单按订单号唯一）
- `V13__after_sales_review.sql`（退款/退货申请单的审核字段：工单 id、审核人、备注、时间）

> 加商品要同时改三处：`products`（+库存）、汇总价格表片段、`QuestionSignals` 的商品名表；
> 漏改任意一处会出现"查得到但下不了单"或"预算少算商品"。`SeededProductCatalogConsistencyTest` 会盯着这件事。

可以回答：

```text
价格刚好699的衣服是什么？
我的衣服比699便宜的有哪几种？
黑色连帽卫衣多少钱？
灰色拉链连帽卫衣多少钱？
驼色双面呢大衣和羊毛混纺大衣哪个贵？
1000 元最多可以买几件衣服，衣服不能重复？
```

## 数据库设计

数据库迁移位于：

```text
backend/src/main/resources/db/migration/
```

主要迁移：

| 文件 | 说明 |
| --- | --- |
| `V1__init_core_schema.sql` | 工单、订单、知识库、Trace、Tool 调用基础表 |
| `V2__seed_demo_data.sql` | 演示订单、客服人员、支付/订单知识库 |
| `V3__add_safe_order_creation.sql` | 商品、库存、地址、支付方式、订单确认表 |
| `V4__seed_demo_order_items.sql` | 演示订单明细 |
| `V5__seed_clothing_catalog_knowledge.sql` | 服装商品目录 RAG 数据（10 件） |
| `V7__clean_catalog_chunk_filler.sql` | 清理目录片段里的「价格刚好X元」填充语 |
| `V8__expand_clothing_catalog_seed.sql` | 服装目录扩到 30 件（新品 + 库存 + 片段 + 整张价格表） |
| `V10__expand_clothing_catalog_to_60.sql` | 服装目录扩到 **60 件**（新增品类 + 库存 + 片段 + 重写整张价格表） |
| `V11__cart_account_and_after_sales.sql` | 购物车 / 优惠券 / 退款申请三张表（售后四类工具的数据底座） |
| `V12__auth_tokens_and_returns.sql` | 登录令牌 + 退货申请（鉴权与退货流程的数据底座） |
| `V13__after_sales_review.sql` | 售后审核字段（审核台的数据底座） |

核心表：

- `orders`
- `order_items`
- `products`
- `product_inventory`
- `customer_addresses`
- `customer_payment_methods`
- `order_confirmations`
- `tickets`
- `knowledge_documents`
- `knowledge_chunks`
- `tool_invocations`

预留表（已建表、当前无 Java 读写）：`workflow_traces`、`workflow_trace_steps`、`assistant_sessions`、`assistant_messages`、`ticket_events`。Trace 目前仅随请求驻留内存。

## SSE 事件

后端 `/assistant/stream` 返回 `text/event-stream`，并设置防缓冲响应头：

```text
Cache-Control: no-cache, no-transform
X-Accel-Buffering: no
Content-Type: text/event-stream
```

主要事件：

- `workflow_started`
- `agent_started`
- `agent_completed`
- `tool_call`
- `tool_result`
- `knowledge_sources`
- `order_confirmation_required`
- `order_created`
- `trace`
- `final`

`final` 的粒度取决于 AI 适配器：DeepSeek 是**逐 token**（上游 `stream=true`），
`fallback` / `qwen` 是整段返回后**按句**（每片 90ms）。两者前端渲染方式一致：
按 `delta` 累加，收到 `done=true` 的帧（带 `fullText`）停止打字光标。
- `error`

前端通过 `EventSource` 订阅并实时更新：

- AI 回答区
- Agent 工作流卡片
- Tool 调用卡片
- RAG Sources
- Workflow Trace
- 订单确认卡片

## Docker 部署

### 一键启动

```bash
cd ai-ticket-assistant
docker compose up -d --build
```

服务端口：

| 服务 | 地址 |
| --- | --- |
| Frontend | http://localhost:15173 |
| Backend | http://localhost:18080 |
| Swagger UI | http://localhost:18080/swagger-ui.html |
| MySQL | localhost:13306 |


### 只重建后端

```bash
docker compose up -d --build backend
```

### 只重建前端

```bash
docker compose up -d --build frontend
```

### 查看服务状态

```bash
docker compose ps
```

### 查看日志

```bash
docker compose logs -f backend
docker compose logs -f frontend
```

## 本地开发

### 后端

```bash
cd backend
./mvnw -s settings.xml test
./mvnw -s settings.xml spring-boot:run
```

**启动慢怎么办（实测数据，2026-11-05）**：`spring-boot:run` 每次冷启动 **23.5s** 才端口可连
（其中 Maven 自己 ~17s，Spring 启动 6.3s）；换成"先打包、再 `java -jar`"只要 **9~10s**。
而这段时间里浏览器打开站点，所有接口都连不上 → 面板空白/报错，观感就是"网站很慢"。

用仓库自带脚本可以一次搞定（自动打包、等**两端都真的能响应**再打开浏览器）：

```powershell
.\scripts\dev-up.ps1            # 起后端(jar) + 前端(vite)，就绪后自动开浏览器
.\scripts\dev-up.ps1 -Rebuild   # 改了 Java 代码后强制重新打包
.\scripts\dev-down.ps1          # 停掉脚本起的两端（不动你自己开的窗口）
```

实测：后端就绪 9.4s、前端 1s，脚本从执行到浏览器打开约 11~15s；`dev-up` 输出里会打印各自的就绪耗时。

> 顺手记一笔**不用重启**的情况：改前端走 Vite HMR；改提示词靠热加载（见下一节）；
> 只有改 Java 代码才需要重启。另外 `spring.main.lazy-initialization`、`-XX:TieredStopAtLevel=1`、
> Spring AOT、关掉 springdoc 都实测过，收益 0~2s 且不稳定，所以没有启用（数据见 `PROJECT_OVERVIEW.md` §12.3）。

### 调提示词（不用重启）

提示词在 `backend/src/main/resources/prompts/`，每个 ID 两个文件：`<id>.system.md` + `<id>.user.md`。

```bash
# 让后端直接读源码目录（跑的是 target/classes 时也能即时生效）
PROMPTS_DIR=src/main/resources/prompts ./mvnw -s settings.xml spring-boot:run
```

- 改完保存即生效（默认 1s 内自动发现，`PROMPTS_CHECK_INTERVAL` 可调）；
- `curl localhost:8080/admin/prompts` 看实际读取路径；`curl -X POST localhost:8080/admin/prompts/reload` 立即重载；
- 文件被删/写空时**保留上一版**并记一条 error，运行中的对话不受影响；启动期缺文件仍会直接启动失败；
- 打包成 jar 后 classpath 资源没有文件路径，热加载自动失效 —— 部署要么挂 `PROMPTS_DIR`，要么接受"改提示词需重新发布"。

默认后端端口：

```text
8080
```

如果改用**宿主机自带的 MySQL**（默认监听 `3306`），需要覆盖数据源；用 Docker 里的 MySQL 则**无需配置**（`application.yml` 默认已指向 `localhost:13306`）：

```bash
export SPRING_DATASOURCE_URL='jdbc:mysql://localhost:3306/ai_ticket_assistant?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC'
export SPRING_DATASOURCE_USERNAME=app
export SPRING_DATASOURCE_PASSWORD=app
```

> 注意两个库名相同但数据不通：Docker MySQL 在宿主机上是 **13306**（容器内才是 3306），本机 MySQL 是 **3306**。
> 判断后端到底连了哪个库，看端口即可：`Get-NetTCPConnection -State Established | ? RemotePort -in 3306,13306`。
> ⚠ **时区设计（别改坏）**：MySQL 全局时区是 `+08:00`，所以 Navicat 等人工会话 `SELECT` 出来**直接就是北京时间**。
> 但应用连接的 JDBC URL 必须带 `connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true`
> 把它自己的会话钉成 UTC —— Hibernate 6 会把 `Instant` 归一化成 UTC 墙钟时间再绑定，会话不是 UTC 就会整体偏 −8 小时。
> 删这个参数 → 偏 +8h；改成 `Asia/Shanghai` → 偏 −8h。改完必须重启后端。详见 `PROJECT_OVERVIEW.md` §6.4。

### 前端

```bash
cd frontend
npm install
npm run dev
```

本地 Vite 默认端口：

```text
5173
```

Docker 部署的前端端口：

```text
15173
```

## 常用测试命令

### SSE 事件流测试

```bash
curl -N "http://localhost:18080/assistant/stream?message=我的衣服比699便宜的有哪几种，我预算有限&customerId=CUST-1001"
```

### RAG 检索测试

```bash
curl "http://localhost:18080/knowledge/search?query=价格刚好699的衣服是什么&limit=5"
```

### 商品接口

```bash
curl "http://localhost:18080/products"
```

### 后端测试

```bash
cd backend
./mvnw -s settings.xml test
```

全量约 20~30 秒，其中 `AssistantSmokeTest`、`SemanticEmbeddingWiringTest`、`PromptHotReloadWiringTest` 是三个 `@SpringBootTest`（H2 内存库 + 规则兜底 AI，不联网、不需要 MySQL/Docker）：
它把整个 Spring 容器拉起来，断言 Agent / 工具白名单 / 提示词 / 历史预算 Bean 都装配成功，并走真实 HTTP + SSE 跑通
「安全下单（只生成待确认单，不建订单）」「知识检索」「闲聊直答」「多轮记忆落库」四条链路。

```bash
# 只想确认"容器还能起来"
cd backend
./mvnw -s settings.xml test -Dtest=AssistantSmokeTest
```

> 它证明的是**装配与链路通**，不覆盖 MySQL 专有行为（时区、REGEXP、真实 schema 漂移）—— 那些仍靠真机启动验证。
> 改 Bean/构造器签名、SSE 事件、路由或工具白名单时，务必跑一遍。

### 前端测试与构建

```bash
cd frontend
npm test
npm run build
```

## 示例问题

普通问答 / 商品目录（V10 后共 60 件，¥99 ~ ¥1299）：

```text
价格刚好699的衣服是什么
我的衣服比699便宜的有哪几种，我预算有限
黑色连帽卫衣多少钱
灰色拉链连帽卫衣多少钱
白色厚底运动鞋和藏青帆布鞋哪个便宜
1000最多可以买几件衣服
1000元最多可以买几件衣服，衣服不能重复
```

业务动作：

```text
我要买一件黑色卫衣
我要买一件驼色双面呢大衣
帮我查一下我的订单
查询订单 ORD-1001 的处理状态
帮我创建支付失败工单
```

售后知识：

```text
支付成功但订单没生成怎么办
怎么申请退款
订单状态一般有哪些
```

## 前端页面能力

- 商品目录展示
- 输入框发送 SSE 请求
- AI 回答 Markdown 渲染
- 输入框：开始为空、发送后立即清空（回答区回显「你：…」，空白输入不可发送）
- Agent 工作流状态展示
- Tool Calling 结果展示
- RAG Sources 展示
- Workflow Trace 流式追加
- 订单确认卡片
- 工单状态卡片
- 会话短号显示 + 「新会话」按钮（切换上下文）

## 已知限制

- 当前未接入鉴权，`customerId` 由请求参数传入，适用于本地演示环境。
- 向量那一路**默认是本地 TF-IDF 特征哈希，不是语义模型**：同义（外套↔大衣）靠同义词表、口语数字（七百↔699）靠数字归一 + 数值邻近。设 `EMBEDDING_PROVIDER=openai-compatible` 可切真语义模型（任何 OpenAI 兼容 `/embeddings`），但需要 embedding key（DeepSeek 无 embeddings 接口），本仓库环境未做真实厂商联调。
- 直答路径的前置检索是**单轮、无工具**的：它能补资料，但接不上多跳问题（"先查订单再比价"要走工作流）；相关性门槛 0.05 是按当前语料标定的经验值。
- **只有 DeepSeek 是真流式**（`stream=true`，逐 token 推送）；`fallback` / `qwen` 仍整段返回后按句切片推送，首字延迟等于整段生成时间。
- 历史裁剪按"字符数"估算成本（中文 1 字 ≈ 1 token 量级），不是精确 token 计数；对英文/代码密度高的会话会偏保守。
- 提示词热加载**只在"文件系统上的 classpath 或外部目录"下有效**：打成 jar 后（本项目的 Docker 镜像就是 `java -jar`）classpath 资源没有文件路径，
  **容器内改提示词需要重建镜像**。要改就挂 `PROMPTS_DIR`（compose 加一行 volume），本轮**有意不做**，理由与触发条件见 `PROJECT_OVERVIEW.md` §11.1 第 8 条。
- `/admin/prompts/**` 未鉴权（`GET` 会回显服务端绝对路径）—— 与下面的鉴权限制同源，属演示环境可接受项；要对外暴露前先补第 1 条的鉴权。
- 测试以单元测试为主（后端 28 个测试类 / 182 用例 + 前端 23 用例），其中 3 个 `@SpringBootTest`（冒烟 + 语义向量开关 + 提示词热加载装配）跑在 H2 上；
  **MySQL 专有行为仍需真机验证**，暂无 Testcontainers 集成测试。

## 注意事项

- AI 不直接写数据库，真实业务动作必须通过 Tool 和应用服务。
- `CREATE_ORDER`（支持 `fromCart:true`：把购物车整车转成一张待确认单） 只创建待确认订单，不自动扣款；一句话里的多件商品进同一张确认单（`order_confirmation_items` 明细表），确认一次整单下单。
- 用户点击确认后才会创建真实订单。
- Docker 前端使用 Nginx 静态部署，构建时通过 `VITE_API_BASE_URL=http://localhost:18080` 指向后端。
- 前端 Dockerfile 使用 `npm ci` 和 `npmmirror`，并通过 `.dockerignore` 排除 `node_modules`、`dist` 等目录以加快构建。
- 检索完全在应用内完成（MySQL 知识片段 + 本地向量/BM25 融合），不需要 ChromaDB 之类的向量库服务。
