package com.example.aiticketassistant.application.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.aiticketassistant.domain.order.Order;
import com.example.aiticketassistant.domain.order.OrderRepository;
import com.example.aiticketassistant.domain.order.OrderStatus;
import com.example.aiticketassistant.domain.order.PaymentStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * 「这单」指哪一单（2026-11-11）。
 *
 * <p>原则：宁可少猜，也不猜错 —— 取消只找"已创建未支付"，退款/退货只找"已支付"，
 * 找不到就让工具照常要订单号（而不是硬拿一张不相干的单子去操作）。
 */
class OrderReferenceResolverTest {

    private static final Instant BASE = Instant.parse("2026-11-11T02:00:00Z");

    private static Order order(String orderNo, OrderStatus status, PaymentStatus payment, int minutesAgo) {
        return new Order(new com.example.aiticketassistant.domain.order.OrderId("id-" + orderNo), orderNo, "CUST-1001", new BigDecimal("299.00"),
                status, payment, BASE.minusSeconds(minutesAgo * 60L), BASE.minusSeconds(minutesAgo * 60L));
    }

    private static OrderReferenceResolver resolver(Order... orders) {
        return new OrderReferenceResolver(new FakeOrderRepository(List.of(orders)));
    }

    @Test
    void cancelTargetsTheMostRecentUnpaidOrder() {
        OrderReferenceResolver resolver = resolver(
                order("ORD-PAID", OrderStatus.PAID, PaymentStatus.PAID, 1),
                order("ORD-NEW", OrderStatus.CREATED, PaymentStatus.UNPAID, 5),
                order("ORD-OLD", OrderStatus.CREATED, PaymentStatus.UNPAID, 60));

        assertThat(resolver.recentCancellable("CUST-1001")).contains("ORD-NEW");
    }

    @Test
    void refundAndReturnTargetTheMostRecentPaidOrder() {
        OrderReferenceResolver resolver = resolver(
                order("ORD-UNPAID", OrderStatus.CREATED, PaymentStatus.UNPAID, 1),
                order("ORD-PAID", OrderStatus.DELIVERED, PaymentStatus.PAID, 30));

        assertThat(resolver.recentPaid("CUST-1001")).contains("ORD-PAID");
    }

    @Test
    void returnsEmptyWhenNothingMatchesSoTheToolStillAsksForTheOrderNo() {
        OrderReferenceResolver resolver = resolver(
                order("ORD-PAID", OrderStatus.PAID, PaymentStatus.PAID, 1));

        // 没有未支付订单 → 取消不猜（不能去取消一张已支付的单子）
        assertThat(resolver.recentCancellable("CUST-1001")).isEmpty();
        // 没有订单的客户 → 都空
        assertThat(new OrderReferenceResolver(new FakeOrderRepository(List.of())).recentAny("CUST-1001")).isEmpty();
        assertThat(resolver.recentAny("")).isEmpty();
    }

    @Test
    void orderQueryFallsBackToTheMostRecentOrderOfAnyStatus() {
        OrderReferenceResolver resolver = resolver(
                order("ORD-LATEST", OrderStatus.CANCELLED, PaymentStatus.UNPAID, 2),
                order("ORD-OLDER", OrderStatus.PAID, PaymentStatus.PAID, 40));

        assertThat(resolver.recentAny("CUST-1001")).contains("ORD-LATEST");
    }

    private record FakeOrderRepository(List<Order> orders) implements OrderRepository {

        @Override
        public Optional<Order> findByOrderNo(String orderNo) {
            return orders.stream().filter(order -> order.orderNo().equals(orderNo)).findFirst();
        }

        @Override
        public Optional<Order> findLatestByCustomerId(String customerId) {
            return orders.stream().filter(order -> order.customerId().equals(customerId)).findFirst();
        }

        @Override
        public List<Order> findRecentByCustomerId(String customerId, int limit) {
            return orders.stream()
                    .filter(order -> order.customerId().equals(customerId))
                    .sorted((left, right) -> right.createdAt().compareTo(left.createdAt()))
                    .limit(limit)
                    .toList();
        }

        @Override
        public Order save(Order order) {
            return order;
        }
    }
}
