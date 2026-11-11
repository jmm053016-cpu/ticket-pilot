package com.example.aiticketassistant.domain.order;

import java.time.Instant;

/**
 * 退货申请单（2026-11-09；2026-11-09 V13 补审核字段）。
 *
 * <p>退货 ≠ 退款：退款是"钱退回来"，退货是"货寄回来，验货后再退款"。
 *
 * <p>状态机：`REQUESTED`（待审）→ `APPROVED`（同意寄回，等收货验货）/ `REJECTED`（驳回）。
 * 验货通过后的退款走退款流程，不在本状态机里。
 */
public record ReturnRequest(String id, String orderNo, String customerId, String reason,
                            String status, String ticketId, String reviewNote,
                            String reviewedBy, Instant reviewedAt, Instant createdAt) {

    public static final String STATUS_REQUESTED = "REQUESTED";
    public static final String STATUS_APPROVED = "APPROVED";
    public static final String STATUS_REJECTED = "REJECTED";

    /** 兼容老调用方（申请时还没有审核信息）。 */
    public ReturnRequest(String id, String orderNo, String customerId, String reason,
                         String status, Instant createdAt) {
        this(id, orderNo, customerId, reason, status, null, null, null, null, createdAt);
    }

    public boolean pending() {
        return STATUS_REQUESTED.equals(status);
    }

    public ReturnRequest approve(String reviewer, String note) {
        requirePending();
        return new ReturnRequest(id, orderNo, customerId, reason, STATUS_APPROVED,
                ticketId, note, reviewedByOf(reviewer), Instant.now(), createdAt);
    }

    public ReturnRequest reject(String reviewer, String note) {
        requirePending();
        return new ReturnRequest(id, orderNo, customerId, reason, STATUS_REJECTED,
                ticketId, note, reviewedByOf(reviewer), Instant.now(), createdAt);
    }

    public ReturnRequest withTicket(String ticketId) {
        return new ReturnRequest(id, orderNo, customerId, reason, status,
                ticketId, reviewNote, reviewedBy, reviewedAt, createdAt);
    }

    private void requirePending() {
        if (!pending()) {
            throw new IllegalStateException("退货申请已处理过（当前状态 %s），不能重复审核: %s"
                    .formatted(status, id));
        }
    }

    private static String reviewedByOf(String reviewer) {
        return reviewer == null || reviewer.isBlank() ? "staff" : reviewer.trim();
    }
}
