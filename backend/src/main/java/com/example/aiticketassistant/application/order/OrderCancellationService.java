package com.example.aiticketassistant.application.order;

import com.example.aiticketassistant.domain.catalog.ProductInventory;
import com.example.aiticketassistant.domain.catalog.ProductRepository;
import com.example.aiticketassistant.domain.order.Order;
import com.example.aiticketassistant.domain.order.OrderItem;
import com.example.aiticketassistant.domain.order.OrderItemRepository;
import com.example.aiticketassistant.domain.order.OrderRepository;
import com.example.aiticketassistant.domain.order.OrderStatus;
import com.example.aiticketassistant.domain.shared.DomainException;
import com.example.aiticketassistant.domain.shared.NotFoundException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 取消订单（2026-11-09 新增）。
 *
 * <p>为什么要有它：用户说「帮我取消订单」时，之前是"工具 0 个 → 没能给出有效回答"，
 * 或者说「把我的订单取消掉」只建了张工单、订单其实没动。现在是真的取消：
 * 校验归属与状态 → 释放预留库存 → 订单置 `CANCELLED`，三步在一个事务里。
 *
 * <p>**边界（有意为之）**：只允许取消 `CREATED + UNPAID`（未支付、未发货）的订单。
 * 已支付的一律拒绝并引导走退款申请 —— 对话里不动钱。
 */
@Service
public class OrderCancellationService {
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final ProductRepository productRepository;

    public OrderCancellationService(OrderRepository orderRepository,
                                    OrderItemRepository orderItemRepository,
                                    ProductRepository productRepository) {
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
        this.productRepository = productRepository;
    }

    @Transactional
    public CancellationResult cancel(String orderNo, String customerId) {
        String normalized = normalize(orderNo);
        Order order = orderRepository.findByOrderNo(normalized)
                .orElseThrow(() -> new NotFoundException("订单不存在: " + normalized));
        if (customerId != null && !customerId.isBlank() && !customerId.equals(order.customerId())) {
            throw new DomainException("订单不属于当前客户，不能取消");
        }
        if (!order.canCancel()) {
            throw new DomainException(("订单当前状态不可取消（%s / %s）。已支付订单请申请退款，"
                    + "已发货订单请联系人工客服。").formatted(order.orderStatus(), order.paymentStatus()));
        }

        List<OrderItem> items = orderItemRepository.findByOrderId(order.id().value());
        List<String> released = new ArrayList<>();
        for (OrderItem item : items) {
            productRepository.findInventory(item.productId()).ifPresent(inventory ->
                    productRepository.saveInventory(releaseStock(inventory, item.quantity())));
            released.add(item.name() + " × " + item.quantity());
        }

        order.cancel();
        orderRepository.save(order);

        return new CancellationResult(
                order.orderNo(),
                order.orderStatus().name(),
                released,
                "订单已取消，预留库存已释放；如已支付请改用「申请退款」。");
    }

    private ProductInventory releaseStock(ProductInventory inventory, int quantity) {
        try {
            return inventory.release(quantity);
        } catch (IllegalArgumentException ex) {
            return inventory;   // 历史数据里预留量可能已经是 0，释放失败不该让取消失败
        }
    }

    private String normalize(String orderNo) {
        String value = orderNo == null ? "" : orderNo.trim().toUpperCase();
        if (value.isEmpty()) {
            throw new DomainException("请提供订单号（例如 ORD-AI-XXXXXXXX）");
        }
        return value;
    }

    public record CancellationResult(String orderNo, String orderStatus, List<String> releasedItems, String message) {}
}
