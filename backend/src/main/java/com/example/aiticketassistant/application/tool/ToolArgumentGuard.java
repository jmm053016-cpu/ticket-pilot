package com.example.aiticketassistant.application.tool;

import com.example.aiticketassistant.application.routing.PurchaseItem;
import com.example.aiticketassistant.application.routing.QuestionSignals;
import com.example.aiticketassistant.application.workflow.WorkflowContext;
import com.example.aiticketassistant.domain.catalog.Product;
import com.example.aiticketassistant.domain.catalog.ProductRepository;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 工具参数的**白名单校验**（2026-11-09）。
 *
 * <p>为什么必须有：`ToolDispatcher` 原来只校验"工具名在不在白名单"，参数**完全不校验** ——
 * productId 直接来自模型输出。实测模型编过 `CLOTH-HOODIE-BLK`（不存在，被下游商品校验挡住），
 * 但只要编出一个**存在**的编号，就会生成一张用户没要求的待确认单（用户点一下确认就真下单了）。
 *
 * <p>三条规则（拒绝原因会作为工具失败结果回给用户，不是静默失败）：
 * <ol>
 *   <li><b>商品必须真实存在且在售</b>：productId 不能是模型想象出来的。</li>
 *   <li><b>数量在 1~{@value #MAX_QUANTITY}</b>：防止 "买 999 件" 这类异常参数打穿库存逻辑。</li>
 *   <li><b>与句子里点名的商品不能完全无关</b>：用户说"买双肩包"、工具却要下单帆布鞋 → 拒绝。
 *       反过来"就买这个"（句子里没有商品名，靠上下文指代）是允许的。</li>
 * </ol>
 */
@Component
public class ToolArgumentGuard {
    /** 单件商品单次上限：与 {@code CartService} 的加购上限保持一致。 */
    static final int MAX_QUANTITY = 20;

    private static final Pattern ORDER_NO = Pattern.compile("^ORD-[A-Za-z0-9-]+$");
    private static final Set<String> TICKET_ACTIONS = Set.of("RESOLVE", "ESCALATE", "WAITING_CUSTOMER");

    private final ProductRepository productRepository;

    public ToolArgumentGuard(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    /** 通过返回 {@code null}；不通过返回一句面向用户的拒绝原因。 */
    public String rejectReason(ToolCall call, WorkflowContext context) {
        Map<String, Object> arguments = call.arguments() == null ? Map.of() : call.arguments();
        return switch (call.tool()) {
            case ToolNames.CREATE_ORDER -> validateOrderItems(arguments, context);
            case ToolNames.ADD_TO_CART -> validateCartItems(arguments);
            case ToolNames.CANCEL_ORDER, ToolNames.REQUEST_REFUND, ToolNames.REQUEST_RETURN ->
                    validateOrderNo(optionalOrderNo(arguments));
            case ToolNames.QUERY_REFUND, ToolNames.QUERY_RETURN -> validateOptionalOrderNo(optionalOrderNo(arguments));
            case ToolNames.UPDATE_TICKET -> validateTicketAction(arguments);
            case ToolNames.SEARCH_KNOWLEDGE -> validateSearchLimit(arguments);
            default -> null;
        };
    }

    private String validateOrderItems(Map<String, Object> arguments, WorkflowContext context) {
        Object fromCart = arguments.get("fromCart");
        if (fromCart != null && Boolean.parseBoolean(String.valueOf(fromCart))) {
            return null;   // 购物车结算的明细来自数据库，不经模型
        }
        List<Item> items = items(arguments);
        if (items.isEmpty()) {
            return "要下单哪件商品？请告诉我商品名称（例如「买一件基础纯棉白色T恤」）";
        }
        String problem = validateAgainstCatalog(items);
        if (problem != null) {
            return problem;
        }
        // 用户句子里点名了商品 → 下单的商品必须与之有交集（防"说 A 下 B"）
        List<PurchaseItem> mentioned = QuestionSignals.productItemsIn(context.userMessage());
        if (!mentioned.isEmpty()) {
            Set<String> mentionedIds = new LinkedHashSet<>();
            mentioned.forEach(item -> mentionedIds.add(item.productId()));
            boolean overlap = items.stream().anyMatch(item -> mentionedIds.contains(item.productId()));
            if (!overlap) {
                return "你这句话里提到的是其他商品，和要下单的商品对不上，请确认后再试（避免下错单）";
            }
        }
        return null;
    }

    private String validateCartItems(Map<String, Object> arguments) {
        List<Item> items = items(arguments);
        if (items.isEmpty()) {
            return "要加购哪件商品？请告诉我商品名称或编号";
        }
        return validateAgainstCatalog(items);
    }

    private String validateAgainstCatalog(List<Item> items) {
        for (Item item : items) {
            if (item.productId() == null || item.productId().isBlank()) {
                return "没能确定具体商品，请说得更具体一点（例如「军绿工装夹克」）；AI 不会替你猜商品。";
            }
            Product product = productRepository.findById(item.productId()).filter(Product::active).orElse(null);
            if (product == null) {
                return "商品不存在或已下架: " + item.productId();
            }
            if (item.quantity() < 1 || item.quantity() > MAX_QUANTITY) {
                return "单件商品数量需在 1~%d 之间：%s".formatted(MAX_QUANTITY, product.name());
            }
        }
        return null;
    }

    private String validateOrderNo(String orderNo) {
        if (orderNo == null || orderNo.isBlank()) {
            return "请提供订单号（例如 ORD-AI-XXXXXXXX）";
        }
        return ORDER_NO.matcher(orderNo.trim().toUpperCase()).matches()
                ? null
                : "订单号格式不对（应为 ORD-xxx）：" + orderNo;
    }

    private String validateOptionalOrderNo(String orderNo) {
        return orderNo == null || orderNo.isBlank() ? null : validateOrderNo(orderNo);
    }

    private String validateTicketAction(Map<String, Object> arguments) {
        Object action = arguments.get("action");
        String value = action == null ? "" : String.valueOf(action).trim().toUpperCase();
        if (value.isEmpty()) {
            return "要执行哪个工单动作？（RESOLVE / ESCALATE / WAITING_CUSTOMER）";
        }
        return TICKET_ACTIONS.contains(value) ? null : "不支持的工单动作: " + action;
    }

    private String validateSearchLimit(Map<String, Object> arguments) {
        Object limit = arguments.get("limit");
        if (limit == null) {
            return null;
        }
        try {
            int value = Integer.parseInt(String.valueOf(limit));
            return value >= 1 && value <= 10 ? null : "检索条数需在 1~10 之间: " + limit;
        } catch (NumberFormatException ex) {
            return "检索条数必须是整数: " + limit;
        }
    }

    private String optionalOrderNo(Map<String, Object> arguments) {
        Object value = arguments.get("orderNo");
        return value == null ? null : String.valueOf(value);
    }

    /** 参数有两种形态：`items:[{productId,quantity}]`（新）与 `productId + quantity`（旧）。 */
    private List<Item> items(Map<String, Object> arguments) {
        List<Item> items = new ArrayList<>();
        Object raw = arguments.get("items");
        if (raw instanceof List<?> list) {
            for (Object element : list) {
                if (element instanceof Map<?, ?> map) {
                    Object productId = map.get("productId");
                    Object quantity = map.get("quantity");
                    items.add(new Item(productId == null ? null : String.valueOf(productId),
                            quantity instanceof Number number ? number.intValue() : 1));
                }
            }
        }
        if (items.isEmpty() && arguments.get("productId") != null) {
            Object quantity = arguments.get("quantity");
            items.add(new Item(String.valueOf(arguments.get("productId")),
                    quantity instanceof Number number ? number.intValue() : 1));
        }
        return items;
    }

    private record Item(String productId, int quantity) {}
}
