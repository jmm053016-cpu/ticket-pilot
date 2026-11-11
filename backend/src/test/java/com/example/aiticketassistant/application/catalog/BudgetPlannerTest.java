package com.example.aiticketassistant.application.catalog;

import com.example.aiticketassistant.domain.catalog.CatalogItem;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

/**
 * 预算测算的确定性测试。
 *
 * <p>这些断言就是"正确答案"的定义 —— 线上出现 6 件 / ¥934 之类的结果时，先看这里。
 * 目录价格：99 / 169 / 189 / 199 / 229 / 259 / 299 / 329 / 399 / 699。
 */
class BudgetPlannerTest {
    private static final List<CatalogItem> CATALOG = List.of(
            item("CLOTH-TEE-001", "基础纯棉白色T恤", 9900),
            item("CLOTH-SHIRT-002", "蓝色牛津纺衬衫", 19900),
            item("CLOTH-JEANS-003", "直筒水洗牛仔裤", 29900),
            item("CLOTH-HOODIE-004", "黑色连帽卫衣", 25900),
            item("CLOTH-DRESS-005", "碎花雪纺连衣裙", 32900),
            item("CLOTH-JACKET-006", "轻薄防风夹克", 39900),
            item("CLOTH-SKIRT-007", "高腰A字半身裙", 18900),
            item("CLOTH-POLO-008", "商务休闲Polo衫", 16900),
            item("CLOTH-COAT-009", "羊毛混纺大衣", 69900),
            item("CLOTH-SWEATER-010", "米色针织毛衣", 22900));

    private static CatalogItem item(String id, String name, long cents) {
        return new CatalogItem(id, name, cents);
    }

    @Test
    void distinctItemsCannotRepeat() {
        // 线上实测反复出错的那道题：1000 元、不能重复 → 5 件 / 885，第 6 件 259 会超
        BudgetPlan plan = BudgetPlanner.plan(100000, false, CATALOG);

        assertThat(plan.maxCount()).isEqualTo(5);
        assertThat(plan.totalCents()).isEqualTo(88500);
        assertThat(plan.remainingCents()).isEqualTo(11500);
        assertThat(plan.items()).extracting(CatalogItem::productId)
                .containsExactly("CLOTH-TEE-001", "CLOTH-POLO-008", "CLOTH-SKIRT-007",
                        "CLOTH-SHIRT-002", "CLOTH-SWEATER-010");
        assertThat(plan.nextCheapest().productId()).isEqualTo("CLOTH-HOODIE-004");
        assertThat(plan.nextCheapest().priceCents()).isEqualTo(25900);
        assertThat(plan.totalCents() + plan.nextCheapest().priceCents()).isGreaterThan(100000);
    }

    @Test
    void repeatAllowedBuysCheapest() {
        BudgetPlan plan = BudgetPlanner.plan(100000, true, CATALOG);

        assertThat(plan.maxCount()).isEqualTo(10);
        assertThat(plan.totalCents()).isEqualTo(99000);
        assertThat(plan.remainingCents()).isEqualTo(1000);
        assertThat(plan.items()).allMatch(item -> item.productId().equals("CLOTH-TEE-001"));
    }

    @Test
    void budgetSmallerThanCheapestItemIsNotFeasible() {
        BudgetPlan plan = BudgetPlanner.plan(5000, false, CATALOG);

        assertThat(plan.feasible()).isFalse();
        assertThat(plan.maxCount()).isZero();
        assertThat(plan.items()).isEmpty();
        assertThat(plan.nextCheapest().productId()).isEqualTo("CLOTH-TEE-001");
    }

    @Test
    void exactBudgetIsSpentCompletely() {
        // 885 元正好买下最便宜 5 件
        BudgetPlan plan = BudgetPlanner.plan(88500, false, CATALOG);

        assertThat(plan.maxCount()).isEqualTo(5);
        assertThat(plan.remainingCents()).isZero();
        assertThat(plan.nextCheapest().productId()).isEqualTo("CLOTH-HOODIE-004");
    }

    @Test
    void emptyCatalogProducesEmptyPlan() {
        BudgetPlan plan = BudgetPlanner.plan(100000, false, List.of());

        assertThat(plan.maxCount()).isZero();
        assertThat(plan.totalCents()).isZero();
        assertThat(plan.toPromptBlock()).contains("预算不足以购买");
    }

    @Test
    void promptBlockCarriesAuthoritativeNumbers() {
        String block = BudgetPlanner.plan(100000, false, CATALOG).toPromptBlock();

        assertThat(block)
                .contains("预算 ¥1000.00")
                .contains("每件商品不能重复")
                .contains("最多可买 5 件，合计 ¥885.00，剩余 ¥115.00")
                .contains("再加一件最便宜的是 黑色连帽卫衣 ¥259.00")
                .contains("> ¥1000.00，因此 5 件已是上限");
    }
}
