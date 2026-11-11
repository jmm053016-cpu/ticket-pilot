package com.example.aiticketassistant.application.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.aiticketassistant.domain.catalog.Product;
import com.example.aiticketassistant.domain.catalog.ProductInventory;
import com.example.aiticketassistant.domain.catalog.ProductRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * 品类候选的判定规则（2026-11-11）。
 *
 * <p>核心是"歧义与否由**目录里有几个 SKU** 决定"，而不是维护一张"品类词/具体名"表：
 * 一个 SKU → 直接下单；多个 SKU → 列候选 + 反问；句子里出现完整商品名 → 当具体款处理。
 */
class CategoryCandidateServiceTest {

    private static final List<Product> CATALOG = List.of(
            new Product("CLOTH-DOWN-023", "黑色短款羽绒服", new BigDecimal("899.00"), true),
            new Product("CLOTH-DOWN-031", "白色长款羽绒服", new BigDecimal("1099.00"), true),
            new Product("CLOTH-DOWN-032", "轻薄羽绒马甲", new BigDecimal("499.00"), true),
            new Product("CLOTH-CANVAS-028", "藏青帆布鞋", new BigDecimal("199.00"), true),
            new Product("CLOTH-TEE-001", "基础纯棉白色T恤", new BigDecimal("99.00"), true));

    private static CategoryCandidateService service() {
        return new CategoryCandidateService(new FakeProductRepository(CATALOG));
    }

    @Test
    void listsCandidatesWhenTheCategoryHasSeveralSkus() {
        CategoryCandidateService.Candidates candidates =
                service().findCandidates("我要买一件羽绒服").orElseThrow();

        assertThat(candidates.category()).isEqualTo("羽绒服");
        // 名称里带"羽绒服"的有 2 件（"轻薄羽绒马甲"不算 —— 它叫马甲）
        assertThat(candidates.totalInCategory()).isEqualTo(2);
        // 最多 3 个，按价格从低到高（便宜的先看到）
        assertThat(candidates.candidates()).extracting(CategoryCandidateService.Candidate::name)
                .containsExactly("黑色短款羽绒服", "白色长款羽绒服");
    }

    @Test
    void keepsDirectOrderingWhenTheCategoryHasOnlyOneSku() {
        assertThat(service().findCandidates("帮我下单一双帆布鞋")).isEmpty();
    }

    @Test
    void treatsAnExplicitProductNameAsSpecificNotAsCategory() {
        // 「黑色短款羽绒服」是完整商品名 → 用户说的是具体款，不该再问
        assertThat(service().findCandidates("我要买一件黑色短款羽绒服")).isEmpty();
    }

    @Test
    void ignoresMessagesWithoutAnyCategoryWord() {
        assertThat(service().findCandidates("我的退款到哪了")).isEmpty();
        assertThat(service().findCandidates("")).isEmpty();
    }

    private record FakeProductRepository(List<Product> products) implements ProductRepository {

        @Override
        public Optional<Product> findById(String id) {
            return products.stream().filter(product -> product.id().equals(id)).findFirst();
        }

        @Override
        public List<Product> findActiveProducts() {
            return products.stream().filter(Product::active).toList();
        }

        @Override
        public Optional<ProductInventory> findInventory(String productId) {
            return Optional.empty();
        }

        @Override
        public ProductInventory saveInventory(ProductInventory inventory) {
            return inventory;
        }
    }
}
