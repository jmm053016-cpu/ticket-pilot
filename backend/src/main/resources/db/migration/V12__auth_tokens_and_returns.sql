-- =====================================================================================
-- V12：登录令牌（鉴权）/ 退货申请
-- =====================================================================================
-- 为什么要有：
--   * 全站原先"无鉴权"：customerId 由请求参数传入，改个参数就能查别人订单与会话历史。
--     这一版把 customerId 绑定到**令牌**上（登录后服务端认令牌，不再信参数）。
--     演示环境没有密码体系：login 只校验 customerId 存在，属于"演示级鉴权"，
--     但它把"换个参数就能越权"这条真实漏洞堵住了（详见 §5.7）。
--   * 退货 ≠ 退款：退款是"钱退回来"，退货是"货寄回来"。上一版只有退款，
--     用户说「我要退货」只能被拒绝，这一版落 return_requests + 工单。
-- =====================================================================================

CREATE TABLE auth_tokens (
    token VARCHAR(64) PRIMARY KEY,
    customer_id VARCHAR(64) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    revoked BOOLEAN NOT NULL DEFAULT FALSE,
    INDEX idx_auth_tokens_customer (customer_id),
    INDEX idx_auth_tokens_expires (expires_at)
);

CREATE TABLE return_requests (
    id VARCHAR(64) PRIMARY KEY,
    order_no VARCHAR(64) NOT NULL,
    customer_id VARCHAR(64) NOT NULL,
    reason VARCHAR(255) NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    INDEX idx_return_requests_customer (customer_id),
    INDEX idx_return_requests_order (order_no),
    -- 同一订单不允许并发重复提交退货（退款那张表还没这个约束，见 §11.1 待办）
    CONSTRAINT uk_return_requests_order UNIQUE (order_no)
);
