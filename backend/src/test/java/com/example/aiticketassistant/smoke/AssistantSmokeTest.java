package com.example.aiticketassistant.smoke;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.aiticketassistant.application.agent.AiClientPort;
import com.example.aiticketassistant.application.agent.IntentAgent;
import com.example.aiticketassistant.application.agent.KnowledgeAgent;
import com.example.aiticketassistant.application.agent.OrderAgent;
import com.example.aiticketassistant.application.assembler.AssistantResponseAssembler;
import com.example.aiticketassistant.application.assistant.AssistantWorkflowService;
import com.example.aiticketassistant.application.conversation.ConversationHistory;
import com.example.aiticketassistant.application.conversation.ConversationMemory;
import com.example.aiticketassistant.application.prompt.PromptCatalog;
import com.example.aiticketassistant.application.prompt.PromptIds;
import com.example.aiticketassistant.application.tool.ToolDispatcher;
import com.example.aiticketassistant.application.tool.ToolMetadata;
import com.example.aiticketassistant.application.tool.ToolNames;
import com.example.aiticketassistant.application.tool.ToolRegistry;
import com.example.aiticketassistant.infrastructure.ai.FallbackAiClientAdapter;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * 真容器冒烟：**以前没有它，Bean 装配错误只能靠人工启动才发现**（见 PROJECT_OVERVIEW §11.3）。
 *
 * <p>两层覆盖：
 * <ol>
 *   <li><b>装配层</b>：整个 Spring 上下文能起来，而且该有的 Agent / 工具 / 端口都在、工具白名单不多不少；</li>
 *   <li><b>端到端</b>：走真实的 HTTP + SSE 通道（{@code /assistant/stream}），
 *       分别跑通「安全下单」「知识检索」「闲聊直答」三条路由，断言 SSE 事件序列与落库结果。</li>
 * </ol>
 *
 * <p>不依赖任何外部进程：H2 内存库（{@code application-test.yml} 的 profile）+ 规则兜底 AI
 * （{@code assistant.ai.provider=fallback}，不联网、不打真模型、结果确定）。
 * 因此它验证的是**装配与链路**，不覆盖 MySQL 专有行为（时区、REGEXP、真实 schema 漂移）。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                // 只用规则兜底：不读 .env 里的 key，也不联网。内联属性优先级高于配置文件，压得住 .env 的 AI_PROVIDER=deepseek。
                "assistant.ai.provider=fallback",
                // application-test.yml 里是 ddl-auto=none（迁移是 MySQL 专有语法，H2 跑不了）；
                // 冒烟测试让 Hibernate 直接建表，测完即删。
                "spring.jpa.hibernate.ddl-auto=create-drop",
                // 主配置把方言钉成了 MySQLDialect，这里必须换回 H2 方言，否则生成的是 MySQL 语法。
                // （Hibernate 会为此打一行 "does not need to be specified explicitly" 的 deprecation 警告，属预期噪音）
                "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
                // .env 可能开着真语义向量（EMBEDDING_PROVIDER=openai-compatible）：冒烟测试必须离线，
                // 否则检索第一次 fit() 就会去连 DashScope（慢、还可能因没网而闪断）。内联属性优先级最高。
                "assistant.retrieval.embedding-provider=hash"
        })
@AutoConfigureWebTestClient
@ActiveProfiles("test")
class AssistantSmokeTest {
    private static final Logger log = LoggerFactory.getLogger(AssistantSmokeTest.class);
    private static final String CUSTOMER_ID = "CUST-1001";
    private static final String HOODIE = "CLOTH-HOODIE-004";
    private static final String BAG = "CLOTH-BAG-029";
    private static final String BOOTS = "CLOTH-BOOTS-055";
    private static final String SHOES = "CLOTH-CANVAS-028";
    private static final String CARDIGAN = "CLOTH-CARDIGAN-017";
    private static final String CANVAS = "CLOTH-CANVAS-028";

    @Autowired
    private ApplicationContext context;
    @Autowired
    private WebTestClient webTestClient;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ObjectMapper objectMapper;

    private WebTestClient client;

    @BeforeEach
    void setUp() {
        // 每个用例自己准备数据：先清掉上一轮写进去的东西（含异步落库的 trace）
        // 明细表要在主表之前删（外键），否则 H2 会拒绝
        for (String table : List.of("cart_items", "refund_requests", "return_requests", "customer_coupons", "auth_tokens",
                "order_confirmation_items", "order_confirmations", "order_items", "orders",
                "products", "product_inventory",
                "customer_addresses", "customer_payment_methods", "knowledge_chunks", "assistant_messages",
                "assistant_sessions", "workflow_trace_steps", "workflow_traces", "tool_invocations",
                "ticket_events", "tickets", "ticket_messages")) {
            jdbc.update("DELETE FROM " + table);
        }
        client = webTestClient.mutate().responseTimeout(Duration.ofSeconds(30)).build();
    }

    // ===================== ① 装配层 =====================

