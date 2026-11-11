-- 多商品待确认单：确认单从"一单一商品"升级为"一单多明细"。
--
-- 背景：用户说「我要买米色帆布双肩包和藏青帆布鞋」时，旧模型只能一单一件，
-- 于是要么丢单、要么生成两张卡各确认一次。2026-11-05 起改成"一张确认单 + N 行明细"，
-- 用户确认一次就把多件一起下单。
--
-- 注意顺序：先建明细表 → 回填历史单 → 再删掉 order_confirmations 上的单商品冗余列
-- （删列前必须先删外键，否则 MySQL 报 1553）。

CREATE TABLE order_confirmation_items (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    confirmation_id VARCHAR(64) NOT NULL,
    product_id VARCHAR(64) NOT NULL,
    product_name VARCHAR(255) NOT NULL,
    quantity INT NOT NULL,
    unit_price DECIMAL(18,2) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    INDEX idx_order_confirmation_items_confirmation (confirmation_id),
    CONSTRAINT fk_order_confirmation_items_confirmation FOREIGN KEY (confirmation_id)
        REFERENCES order_confirmations (id),
    CONSTRAINT fk_order_confirmation_items_product FOREIGN KEY (product_id)
        REFERENCES products (id)
);

-- 回填：历史上每张确认单只有一个商品，转成一行明细（含已在库的 PENDING / 已确认单）
INSERT INTO order_confirmation_items
    (confirmation_id, product_id, product_name, quantity, unit_price, created_at, updated_at)
SELECT c.id, c.product_id, p.name, c.quantity, c.unit_price, c.created_at, c.updated_at
FROM order_confirmations c
JOIN products p ON p.id = c.product_id;

-- 单商品时代的冗余列下线：明细表是唯一事实来源，避免两处数据打架
ALTER TABLE order_confirmations
    DROP FOREIGN KEY fk_order_confirmations_product,
    DROP COLUMN product_id,
    DROP COLUMN quantity,
    DROP COLUMN unit_price;
