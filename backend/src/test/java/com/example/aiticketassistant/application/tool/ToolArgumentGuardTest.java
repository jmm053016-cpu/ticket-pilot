package com.example.aiticketassistant.application.tool;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.aiticketassistant.application.workflow.WorkflowContext;
import com.example.aiticketassistant.domain.catalog.Product;
import com.example.aiticketassistant.domain.catalog.ProductInventory;
import com.example.aiticketassistant.domain.catalog.ProductRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * 工具参数白名单校验（2026-11-09）。
 *
 * <p>为什么值得单独测：这是唯一能挡住"模型编出商品编号就去下单"的地方 ——
 * 上一步的 E2E 测不出来（真实模型编什么编号不可控），只能在这里把规则钉死。
 */
class ToolArgumentGuardTest {

    private final ProductRepository products = new FakeProductRepository();
    private final ToolArgumentGuard guard = new ToolArgumentGuard(products);

    private WorkflowContext context(String message) {
        return new WorkflowContext("s-1", "CUST-1001", "", message);
    }

    @Test
    void rejectsProductThatDoesNotExist() {
        // 实测过模型会编这个编号（不存在）
        String reason = guard.rejectReason(
                new ToolCall(ToolNames.CREATE_ORDER, Map.of("productId", "CLOTH-HOODIE-BLK", "quantity", 1)),
                context("把黑色连帽卫衣加入购物车"));
        assertThat(reason).contains("商品不存在或已下架").contains("CLOTH-HOODIE-BLK");
    }

    @Test
    void rejectsExistingProductWhenSentenceNamesAnotherOne() {
        // 用户说买T恤，工具却要下单帆布鞋（模型理解跑偏）→ 拒绝，避免下错单
        String reason = guard.rejectReason(
                new ToolCall(ToolNames.CREATE_ORDER, Map.of("items", List.of(
                        Map.of("productId", "CLOTH-SHOE-030", "quantity", 1)))),
                context("买一件基础纯棉白色T恤"));
        assertThat(reason).contains("对不上");
    }

    @Test
    void allowsContextualOrderWithoutProductInSentence() {
        // "就买这个" 靠上下文指代，句子里没有商品名 → 允许（不能一刀切拒绝）
        assertThat(guard.rejectReason(
                new ToolCall(ToolNames.CREATE_ORDER, Map.of("items", List.of(
                        Map.of("productId", "CLOTH-TEE-001", "quantity", 1)))),
                context("就买这个吧"))).isNull();
    }

    @Test
    void rejectsQuantityOutOfRange() {
        assertThat(guard.rejectReason(
                new ToolCall(ToolNames.CREATE_ORDER, Map.of("productId", "CLOTH-TEE-001", "quantity", 999)),
                context("买 999 件基础纯棉白色T恤"))).contains("1~20");
        assertThat(guard.rejectReason(
                new ToolCall(ToolNames.ADD_TO_CART, Map.of("items", List.of(
                        Map.of("productId", "CLOTH-TEE-001", "quantity", 0)))),
                context("把基础纯棉白色T恤加入购物车"))).contains("1~20");
    }

    @Test
    void cartFromDatabaseBypassesItemValidation() {
        // 购物车结算的明细来自数据库，不经模型 → 不校验商品（但仍要过服务层校验）
        assertThat(guard.rejectReason(
                new ToolCall(ToolNames.CREATE_ORDER, Map.of("fromCart", true)),
                context("把购物车里的都下单"))).isNull();
    }

    @Test
    void orderActionsRequireWellFormedOrderNo() {
        assertThat(guard.rejectReason(
                new ToolCall(ToolNames.CANCEL_ORDER, Map.of("orderNo", "")), context("帮我取消订单")))
                .contains("请提供订单号");
        assertThat(guard.rejectReason(
                new ToolCall(ToolNames.CANCEL_ORDER, Map.of("orderNo", "随便一个号")), context("帮我取消订单")))
                .contains("格式不对");
        assertThat(guard.rejectReason(
                new ToolCall(ToolNames.CANCEL_ORDER, Map.of("orderNo", "ORD-AI-BEC2138C")), context("帮我取消订单 ORD-AI-BEC2138C")))
                .isNull();
        // 退款进度查询的订单号可以留空（= 查最近几条）
        assertThat(guard.rejectReason(
                new ToolCall(ToolNames.QUERY_REFUND, Map.of()), context("我的退款到哪了"))).isNull();
    }

    @Test
    void ticketActionAndSearchLimitAreConstrained() {
        assertThat(guard.rejectReason(
                new ToolCall(ToolNames.UPDATE_TICKET, Map.of("action", "DELETE_EVERYTHING")), context("更新工单")))
                .contains("不支持的工单动作");
        assertThat(guard.rejectReason(
                new ToolCall(ToolNames.SEARCH_KNOWLEDGE, Map.of("query", "T恤", "limit", 99)), context("T恤多少钱")))
                .contains("1~10");
        assertThat(guard.rejectReason(
                new ToolCall(ToolNames.SEARCH_KNOWLEDGE, Map.of("query", "T恤", "limit", 5)), context("T恤多少钱")))
                .isNull();
    }

    /** 只实现守卫用到的两个方法，其余给空实现。 */
    private static final class FakeProductRepository implements ProductRepository {
        private static final Map<String, Product> CATALOG = Map.of(
                "CLOTH-TEE-001", new Product("CLOTH-TEE-001", "基础纯棉白色T恤", new BigDecimal("99.00"), true),
                "CLOTH-SHOE-030", new Product("CLOTH-SHOE-030", "藏青帆布鞋", new BigDecimal("199.00"), true),
                "CLOTH-OFF-099", new Product("CLOTH-OFF-099", "已下架商品", new BigDecimal("88.00"), false));

        @Override
        public Optional<Product> findById(String id) {
            return Optional.ofNullable(CATALOG.get(id));
        }

        @Override
        public List<Product> findActiveProducts() {
            return List.copyOf(CATALOG.values());
        }

        @Override
        public Optional<ProductInventory> findInventory(String productId) {
            return Optional.of(new ProductInventory(productId, 10, 0));
        }

        @Override
        public ProductInventory saveInventory(ProductInventory inventory) {
            return inventory;
        }
    }
}