    @Test
    void contextWiresAgentsToolsAndPorts() {
        // AI 端口必须是规则兜底：万一 .env 的 provider 覆盖生效，测试就会去联网打真模型
        assertThat(context.getBean(AiClientPort.class))
                .as("冒烟测试必须跑在规则兜底适配器上，不能联网")
                .isInstanceOf(FallbackAiClientAdapter.class);

        // 三个 Agent + 编排器 + 工具闸门 + 组装器（少一个 Bean 这里就会炸）
        assertThat(context.getBean(IntentAgent.class)).isNotNull();
        assertThat(context.getBean(OrderAgent.class)).isNotNull();
        assertThat(context.getBean(KnowledgeAgent.class)).isNotNull();
        assertThat(context.getBean(AssistantWorkflowService.class)).isNotNull();
        assertThat(context.getBean(ToolDispatcher.class)).isNotNull();
        assertThat(context.getBean(AssistantResponseAssembler.class)).isNotNull();
        assertThat(context.getBean(ConversationMemory.class)).isNotNull();
        assertThat(context.getBean(ConversationHistory.Budget.class)).isEqualTo(ConversationHistory.Budget.defaults());

        // 工具白名单：不多不少正好这些（模型只能调用它们）
        assertThat(context.getBean(ToolRegistry.class).metadata())
                .extracting(ToolMetadata::name)
                .containsExactlyInAnyOrder(
                        ToolNames.CREATE_TICKET, ToolNames.QUERY_ORDER, ToolNames.UPDATE_TICKET,
                        ToolNames.SEARCH_KNOWLEDGE, ToolNames.CREATE_ORDER, ToolNames.PLAN_BUDGET,
                        ToolNames.ADD_TO_CART, ToolNames.VIEW_CART, ToolNames.QUERY_ACCOUNT,
                        ToolNames.CANCEL_ORDER, ToolNames.REQUEST_REFUND, ToolNames.QUERY_REFUND,
                        ToolNames.REQUEST_RETURN, ToolNames.QUERY_RETURN);

        // 提示词模板：4 个 ID 全部能取到（缺文件时 ClasspathPromptCatalog 会让启动失败，这里再确认一遍）
        PromptCatalog prompts = context.getBean(PromptCatalog.class);
        for (String id : PromptIds.ALL) {
            assertThat(prompts.get(id).system()).as("提示词 %s 的 system", id).isNotBlank();
            assertThat(prompts.get(id).userTemplate()).as("提示词 %s 的 user 模板", id).isNotBlank();
        }
    }

    @Test
    void unknownPathReturns404NotServerError() {
        // WebFlux 对未匹配路径抛 NoResourceFoundException，曾被兜底成 500 + error 堆栈
        client.get().uri("/knowledge/health").exchange().expectStatus().isNotFound();
        client.get().uri("/no-such-endpoint").exchange().expectStatus().isNotFound();
    }

    @Test
    void promptAdminEndpointReportsSourcesAndReloadsWithoutRestart() {
        // GET /admin/prompts：每个提示词都要能报出"读的是哪个文件"（排障第一条信息）
        List<JsonNode> infos = client.get().uri("/admin/prompts")
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(JsonNode.class)
                .returnResult().getResponseBody();
        assertThat(infos).hasSize(PromptIds.ALL.size());
        assertThat(infos).allSatisfy(info -> {
            assertThat(info.path("id").asText()).isNotBlank();
            assertThat(info.path("systemFile").asText()).isNotBlank();
        });

        // POST /admin/prompts/reload：不重启也能重新读一遍（这里只验通道 + 幂等成功）
        JsonNode result = client.post().uri("/admin/prompts/reload")
                .exchange()
                .expectStatus().isOk()
                .expectBody(JsonNode.class)
                .returnResult().getResponseBody();
        assertThat(result.path("success").asBoolean()).isTrue();
        assertThat(result.path("templates").asInt()).isEqualTo(PromptIds.ALL.size());
    }

    // ===================== ② 端到端：安全下单 =====================

    @Test
    void purchaseRequestCreatesPendingConfirmationEndToEnd() {
        seedSellableProduct();

        List<JsonNode> events = stream("我要买一件黑色卫衣", "smoke-buy-1");

        assertThat(eventTypes(events)).containsSubsequence(
                "workflow_started", "agent_started", "agent_completed", "tool_call", "tool_result",
                "order_confirmation_required", "final");

        JsonNode toolCall = firstPayload(events, "tool_call").path("toolCalls").get(0);
        assertThat(toolCall.path("tool").asText()).isEqualTo(ToolNames.CREATE_ORDER);
        assertThat(toolCall.path("arguments").path("items").get(0).path("productId").asText()).isEqualTo(HOODIE);

        JsonNode confirmation = firstPayload(events, "order_confirmation_required").path("confirmation");
        assertThat(confirmation.path("confirmationId").asText()).startsWith("OCF-");
        assertThat(confirmation.path("totalAmount").asDouble()).isEqualTo(259.00);
        assertThat(confirmation.path("totalQuantity").asInt()).isEqualTo(1);
        assertThat(confirmation.path("items")).hasSize(1);
        assertThat(confirmation.path("items").get(0).path("quantity").asInt()).isEqualTo(1);

        // 关键业务约束：只生成「待确认单」，绝不自动建单/扣款
        assertThat(count("order_confirmations")).isEqualTo(1);
        assertThat(count("orders")).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM order_confirmations", String.class)).isEqualTo("PENDING");
    }

