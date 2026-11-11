package com.example.aiticketassistant.application.catalog;

import com.example.aiticketassistant.domain.catalog.CatalogItem;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 预算测算结果（**由代码算出，模型只负责解释**）。
 *
 * @param budgetCents   预算
 * @param allowRepeat   是否允许同一商品重复购买
 * @param maxCount      最多可买件数
 * @param items         选中的商品（允许重复时按件数重复出现）
 * @param totalCents    合计金额
 * @param remainingCents 剩余金额
 * @param nextCheapest  未被选中商品里最便宜的那个（用来解释"为什么加不了第 N+1 件"），可能为 null
 */
public record BudgetPlan(
        long budgetCents,
        boolean allowRepeat,
        int maxCount,
        List<CatalogItem> items,
        long totalCents,
        long remainingCents,
        CatalogItem nextCheapest) {

    public boolean feasible() {
        return maxCount > 0;
    }

    /** 给模型看的权威测算块：模型必须照抄这些数字，不得自行换算。 */
    public String toPromptBlock() {
        StringBuilder block = new StringBuilder();
        block.append("预算 ").append(money(budgetCents))
                .append("；").append(allowRepeat ? "允许重复购买同一商品" : "每件商品不能重复").append('\n');
        if (maxCount == 0) {
            block.append("结论：预算不足以购买目录中任何一件商品（最便宜为 ")
                    .append(nextCheapest == null ? "无" : nextCheapest.name() + " " + nextCheapest.priceLabel())
                    .append("）。\n");
            return block.toString();
        }
        block.append("最多可买 ").append(maxCount).append(" 件，合计 ").append(money(totalCents))
                .append("，剩余 ").append(money(remainingCents)).append('\n');
        block.append("组合明细：\n");
        for (Map.Entry<String, Integer> entry : grouped(items).entrySet()) {
            block.append("- ").append(entry.getKey());
            if (entry.getValue() > 1) {
                block.append(" × ").append(entry.getValue()).append(" 件");
            }
            block.append('\n');
        }
        if (nextCheapest != null) {
            block.append("再加一件最便宜的是 ").append(nextCheapest.name()).append(" ")
                    .append(nextCheapest.priceLabel()).append("，")
                    .append(money(totalCents)).append(" + ").append(money(nextCheapest.priceCents()))
                    .append(" = ").append(money(totalCents + nextCheapest.priceCents()))
                    .append(" > ").append(money(budgetCents)).append("，因此 ").append(maxCount).append(" 件已是上限。\n");
        }
        return block.toString();
    }

    /** 同名同价的商品合并成一行（允许重复时会出现多条相同项）。 */
    private Map<String, Integer> grouped(List<CatalogItem> chosen) {
        Map<String, Integer> grouped = new LinkedHashMap<>();
        for (CatalogItem item : chosen) {
            grouped.merge(item.name() + " " + item.priceLabel(), 1, Integer::sum);
        }
        return grouped;
    }

    private static String money(long cents) {
        return "¥" + String.format("%.2f", cents / 100.0);
    }

    static List<CatalogItem> repeated(CatalogItem item, int count) {
        List<CatalogItem> items = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            items.add(item);
        }
        return List.copyOf(items);
    }
}
