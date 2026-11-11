-- =====================================================================================
-- V13：退款/退货申请单的**审核**字段（审核端界面用）
-- =====================================================================================
-- 为什么要有：V11/V12 只做到"用户提交申请 + 建工单"，申请单停在 REQUESTED，
-- 审核只能靠人肉翻库。这一版把审核结果落回申请单：
--   status: REQUESTED → APPROVED / REJECTED
--   同时记录审核人、审核备注、审核时间，并**存下关联工单 id**（原来只在工具返回值里，读不出来）。
-- =====================================================================================

ALTER TABLE refund_requests
    ADD COLUMN ticket_id VARCHAR(64) NULL,
    ADD COLUMN review_note VARCHAR(255) NULL,
    ADD COLUMN reviewed_by VARCHAR(64) NULL,
    ADD COLUMN reviewed_at TIMESTAMP(6) NULL;

ALTER TABLE return_requests
    ADD COLUMN ticket_id VARCHAR(64) NULL,
    ADD COLUMN review_note VARCHAR(255) NULL,
    ADD COLUMN reviewed_by VARCHAR(64) NULL,
    ADD COLUMN reviewed_at TIMESTAMP(6) NULL;

-- 审核台按状态翻页/筛选：给 status 建索引
CREATE INDEX idx_refund_requests_status ON refund_requests (status, created_at);
CREATE INDEX idx_return_requests_status ON return_requests (status, created_at);