    /**
     * 一句话买两件：**一张确认单、两行明细，确认一次整单下单**。
     *
     * <p>2026-11-05 用户反馈的真实故障（「买米色帆布双肩包和藏青帆布鞋」只下成了后者）的端到端回归。
     * 修完后确认单升级为一单多明细（V9 迁移），所以这里连"确认 → 建单 → 两条 order_items"一起跑通。
     */
    @Test
    void multiProductPurchaseCreatesOneConfirmationWithAllItems() {
        seedSellableProduct(BAG, "米色帆布双肩包", "299.00");
        seedSellableProduct(CANVAS, "藏青帆布鞋", "199.00");
        seedCustomerDefaults();

        List<JsonNode> events = stream("我想要买米色帆布双肩包和藏青帆布鞋，帮我下单", "smoke-buy-2");

        // 一次工具调用，带 items 数组
        JsonNode arguments = firstPayload(events, "tool_call").path("toolCalls").get(0).path("arguments");
        assertThat(arguments.path("items")).as("两件商品都在同一个 CREATE_ORDER 里").hasSize(2);

        // 一张卡片，多行明细
        List<JsonNode> confirmations = eventPayloads(events, "order_confirmation_required");
        assertThat(confirmations).as("只有一张待确认卡片").hasSize(1);
        JsonNode confirmation = confirmations.get(0).path("confirmation");
        assertThat(confirmation.path("items")).extracting(node -> node.path("productName").asText())
                .containsExactly("米色帆布双肩包", "藏青帆布鞋");
        assertThat(confirmation.path("items")).extracting(node -> node.path("lineAmount").asDouble())
                .containsExactly(299.00, 199.00);
        assertThat(confirmation.path("totalQuantity").asInt()).isEqualTo(2);
        assertThat(confirmation.path("totalAmount").asDouble()).isEqualTo(498.00);

        assertThat(count("order_confirmations")).as("主表 1 行").isEqualTo(1);
        assertThat(count("order_confirmation_items")).as("明细 2 行").isEqualTo(2);
        assertThat(count("orders")).isZero();

        String answer = finalText(events);
        assertThat(answer).as("回答要交代清楚两件与合计").contains("米色帆布双肩包").contains("藏青帆布鞋");

        // ===== 确认接口：一次确认 → 一张订单 + 两条明细 =====
        String confirmationId = confirmation.path("confirmationId").asText();
        JsonNode created = post("/assistant/order-confirmations/" + confirmationId + "/confirm",
                objectMapper.createObjectNode().put("customerId", CUSTOMER_ID).put("sessionId", "smoke-buy-2"));

        assertThat(created.path("orderNo").asText()).startsWith("ORD-AI-");
        assertThat(created.path("amount").asDouble()).isEqualTo(498.00);
        assertThat(created.path("items")).as("下单结果也带明细").hasSize(2);
        assertThat(count("orders")).isEqualTo(1);
        assertThat(count("order_items")).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM order_confirmations WHERE id = ?", String.class, confirmationId))
                .isEqualTo("CONFIRMED");
        assertThat(jdbc.queryForObject(
                "SELECT SUM(reserved_stock) FROM product_inventory WHERE product_id IN (?, ?)", Integer.class, BAG, CANVAS))
                .as("两件商品的库存各被预留 1 件").isEqualTo(2);
    }

    /**
     * 有购买意图但认不出商品 → **走直答**，让模型（或兜底文案）自己说话。
     *
     * <p>2026-11-05 的真实观感问题：「我想买个暖和的围脖」进了工作流，但没有任何工具可调，
     * 正文只剩空文案，用户看到"没能给出有效回答"。这类问法没有可下单的东西，不该进工作流。
     */
    @Test
    void categoryOnlyPurchaseListsCandidatesInsteadOfPickingOne() {
        // 2026-11-11：「我要买一件羽绒服」以前会替用户挑一件（黑色短款羽绒服 ¥899）直接生成待确认单。
        // 现在：目录里该品类有多个 SKU → 列 2~3 个候选 + 反问，不调下单工具。
        seedSellableProduct("CLOTH-DOWN-023", "黑色短款羽绒服", "899.00");
        seedSellableProduct("CLOTH-DOWN-031", "白色长款羽绒服", "1099.00");
        seedCustomerDefaults();

        List<JsonNode> events = stream("我要买一件羽绒服", "smoke-category-1");

        assertThat(eventTypes(events)).as("只说了品类，不该直接下单").doesNotContain("tool_call");
        assertThat(finalText(events))
                .contains("帮你选一下")
                .contains("黑色短款羽绒服")
                .contains("白色长款羽绒服");

        // 用户选了具体款之后，才正常下单
        List<JsonNode> chosen = stream("帮我下单黑色短款羽绒服", "smoke-category-2");
        JsonNode arguments = firstPayload(chosen, "tool_call").path("toolCalls").get(0).path("arguments");
        assertThat(arguments.toString()).contains("CLOTH-DOWN-023");
    }

    @Test
    void purchaseIntentWithoutProductFallsBackToDirectAnswer() {
        // 2026-11-11 起路由交给模型：这句会进工作流问一次意图，但模型/兜底都定位不到商品
        // → 不调工具 → 落回直答（以前是关键词闸门直接挡在门外，现在多了一次意图调用）。
        List<JsonNode> events = stream("我想买个暖和的围脖", "smoke-buy-noproduct");

        assertThat(eventTypes(events))
                .as("没有可下单的商品，就不该调任何工具")
                .doesNotContain("tool_call");
        assertThat(finalText(events))
                .as("不能是空回答兜底话术")
                .isNotBlank()
                .doesNotContain("没能给出有效回答");
    }

    // ===================== ③ 端到端：知识检索 =====================

    @Test
    void catalogQuestionRetrievesKnowledgeAndAnswersEndToEnd() {
        seedCatalogChunk();

        List<JsonNode> events = stream("价格刚好699的衣服是什么", "smoke-kb-1");

        assertThat(eventTypes(events)).contains("workflow_started", "knowledge_sources", "final");
        JsonNode sources = firstPayload(events, "knowledge_sources").path("sources");
        assertThat(sources).isNotEmpty();
        assertThat(sources.get(0).path("chunk").path("id").asText()).isEqualTo("CHK-CATALOG-CLOTHING-COAT-009");

        String answer = finalText(events);
        assertThat(answer).contains("根据知识库").contains("699");
    }

    // ===================== ④ 端到端：闲聊直答 =====================

    @Test
    void chitchatStaysOnTheDirectPathWithoutTools() {
        List<JsonNode> events = stream("你好", "smoke-chat-1");

        assertThat(eventTypes(events))
                .contains("final")
                .doesNotContain("workflow_started", "tool_call", "tool_result");
        assertThat(finalText(events)).isNotBlank();
        assertThat(count("tool_invocations")).isZero();
    }

    // ===================== ⑤ 端到端：多轮记忆落库 =====================

