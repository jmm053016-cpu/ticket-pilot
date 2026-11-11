package com.example.aiticketassistant.application.routing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 「商品/预算类问题」判定的回归测试。
 *
 * <p>这些用例都是实测漏判过的问法：它们被当成闲聊走了直答，模型没有商品数据，只能凭空编。
 */
class QuestionSignalsTest {

    @Test
    void budgetPhraseWithoutCategoryIsDetected() {
        assertThat(QuestionSignals.isCatalogOrBudgetQuestion("1000元预算可以怎么搭配")).isTrue();
    }

    @Test
    void quantityPhraseWithoutPriceCueIsDetected() {
        assertThat(QuestionSignals.isCatalogOrBudgetQuestion("我有1000元，最多可以买几件衣服")).isTrue();
    }

    @Test
    void catalogPriceQuestionIsDetected() {
        assertThat(QuestionSignals.isCatalogOrBudgetQuestion("价格刚好699的衣服是什么")).isTrue();
        assertThat(QuestionSignals.isCatalogOrBudgetQuestion("我的衣服比699便宜的有哪几种")).isTrue();
        assertThat(QuestionSignals.isCatalogOrBudgetQuestion("黑色连帽卫衣多少钱")).isTrue();
    }

    @Test
    void recommendationWithCategoryIsDetected() {
        assertThat(QuestionSignals.isCatalogOrBudgetQuestion("推荐几件便宜的衣服")).isTrue();
    }

    @Test
    void explicitPurchaseIsNotTreatedAsCatalogQuestion() {
        // 下单走 CREATE_ORDER 判定，不应被当成"查资料"，否则会干扰确认卡片
        assertThat(QuestionSignals.isCatalogOrBudgetQuestion("我要买一件黑色卫衣")).isFalse();
    }

    @Test
    void chitchatAndUnrelatedQuestionsAreNotDetected() {
        assertThat(QuestionSignals.isCatalogOrBudgetQuestion("你好")).isFalse();
        assertThat(QuestionSignals.isCatalogOrBudgetQuestion("退款怎么弄")).isFalse();
        assertThat(QuestionSignals.isCatalogOrBudgetQuestion("推荐一个退款流程")).isFalse();
        assertThat(QuestionSignals.isCatalogOrBudgetQuestion("支付失败怎么办")).isFalse();
        assertThat(QuestionSignals.isCatalogOrBudgetQuestion(null)).isFalse();
    }
    @Test
    void queryIsExpandedOnlyWhenNoCategoryWordProvided() {
        // 无品类词：必须补领域词，否则 BM25 检不到任何 chunk
        assertThat(QuestionSignals.catalogQueryFor("1000元预算可以怎么搭配"))
                .isEqualTo("1000元预算可以怎么搭配 服装 商品 价格");
        // 已含品类词：原样使用，避免加词把语义带偏
        assertThat(QuestionSignals.catalogQueryFor("黑色连帽卫衣多少钱")).isEqualTo("黑色连帽卫衣多少钱");
    }
    @Test
    void budgetCountingQuestionIsDetectedWithAmount() {
        assertThat(QuestionSignals.isBudgetCountingQuestion("我有1000元，最多可以买几件衣服，衣服不能重复")).isTrue();
        assertThat(QuestionSignals.extractBudgetAmount("我有1000元，最多可以买几件衣服")).isEqualTo(1000);
        assertThat(QuestionSignals.extractBudgetAmount("预算500能买几件")).isEqualTo(500);
        assertThat(QuestionSignals.extractBudgetAmount("预算有限，能买几件")).isNull();
        assertThat(QuestionSignals.isBudgetCountingQuestion("你好")).isFalse();
    }

    @Test
    void repeatConstraintIsDetected() {
        assertThat(QuestionSignals.forbidsRepeat("衣服不能重复")).isTrue();
        assertThat(QuestionSignals.forbidsRepeat("每件不同")).isTrue();
        assertThat(QuestionSignals.forbidsRepeat("我有1000元最多可以买几件衣服")).isFalse();
    }
    @Test
    void repeatPreferenceDistinguishesUnstatedFromForbidden() {
        // 没提"重复"时必须是 null（→ 按允许重复处理），否则"最多能买几件"会被算成不重复的 5 件
        assertThat(QuestionSignals.repeatPreference("我有1000元，最多可以买几件衣服")).isNull();
        assertThat(QuestionSignals.repeatPreference("衣服不能重复")).isFalse();
        assertThat(QuestionSignals.repeatPreference("同款可以多买几件吗")).isTrue();
    }

