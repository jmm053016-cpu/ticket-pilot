package com.example.aiticketassistant.application.order;

import com.example.aiticketassistant.domain.order.Order;
import com.example.aiticketassistant.domain.order.OrderRepository;
import com.example.aiticketassistant.domain.order.PaymentStatus;
import com.example.aiticketassistant.domain.order.RefundRequest;
import com.example.aiticketassistant.domain.order.RefundRequestRepository;
import com.example.aiticketassistant.domain.shared.DomainException;
import com.example.aiticketassistant.domain.shared.NotFoundException;
import com.example.aiticketassistant.domain.ticket.Ticket;
import com.example.aiticketassistant.domain.ticket.TicketCategory;
import com.example.aiticketassistant.domain.ticket.TicketPriority;
import com.example.aiticketassistant.domain.ticket.TicketRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 退款申请 + 退款进度查询（2026-11-09 新增，2026-11-09 补充查询与工单）。
 *
 * <p>为什么这么做而不是"直接退款"：本项目的边界是**从不自动扣款**，反向也一样 ——
 * 对话里不动钱。所以这里落一条 `REQUESTED` 申请单，由人工/后台推进。
 *
 * <p>2026-11-09 补的两处（上一版只做了一半）：
 * <ol>
 *   <li><b>申请必须有人接手</b>：落申请单的同时建一张 `REFUND` 工单，否则 `REQUESTED` 就是终点，
 *       没人会看到这张单子。</li>
 *   <li><b>能查进度</b>：`query()` 供 `QUERY_REFUND` 用 —— 否则用户刚申请完问"我的退款到哪了"
 *       只会得到"我这边无法查看"，比不能申请更伤信任。</li>
 * </ol>
 */
@Service
public class RefundApplicationService {
    private final OrderRepository orderRepository;
    private final RefundRequestRepository refundRequestRepository;
    private final TicketRepository ticketRepository;

    public RefundApplicationService(OrderRepository orderRepository,
                                    RefundRequestRepository refundRequestRepository,
                                    TicketRepository ticketRepository) {
        this.orderRepository = orderRepository;
        this.refundRequestRepository = refundRequestRepository;
        this.ticketRepository = ticketRepository;
    }

    @Transactional
    public RefundResult request(String orderNo, String customerId, String reason) {
        String normalized = normalize(orderNo);
        Order order = orderRepository.findByOrderNo(normalized)
                .orElseThrow(() -> new NotFoundException("订单不存在: " + normalized));
        if (customerId != null && !customerId.isBlank() && !customerId.equals(order.customerId())) {
            throw new DomainException("订单不属于当前客户，不能申请退款");
        }
        if (order.paymentStatus() != PaymentStatus.PAID) {
            throw new DomainException(("该订单当前是 %s / %s，还没支付成功，无需退款 —— "
                    + "如果是想撤销，可以直接取消订单。").formatted(order.orderStatus(), order.paymentStatus()));
        }
        if (refundRequestRepository.existsByOrderNo(normalized)) {
            throw new DomainException("该订单已经有退款申请在处理中，请勿重复提交");
        }

        String requestId = "RFD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String refundReason = reason == null || reason.isBlank() ? "用户通过 AI 客服申请退款" : reason.trim();

        // 先建工单，再把 ticketId 一起落到申请单上 —— 审核端要靠它推进工单（V13）
        Ticket ticket = ticketRepository.save(Ticket.open(
                order.customerId(),
                "退款申请 %s：订单 %s".formatted(requestId, order.orderNo()),
                "金额 ¥%s；原因：%s；退款申请单 %s（状态 %s）".formatted(
                        order.amount().toPlainString(), refundReason, requestId, RefundRequest.STATUS_REQUESTED),
                TicketCategory.REFUND,
                TicketPriority.HIGH));

        RefundRequest saved = refundRequestRepository.save(new RefundRequest(
                requestId,
                order.orderNo(),
                order.customerId(),
                order.amount(),
                refundReason,
                RefundRequest.STATUS_REQUESTED,
                Instant.now()).withTicket(ticket.id().value()));

        return new RefundResult(saved.id(), saved.orderNo(), saved.amount(), saved.status(),
                ticket.id().value(),
                "退款申请已提交（%s），已转人工工单（%s）；审核通过后原路退回，结果会另行通知。"
                        .formatted(saved.id(), ticket.id().value()));
    }

    /**
     * 查询退款进度：给了订单号就查那一单，没给就列该客户最近的申请。
     *
     * <p>返回空列表表示"没有申请过" —— 调用方（{@code QueryRefundTool}）据此给不同话术，
     * 而不是含糊地说"查不到"。
     */
    @Transactional(readOnly = true)
    public List<RefundRequest> query(String customerId, String orderNo) {
        String normalized = orderNo == null ? "" : orderNo.trim().toUpperCase();
        if (!normalized.isEmpty()) {
            return refundRequestRepository.findLatestByOrderNo(normalized).stream()
                    .filter(request -> customerId == null || customerId.isBlank()
                            || customerId.equals(request.customerId()))
                    .toList();
        }
        return refundRequestRepository.findRecentByCustomer(customerId, 10);
    }

    private String normalize(String orderNo) {
        String value = orderNo == null ? "" : orderNo.trim().toUpperCase();
        if (value.isEmpty()) {
            throw new DomainException("请提供订单号（例如 ORD-AI-XXXXXXXX）");
        }
        return value;
    }

    public record RefundResult(String refundId, String orderNo, java.math.BigDecimal amount,
                               String status, String ticketId, String message) {}
}