    @Test
    void completedTurnIsPersistedAndReusedAsHistory() {
        // 一轮正常完成后：assistant_messages 落 user+assistant 两条，assistant_sessions 一行；
        // 同一 sessionId 再问一次时，第二轮的历史里必须能看到第一轮（多轮记忆的真链路）
        stream("你好", "smoke-mem-1");

        // 落库在 doFinally 里丢给 boundedElastic 异步执行，等它跑完（最多 5s）
        long deadline = System.currentTimeMillis() + 5000;
        while (count("assistant_messages") < 2 && System.currentTimeMillis() < deadline) {
            sleep(50);
        }
        assertThat(count("assistant_messages")).as("一轮结束应写入 user + assistant 两条").isEqualTo(2);
        assertThat(count("assistant_sessions")).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT role FROM assistant_messages ORDER BY id", String.class))
                .containsExactly("user", "assistant");

        List<JsonNode> secondRound = stream("那再帮我算算 2000 元", "smoke-mem-1");
        assertThat(eventTypes(secondRound)).contains("final");
        // 落库同样是异步的：等第二轮写完再断言
        deadline = System.currentTimeMillis() + 5000;
        while (count("assistant_messages") < 4 && System.currentTimeMillis() < deadline) {
            sleep(50);
        }
        assertThat(count("assistant_messages")).as("第二轮也要落库").isEqualTo(4);
    }

    @Test
    void colloquialBusinessPhrasesReachTheIntentAgent() {
        // 2026-11-11 的实测缺口：这些说法以前被关键词闸门挡在门外（7 条里 6 条），
        // 用户只会得到"我无法直接操作"。现在闸门交给模型：先进工作流问一次意图，再决定调工具还是直答。
        int index = 0;
        for (String message : List.of("这单我不要了", "这个能退吗", "帮我查下我的单子")) {
            List<JsonNode> events = stream(message, "smoke-colloquial-" + index++);
            assertThat(eventTypes(events))
                    .as("「%s」应该进工作流让意图 Agent 判断", message)
                    .contains("workflow_started", "agent_started", "agent_completed");
            assertThat(finalText(events)).as("「%s」不能是空回答", message).isNotBlank();
        }
    }

    @Test
    void cartRuleQuestionIsNotHijackedByViewCart() {
        // 2026-11-09 实测过的答非所问：问机制，却被当成"查看购物车"回"购物车现在是空的"
        List<JsonNode> events = stream("购物车里的东西会自动清空吗", "smoke-cart-rule-1");
        // 2026-11-11 起会进工作流问一次意图，但**不该调工具**（调了 VIEW_CART 就会被答成购物车状态）
        assertThat(eventTypes(events)).as("规则类问法不该调工具").doesNotContain("tool_call");
        assertThat(finalText(events)).as("不能被答成购物车状态").doesNotContain("购物车现在是空的");
    }

    // ===================== ⑦ 端到端：购物车 / 售后 / 账户（2026-11-09 新增工具） =====================

    @Test
    void cartAddViewAndCheckoutEndToEnd() {
        seedSellableProduct(BAG, "米色帆布双肩包", "299.00");
        seedCustomerDefaults();

        List<JsonNode> added = stream("把米色帆布双肩包加入购物车", "smoke-cart-1");
        assertThat(firstPayload(added, "tool_call").path("toolCalls").get(0).path("tool").asText())
                .as("加购必须是 ADD_TO_CART，不是 CREATE_ORDER")
                .isEqualTo(ToolNames.ADD_TO_CART);
        assertThat(finalText(added)).contains("购物车").contains("米色帆布双肩包");
        assertThat(count("cart_items")).isEqualTo(1);

        // 同一商品再加一次 → 数量合并成 2（不新增行）
        stream("再帮我加一件米色帆布双肩包到购物车", "smoke-cart-1");
        assertThat(jdbc.queryForObject("SELECT quantity FROM cart_items", Integer.class)).isEqualTo(2);
        assertThat(count("cart_items")).as("重复加购合并，不插两行").isEqualTo(1);

        // 查看
        List<JsonNode> viewed = stream("我的购物车里有什么", "smoke-cart-1");
        assertThat(firstPayload(viewed, "tool_call").path("toolCalls").get(0).path("tool").asText())
                .isEqualTo(ToolNames.VIEW_CART);
        assertThat(finalText(viewed)).contains("米色帆布双肩包").contains("合计");

        // 结算 → 一张确认单（2 件）+ 购物车清空 + 不自动建单
        List<JsonNode> checkout = stream("把购物车里的都下单", "smoke-cart-1");
        JsonNode confirmation = firstPayload(checkout, "order_confirmation_required").path("confirmation");
        assertThat(confirmation.path("items")).hasSize(1);
        assertThat(confirmation.path("totalQuantity").asInt()).isEqualTo(2);
        assertThat(confirmation.path("totalAmount").asDouble()).isEqualTo(598.00);
        assertThat(count("cart_items")).as("下单成功后清车").isZero();
        assertThat(count("orders")).as("仍然只是待确认单").isZero();
    }

    @Test
    void cancelOrderReleasesStockAndIsIdempotent() {
        seedSellableProduct(BAG, "米色帆布双肩包", "299.00");
        seedCustomerDefaults();
        String orderNo = buyAndConfirm("把米色帆布双肩包加入购物车", "smoke-cancel-1");
        assertThat(jdbc.queryForObject(
                "SELECT reserved_stock FROM product_inventory WHERE product_id = ?", Integer.class, BAG))
                .as("下单后库存被预留").isEqualTo(1);

        List<JsonNode> events = stream("帮我取消订单 " + orderNo, "smoke-cancel-1");
        assertThat(finalText(events)).contains("订单已取消").contains(orderNo);
        assertThat(jdbc.queryForObject("SELECT order_status FROM orders WHERE order_no = ?", String.class, orderNo))
                .isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject(
                "SELECT reserved_stock FROM product_inventory WHERE product_id = ?", Integer.class, BAG))
                .as("取消后预留库存要释放").isZero();

