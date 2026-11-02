# 企业级 Multi-Agent AI 智能工单协同系统（AI Ticket Assistant）

AI Ticket Assistant 是一个面向企业客服/工单/订单场景的 AI Native 示例项目。项目重点不在“训练模型”，而在后端架构与业务集成：通过 Spring Boot 后端编排 Intent Agent、Tool Calling、RAG 检索、订单确认、JPA 持久化和 SSE 分阶段事件推送，前端以 React 展示多 Agent 工作流、工具调用、RAG 来源和订单确认卡片。

## 核心功能

- **普通问答 / 商品推理**：普通咨询、简单计算、商品目录问答可直接回答或走 RAG 检索。
- **商品目录 RAG**：服装商品编号、名称、价格已写入知识库，可回答“价格刚好 699 的衣服是什么”“比 699 便宜的衣服有哪些”。
- **多 Agent 并行编排**：Intent Agent 识别意图，Order Agent 与 Knowledge Agent 通过 `Mono.zip` 并行分析，最后统一组装回答。
- **Tool Calling 安全边界**：AI 只返回工具调用 JSON，真实业务动作由后端 ToolDispatcher 和领域服务执行。
- **安全下单确认**：购买类语义先创建待确认订单，不自动扣款；用户确认后才创建真实订单。
- **工单与订单查询**：支持创建/更新工单、查询用户订单、结合知识库解释订单/支付问题。
- **SSE 分阶段事件推送**：后端以 Server-Sent Events 推送 workflow、agent、tool、RAG source、final answer、trace。
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

- `AiClientPort` 统一 AI 调用端口
- DeepSeek Adapter
- Qwen Adapter
- Anthropic Claude Adapter
- Fallback Adapter
- Intent Agent
- Order Agent
- Knowledge Agent
- ToolDispatcher
- RAG：BM25 检索（Chroma 向量适配层已预留，embedding 管线待补）

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
- ChromaDB 容器

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
│       │       ├── ai/            # Qwen / Anthropic / Fallback AI Adapter
│       │       ├── knowledge/     # BM25、Chroma、Hybrid Search
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

当前工作流在 `AssistantWorkflowService` 中先做极简路由：

- 普通问答、简单数学、非业务动作：直连 AI。
- 商品目录事实查询：走 RAG / `SEARCH_KNOWLEDGE`。
- 购买、下单、购物车、订单查询、退款、工单动作：走 Agent/Tool。

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
- `CREATE_ORDER`

## AI Adapter 设计

后端通过 `AiClientPort` 解耦外部 AI 服务：

```text
Application Layer
  -> AiClientPort
      -> DeepSeekAiClientAdapter
      -> QwenAiClientAdapter
      -> AnthropicClaudeClientAdapter
      -> FallbackAiClientAdapter
```

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
    anthropic-api-key: ${ANTHROPIC_API_KEY:}
    model: ${ANTHROPIC_MODEL:claude-opus-4-7}
    timeout: ${ANTHROPIC_TIMEOUT:PT30S}
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

## RAG / 知识库

知识库使用 MySQL 表保存文档和 chunk：

- `knowledge_documents`
- `knowledge_chunks`

本地检索使用 BM25：

- `Bm25SearchAdapter`
- `HybridKnowledgeSearchRepository`

ChromaDB 为预留适配层：`ChromaClient` 与 `docker-compose.yml` 中的 `chromadb` 服务保留，embedding 管线未实现，检索打分当前为纯 BM25。

商品目录知识在 Flyway 迁移中写入：

- `V5__seed_clothing_catalog_knowledge.sql`

可以回答：

```text
价格刚好699的衣服是什么？
我的衣服比699便宜的有哪几种？
黑色连帽卫衣多少钱？
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
| `V5__seed_clothing_catalog_knowledge.sql` | 服装商品目录 RAG 数据 |

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
| ChromaDB | http://localhost:8000 |

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

默认后端端口：

```text
8080
```

如果使用 Docker MySQL，需要配置：

```bash
export SPRING_DATASOURCE_URL='jdbc:mysql://localhost:13306/ai_ticket_assistant?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC'
export SPRING_DATASOURCE_USERNAME=app
export SPRING_DATASOURCE_PASSWORD=app
```

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

### 前端测试与构建

```bash
cd frontend
npm test
npm run build
```

## 示例问题

普通问答 / 商品目录：

```text
价格刚好699的衣服是什么
我的衣服比699便宜的有哪几种，我预算有限
黑色连帽卫衣多少钱
1000最多可以买几件衣服
```

业务动作：

```text
我要买一件黑色卫衣
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
- Agent 工作流状态展示
- Tool Calling 结果展示
- RAG Sources 展示
- Workflow Trace 流式追加
- 订单确认卡片
- 工单状态卡片

## 已知限制

- 当前未接入鉴权，`customerId` 由请求参数传入，适用于本地演示环境。
- 向量检索未启用：Chroma 仅有心跳探活，embedding / upsert / query 管线待补，RAG 实际为 BM25。
- SSE 为分阶段事件推送，不是 token 级流式；AI 调用同步返回后按句切片推送。
- `workflow_traces`、`workflow_trace_steps`、`assistant_sessions`、`assistant_messages`、`ticket_events` 为预留表，尚未落库。
- 测试以单元测试为主，暂无 `@SpringBootTest` 与 Testcontainers 集成测试。

## 注意事项

- AI 不直接写数据库，真实业务动作必须通过 Tool 和应用服务。
- `CREATE_ORDER` 只创建待确认订单，不自动扣款。
- 用户点击确认后才会创建真实订单。
- Docker 前端使用 Nginx 静态部署，构建时通过 `VITE_API_BASE_URL=http://localhost:18080` 指向后端。
- 前端 Dockerfile 使用 `npm ci` 和 `npmmirror`，并通过 `.dockerignore` 排除 `node_modules`、`dist` 等目录以加快构建。
- ChromaDB 为预留适配层，未启用向量检索；当前 RAG 走 MySQL 知识库 + BM25。