    // ===== 以下用例锁住"关键词表唯一"：曾经分散在 WorkflowService / IntentAgent / Fallback 里的判定 =====

    @Test
    void purchaseIntentNeedsBothActionAndIdentifiableProduct() {
        // 只有动作没有商品 → 不能下单（让模型反问）
        assertThat(QuestionSignals.hasPurchaseIntent("我要买，选哪个好")).isTrue();
        assertThat(QuestionSignals.purchaseProductId("我要买，选哪个好")).isNull();
        // 只有商品名没有动作 → 那是在问价格
        assertThat(QuestionSignals.hasPurchaseIntent("黑色卫衣多少钱")).isFalse();
        assertThat(QuestionSignals.purchaseProductId("黑色卫衣多少钱")).isNull();
        // 动作 + 商品 → 可下单
        assertThat(QuestionSignals.purchaseProductId("来一件黑色卫衣")).isEqualTo("CLOTH-HOODIE-004");
        assertThat(QuestionSignals.purchaseProductId("下单 CLOTH-COAT-009")).isEqualTo("CLOTH-COAT-009");
    }

    @Test
    void budgetWordingNeverCountsAsPurchaseAction() {
        // 「最多可以买几件衣服」里的"买"是选购，不是下单 —— 判成购买会弹出确认卡片干扰用户
        assertThat(QuestionSignals.hasPurchaseIntent("我有1000元，最多可以买几件衣服")).isFalse();
        assertThat(QuestionSignals.hasPurchaseIntent("1000元预算可以怎么搭配")).isFalse();
    }

    @Test
    void quantityIsParsedWithTheSameWordsAsThePurchaseCheck() {
        assertThat(QuestionSignals.extractPurchaseQuantity("买3件T恤")).isEqualTo(3);
        assertThat(QuestionSignals.extractPurchaseQuantity("来一件黑色卫衣")).isEqualTo(1);
        assertThat(QuestionSignals.extractPurchaseQuantity("你好")).isEqualTo(1);
    }

    // ===== 一句话买多件：2026-11-05 真实故障的回归（只下成了后一件） =====

    @Test
    void multiProductPurchaseReturnsEveryItemInSentenceOrder() {
        List<PurchaseItem> items = QuestionSignals.purchaseItems("我想要买米色帆布双肩包和藏青帆布鞋，帮我下单");

        assertThat(items).extracting(PurchaseItem::productId)
                .as("按句子先后返回，两件都不能少")
                .containsExactly("CLOTH-BAG-029", "CLOTH-CANVAS-028");
        assertThat(items).extracting(PurchaseItem::name)
                .containsExactly("米色帆布双肩包", "藏青帆布鞋");
        assertThat(items).allSatisfy(item -> assertThat(item.quantity()).isEqualTo(1));
    }

    @Test
    void longestProductNameWinsOverItsSubstrings() {
        // 「米色帆布双肩包」里还包含「双肩包」「包」；「灰色拉链连帽卫衣」里包含「连帽卫衣」「卫衣」
        assertThat(QuestionSignals.purchaseItems("买一个米色帆布双肩包"))
                .extracting(PurchaseItem::productId).containsExactly("CLOTH-BAG-029");
        assertThat(QuestionSignals.purchaseItems("买一件灰色拉链连帽卫衣"))
                .extracting(PurchaseItem::productId).containsExactly("CLOTH-HOODIE-015");
    }

    @Test
    void sameProductMentionedTwiceCountsOnce() {
        assertThat(QuestionSignals.purchaseItems("买一件藏青帆布鞋，就要藏青帆布鞋"))
                .as("两种说法指同一件商品时只下一单")
                .extracting(PurchaseItem::productId).containsExactly("CLOTH-CANVAS-028");
    }

