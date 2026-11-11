-- =====================================================================================
-- V8：扩充服装商品目录（10 件 → 30 件），用于让检索 / 预算测算 / 下单链路有足够测试样本
-- =====================================================================================
-- 为什么必须是新迁移：V5 是已应用的种子，改它会破坏 Flyway checksum，已有库直接启动失败。
--
-- 新增 20 件覆盖：上装（T恤/衬衫/卫衣/毛衣/开衫）、下装（牛仔裤/休闲裤/短裤）、
-- 外套（夹克/风衣/羽绒服/大衣）、裙装（连衣裙/半身裙）、鞋、包与配饰；
-- 价格区间 ¥109 ~ ¥1299，其中：
--   * 最低价仍是 ¥99（CLOTH-TEE-001）—— 保证"1000 元最多买几件（可重复）"的既有实测结论 10 件/¥990 不变；
--   * ¥699 仍然唯一（CLOTH-COAT-009）—— RetrievalEvaluationTest 的"精确价格问号只应命中同一件"依赖它；
--   * 刻意保留几个重复价位（169 / 199 / 259 / 299 / 399），用来测同价排序与"并列噪音"。
--
-- 三张表必须同时写，缺一张就会出现"能查到商品但不能下单"或"下单拿到空价格"：
--   1) products          —— 下单与 /products 接口的事实来源
--   2) product_inventory —— CreateOrderConfirmationService 校验库存
--   3) knowledge_chunks  —— 检索与 PriceCatalogService（预算测算）的事实来源
--
-- 格式约定（改数据时别破坏）：
--   * 单品片段：商品编号 CLOTH-XXX-NNN，商品名称 <名字>，服装商品，价格 ¥<价>。   ← PriceCatalogService 的单品正则
--   * 汇总片段：<名字> CLOTH-XXX-NNN ¥<价>；                                    ← 汇总正则（¥ 号前后空格不能少）
--   * 单品片段里**不要**再写「，价格刚好X元。」这类为凑关键词加的填充语（V7 已专门清理过一次）。
-- =====================================================================================

