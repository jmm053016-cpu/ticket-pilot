package com.example.aiticketassistant.application.review;

import com.example.aiticketassistant.domain.order.RefundRequest;
import com.example.aiticketassistant.domain.order.RefundRequestRepository;
import com.example.aiticketassistant.domain.order.ReturnRequest;
import com.example.aiticketassistant.domain.order.ReturnRequestRepository;
import com.example.aiticketassistant.domain.shared.NotFoundException;
import com.example.aiticketassistant.domain.ticket.Ticket;
import com.example.aiticketassistant.domain.ticket.TicketId;
import com.example.aiticketassistant.domain.ticket.TicketRepository;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 售后审核（2026-11-09，V13）。
 *
 * <p>为什么要有：V11/V12 只做到"用户提交申请 + 自动建工单"，申请单停在 `REQUESTED`，
 * 审核只能人肉翻库。这一版提供审核端需要的四个动作（退款/退货 各 通过、驳回），并把结果
 * **写回申请单**（状态 / 审核人 / 备注 / 时间）与**推进关联工单**（通过→resolve，驳回→resolve + 说明）。
 *
 * <p>三条规则（都在这里统一，不散落到控制器）：
 * <ol>
 *   <li>只有 `REQUESTED` 能审核；重复审核直接报错（领域里也挡了一层）。</li>
 *   <li>驳回**必须写原因** —— 用户会看到这句话，空备注等于没解释。</li>
 *   <li>审核人缺省记 `staff`；不阻断流程，但审计上能看出是谁处理的。</li>
 * </ol>
 *
 * <p>边界：本项目**不动钱**，通过只代表"同意退款/同意寄回"，真实打款在支付系统里完成。
 */
@Service
public class AfterSalesReviewService {
    private static final Logger log = LoggerFactory.getLogger(AfterSalesReviewService.class);
    private static final int MAX_NOTE = 200;

    private final RefundRequestRepository refundRepository;
    private final ReturnRequestRepository returnRepository;
    private final TicketRepository ticketRepository;

    public AfterSalesReviewService(RefundRequestRepository refundRepository,
                                   ReturnRequestRepository returnRepository,
                                   TicketRepository ticketRepository) {
        this.refundRepository = refundRepository;
        this.returnRepository = returnRepository;
        this.ticketRepository = ticketRepository;
    }

    // ===================== 列表 =====================

    @Transactional(readOnly = true)
    public List<RefundRequest> refunds(String status) {
        return refundRepository.findByStatus(normalizeStatus(status));
    }

    @Transactional(readOnly = true)
    public List<ReturnRequest> returns(String status) {
        return returnRepository.findByStatus(normalizeStatus(status));
    }

    // ===================== 退款审核 =====================

    @Transactional
    public RefundRequest approveRefund(String refundId, String reviewer, String note) {
        RefundRequest request = requireRefund(refundId);
        RefundRequest reviewed = refundRepository.save(request.approve(reviewer, trim(note)));
        closeTicket(reviewed.ticketId(), "退款申请 %s 已通过：%s".formatted(reviewed.id(), reviewed.orderNo()));
        log.info("退款审核通过 {} 订单 {} 审核人 {}", reviewed.id(), reviewed.orderNo(), reviewed.reviewedBy());
        return reviewed;
    }

    @Transactional
    public RefundRequest rejectRefund(String refundId, String reviewer, String note) {
        RefundRequest request = requireRefund(refundId);
        requireReason(note, "驳回退款");
        RefundRequest reviewed = refundRepository.save(request.reject(reviewer, trim(note)));
        closeTicket(reviewed.ticketId(),
                "退款申请 %s 已驳回：%s；备注：%s".formatted(reviewed.id(), reviewed.orderNo(), reviewed.reviewNote()));
        log.info("退款审核驳回 {} 订单 {} 审核人 {}", reviewed.id(), reviewed.orderNo(), reviewed.reviewedBy());
        return reviewed;
    }

    // ===================== 退货审核 =====================

