package com.example.aiticketassistant.application.order;

import com.example.aiticketassistant.domain.order.Order;
import com.example.aiticketassistant.domain.order.OrderRepository;
import com.example.aiticketassistant.domain.order.OrderStatus;
import com.example.aiticketassistant.domain.order.PaymentStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 用户说「这单」「刚才那个」却没给订单号时，判断他指的是哪一单（2026-11-11）。
 *
 * <p>起因：模型选对了动作（`CANCEL_ORDER` / `REQUEST_RETURN`），但用户没写订单号，
 * 于是只能回一句"请提供订单号"。可实际上客户大概率是在说**最近的那一单**。
 *
 * <p>规则（宁可少猜，也不猜错单子 —— 猜错就是对着别人的/不相干的订单做取消）：
 * <ul>
 *   <li>取消：只取**最近一笔可取消**的（{@code CREATED} 且未支付）；找不到就返回空，让工具照常要订单号；</li>
 *   <li>退款 / 退货：只取**最近一笔已支付**的（未支付的没有钱可退，也没货可退）；</li>
 *   <li>查订单：取最近一笔（不限状态）。</li>
 * </ul>
 *
 * <p>**不做的事**：不跨状态兜底（取消不会退而求其次去取消一笔已支付订单）、
 * 不按商品名反查订单（那需要解析"那件夹克"指哪张单，容易猜错）。
 */
@Service
public class OrderReferenceResolver {
    /** 查询候选时拉取的近单数量上限。 */
    private static final int SCAN_LIMIT = 10;

    private final OrderRepository orderRepository;

    public OrderReferenceResolver(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    /** 取消：最近一笔「已创建且未支付」的订单。 */
    @Transactional(readOnly = true)
    public Optional<String> recentCancellable(String customerId) {
        return recent(customerId).stream()
                .filter(order -> order.orderStatus() == OrderStatus.CREATED
                        && order.paymentStatus() == PaymentStatus.UNPAID)
                .map(Order::orderNo)
                .findFirst();
    }

    /** 退款 / 退货：最近一笔「已支付」的订单。 */
    @Transactional(readOnly = true)
    public Optional<String> recentPaid(String customerId) {
        return recent(customerId).stream()
                .filter(order -> order.paymentStatus() == PaymentStatus.PAID)
                .map(Order::orderNo)
                .findFirst();
    }

    /** 查订单：最近一笔，不限状态。 */
    @Transactional(readOnly = true)
    public Optional<String> recentAny(String customerId) {
        return recent(customerId).stream().map(Order::orderNo).findFirst();
    }

    private List<Order> recent(String customerId) {
        if (customerId == null || customerId.isBlank()) {
            return List.of();
        }
        List<Order> orders = orderRepository.findRecentByCustomerId(customerId, SCAN_LIMIT);
        if (orders == null || orders.isEmpty()) {
            return List.of();
        }
        // findRecentByCustomerId 已按时间倒序返回；这里再排一遍是为了不依赖实现细节
        return orders.stream()
                .sorted((left, right) -> {
                    if (left.createdAt() == null || right.createdAt() == null) {
                        return 0;
                    }
                    return right.createdAt().compareTo(left.createdAt());
                })
                .toList();
    }
}
