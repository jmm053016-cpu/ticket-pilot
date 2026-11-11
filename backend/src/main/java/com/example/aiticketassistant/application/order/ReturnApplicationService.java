package com.example.aiticketassistant.application.order;

import com.example.aiticketassistant.domain.order.Order;
import com.example.aiticketassistant.domain.order.OrderRepository;
import com.example.aiticketassistant.domain.order.PaymentStatus;
import com.example.aiticketassistant.domain.order.ReturnRequest;
import com.example.aiticketassistant.domain.order.ReturnRequestRepository;
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
 * 退货申请 + 进度查询（2026-11-09）。
 *
 * <p>与退款的区别（别混）：
 * <ul>
 *   <li><b>退款</b> = 钱退回来，落 `refund_requests`，不涉及物流。</li>
 *   <li><b>退货</b> = 货寄回来，落 `return_requests`，需要人工核验商品后才会退款。</li>
 * </ul>
 *
 * <p>边界：对话里**只受理申请**，不生成寄回单号、不退款、不自动改订单状态（等人工）。
 * 未支付订单不能退货（应该直接取消订单）。
 */
@Service
public class ReturnApplicationService {
    private final OrderRepository orderRepository;
    private final ReturnRequestRepository returnRequestRepository;
    private final TicketRepository ticketRepository;

    public ReturnApplicationService(OrderRepository orderRepository,
                                    ReturnRequestRepository returnRequestRepository,
                                    TicketRepository ticketRepository) {
        this.orderRepository = orderRepository;
        this.returnRequestRepository = returnRequestRepository;
        this.ticketRepository = ticketRepository;
    }

    @Transactional
    public ReturnResult request(String orderNo, String customerId, String reason) {
        String normalized = normalize(orderNo);
        Order order = orderRepository.findByOrderNo(normalized)
                .orElseThrow(() -> new NotFoundException("订单不存在: " + normalized));
        if (customerId != null && !customerId.isBlank() && !customerId.equals(order.customerId())) {
            throw new DomainException("订单不属于当前客户，不能申请退货");
        }
        if (order.paymentStatus() != PaymentStatus.PAID) {
            throw new DomainException(("该订单当前是 %s / %s，还没支付成功，无需退货 —— "
                    + "如果是想撤销，可以直接取消订单。").formatted(order.orderStatus(), order.paymentStatus()));
        }
        if (returnRequestRepository.existsByOrderNo(normalized)) {
            throw new DomainException("该订单已经有退货申请在处理中，请勿重复提交");
        }

        String returnReason = reason == null || reason.isBlank() ? "用户通过 AI 客服申请退货" : reason.trim();
        String requestId = "RTN-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        // 先建工单，再把 ticketId 落到申请单（审核端要靠它推进工单，V13）
        Ticket ticket = ticketRepository.save(Ticket.open(
                order.customerId(),
                "退货申请 %s：订单 %s".formatted(requestId, order.orderNo()),
                "退货申请单 %s（状态 %s）；原因：%s；请核验商品后引导寄回并处理退款。".formatted(
                        requestId, ReturnRequest.STATUS_REQUESTED, returnReason),
                TicketCategory.LOGISTICS,
                TicketPriority.HIGH));

        ReturnRequest saved = returnRequestRepository.save(new ReturnRequest(
                requestId, order.orderNo(), order.customerId(), returnReason,
                ReturnRequest.STATUS_REQUESTED, Instant.now()).withTicket(ticket.id().value()));

        return new ReturnResult(saved.id(), saved.orderNo(), saved.status(), ticket.id().value(),
                "退货申请已受理（%s），已转人工工单（%s）；请按客服指引寄回商品，验货通过后原路退款。"
                        .formatted(saved.id(), ticket.id().value()));
    }

    @Transactional(readOnly = true)
    public List<ReturnRequest> query(String customerId, String orderNo) {
        String normalized = orderNo == null ? "" : orderNo.trim().toUpperCase();
        if (!normalized.isEmpty()) {
            return returnRequestRepository.findLatestByOrderNo(normalized).stream()
                    .filter(request -> customerId == null || customerId.isBlank()
                            || customerId.equals(request.customerId()))
                    .toList();
        }
        return returnRequestRepository.findRecentByCustomer(customerId, 10);
    }

    private String normalize(String orderNo) {
        String value = orderNo == null ? "" : orderNo.trim().toUpperCase();
        if (value.isEmpty()) {
            throw new DomainException("请提供订单号（例如 ORD-AI-XXXXXXXX）");
        }
        return value;
    }

    public record ReturnResult(String returnId, String orderNo, String status, String ticketId, String message) {}
}
