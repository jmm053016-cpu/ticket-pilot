-- =====================================================================================
-- V10：再扩 30 件（30 → 60 件），把目录铺到"品类够宽"的程度
-- =====================================================================================
-- 为什么：30 件只覆盖了基础的上装/下装/外套/裙装 + 各 1 双鞋包，用户问「靴子」「睡袍」
-- 「泳衣」「太阳镜」时目录里根本没有可答的商品。V10 补 30 件，新增或加密这些品类：
--   童装、家居服/睡袍、运动（速干T恤/瑜伽裤/泳衣）、鞋（短靴/高跟鞋/凉鞋）、
--   配饰（毛线帽/手套/太阳镜），以及同品类的款式差异（牛仔夹克/冲锋衣/毛呢外套、
--   西装直筒裤/束脚休闲裤、三种半身裙、长裙/西装连衣裙…）。
--
-- 三条既有约束必须保持（改了会连带改掉已标定的验收数字，见 §5.4.3 / §6.5 ⑤）：
--   * 最低价仍是 ¥99（CLOTH-TEE-001）——「1000 元最多买几件（可重复）」= 10 件 / ¥990 不变；
--   * ¥699 仍然唯一（CLOTH-COAT-009）—— RetrievalEvaluationTest 的"精确价格问句只命中同一件"依赖它；
--   * 「不重复」场景的最优组合会变（新增了 119/129/139 等便宜价位），验收数字在 §5.4.3 重新标定。
--
-- 同步清单（SeededProductCatalogConsistencyTest 会盯着后三处）：
--   1) products           2) product_inventory        3) knowledge_chunks（每件一条单品片段）
--   4) 汇总价格表片段 CHK-CATALOG-CLOTHING-ALL        5) 文档正文 DOC-CATALOG-CLOTHING
--   6) QuestionSignals 的商品名→productId 表（代码侧）
-- =====================================================================================

