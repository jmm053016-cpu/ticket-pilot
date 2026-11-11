package com.example.aiticketassistant.domain.order;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 退款申请单：对话里只"申请"，不动钱；状态由**审核端**推进（2026-11-09 V13）。
 *
 * <p>状态机：`REQUESTED`（待审）→ `APPROVED`（通过，走原路退款）/ `REJECTED`（驳回）。
 * 只有 `REQUESTED` 能被审核，重复审核会被拒绝 —— 这条规则收在领域里，服务层不用各写一遍。
 */
public record RefundRequest(String id, String orderNo, String customerId, BigDecimal amount,
                            String reason, String status, String ticketId, String reviewNote,
                            String reviewedBy, Instant reviewedAt, Instant createdAt) {

    public static final String STATUS_REQUESTED = "REQUESTED";
    public static final String STATUS_APPROVED = "APPROVED";
    public static final String STATUS_REJECTED = "REJECTED";

    /** 兼容老调用方（申请时还没有审核信息）。 */
    public RefundRequest(String id, String orderNo, String customerId, BigDecimal amount,
                         String reason, String status, Instant createdAt) {
        this(id, orderNo, customerId, amount, reason, status, null, null, null, null, createdAt);
    }

    public boolean pending() {
        return STATUS_REQUESTED.equals(status);
    }

    /** 审核通过：只有待审状态可以，且必须留下审核人。 */
    public RefundRequest approve(String reviewer, String note) {
        requirePending();
        return new RefundRequest(id, orderNo, customerId, amount, reason, STATUS_APPROVED,
                ticketId, note, reviewedByOf(reviewer), Instant.now(), createdAt);
    }

    /** 审核驳回：同样只允许待审状态。 */
    public RefundRequest reject(String reviewer, String note) {
        requirePending();
        return new RefundRequest(id, orderNo, customerId, amount, reason, STATUS_REJECTED,
                ticketId, note, reviewedByOf(reviewer), Instant.now(), createdAt);
    }

    /** 绑定关联工单（申请时写入，审核端据此推进工单）。 */
    public RefundRequest withTicket(String ticketId) {
        return new RefundRequest(id, orderNo, customerId, amount, reason, status,
                ticketId, reviewNote, reviewedBy, reviewedAt, createdAt);
    }

    private void requirePending() {
        if (!pending()) {
            throw new IllegalStateException("退款申请已处理过（当前状态 %s），不能重复审核: %s"
                    .formatted(status, id));
        }
    }

    private static String reviewedByOf(String reviewer) {
        return reviewer == null || reviewer.isBlank() ? "staff" : reviewer.trim();
    }
}
