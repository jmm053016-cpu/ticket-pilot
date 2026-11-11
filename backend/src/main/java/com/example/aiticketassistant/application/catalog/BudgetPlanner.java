package com.example.aiticketassistant.application.catalog;

import com.example.aiticketassistant.domain.catalog.CatalogItem;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 预算内"最多能买几件 / 最便宜组合"的确定性算法。
 *
 * <p>纯函数，无依赖，好测。放进代码里而不是交给模型，是因为实测模型对同一问题会给出
 * 934 / 954 / 964 三个不同的合计（真实值是 1144），并且同一段回答里结论与明细互相矛盾。
 */
public final class BudgetPlanner {

    private BudgetPlanner() {}

    /**
     * @param budgetCents 预算（分）
     * @param allowRepeat 是否允许重复购买同一商品
     * @param catalog     商品价格表（内部会按价格排序，无需预排序）
     */
    public static BudgetPlan plan(long budgetCents, boolean allowRepeat, List<CatalogItem> catalog) {
        List<CatalogItem> sorted = new ArrayList<>(catalog == null ? List.of() : catalog);
        sorted.sort(Comparator.comparingLong(CatalogItem::priceCents));
        if (sorted.isEmpty() || budgetCents <= 0) {
            return new BudgetPlan(Math.max(budgetCents, 0), allowRepeat, 0, List.of(), 0, Math.max(budgetCents, 0), null);
        }
        CatalogItem cheapest = sorted.get(0);
        if (cheapest.priceCents() > budgetCents) {
            return new BudgetPlan(budgetCents, allowRepeat, 0, List.of(), 0, budgetCents, cheapest);
        }
        return allowRepeat
                ? planWithRepeat(budgetCents, sorted)
                : planDistinct(budgetCents, sorted);
    }

    /** 允许重复：全买最便宜的那件。 */
    private static BudgetPlan planWithRepeat(long budgetCents, List<CatalogItem> sorted) {
        CatalogItem cheapest = sorted.get(0);
        int count = (int) (budgetCents / cheapest.priceCents());
        long total = count * cheapest.priceCents();
        // 允许重复时件数没有上限压力，不存在"下一件买不起"的解释需求
        return new BudgetPlan(budgetCents, true, count, BudgetPlan.repeated(cheapest, count), total,
                budgetCents - total, null);
    }

    /** 不允许重复：从最便宜的开始累加，直到加上下一件会超预算。 */
    private static BudgetPlan planDistinct(long budgetCents, List<CatalogItem> sorted) {
        List<CatalogItem> chosen = new ArrayList<>();
        long total = 0;
        CatalogItem nextCheapest = null;
        for (CatalogItem item : sorted) {
            if (total + item.priceCents() <= budgetCents) {
                chosen.add(item);
                total += item.priceCents();
            } else {
                nextCheapest = item;
                break;
            }
        }
        return new BudgetPlan(budgetCents, false, chosen.size(), List.copyOf(chosen), total,
                budgetCents - total, nextCheapest);
    }
}