INSERT INTO products (id, name, price, active, created_at, updated_at)
VALUES
  ('CLOTH-SHIRT-031', '男士亚麻短袖衬衫', 229.00, true, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
  ('CLOTH-SHIRT-032', '女士雪纺系带衬衫', 269.00, true, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
  ('CLOTH-TEE-033', '重磅纯棉宽松T恤', 119.00, true, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
  ('CLOTH-TEE-034', '复古印花短袖T恤', 139.00, true, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
  ('CLOTH-SWEATER-035', '男式圆领羊绒衫', 499.00, true, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
  ('CLOTH-SWEATER-036', '马海毛宽松毛衣', 289.00, true, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
  ('CLOTH-HOODIE-037', '儿童卡通连帽卫衣', 159.00, true, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
  ('CLOTH-JACKET-038', '复古牛仔夹克', 389.00, true, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
  ('CLOTH-JACKET-039', '三防冲锋衣', 629.00, true, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
  ('CLOTH-COAT-040', '双排扣羊毛大衣', 1189.00, true, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
  ('CLOTH-COAT-041', '短款毛呢外套', 759.00, true, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
  ('CLOTH-PANTS-042', '高腰西装直筒裤', 309.00, true, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
  ('CLOTH-PANTS-043', '运动束脚休闲裤', 219.00, true, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
  ('CLOTH-JEANS-044', '复古水洗直筒牛仔裤', 339.00, true, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
  ('CLOTH-SHORTS-045', '牛仔五分短裤', 169.00, true, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
  ('CLOTH-SKIRT-046', '牛仔A字半身裙', 229.00, true, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
  ('CLOTH-SKIRT-047', '蕾丝中长半身裙', 259.00, true, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
  ('CLOTH-DRESS-048', '针织连衣长裙', 429.00, true, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
  ('CLOTH-DRESS-049', '通勤西装连衣裙', 519.00, true, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
  ('CLOTH-PAJAMA-050', '纯棉家居服套装', 179.00, true, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
  ('CLOTH-PAJAMA-051', '法兰绒睡袍', 249.00, true, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
  ('CLOTH-SPORT-052', '速干运动T恤', 149.00, true, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
  ('CLOTH-SPORT-053', '高弹瑜伽裤', 239.00, true, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
  ('CLOTH-SWIM-054', '连体泳衣', 299.00, true, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
  ('CLOTH-BOOTS-055', '切尔西短靴', 589.00, true, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
  ('CLOTH-HEEL-056', '细高跟鞋', 459.00, true, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
  ('CLOTH-SANDAL-057', '平底凉鞋', 229.00, true, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
  ('CLOTH-HAT-058', '针织毛线帽', 129.00, true, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
  ('CLOTH-GLOVES-059', '加绒保暖手套', 119.00, true, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
  ('CLOTH-SUNGLASS-060', '偏光太阳镜', 259.00, true, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6));

INSERT INTO product_inventory (product_id, stock_on_hand, reserved_stock, updated_at)
VALUES
  ('CLOTH-SHIRT-031', 36, 0, CURRENT_TIMESTAMP(6)),
  ('CLOTH-SHIRT-032', 30, 0, CURRENT_TIMESTAMP(6)),
  ('CLOTH-TEE-033', 80, 0, CURRENT_TIMESTAMP(6)),
  ('CLOTH-TEE-034', 70, 0, CURRENT_TIMESTAMP(6)),
  ('CLOTH-SWEATER-035', 12, 0, CURRENT_TIMESTAMP(6)),
  ('CLOTH-SWEATER-036', 24, 0, CURRENT_TIMESTAMP(6)),
  ('CLOTH-HOODIE-037', 46, 0, CURRENT_TIMESTAMP(6)),
  ('CLOTH-JACKET-038', 18, 0, CURRENT_TIMESTAMP(6)),
  ('CLOTH-JACKET-039', 11, 0, CURRENT_TIMESTAMP(6)),
  ('CLOTH-COAT-040', 7, 0, CURRENT_TIMESTAMP(6)),
  ('CLOTH-COAT-041', 13, 0, CURRENT_TIMESTAMP(6)),
  ('CLOTH-PANTS-042', 28, 0, CURRENT_TIMESTAMP(6)),
  ('CLOTH-PANTS-043', 34, 0, CURRENT_TIMESTAMP(6)),
  ('CLOTH-JEANS-044', 26, 0, CURRENT_TIMESTAMP(6)),
  ('CLOTH-SHORTS-045', 40, 0, CURRENT_TIMESTAMP(6)),
  ('CLOTH-SKIRT-046', 33, 0, CURRENT_TIMESTAMP(6)),
  ('CLOTH-SKIRT-047', 29, 0, CURRENT_TIMESTAMP(6)),
  ('CLOTH-DRESS-048', 16, 0, CURRENT_TIMESTAMP(6)),
  ('CLOTH-DRESS-049', 14, 0, CURRENT_TIMESTAMP(6)),
  ('CLOTH-PAJAMA-050', 38, 0, CURRENT_TIMESTAMP(6)),
  ('CLOTH-PAJAMA-051', 21, 0, CURRENT_TIMESTAMP(6)),
  ('CLOTH-SPORT-052', 52, 0, CURRENT_TIMESTAMP(6)),
  ('CLOTH-SPORT-053', 31, 0, CURRENT_TIMESTAMP(6)),
  ('CLOTH-SWIM-054', 19, 0, CURRENT_TIMESTAMP(6)),
  ('CLOTH-BOOTS-055', 15, 0, CURRENT_TIMESTAMP(6)),
  ('CLOTH-HEEL-056', 17, 0, CURRENT_TIMESTAMP(6)),
  ('CLOTH-SANDAL-057', 27, 0, CURRENT_TIMESTAMP(6)),
  ('CLOTH-HAT-058', 60, 0, CURRENT_TIMESTAMP(6)),
  ('CLOTH-GLOVES-059', 65, 0, CURRENT_TIMESTAMP(6)),
  ('CLOTH-SUNGLASS-060', 23, 0, CURRENT_TIMESTAMP(6));

INSERT INTO knowledge_chunks (id, document_id, title, category, source, content)
VALUES
  ('CHK-CATALOG-CLOTHING-SHIRT-031', 'DOC-CATALOG-CLOTHING', '服装商品目录：男士亚麻短袖衬衫', 'CATALOG', 'internal://catalog/clothing#CLOTH-SHIRT-031', '商品编号 CLOTH-SHIRT-031，商品名称 男士亚麻短袖衬衫，服装商品，价格 ¥229.00。'),
  ('CHK-CATALOG-CLOTHING-SHIRT-032', 'DOC-CATALOG-CLOTHING', '服装商品目录：女士雪纺系带衬衫', 'CATALOG', 'internal://catalog/clothing#CLOTH-SHIRT-032', '商品编号 CLOTH-SHIRT-032，商品名称 女士雪纺系带衬衫，服装商品，价格 ¥269.00。'),
  ('CHK-CATALOG-CLOTHING-TEE-033', 'DOC-CATALOG-CLOTHING', '服装商品目录：重磅纯棉宽松T恤', 'CATALOG', 'internal://catalog/clothing#CLOTH-TEE-033', '商品编号 CLOTH-TEE-033，商品名称 重磅纯棉宽松T恤，服装商品，价格 ¥119.00。'),
  ('CHK-CATALOG-CLOTHING-TEE-034', 'DOC-CATALOG-CLOTHING', '服装商品目录：复古印花短袖T恤', 'CATALOG', 'internal://catalog/clothing#CLOTH-TEE-034', '商品编号 CLOTH-TEE-034，商品名称 复古印花短袖T恤，服装商品，价格 ¥139.00。'),
  ('CHK-CATALOG-CLOTHING-SWEATER-035', 'DOC-CATALOG-CLOTHING', '服装商品目录：男式圆领羊绒衫', 'CATALOG', 'internal://catalog/clothing#CLOTH-SWEATER-035', '商品编号 CLOTH-SWEATER-035，商品名称 男式圆领羊绒衫，服装商品，价格 ¥499.00。'),
  ('CHK-CATALOG-CLOTHING-SWEATER-036', 'DOC-CATALOG-CLOTHING', '服装商品目录：马海毛宽松毛衣', 'CATALOG', 'internal://catalog/clothing#CLOTH-SWEATER-036', '商品编号 CLOTH-SWEATER-036，商品名称 马海毛宽松毛衣，服装商品，价格 ¥289.00。'),
  ('CHK-CATALOG-CLOTHING-HOODIE-037', 'DOC-CATALOG-CLOTHING', '服装商品目录：儿童卡通连帽卫衣', 'CATALOG', 'internal://catalog/clothing#CLOTH-HOODIE-037', '商品编号 CLOTH-HOODIE-037，商品名称 儿童卡通连帽卫衣，服装商品，价格 ¥159.00。'),
  ('CHK-CATALOG-CLOTHING-JACKET-038', 'DOC-CATALOG-CLOTHING', '服装商品目录：复古牛仔夹克', 'CATALOG', 'internal://catalog/clothing#CLOTH-JACKET-038', '商品编号 CLOTH-JACKET-038，商品名称 复古牛仔夹克，服装商品，价格 ¥389.00。'),
  ('CHK-CATALOG-CLOTHING-JACKET-039', 'DOC-CATALOG-CLOTHING', '服装商品目录：三防冲锋衣', 'CATALOG', 'internal://catalog/clothing#CLOTH-JACKET-039', '商品编号 CLOTH-JACKET-039，商品名称 三防冲锋衣，服装商品，价格 ¥629.00。'),
  ('CHK-CATALOG-CLOTHING-COAT-040', 'DOC-CATALOG-CLOTHING', '服装商品目录：双排扣羊毛大衣', 'CATALOG', 'internal://catalog/clothing#CLOTH-COAT-040', '商品编号 CLOTH-COAT-040，商品名称 双排扣羊毛大衣，服装商品，价格 ¥1189.00。'),
  ('CHK-CATALOG-CLOTHING-COAT-041', 'DOC-CATALOG-CLOTHING', '服装商品目录：短款毛呢外套', 'CATALOG', 'internal://catalog/clothing#CLOTH-COAT-041', '商品编号 CLOTH-COAT-041，商品名称 短款毛呢外套，服装商品，价格 ¥759.00。'),
  ('CHK-CATALOG-CLOTHING-PANTS-042', 'DOC-CATALOG-CLOTHING', '服装商品目录：高腰西装直筒裤', 'CATALOG', 'internal://catalog/clothing#CLOTH-PANTS-042', '商品编号 CLOTH-PANTS-042，商品名称 高腰西装直筒裤，服装商品，价格 ¥309.00。'),
  ('CHK-CATALOG-CLOTHING-PANTS-043', 'DOC-CATALOG-CLOTHING', '服装商品目录：运动束脚休闲裤', 'CATALOG', 'internal://catalog/clothing#CLOTH-PANTS-043', '商品编号 CLOTH-PANTS-043，商品名称 运动束脚休闲裤，服装商品，价格 ¥219.00。'),
  ('CHK-CATALOG-CLOTHING-JEANS-044', 'DOC-CATALOG-CLOTHING', '服装商品目录：复古水洗直筒牛仔裤', 'CATALOG', 'internal://catalog/clothing#CLOTH-JEANS-044', '商品编号 CLOTH-JEANS-044，商品名称 复古水洗直筒牛仔裤，服装商品，价格 ¥339.00。'),
  ('CHK-CATALOG-CLOTHING-SHORTS-045', 'DOC-CATALOG-CLOTHING', '服装商品目录：牛仔五分短裤', 'CATALOG', 'internal://catalog/clothing#CLOTH-SHORTS-045', '商品编号 CLOTH-SHORTS-045，商品名称 牛仔五分短裤，服装商品，价格 ¥169.00。'),
  ('CHK-CATALOG-CLOTHING-SKIRT-046', 'DOC-CATALOG-CLOTHING', '服装商品目录：牛仔A字半身裙', 'CATALOG', 'internal://catalog/clothing#CLOTH-SKIRT-046', '商品编号 CLOTH-SKIRT-046，商品名称 牛仔A字半身裙，服装商品，价格 ¥229.00。'),
  ('CHK-CATALOG-CLOTHING-SKIRT-047', 'DOC-CATALOG-CLOTHING', '服装商品目录：蕾丝中长半身裙', 'CATALOG', 'internal://catalog/clothing#CLOTH-SKIRT-047', '商品编号 CLOTH-SKIRT-047，商品名称 蕾丝中长半身裙，服装商品，价格 ¥259.00。'),
  ('CHK-CATALOG-CLOTHING-DRESS-048', 'DOC-CATALOG-CLOTHING', '服装商品目录：针织连衣长裙', 'CATALOG', 'internal://catalog/clothing#CLOTH-DRESS-048', '商品编号 CLOTH-DRESS-048，商品名称 针织连衣长裙，服装商品，价格 ¥429.00。'),
  ('CHK-CATALOG-CLOTHING-DRESS-049', 'DOC-CATALOG-CLOTHING', '服装商品目录：通勤西装连衣裙', 'CATALOG', 'internal://catalog/clothing#CLOTH-DRESS-049', '商品编号 CLOTH-DRESS-049，商品名称 通勤西装连衣裙，服装商品，价格 ¥519.00。'),
  ('CHK-CATALOG-CLOTHING-PAJAMA-050', 'DOC-CATALOG-CLOTHING', '服装商品目录：纯棉家居服套装', 'CATALOG', 'internal://catalog/clothing#CLOTH-PAJAMA-050', '商品编号 CLOTH-PAJAMA-050，商品名称 纯棉家居服套装，服装商品，价格 ¥179.00。'),
  ('CHK-CATALOG-CLOTHING-PAJAMA-051', 'DOC-CATALOG-CLOTHING', '服装商品目录：法兰绒睡袍', 'CATALOG', 'internal://catalog/clothing#CLOTH-PAJAMA-051', '商品编号 CLOTH-PAJAMA-051，商品名称 法兰绒睡袍，服装商品，价格 ¥249.00。'),
  ('CHK-CATALOG-CLOTHING-SPORT-052', 'DOC-CATALOG-CLOTHING', '服装商品目录：速干运动T恤', 'CATALOG', 'internal://catalog/clothing#CLOTH-SPORT-052', '商品编号 CLOTH-SPORT-052，商品名称 速干运动T恤，服装商品，价格 ¥149.00。'),
  ('CHK-CATALOG-CLOTHING-SPORT-053', 'DOC-CATALOG-CLOTHING', '服装商品目录：高弹瑜伽裤', 'CATALOG', 'internal://catalog/clothing#CLOTH-SPORT-053', '商品编号 CLOTH-SPORT-053，商品名称 高弹瑜伽裤，服装商品，价格 ¥239.00。'),
  ('CHK-CATALOG-CLOTHING-SWIM-054', 'DOC-CATALOG-CLOTHING', '服装商品目录：连体泳衣', 'CATALOG', 'internal://catalog/clothing#CLOTH-SWIM-054', '商品编号 CLOTH-SWIM-054，商品名称 连体泳衣，服装商品，价格 ¥299.00。'),
  ('CHK-CATALOG-CLOTHING-BOOTS-055', 'DOC-CATALOG-CLOTHING', '服装商品目录：切尔西短靴', 'CATALOG', 'internal://catalog/clothing#CLOTH-BOOTS-055', '商品编号 CLOTH-BOOTS-055，商品名称 切尔西短靴，服装商品，价格 ¥589.00。'),
  ('CHK-CATALOG-CLOTHING-HEEL-056', 'DOC-CATALOG-CLOTHING', '服装商品目录：细高跟鞋', 'CATALOG', 'internal://catalog/clothing#CLOTH-HEEL-056', '商品编号 CLOTH-HEEL-056，商品名称 细高跟鞋，服装商品，价格 ¥459.00。'),
  ('CHK-CATALOG-CLOTHING-SANDAL-057', 'DOC-CATALOG-CLOTHING', '服装商品目录：平底凉鞋', 'CATALOG', 'internal://catalog/clothing#CLOTH-SANDAL-057', '商品编号 CLOTH-SANDAL-057，商品名称 平底凉鞋，服装商品，价格 ¥229.00。'),
  ('CHK-CATALOG-CLOTHING-HAT-058', 'DOC-CATALOG-CLOTHING', '服装商品目录：针织毛线帽', 'CATALOG', 'internal://catalog/clothing#CLOTH-HAT-058', '商品编号 CLOTH-HAT-058，商品名称 针织毛线帽，服装商品，价格 ¥129.00。'),
  ('CHK-CATALOG-CLOTHING-GLOVES-059', 'DOC-CATALOG-CLOTHING', '服装商品目录：加绒保暖手套', 'CATALOG', 'internal://catalog/clothing#CLOTH-GLOVES-059', '商品编号 CLOTH-GLOVES-059，商品名称 加绒保暖手套，服装商品，价格 ¥119.00。'),
  ('CHK-CATALOG-CLOTHING-SUNGLASS-060', 'DOC-CATALOG-CLOTHING', '服装商品目录：偏光太阳镜', 'CATALOG', 'internal://catalog/clothing#CLOTH-SUNGLASS-060', '商品编号 CLOTH-SUNGLASS-060，商品名称 偏光太阳镜，服装商品，价格 ¥259.00。');

-- 汇总价格表：一次拿到全目录（预算测算/搭配的数据源），必须与 products 一一对应
UPDATE knowledge_chunks
SET content = '服装商品完整价格表：基础纯棉白色T恤 CLOTH-TEE-001 ¥99.00；蓝色牛津纺衬衫 CLOTH-SHIRT-002 ¥199.00；直筒水洗牛仔裤 CLOTH-JEANS-003 ¥299.00；黑色连帽卫衣 CLOTH-HOODIE-004 ¥259.00；碎花雪纺连衣裙 CLOTH-DRESS-005 ¥329.00；轻薄防风夹克 CLOTH-JACKET-006 ¥399.00；高腰A字半身裙 CLOTH-SKIRT-007 ¥189.00；商务休闲Polo衫 CLOTH-POLO-008 ¥169.00；羊毛混纺大衣 CLOTH-COAT-009 ¥699.00；米色针织毛衣 CLOTH-SWEATER-010 ¥229.00；白色圆领短袖T恤 CLOTH-TEE-011 ¥129.00；条纹短袖T恤 CLOTH-TEE-012 ¥149.00；白色免烫衬衫 CLOTH-SHIRT-013 ¥249.00；格纹法兰绒衬衫 CLOTH-SHIRT-014 ¥219.00；灰色拉链连帽卫衣 CLOTH-HOODIE-015 ¥279.00；燕麦色高领毛衣 CLOTH-SWEATER-016 ¥239.00；深灰针织开衫 CLOTH-CARDIGAN-017 ¥269.00；浅蓝阔腿牛仔裤 CLOTH-JEANS-018 ¥319.00；黑色直筒休闲裤 CLOTH-PANTS-019 ¥259.00；卡其五分短裤 CLOTH-SHORTS-020 ¥139.00；军绿工装夹克 CLOTH-JACKET-021 ¥459.00；米白风衣外套 CLOTH-WINDBREAKER-022 ¥529.00；黑色短款羽绒服 CLOTH-DOWN-023 ¥899.00；驼色双面呢大衣 CLOTH-COAT-024 ¥1299.00；法式方领连衣裙 CLOTH-DRESS-025 ¥369.00；百褶中长半身裙 CLOTH-SKIRT-026 ¥179.00；白色厚底运动鞋 CLOTH-SNEAKER-027 ¥399.00；藏青帆布鞋 CLOTH-CANVAS-028 ¥199.00；米色帆布双肩包 CLOTH-BAG-029 ¥299.00；灰色羊毛围巾 CLOTH-SCARF-030 ¥109.00；男士亚麻短袖衬衫 CLOTH-SHIRT-031 ¥229.00；女士雪纺系带衬衫 CLOTH-SHIRT-032 ¥269.00；重磅纯棉宽松T恤 CLOTH-TEE-033 ¥119.00；复古印花短袖T恤 CLOTH-TEE-034 ¥139.00；男式圆领羊绒衫 CLOTH-SWEATER-035 ¥499.00；马海毛宽松毛衣 CLOTH-SWEATER-036 ¥289.00；儿童卡通连帽卫衣 CLOTH-HOODIE-037 ¥159.00；复古牛仔夹克 CLOTH-JACKET-038 ¥389.00；三防冲锋衣 CLOTH-JACKET-039 ¥629.00；双排扣羊毛大衣 CLOTH-COAT-040 ¥1189.00；短款毛呢外套 CLOTH-COAT-041 ¥759.00；高腰西装直筒裤 CLOTH-PANTS-042 ¥309.00；运动束脚休闲裤 CLOTH-PANTS-043 ¥219.00；复古水洗直筒牛仔裤 CLOTH-JEANS-044 ¥339.00；牛仔五分短裤 CLOTH-SHORTS-045 ¥169.00；牛仔A字半身裙 CLOTH-SKIRT-046 ¥229.00；蕾丝中长半身裙 CLOTH-SKIRT-047 ¥259.00；针织连衣长裙 CLOTH-DRESS-048 ¥429.00；通勤西装连衣裙 CLOTH-DRESS-049 ¥519.00；纯棉家居服套装 CLOTH-PAJAMA-050 ¥179.00；法兰绒睡袍 CLOTH-PAJAMA-051 ¥249.00；速干运动T恤 CLOTH-SPORT-052 ¥149.00；高弹瑜伽裤 CLOTH-SPORT-053 ¥239.00；连体泳衣 CLOTH-SWIM-054 ¥299.00；切尔西短靴 CLOTH-BOOTS-055 ¥589.00；细高跟鞋 CLOTH-HEEL-056 ¥459.00；平底凉鞋 CLOTH-SANDAL-057 ¥229.00；针织毛线帽 CLOTH-HAT-058 ¥129.00；加绒保暖手套 CLOTH-GLOVES-059 ¥119.00；偏光太阳镜 CLOTH-SUNGLASS-060 ¥259.00。'
WHERE id = 'CHK-CATALOG-CLOTHING-ALL';

-- 文档正文同步（只影响文档完整性，检索走 chunk）
UPDATE knowledge_documents
SET content = '服装商品目录包含 60 件服装商品（商品编号、商品名称、价格）：上装 17 件（T恤/衬衫/卫衣/毛衣/开衫/Polo/羊绒衫）、下装 8 件（牛仔裤/休闲裤/短裤/西装裤/束脚裤）、外套 9 件（夹克/风衣/羽绒服/大衣/冲锋衣/毛呢外套）、裙装 9 件（连衣裙/半身裙/长裙）、鞋 5 件（运动鞋/帆布鞋/短靴/高跟鞋/凉鞋）、包与配饰 7 件（双肩包/围巾/毛线帽/手套/太阳镜）、童装 1 件、家居服 2 件、运动 3 件（速干T恤/瑜伽裤/泳衣），价格区间 ¥99.00 ~ ¥1299.00。完整价格表见知识库片段 CHK-CATALOG-CLOTHING-ALL。',
    updated_at = CURRENT_TIMESTAMP(6)
WHERE id = 'DOC-CATALOG-CLOTHING';