INSERT INTO products (id, name, price, active, created_at, updated_at)
VALUES
  ('CLOTH-TEE-011', '白色圆领短袖T恤', 129.00, true, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
  ('CLOTH-TEE-012', '条纹短袖T恤', 149.00, true, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
  ('CLOTH-SHIRT-013', '白色免烫衬衫', 249.00, true, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
  ('CLOTH-SHIRT-014', '格纹法兰绒衬衫', 219.00, true, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
  ('CLOTH-HOODIE-015', '灰色拉链连帽卫衣', 279.00, true, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
  ('CLOTH-SWEATER-016', '燕麦色高领毛衣', 239.00, true, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
  ('CLOTH-CARDIGAN-017', '深灰针织开衫', 269.00, true, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
  ('CLOTH-JEANS-018', '浅蓝阔腿牛仔裤', 319.00, true, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
  ('CLOTH-PANTS-019', '黑色直筒休闲裤', 259.00, true, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
  ('CLOTH-SHORTS-020', '卡其五分短裤', 139.00, true, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
  ('CLOTH-JACKET-021', '军绿工装夹克', 459.00, true, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
  ('CLOTH-WINDBREAKER-022', '米白风衣外套', 529.00, true, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
  ('CLOTH-DOWN-023', '黑色短款羽绒服', 899.00, true, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
  ('CLOTH-COAT-024', '驼色双面呢大衣', 1299.00, true, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
  ('CLOTH-DRESS-025', '法式方领连衣裙', 369.00, true, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
  ('CLOTH-SKIRT-026', '百褶中长半身裙', 179.00, true, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
  ('CLOTH-SNEAKER-027', '白色厚底运动鞋', 399.00, true, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
  ('CLOTH-CANVAS-028', '藏青帆布鞋', 199.00, true, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
  ('CLOTH-BAG-029', '米色帆布双肩包', 299.00, true, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
  ('CLOTH-SCARF-030', '灰色羊毛围巾', 109.00, true, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6));

INSERT INTO product_inventory (product_id, stock_on_hand, reserved_stock, updated_at)
VALUES
  ('CLOTH-TEE-011', 60, 0, CURRENT_TIMESTAMP(6)),
  ('CLOTH-TEE-012', 55, 0, CURRENT_TIMESTAMP(6)),
  ('CLOTH-SHIRT-013', 40, 0, CURRENT_TIMESTAMP(6)),
  ('CLOTH-SHIRT-014', 32, 0, CURRENT_TIMESTAMP(6)),
  ('CLOTH-HOODIE-015', 28, 0, CURRENT_TIMESTAMP(6)),
  ('CLOTH-SWEATER-016', 26, 0, CURRENT_TIMESTAMP(6)),
  ('CLOTH-CARDIGAN-017', 22, 0, CURRENT_TIMESTAMP(6)),
  ('CLOTH-JEANS-018', 24, 0, CURRENT_TIMESTAMP(6)),
  ('CLOTH-PANTS-019', 30, 0, CURRENT_TIMESTAMP(6)),
  ('CLOTH-SHORTS-020', 44, 0, CURRENT_TIMESTAMP(6)),
  ('CLOTH-JACKET-021', 16, 0, CURRENT_TIMESTAMP(6)),
  ('CLOTH-WINDBREAKER-022', 14, 0, CURRENT_TIMESTAMP(6)),
  ('CLOTH-DOWN-023', 9, 0, CURRENT_TIMESTAMP(6)),
  ('CLOTH-COAT-024', 6, 0, CURRENT_TIMESTAMP(6)),
  ('CLOTH-DRESS-025', 20, 0, CURRENT_TIMESTAMP(6)),
  ('CLOTH-SKIRT-026', 38, 0, CURRENT_TIMESTAMP(6)),
  ('CLOTH-SNEAKER-027', 35, 0, CURRENT_TIMESTAMP(6)),
  ('CLOTH-CANVAS-028', 48, 0, CURRENT_TIMESTAMP(6)),
  ('CLOTH-BAG-029', 42, 0, CURRENT_TIMESTAMP(6)),
  ('CLOTH-SCARF-030', 70, 0, CURRENT_TIMESTAMP(6));

INSERT INTO knowledge_chunks (id, document_id, title, category, source, content)
VALUES
  ('CHK-CATALOG-CLOTHING-TEE-011', 'DOC-CATALOG-CLOTHING', '服装商品目录：白色圆领短袖T恤', 'CATALOG', 'internal://catalog/clothing#CLOTH-TEE-011', '商品编号 CLOTH-TEE-011，商品名称 白色圆领短袖T恤，服装商品，价格 ¥129.00。'),
  ('CHK-CATALOG-CLOTHING-TEE-012', 'DOC-CATALOG-CLOTHING', '服装商品目录：条纹短袖T恤', 'CATALOG', 'internal://catalog/clothing#CLOTH-TEE-012', '商品编号 CLOTH-TEE-012，商品名称 条纹短袖T恤，服装商品，价格 ¥149.00。'),
  ('CHK-CATALOG-CLOTHING-SHIRT-013', 'DOC-CATALOG-CLOTHING', '服装商品目录：白色免烫衬衫', 'CATALOG', 'internal://catalog/clothing#CLOTH-SHIRT-013', '商品编号 CLOTH-SHIRT-013，商品名称 白色免烫衬衫，服装商品，价格 ¥249.00。'),
  ('CHK-CATALOG-CLOTHING-SHIRT-014', 'DOC-CATALOG-CLOTHING', '服装商品目录：格纹法兰绒衬衫', 'CATALOG', 'internal://catalog/clothing#CLOTH-SHIRT-014', '商品编号 CLOTH-SHIRT-014，商品名称 格纹法兰绒衬衫，服装商品，价格 ¥219.00。'),
  ('CHK-CATALOG-CLOTHING-HOODIE-015', 'DOC-CATALOG-CLOTHING', '服装商品目录：灰色拉链连帽卫衣', 'CATALOG', 'internal://catalog/clothing#CLOTH-HOODIE-015', '商品编号 CLOTH-HOODIE-015，商品名称 灰色拉链连帽卫衣，服装商品，价格 ¥279.00。'),
  ('CHK-CATALOG-CLOTHING-SWEATER-016', 'DOC-CATALOG-CLOTHING', '服装商品目录：燕麦色高领毛衣', 'CATALOG', 'internal://catalog/clothing#CLOTH-SWEATER-016', '商品编号 CLOTH-SWEATER-016，商品名称 燕麦色高领毛衣，服装商品，价格 ¥239.00。'),
  ('CHK-CATALOG-CLOTHING-CARDIGAN-017', 'DOC-CATALOG-CLOTHING', '服装商品目录：深灰针织开衫', 'CATALOG', 'internal://catalog/clothing#CLOTH-CARDIGAN-017', '商品编号 CLOTH-CARDIGAN-017，商品名称 深灰针织开衫，服装商品，价格 ¥269.00。'),
  ('CHK-CATALOG-CLOTHING-JEANS-018', 'DOC-CATALOG-CLOTHING', '服装商品目录：浅蓝阔腿牛仔裤', 'CATALOG', 'internal://catalog/clothing#CLOTH-JEANS-018', '商品编号 CLOTH-JEANS-018，商品名称 浅蓝阔腿牛仔裤，服装商品，价格 ¥319.00。'),
  ('CHK-CATALOG-CLOTHING-PANTS-019', 'DOC-CATALOG-CLOTHING', '服装商品目录：黑色直筒休闲裤', 'CATALOG', 'internal://catalog/clothing#CLOTH-PANTS-019', '商品编号 CLOTH-PANTS-019，商品名称 黑色直筒休闲裤，服装商品，价格 ¥259.00。'),
  ('CHK-CATALOG-CLOTHING-SHORTS-020', 'DOC-CATALOG-CLOTHING', '服装商品目录：卡其五分短裤', 'CATALOG', 'internal://catalog/clothing#CLOTH-SHORTS-020', '商品编号 CLOTH-SHORTS-020，商品名称 卡其五分短裤，服装商品，价格 ¥139.00。'),
  ('CHK-CATALOG-CLOTHING-JACKET-021', 'DOC-CATALOG-CLOTHING', '服装商品目录：军绿工装夹克', 'CATALOG', 'internal://catalog/clothing#CLOTH-JACKET-021', '商品编号 CLOTH-JACKET-021，商品名称 军绿工装夹克，服装商品，价格 ¥459.00。'),
  ('CHK-CATALOG-CLOTHING-WINDBREAKER-022', 'DOC-CATALOG-CLOTHING', '服装商品目录：米白风衣外套', 'CATALOG', 'internal://catalog/clothing#CLOTH-WINDBREAKER-022', '商品编号 CLOTH-WINDBREAKER-022，商品名称 米白风衣外套，服装商品，价格 ¥529.00。'),
  ('CHK-CATALOG-CLOTHING-DOWN-023', 'DOC-CATALOG-CLOTHING', '服装商品目录：黑色短款羽绒服', 'CATALOG', 'internal://catalog/clothing#CLOTH-DOWN-023', '商品编号 CLOTH-DOWN-023，商品名称 黑色短款羽绒服，服装商品，价格 ¥899.00。'),
  ('CHK-CATALOG-CLOTHING-COAT-024', 'DOC-CATALOG-CLOTHING', '服装商品目录：驼色双面呢大衣', 'CATALOG', 'internal://catalog/clothing#CLOTH-COAT-024', '商品编号 CLOTH-COAT-024，商品名称 驼色双面呢大衣，服装商品，价格 ¥1299.00。'),
  ('CHK-CATALOG-CLOTHING-DRESS-025', 'DOC-CATALOG-CLOTHING', '服装商品目录：法式方领连衣裙', 'CATALOG', 'internal://catalog/clothing#CLOTH-DRESS-025', '商品编号 CLOTH-DRESS-025，商品名称 法式方领连衣裙，服装商品，价格 ¥369.00。'),
  ('CHK-CATALOG-CLOTHING-SKIRT-026', 'DOC-CATALOG-CLOTHING', '服装商品目录：百褶中长半身裙', 'CATALOG', 'internal://catalog/clothing#CLOTH-SKIRT-026', '商品编号 CLOTH-SKIRT-026，商品名称 百褶中长半身裙，服装商品，价格 ¥179.00。'),
  ('CHK-CATALOG-CLOTHING-SNEAKER-027', 'DOC-CATALOG-CLOTHING', '服装商品目录：白色厚底运动鞋', 'CATALOG', 'internal://catalog/clothing#CLOTH-SNEAKER-027', '商品编号 CLOTH-SNEAKER-027，商品名称 白色厚底运动鞋，服装商品，价格 ¥399.00。'),
  ('CHK-CATALOG-CLOTHING-CANVAS-028', 'DOC-CATALOG-CLOTHING', '服装商品目录：藏青帆布鞋', 'CATALOG', 'internal://catalog/clothing#CLOTH-CANVAS-028', '商品编号 CLOTH-CANVAS-028，商品名称 藏青帆布鞋，服装商品，价格 ¥199.00。'),
  ('CHK-CATALOG-CLOTHING-BAG-029', 'DOC-CATALOG-CLOTHING', '服装商品目录：米色帆布双肩包', 'CATALOG', 'internal://catalog/clothing#CLOTH-BAG-029', '商品编号 CLOTH-BAG-029，商品名称 米色帆布双肩包，服装商品，价格 ¥299.00。'),
  ('CHK-CATALOG-CLOTHING-SCARF-030', 'DOC-CATALOG-CLOTHING', '服装商品目录：灰色羊毛围巾', 'CATALOG', 'internal://catalog/clothing#CLOTH-SCARF-030', '商品编号 CLOTH-SCARF-030，商品名称 灰色羊毛围巾，服装商品，价格 ¥109.00。');

-- 汇总片段是"整张价格表"的唯一副本：预算/搭配类问题靠它一次拿到全目录，
-- PriceCatalogService 也靠它做交叉校验。加商品时必须同步这里（有测试盯着，见 SeededProductCatalogConsistencyTest）。
UPDATE knowledge_chunks
SET content = '服装商品完整价格表：基础纯棉白色T恤 CLOTH-TEE-001 ¥99.00；蓝色牛津纺衬衫 CLOTH-SHIRT-002 ¥199.00；直筒水洗牛仔裤 CLOTH-JEANS-003 ¥299.00；黑色连帽卫衣 CLOTH-HOODIE-004 ¥259.00；碎花雪纺连衣裙 CLOTH-DRESS-005 ¥329.00；轻薄防风夹克 CLOTH-JACKET-006 ¥399.00；高腰A字半身裙 CLOTH-SKIRT-007 ¥189.00；商务休闲Polo衫 CLOTH-POLO-008 ¥169.00；羊毛混纺大衣 CLOTH-COAT-009 ¥699.00；米色针织毛衣 CLOTH-SWEATER-010 ¥229.00；白色圆领短袖T恤 CLOTH-TEE-011 ¥129.00；条纹短袖T恤 CLOTH-TEE-012 ¥149.00；白色免烫衬衫 CLOTH-SHIRT-013 ¥249.00；格纹法兰绒衬衫 CLOTH-SHIRT-014 ¥219.00；灰色拉链连帽卫衣 CLOTH-HOODIE-015 ¥279.00；燕麦色高领毛衣 CLOTH-SWEATER-016 ¥239.00；深灰针织开衫 CLOTH-CARDIGAN-017 ¥269.00；浅蓝阔腿牛仔裤 CLOTH-JEANS-018 ¥319.00；黑色直筒休闲裤 CLOTH-PANTS-019 ¥259.00；卡其五分短裤 CLOTH-SHORTS-020 ¥139.00；军绿工装夹克 CLOTH-JACKET-021 ¥459.00；米白风衣外套 CLOTH-WINDBREAKER-022 ¥529.00；黑色短款羽绒服 CLOTH-DOWN-023 ¥899.00；驼色双面呢大衣 CLOTH-COAT-024 ¥1299.00；法式方领连衣裙 CLOTH-DRESS-025 ¥369.00；百褶中长半身裙 CLOTH-SKIRT-026 ¥179.00；白色厚底运动鞋 CLOTH-SNEAKER-027 ¥399.00；藏青帆布鞋 CLOTH-CANVAS-028 ¥199.00；米色帆布双肩包 CLOTH-BAG-029 ¥299.00；灰色羊毛围巾 CLOTH-SCARF-030 ¥109.00。'
WHERE id = 'CHK-CATALOG-CLOTHING-ALL';

-- 文档正文同步（检索走 chunk，这里只影响文档本身的完整性）
UPDATE knowledge_documents
SET content = '服装商品目录包含 30 件服装商品（商品编号、商品名称、价格）：上装 12 件（T恤/衬衫/卫衣/毛衣/开衫/Polo）、下装 4 件（牛仔裤/休闲裤/短裤）、外套 6 件（夹克/风衣/羽绒服/大衣）、裙装 4 件（连衣裙/半身裙）、鞋 2 件、包与配饰 2 件，价格区间 ¥99.00 ~ ¥1299.00。完整价格表见知识库片段 CHK-CATALOG-CLOTHING-ALL。',
    updated_at = CURRENT_TIMESTAMP(6)
WHERE id = 'DOC-CATALOG-CLOTHING';
