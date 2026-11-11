-- =====================================================================================
-- V11：购物车 / 账户（优惠券）/ 售后（退款申请）三张表
-- =====================================================================================
-- 为什么要有：路由里有 isCartAction / isAccountQuery / isOrderLifecycleAction 三路，
-- 但工具清单里一直没有对应的工具 —— 用户说「加入购物车」「取消订单」「我要退款」时，
-- 模型只能自由发挥（实测出现：工具 0 个直接哑火、把"加购"当成下单、把"取消"只建成工单）。
-- 这一版把这三类需求做成**真工具**：有表、有校验、有状态，答案不再靠模型猜。
--
-- 设计口径（与项目既有的"两阶段 + 从不自动扣款"保持一致）：
--   * 购物车：低风险，直接执行；下单仍走既有 order_confirmations 两阶段确认。
--   * 取消订单：只允许取消 **CREATED 且 UNPAID** 的订单（未支付、未发货），并释放预留库存；
--     已支付订单一律引导走退款申请。
--   * 退款：不在对话里动钱 —— 落一条 REFUND_REQUESTED 申请单，等人工审批（演示环境的合理边界）。
-- =====================================================================================

CREATE TABLE cart_items (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    customer_id VARCHAR(64) NOT NULL,
    product_id VARCHAR(64) NOT NULL,
    quantity INT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_cart_items_customer_product UNIQUE (customer_id, product_id),
    CONSTRAINT fk_cart_items_product FOREIGN KEY (product_id) REFERENCES products (id),
    INDEX idx_cart_items_customer (customer_id)
);

CREATE TABLE customer_coupons (
    id VARCHAR(64) PRIMARY KEY,
    customer_id VARCHAR(64) NOT NULL,
    code VARCHAR(64) NOT NULL,
    title VARCHAR(128) NOT NULL,
    amount_off DECIMAL(18,2) NOT NULL,
    min_spend DECIMAL(18,2) NOT NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    INDEX idx_customer_coupons_customer (customer_id)
);

CREATE TABLE refund_requests (
    id VARCHAR(64) PRIMARY KEY,
    order_no VARCHAR(64) NOT NULL,
    customer_id VARCHAR(64) NOT NULL,
    amount DECIMAL(18,2) NOT NULL,
    reason VARCHAR(255) NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    INDEX idx_refund_requests_customer (customer_id),
    INDEX idx_refund_requests_order (order_no)
);

-- 演示数据：CUST-1001 的三张优惠券（一张已过期，用来验证"过期券不算"）
INSERT INTO customer_coupons (id, customer_id, code, title, amount_off, min_spend, expires_at, status,
                              created_at, updated_at)
VALUES
  ('CPN-1001-WELCOME', 'CUST-1001', 'WELCOME30', '新人立减 30 元', 30.00, 199.00, '2027-12-31 23:59:59', 'UNUSED',
   CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
  ('CPN-1001-SUMMER', 'CUST-1001', 'SUMMER50', '夏季满 399 减 50', 50.00, 399.00, '2027-08-31 23:59:59', 'UNUSED',
   CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
  ('CPN-1001-EXPIRED', 'CUST-1001', 'OLD2024', '过期券（已失效，不应出现在可用列表）', 20.00, 99.00,
   '2024-01-01 00:00:00', 'UNUSED', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6));