    @Transactional
    public ReturnRequest approveReturn(String returnId, String reviewer, String note) {
        ReturnRequest request = requireReturn(returnId);
        ReturnRequest reviewed = returnRepository.save(request.approve(reviewer, trim(note)));
        closeTicket(reviewed.ticketId(), "退货申请 %s 已通过，等待寄回：%s".formatted(reviewed.id(), reviewed.orderNo()));
        log.info("退货审核通过 {} 订单 {} 审核人 {}", reviewed.id(), reviewed.orderNo(), reviewed.reviewedBy());
        return reviewed;
    }

    @Transactional
    public ReturnRequest rejectReturn(String returnId, String reviewer, String note) {
        ReturnRequest request = requireReturn(returnId);
        requireReason(note, "驳回退货");
        ReturnRequest reviewed = returnRepository.save(request.reject(reviewer, trim(note)));
        closeTicket(reviewed.ticketId(),
                "退货申请 %s 已驳回：%s；备注：%s".formatted(reviewed.id(), reviewed.orderNo(), reviewed.reviewNote()));
        log.info("退货审核驳回 {} 订单 {} 审核人 {}", reviewed.id(), reviewed.orderNo(), reviewed.reviewedBy());
        return reviewed;
    }

    // ===================== 内部 =====================

    private RefundRequest requireRefund(String refundId) {
        return refundRepository.findById(refundId)
                .orElseThrow(() -> new NotFoundException("退款申请不存在: " + refundId));
    }

    private ReturnRequest requireReturn(String returnId) {
        return returnRepository.findById(returnId)
                .orElseThrow(() -> new NotFoundException("退货申请不存在: " + returnId));
    }

    /** 关联工单收口：找不到工单只 warn（审核结果本身不能因此失败）。 */
    private void closeTicket(String ticketId, String resolution) {
        if (ticketId == null || ticketId.isBlank()) {
            return;
        }
        try {
            ticketRepository.findById(new TicketId(ticketId)).ifPresent(ticket -> {
                ticket.resolve(resolution);
                ticketRepository.save(ticket);
            });
        } catch (Exception ex) {
            log.warn("售后审核后推进工单失败（不影响审核结果）: ticketId={} err={}", ticketId, ex.getMessage());
        }
    }

    private String normalizeStatus(String status) {
        if (status == null || status.isBlank()) {
            return RefundRequest.STATUS_REQUESTED;
        }
        String normalized = status.trim().toUpperCase();
        return switch (normalized) {
            case RefundRequest.STATUS_REQUESTED, RefundRequest.STATUS_APPROVED, RefundRequest.STATUS_REJECTED -> normalized;
            default -> throw new IllegalArgumentException("不支持的状态: " + status);
        };
    }

    private void requireReason(String note, String action) {
        if (note == null || note.isBlank()) {
            throw new IllegalArgumentException(action + "必须填写原因（用户会看到这句话）");
        }
    }

    private String trim(String note) {
        if (note == null) {
            return null;
        }
        String value = note.trim();
        return value.length() <= MAX_NOTE ? value : value.substring(0, MAX_NOTE);
    }

    /** 审核结果视图（给 REST 用，避免把领域对象结构直接暴露给前端）。 */
    public record ReviewView(String id, String orderNo, String customerId, String status, String ticketId,
                             String reviewNote, String reviewedBy, String reviewedAt, String createdAt) {

        public static ReviewView of(RefundRequest request) {
            return new ReviewView(request.id(), request.orderNo(), request.customerId(), request.status(),
                    request.ticketId(), request.reviewNote(), request.reviewedBy(),
                    instant(request.reviewedAt()), instant(request.createdAt()));
        }

        public static ReviewView of(ReturnRequest request) {
            return new ReviewView(request.id(), request.orderNo(), request.customerId(), request.status(),
                    request.ticketId(), request.reviewNote(), request.reviewedBy(),
                    instant(request.reviewedAt()), instant(request.createdAt()));
        }

        private static String instant(Instant value) {
            return value == null ? "" : value.toString();
        }
    }
}