    @Test
    void quantityAppliesPerItemOnlyWhenExplicitlyShared() {
        // 单个商品：沿用「买 3 件」
        assertThat(QuestionSignals.purchaseItems("买3件基础纯棉白色T恤"))
                .extracting(PurchaseItem::quantity).containsExactly(3);
        // 多个商品：默认各 1 件（"买 2 件卫衣和裤子"不该把裤子也当成 2 件）
        assertThat(QuestionSignals.purchaseItems("买2件黑色连帽卫衣和藏青帆布鞋"))
                .extracting(PurchaseItem::quantity).containsExactly(1, 1);
        // 明确说"各 2 件"才每个 2 件
        assertThat(QuestionSignals.purchaseItems("黑色连帽卫衣和藏青帆布鞋各2件，帮我下单"))
                .extracting(PurchaseItem::quantity).containsExactly(2, 2);
    }

    @Test
    void purchaseIntentWithoutIdentifiableProductStaysOnTheDirectPath() {
        // 2026-11-05：只有"买"没有商品时，工作流里没有任何可调用的工具，正文会空转成
        // "没能给出有效回答"。现在这类问法走直答（检索 + 模型反问要哪一件）。
        assertThat(QuestionSignals.hasPurchaseIntent("我想买个暖和的围脖")).isTrue();
        assertThat(QuestionSignals.purchaseProductId("我想买个暖和的围脖")).isNull();
        assertThat(QuestionSignals.needsAgentWorkflow("我想买个暖和的围脖")).isFalse();

        // 能定位到商品 → 还是走工作流（下单/确认卡片那条路不能被带偏）
        assertThat(QuestionSignals.needsAgentWorkflow("我要买一件黑色连帽卫衣")).isTrue();
        assertThat(QuestionSignals.needsAgentWorkflow("帮我下单 CLOTH-COAT-009")).isTrue();
        // 预算/搭配类问题仍走工作流（PLAN_BUDGET 在里面）
        assertThat(QuestionSignals.needsAgentWorkflow("1000元预算可以怎么搭配")).isTrue();
    }

    @Test
    void cartAndAfterSalesIntentsAreDistinguished() {
        // 加购：购物车 + 添加动词（两种措辞都要认）
        assertThat(QuestionSignals.isAddToCartRequest("把米色帆布双肩包加入购物车")).isTrue();
        assertThat(QuestionSignals.isAddToCartRequest("再帮我加一件米色帆布双肩包到购物车")).isTrue();
        assertThat(QuestionSignals.isAddToCartRequest("我的购物车里有什么")).isFalse();
        assertThat(QuestionSignals.isAddToCartRequest("清空购物车")).isFalse();

        // 查看 vs 移除
        assertThat(QuestionSignals.isViewCartRequest("我的购物车里有什么")).isTrue();
        assertThat(QuestionSignals.isRemoveFromCartRequest("清空购物车")).isTrue();

        // 结算
        assertThat(QuestionSignals.isCartCheckoutRequest("把购物车里的都下单")).isTrue();
        assertThat(QuestionSignals.isCartCheckoutRequest("我的购物车里有什么")).isFalse();

        // 取消订单（"取消订阅"这类不算）
        assertThat(QuestionSignals.isCancelOrderRequest("帮我取消订单")).isTrue();
        assertThat(QuestionSignals.isCancelOrderRequest("把我的订单取消掉")).isTrue();
        assertThat(QuestionSignals.isCancelOrderRequest("取消订阅")).isFalse();

        // 退货（货寄回）≠ 退款（钱退回）
        assertThat(QuestionSignals.isReturnRequest("我要退货")).isTrue();
        assertThat(QuestionSignals.isReturnRequest("申请换货")).isTrue();
        assertThat(QuestionSignals.isReturnRequest("退货流程是什么")).as("知识问法不落单").isFalse();
        assertThat(QuestionSignals.isReturnStatusQuery("我的退货到哪了")).isTrue();
        assertThat(QuestionSignals.isReturnRequest("我的退货到哪了")).as("进度不是申请").isFalse();
        assertThat(QuestionSignals.isRefundRequest("我要退货")).as("退货不是退款").isFalse();

        // 退款进度查询 ≠ 退款动作 ≠ 退款知识
        assertThat(QuestionSignals.isRefundStatusQuery("我的退款到哪了")).isTrue();
        assertThat(QuestionSignals.isRefundStatusQuery("退款申请状态怎么查")).isTrue();
        assertThat(QuestionSignals.isRefundStatusQuery("退款要几天")).as("时效是知识问法").isFalse();
        assertThat(QuestionSignals.isRefundRequest("我的退款到哪了")).as("进度不是申请").isFalse();

        // 购物车规则问句不能被当成查看命令（实测过答非所问）
        assertThat(QuestionSignals.isViewCartRequest("购物车里的东西会自动清空吗")).isFalse();
        assertThat(QuestionSignals.isViewCartRequest("购物车能放几件")).isFalse();
        assertThat(QuestionSignals.isViewCartRequest("我的购物车里有什么")).isTrue();
        assertThat(QuestionSignals.isViewCartRequest("购物车")).isTrue();

        // 退款：动作 vs 知识问法
        assertThat(QuestionSignals.isRefundRequest("我要退款")).isTrue();
        assertThat(QuestionSignals.isRefundRequest("申请退款")).isTrue();
        assertThat(QuestionSignals.isRefundRequest("退款怎么操作")).as("知识问法走检索，不落退款单").isFalse();
        assertThat(QuestionSignals.isRefundRequest("退款要几天到账")).isFalse();

        // 优惠券属于账户数据
        assertThat(QuestionSignals.isAccountQuery("我有几张优惠券")).isTrue();

        // 订单号可以从消息里抽（原来只认请求参数）
        assertThat(QuestionSignals.orderNoIn("帮我取消订单 ORD-AI-BEC2138C")).isEqualTo("ORD-AI-BEC2138C");
        assertThat(QuestionSignals.orderNoIn("帮我取消订单")).isEmpty();
    }