        // 再取消一次：不能重复动作，要给明确原因（不再是"没能给出有效回答"）
        List<JsonNode> again = stream("帮我取消订单 " + orderNo, "smoke-cancel-1");
        assertThat(finalText(again)).contains("不可取消");
    }

    @Test
    void refundRequestOnlyAcceptsPaidOrders() {
        seedSellableProduct(BAG, "米色帆布双肩包", "299.00");
        seedCustomerDefaults();
        String orderNo = buyAndConfirm("把米色帆布双肩包加入购物车", "smoke-refund-1");

        // 未支付 → 拒绝退款并引导取消订单
        List<JsonNode> unpaid = stream("我要退款 " + orderNo, "smoke-refund-1");
        assertThat(finalText(unpaid)).contains("还没支付成功");
        assertThat(count("refund_requests")).isZero();

        // 标记为已支付 → 退款申请落单
        jdbc.update("UPDATE orders SET payment_status = 'PAID' WHERE order_no = ?", orderNo);
        List<JsonNode> paid = stream("我要退款 " + orderNo, "smoke-refund-1");
        assertThat(finalText(paid)).contains("退款申请");
        assertThat(count("refund_requests")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM refund_requests", String.class)).isEqualTo("REQUESTED");
        assertThat(jdbc.queryForObject("SELECT amount FROM refund_requests", java.math.BigDecimal.class))
                .isEqualByComparingTo("299.00");

        // 申请单必须有下游：自动建一张 REFUND 工单（否则 REQUESTED 就是终点，没人处理）
        assertThat(count("tickets")).as("退款申请自动建工单").isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT category FROM tickets", String.class)).isEqualTo("REFUND");
        assertThat(jdbc.queryForObject("SELECT priority FROM tickets", String.class)).isEqualTo("HIGH");

        // 能查进度（上一版只能申请不能查）
        List<JsonNode> progress = stream("我的退款到哪了 " + orderNo, "smoke-refund-1");
        assertThat(firstPayload(progress, "tool_call").path("toolCalls").get(0).path("tool").asText())
                .isEqualTo(ToolNames.QUERY_REFUND);
        assertThat(finalText(progress)).contains("退款进度").contains("REQUESTED").contains("RFD-");

        // 重复申请被拦
        List<JsonNode> twice = stream("我要退款 " + orderNo, "smoke-refund-1");
        assertThat(finalText(twice)).contains("请勿重复提交");
        assertThat(count("refund_requests")).isEqualTo(1);
    }

    @Test
    void accountQueryListsCouponsAndMasksPhone() {
        seedCustomerDefaults();
        jdbc.update("""
                INSERT INTO customer_coupons (id, customer_id, code, title, amount_off, min_spend, expires_at, status,
                                              created_at, updated_at)
                VALUES ('CPN-SMOKE-1', ?, 'SMOKE30', '测试立减 30 元', 30.00, 199.00, '2030-12-31 23:59:59', 'UNUSED',
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, CUSTOMER_ID);
        jdbc.update("""
                INSERT INTO customer_coupons (id, customer_id, code, title, amount_off, min_spend, expires_at, status,
                                              created_at, updated_at)
                VALUES ('CPN-SMOKE-OLD', ?, 'OLD', '过期券', 20.00, 99.00, '2020-01-01 00:00:00', 'UNUSED',
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, CUSTOMER_ID);

        List<JsonNode> events = stream("我有几张优惠券", "smoke-account-1");

        assertThat(firstPayload(events, "tool_call").path("toolCalls").get(0).path("tool").asText())
                .isEqualTo(ToolNames.QUERY_ACCOUNT);
        String answer = finalText(events);
        assertThat(answer).contains("测试立减 30 元").contains("SMOKE30");
        assertThat(answer).as("过期券不计入可用").doesNotContain("过期券（");
        assertThat(answer).contains("1 张已过期");
        assertThat(answer).as("手机号脱敏").doesNotContain("13800001001");
    }

    // ===================== ⑧ 端到端：购物车 REST / 退货 / 数据清理（2026-11-09） =====================

    @Test
    void cartRestApiSupportsQuantityAndRemoval() {
        seedSellableProduct(BAG, "米色帆布双肩包", "299.00");
        seedCustomerDefaults();
        stream("把米色帆布双肩包加入购物车", "smoke-cart-api-1");

        JsonNode cart = getJson("/assistant/cart?customerId=" + CUSTOMER_ID);
        assertThat(cart.path("totalQuantity").asInt()).isEqualTo(1);

        JsonNode updated = patchJson("/assistant/cart/items/" + BAG, "{\"quantity\":3}");
        assertThat(updated.path("totalQuantity").asInt()).as("改数量").isEqualTo(3);

        JsonNode cleared = patchJson("/assistant/cart/items/" + BAG, "{\"quantity\":0}");
        assertThat(cleared.path("empty").asBoolean()).as("数量归零等于移除").isTrue();
        assertThat(count("cart_items")).isZero();
    }

    @Test
    void returnRequestCreatesLogisticsTicketAndCanBeQueried() {
        seedSellableProduct(BAG, "米色帆布双肩包", "299.00");
        seedCustomerDefaults();
        String orderNo = buyAndConfirm("把米色帆布双肩包加入购物车", "smoke-return-1");
        jdbc.update("UPDATE orders SET payment_status = 'PAID' WHERE order_no = ?", orderNo);

        List<JsonNode> events = stream("我要退货 " + orderNo, "smoke-return-1");
        assertThat(firstPayload(events, "tool_call").path("toolCalls").get(0).path("tool").asText())
                .isEqualTo(ToolNames.REQUEST_RETURN);
        assertThat(finalText(events)).contains("退货申请");
        assertThat(count("return_requests")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM return_requests", String.class)).isEqualTo("REQUESTED");
        assertThat(jdbc.queryForObject("SELECT category FROM tickets", String.class)).as("退货建物流工单")
                .isEqualTo("LOGISTICS");

        List<JsonNode> progress = stream("我的退货到哪了 " + orderNo, "smoke-return-1");
        assertThat(firstPayload(progress, "tool_call").path("toolCalls").get(0).path("tool").asText())
                .isEqualTo(ToolNames.QUERY_RETURN);
        assertThat(finalText(progress)).contains("退货进度").contains("RTN-").contains("REQUESTED");

        // 未支付订单不能退货
        String unpaid = buyAndConfirm("把米色帆布双肩包加入购物车", "smoke-return-2");
        List<JsonNode> rejected = stream("我要退货 " + unpaid, "smoke-return-2");
        assertThat(finalText(rejected)).contains("还没支付成功");
        assertThat(count("return_requests")).isEqualTo(1);
    }

    @Test
    void retentionCleanupRemovesOnlyOldRows() {
        seedSellableProduct(BAG, "米色帆布双肩包", "299.00");
        seedCustomerDefaults();
        // 老会话 + 新会话
        jdbc.update("""
                INSERT INTO assistant_sessions (id, customer_id, created_at, updated_at)
                VALUES ('old-session', ?, TIMESTAMPADD(DAY, -90, CURRENT_TIMESTAMP), TIMESTAMPADD(DAY, -90, CURRENT_TIMESTAMP)),
                       ('fresh-session', ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, CUSTOMER_ID, CUSTOMER_ID);
        // 老购物车行 + 新购物车行
        jdbc.update("""
                INSERT INTO cart_items (customer_id, product_id, quantity, created_at, updated_at)
                VALUES (?, ?, 1, TIMESTAMPADD(DAY, -60, CURRENT_TIMESTAMP), TIMESTAMPADD(DAY, -60, CURRENT_TIMESTAMP)),
                       (?, ?, 2, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, CUSTOMER_ID, BAG, CUSTOMER_ID, BAG);
        jdbc.update("UPDATE cart_items SET product_id = ? WHERE quantity = 2", BAG);

        var report = context.getBean(com.example.aiticketassistant.application.maintenance.RetentionCleanupService.class)
                .runOnce();

        assertThat(report.sessions()).as("老会话被清理").isGreaterThanOrEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM assistant_sessions WHERE id = 'old-session'", Long.class))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM assistant_sessions WHERE id = 'fresh-session'", Long.class))
                .as("新会话必须保留").isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cart_items", Long.class)).as("新购物车行必须保留")
                .isEqualTo(1);
    }

    // ===================== ⑩ 端到端：购物车优先于下单（2026-11-11 真机回归） =====================

    @Test
    void cartRequestWinsOverPurchaseIntent() {
        // 真机踩到过：句子里既有购买词（"买个"）又能定位商品，被当成下单直接生成了待确认单。
        // 只要提到购物车，动作就该是加购，不能升级成下单。
        seedSellableProduct(BOOTS, "切尔西短靴", "589.00");
        seedSellableProduct(SHOES, "藏青帆布鞋", "199.00");
        seedSellableProduct(CARDIGAN, "深灰针织开衫", "269.00");
        seedCustomerDefaults();

        List<JsonNode> events = stream("我想要买个切尔西短靴、藏青帆布鞋、深灰针织开衫。先帮我加入购物车", "smoke-cart-vs-order-1");

        assertThat(firstPayload(events, "tool_call").path("toolCalls").get(0).path("tool").asText())
                .as("应该是加购，不是下单")
                .isEqualTo(ToolNames.ADD_TO_CART);
        assertThat(eventTypes(events)).as("不能生成待确认订单").doesNotContain("order_confirmation_required");
        assertThat(count("order_confirmations")).isZero();
        assertThat(count("orders")).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cart_items", Long.class)).isEqualTo(3);
        assertThat(finalText(events)).contains("购物车").contains("切尔西短靴").contains("深灰针织开衫");
    }

    // ===================== ⑨ 端到端：售后审核台（2026-11-09 V13） =====================

    @Test
    void reviewConsoleApprovesRefundAndClosesTicket() {
        seedSellableProduct(BAG, "米色帆布双肩包", "299.00");
        seedCustomerDefaults();
        String orderNo = buyAndConfirm("把米色帆布双肩包加入购物车", "smoke-review-1");
        jdbc.update("UPDATE orders SET payment_status = 'PAID' WHERE order_no = ?", orderNo);
        stream("我要退款 " + orderNo, "smoke-review-1");

        String refundId = jdbc.queryForObject("SELECT id FROM refund_requests", String.class);
        String ticketId = jdbc.queryForObject("SELECT ticket_id FROM refund_requests", String.class);
        assertThat(ticketId).as("申请单必须存下关联工单（V13）").isNotBlank();

        // 审核台列表
        JsonNode pending = getJson("/admin/after-sales/refunds?status=REQUESTED");
        assertThat(pending.isArray()).isTrue();
        assertThat(pending.get(0).path("id").asText()).isEqualTo(refundId);
        assertThat(pending.get(0).path("ticketId").asText()).isEqualTo(ticketId);

        // 通过
        JsonNode approved = postJson("/admin/after-sales/refunds/" + refundId + "/approve",
                "{\"note\":\"同意退款\",\"reviewer\":\"alice\"}");
        assertThat(approved.path("status").asText()).isEqualTo("APPROVED");
        assertThat(approved.path("reviewNote").asText()).isEqualTo("同意退款");
        assertThat(approved.path("reviewedBy").asText()).isEqualTo("alice");

        // 申请单与工单都收口
        assertThat(jdbc.queryForObject("SELECT status FROM refund_requests WHERE id = ?", String.class, refundId))
                .isEqualTo("APPROVED");
        assertThat(jdbc.queryForObject("SELECT status FROM tickets WHERE id = ?", String.class, ticketId))
                .as("审核通过后工单要 resolve").isEqualTo("RESOLVED");

        // 用户侧能查到审核结果与备注
        List<JsonNode> progress = stream("我的退款到哪了 " + orderNo, "smoke-review-1");
        assertThat(finalText(progress)).contains("APPROVED").contains("同意退款");

        // 重复审核 → 409（不是 500）
        webTestClient.post().uri("/admin/after-sales/refunds/" + refundId + "/approve")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"note\":\"再通过一次\"}")
                .exchange()
                .expectStatus().isEqualTo(409);
    }

    @Test
    void reviewConsoleRejectsReturnAndRequiresReason() {
        seedSellableProduct(BAG, "米色帆布双肩包", "299.00");
        seedCustomerDefaults();
        String orderNo = buyAndConfirm("把米色帆布双肩包加入购物车", "smoke-review-2");
        jdbc.update("UPDATE orders SET payment_status = 'PAID' WHERE order_no = ?", orderNo);
        stream("我要退货 " + orderNo, "smoke-review-2");
        String returnId = jdbc.queryForObject("SELECT id FROM return_requests", String.class);

        // 驳回不写原因 → 400，且状态不变
        webTestClient.post().uri("/admin/after-sales/returns/" + returnId + "/reject")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"note\":\"\"}")
                .exchange()
                .expectStatus().isBadRequest();
        assertThat(jdbc.queryForObject("SELECT status FROM return_requests WHERE id = ?", String.class, returnId))
                .isEqualTo("REQUESTED");

        JsonNode rejected = postJson("/admin/after-sales/returns/" + returnId + "/reject",
                "{\"note\":\"已超过退货时效\"}");
        assertThat(rejected.path("status").asText()).isEqualTo("REJECTED");
        assertThat(rejected.path("reviewNote").asText()).isEqualTo("已超过退货时效");

        // 用户侧看到驳回原因
        List<JsonNode> progress = stream("我的退货到哪了 " + orderNo, "smoke-review-2");
        assertThat(finalText(progress)).contains("REJECTED").contains("已超过退货时效");
    }

    // ===================== ⑥ 端到端：删除会话 =====================

    @Test
    void deletingSessionRemovesItAndItsMessages() {
        stream("你好", "smoke-del-1");
        stream("你好", "smoke-del-2");

        // 异步落库：等两个会话 + 4 条消息都到齐再断言（机器负载高时 5s 不够，放宽到 20s）
        // 注意**只统计这两个会话**：其他用例的 recordTurn 可能晚到，整表计数会互相干扰
        long deadline = System.currentTimeMillis() + 20000;
        while ((countWhere("assistant_messages", "session_id IN ('smoke-del-1','smoke-del-2')") < 4
                || countWhere("assistant_sessions", "id IN ('smoke-del-1','smoke-del-2')") < 2)
                && System.currentTimeMillis() < deadline) {
            sleep(50);
        }
        assertThat(countWhere("assistant_sessions", "id IN ('smoke-del-1','smoke-del-2')"))
                .as("两个会话都落库").isEqualTo(2);
        assertThat(countWhere("assistant_messages", "session_id IN ('smoke-del-1','smoke-del-2')")).isEqualTo(4);

        // 侧栏能看到两个
        List<JsonNode> listed = getList("/assistant/sessions?customerId=" + CUSTOMER_ID);
        assertThat(listed).extracting(node -> node.path("sessionId").asText())
                .contains("smoke-del-1", "smoke-del-2");

        // 删一个
        JsonNode deleted = delete("/assistant/sessions/smoke-del-1?customerId=" + CUSTOMER_ID);
        assertThat(deleted.path("deleted").asBoolean()).isTrue();

        // 会话行与它的消息一起没了，另一个会话不受影响（表上没有外键，级联是代码里做的）
        assertThat(countWhere("assistant_sessions", "id = 'smoke-del-1'")).isZero();
        assertThat(countWhere("assistant_sessions", "id = 'smoke-del-2'")).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM assistant_messages WHERE session_id = 'smoke-del-1'", Long.class))
                .as("被删会话的消息不能留下孤儿")
                .isZero();
        assertThat(countWhere("assistant_messages", "session_id = 'smoke-del-2'")).isEqualTo(2);
        assertThat(getList("/assistant/sessions?customerId=" + CUSTOMER_ID))
                .extracting(node -> node.path("sessionId").asText())
                .contains("smoke-del-2")
                .doesNotContain("smoke-del-1");

        // 幂等：再删一次不报错，只是 deleted=false
        assertThat(delete("/assistant/sessions/smoke-del-1?customerId=" + CUSTOMER_ID)
                .path("deleted").asBoolean()).isFalse();
    }

    @Test
    void deletingSomeoneElsesSessionIsRejected() {
        // 直接插会话行，不依赖"流结束异步落库"的时序（这条用例只验证归属校验）
        jdbc.update("""
                INSERT INTO assistant_sessions (id, customer_id, created_at, updated_at)
                VALUES ('smoke-del-3', ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, CUSTOMER_ID);

        client.delete().uri("/assistant/sessions/smoke-del-3?customerId=CUST-9999")
                .exchange()
                .expectStatus().isBadRequest();

        // 只看这一条：别的用例的异步落库可能在本用例 setUp 之后才到，不能用全表计数
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM assistant_sessions WHERE id = 'smoke-del-3'", Long.class))
                .as("越权删除必须无效")
                .isEqualTo(1);
    }

    // ===================== 工具方法 =====================

    /** 走一遍"加购 → 结算 → 确认下单"，返回订单号（售后用例的准备动作）。 */
    private String buyAndConfirm(String addToCartMessage, String sessionId) {
        stream(addToCartMessage, sessionId);
        List<JsonNode> checkout = stream("把购物车里的都下单", sessionId);
        String confirmationId = firstPayload(checkout, "order_confirmation_required")
                .path("confirmation").path("confirmationId").asText();
        JsonNode created = post("/assistant/order-confirmations/" + confirmationId + "/confirm",
                objectMapper.createObjectNode().put("customerId", CUSTOMER_ID).put("sessionId", sessionId));
        return created.path("orderNo").asText();
    }
    private JsonNode postJson(String path, String json) {
        String body = webTestClient.post().uri(path)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(json)
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();
        return read(body);
    }
    private JsonNode getJson(String pathAndQuery) {
        String body = webTestClient.get().uri(pathAndQuery)
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();
        return read(body);
    }

    private JsonNode patchJson(String path, String json) {
        String body = webTestClient.patch().uri(path)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(json)
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();
        return read(body);
    }

    private JsonNode read(String body) {
        try {
            return objectMapper.readTree(body == null ? "{}" : body);
        } catch (Exception ex) {
            throw new IllegalStateException("响应不是合法 JSON: " + body, ex);
        }
    }
    private List<JsonNode> getList(String pathAndQuery) {
        String raw = client.get().uri(pathAndQuery)
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();
        try {
            JavaType type = objectMapper.getTypeFactory().constructCollectionType(List.class, JsonNode.class);
            return objectMapper.readValue(raw == null ? "[]" : raw, type);
        } catch (Exception ex) {
            throw new AssertionError("响应不是 JSON 数组: " + raw, ex);
        }
    }

    private JsonNode delete(String pathAndQuery) {
        String raw = client.delete().uri(pathAndQuery)
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();
        try {
            return objectMapper.readTree(raw == null ? "{}" : raw);
        } catch (Exception ex) {
            throw new AssertionError("响应不是 JSON: " + raw, ex);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    /** 走真实 SSE 通道收集一轮问答的全部事件（data 里的 JSON）。 */
    private List<JsonNode> stream(String message, String sessionId) {
        List<String> frames = client.get()
                .uri(uri -> uri.path("/assistant/stream")
                        .queryParam("message", message)
                        .queryParam("customerId", CUSTOMER_ID)
                        .queryParam("sessionId", sessionId)
                        .build())
                .accept(MediaType.TEXT_EVENT_STREAM)
                .exchange()
                .expectStatus().isOk()
                .returnResult(String.class)
                .getResponseBody()
                .collectList()
                .block(Duration.ofSeconds(30));

        List<JsonNode> events = new ArrayList<>();
        for (String frame : frames == null ? List.<String>of() : frames) {
            String data = frame.startsWith("data:") ? frame.substring("data:".length()).trim() : frame.trim();
            if (!data.startsWith("{")) {
                continue;
            }
            try {
                events.add(objectMapper.readTree(data));
            } catch (Exception ex) {
                log.warn("跳过无法解析的 SSE 帧: {}", data, ex);
            }
        }
        assertThat(events).as("SSE 至少应推回一个事件").isNotEmpty();
        return events;
    }

    /** 发一个 JSON POST 并解析响应（确认/取消下单这类非 SSE 接口用）。 */
    private JsonNode post(String path, com.fasterxml.jackson.databind.node.ObjectNode body) {
        String raw = client.post().uri(path)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();
        try {
            return objectMapper.readTree(raw == null ? "{}" : raw);
        } catch (Exception ex) {
            throw new AssertionError("响应不是 JSON: " + raw, ex);
        }
    }

    private static List<String> eventTypes(List<JsonNode> events) {        return events.stream().map(event -> event.path("type").asText()).toList();
    }

    private static JsonNode firstPayload(List<JsonNode> events, String type) {
        return events.stream()
                .filter(event -> type.equals(event.path("type").asText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("SSE 里没有 " + type + " 事件，实际: " + eventTypes(events)))
                .path("payload");
    }

    /** 所有某类事件的 payload（一事件多张确认单时要用）。 */
    private static List<JsonNode> eventPayloads(List<JsonNode> events, String type) {
        return events.stream()
                .filter(event -> type.equals(event.path("type").asText()))
                .map(event -> event.path("payload"))
                .toList();
    }

    /** 最后一个 final 事件的完整回答（done=true 的那条）。 */
    private static String finalText(List<JsonNode> events) {
        return events.stream()
                .filter(event -> "final".equals(event.path("type").asText()))
                .filter(event -> event.path("payload").path("done").asBoolean(false))
                .map(event -> event.path("payload").path("fullText").asText(""))
                .filter(text -> !text.isBlank())
                .findFirst()
                .orElse("");
    }

    /** 带条件的计数（异步落库会让整表计数互相干扰，定位到具体会话更稳）。 */
    private long countWhere(String table, String where) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + where, Long.class);
    }

    private long count(String table) {
        Long value = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
        return value == null ? 0 : value;
    }

    private void seedSellableProduct() {
        jdbc.update("""
                INSERT INTO products (id, name, price, active, created_at, updated_at)
                VALUES (?, '黑色连帽卫衣', 259.00, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, HOODIE);
        jdbc.update("""
                INSERT INTO product_inventory (product_id, stock_on_hand, reserved_stock, updated_at)
                VALUES (?, 20, 0, CURRENT_TIMESTAMP)
                """, HOODIE);
        seedCustomerDefaults();
    }

    /** 单个可售商品（多商品下单的用例按需调用多次）。 */
    private void seedSellableProduct(String productId, String name, String price) {
        jdbc.update("""
                INSERT INTO products (id, name, price, active, created_at, updated_at)
                VALUES (?, ?, ?, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, productId, name, new java.math.BigDecimal(price));
        jdbc.update("""
                INSERT INTO product_inventory (product_id, stock_on_hand, reserved_stock, updated_at)
                VALUES (?, 20, 0, CURRENT_TIMESTAMP)
                """, productId);
    }

    /** 默认收货地址 + 支付方式（确认单创建时校验归属）。 */
    private void seedCustomerDefaults() {
        jdbc.update("""
                INSERT INTO customer_addresses
                    (id, customer_id, recipient_name, phone, address_line, is_default, active, created_at, updated_at)
                VALUES ('ADDR-SMOKE-1', ?, '王同学', '138****1001', '上海市浦东新区世纪大道 100 号', TRUE, TRUE,
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, CUSTOMER_ID);
        jdbc.update("""
                INSERT INTO customer_payment_methods
                    (id, customer_id, method_type, display_label, is_default, active, created_at, updated_at)
                VALUES ('PAY-SMOKE-1', ?, 'CARD', '招商银行信用卡 **** 1001', TRUE, TRUE,
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, CUSTOMER_ID);
    }

    private void seedCatalogChunk() {
        jdbc.update("""
                INSERT INTO knowledge_chunks (id, document_id, title, category, source, content)
                VALUES ('CHK-CATALOG-CLOTHING-COAT-009', 'DOC-CATALOG-CLOTHING', '服装商品目录：羊毛混纺大衣',
                        'CATALOG', 'internal://catalog/clothing#CLOTH-COAT-009',
                        '商品编号 CLOTH-COAT-009，商品名称 羊毛混纺大衣，服装商品，价格 ¥699.00。')
                """);
    }
}