    @Test
    void productItemsAreFoundWithoutPurchaseAction() {
        // 加购措辞不是购买词，但要点名商品（这是 ADD_TO_CART 能工作的前提）
        assertThat(QuestionSignals.purchaseItems("把米色帆布双肩包加入购物车")).isEmpty();
        assertThat(QuestionSignals.productItemsIn("把米色帆布双肩包加入购物车"))
                .extracting(PurchaseItem::productId).containsExactly("CLOTH-BAG-029");
    }

    @Test
    void purchaseItemsIsEmptyWithoutPurchaseActionOrProduct() {
        assertThat(QuestionSignals.purchaseItems("米色帆布双肩包多少钱")).isEmpty();
        assertThat(QuestionSignals.purchaseItems("我要买，选哪个好")).isEmpty();
        assertThat(QuestionSignals.purchaseItems("1000元预算可以怎么搭配")).isEmpty();
    }

    @Test
    void orderListAndSingleOrderQueryAreDistinguished() {
        assertThat(QuestionSignals.isOrderListRequest("我有几个订单")).isTrue();
        assertThat(QuestionSignals.isOrderListRequest("帮我查一下我的订单")).isTrue();
        assertThat(QuestionSignals.isOrderListRequest("把所有订单展示一下")).isTrue();
        assertThat(QuestionSignals.isOrderListRequest("你好")).isFalse();
    }

    @Test
    void businessRouteCuesAreAllCoveredByQuestionSignals() {
        // 这些问法以前只写在 AssistantWorkflowService 里；现在必须由唯一关键词表覆盖，
        // 否则搬运规则时很容易把某一路需求漏成"直答"。
        assertThat(QuestionSignals.isCartAction("把这件卫衣加入购物车")).isTrue();
        assertThat(QuestionSignals.isAccountQuery("我想改一下收货地址")).isTrue();
        assertThat(QuestionSignals.isOrderLifecycleAction("帮我取消订单")).isTrue();
        assertThat(QuestionSignals.isOrderLifecycleAction("怎么申请退款")).isTrue();
        assertThat(QuestionSignals.isTicketCreateRequest("帮我创建支付失败工单")).isTrue();
        assertThat(QuestionSignals.isTicketActionRequest("我要投诉")).isTrue();
        assertThat(QuestionSignals.hasOrderNo("查一下 ORD-1001 的状态")).isTrue();
        assertThat(QuestionSignals.hasOrderNo("我的订单呢")).isFalse();
    }

    @Test
    void ticketIntentIsWiderThanTicketRouting() {
        // 意图判定只要提到"工单"就算（工作流内补 CREATE_TICKET 更安全），路由仍要求"动词 + 工单"
        assertThat(QuestionSignals.mentionsTicket("工单处理进度怎么查")).isTrue();
        assertThat(QuestionSignals.isTicketCreateRequest("工单处理进度怎么查")).isFalse();
        assertThat(QuestionSignals.mentionsTicket("我要买一件黑色卫衣")).isFalse();
    }
}