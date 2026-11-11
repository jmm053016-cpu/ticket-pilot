package com.example.aiticketassistant.application.tool.tools;

import com.example.aiticketassistant.application.catalog.BudgetPlan;
import com.example.aiticketassistant.application.catalog.BudgetPlanner;
import com.example.aiticketassistant.application.catalog.PriceCatalogService;
import com.example.aiticketassistant.application.routing.QuestionSignals;
import com.example.aiticketassistant.application.tool.ToolExecutor;
import com.example.aiticketassistant.application.tool.ToolMetadata;
import com.example.aiticketassistant.application.tool.ToolNames;
import com.example.aiticketassistant.application.tool.ToolResult;
import com.example.aiticketassistant.application.workflow.WorkflowContext;
import com.example.aiticketassistant.domain.catalog.CatalogItem;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 预算测算工具：给定预算，算出**最多能买几件**和**最便宜的组合**。
 *
 * <p>存在的唯一理由：这类问题本质是排序 + 贪心（重复购买时是除法），属于确定性计算。
 * 交给模型做会出错且不可复现（实测同一问题三次给出 934 / 954 / 964 三个不同合计，真值 1144）。
 * 由工具算完之后，模型只负责把结果讲成人话。
 *
 * <p>价格表来自 {@link PriceCatalogService}（全量知识库片段解析），**不依赖检索命中**，
 * 避免"少召回一个商品"导致结论错。
 */
@Component
public class PlanBudgetTool implements ToolExecutor {
    private static final Logger log = LoggerFactory.getLogger(PlanBudgetTool.class);
    /** 把测算结果放进上下文，供 KnowledgeAgent 引用。 */
    public static final String PLAN_CONTEXT_KEY = "budgetPlan";

    private final PriceCatalogService priceCatalog;

    public PlanBudgetTool(PriceCatalogService priceCatalog) {
        this.priceCatalog = priceCatalog;
    }

    @Override
    public ToolMetadata metadata() {
        return new ToolMetadata(
                ToolNames.PLAN_BUDGET,
                "Calculate, for a given budget, the max number of items that can be bought and the cheapest combination. "
                        + "Deterministic; use it instead of doing arithmetic yourself.",
                Map.of("budget", "number", "allowRepeat", "boolean"),
                Map.of("maxCount", "integer", "total", "string", "remaining", "string", "items", "array"));
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments, WorkflowContext context) {
        Long budgetCents = budgetCentsFrom(arguments, context);
        if (budgetCents == null) {
            return ToolResult.failure(ToolNames.PLAN_BUDGET, "没有从问题里识别出预算金额，无法测算");
        }
        boolean allowRepeat = allowRepeatFrom(arguments, context);
        List<CatalogItem> catalog = priceCatalog.items();
        if (catalog.isEmpty()) {
            return ToolResult.failure(ToolNames.PLAN_BUDGET, "知识库里没有可用的商品价格数据");
        }

        BudgetPlan plan = BudgetPlanner.plan(budgetCents, allowRepeat, catalog);
        context.remember(PLAN_CONTEXT_KEY, plan);
        log.info("PLAN_BUDGET 预算={} 允许重复={} → 最多 {} 件，合计 {} 分",
                budgetCents, allowRepeat, plan.maxCount(), plan.totalCents());

        return ToolResult.success(ToolNames.PLAN_BUDGET, Map.of(
                "budget", money(plan.budgetCents()),
                "allowRepeat", plan.allowRepeat(),
                "maxCount", plan.maxCount(),
                "total", money(plan.totalCents()),
                "remaining", money(plan.remainingCents()),
                "items", plan.items().stream()
                        .map(item -> Map.of("productId", item.productId(), "name", item.name(), "price", item.priceLabel()))
                        .toList(),
                "nextCheapest", plan.nextCheapest() == null
                        ? ""
                        : plan.nextCheapest().name() + " " + plan.nextCheapest().priceLabel()));
    }

    /** 优先用参数里的 budget（单位：元），否则从用户原话里解析。 */
    private Long budgetCentsFrom(Map<String, Object> arguments, WorkflowContext context) {
        Object raw = arguments == null ? null : arguments.get("budget");
        if (raw instanceof Number number && number.doubleValue() > 0) {
            return Math.round(number.doubleValue() * 100);
        }
        if (raw instanceof String text && !text.isBlank()) {
            try {
                return Math.round(Double.parseDouble(text.trim()) * 100);
            } catch (NumberFormatException ignored) {
                // 落到下面的文本解析
            }
        }
        Integer extracted = QuestionSignals.extractBudgetAmount(context.userMessage());
        return extracted == null ? null : extracted * 100L;
    }

    /**
     * 是否允许重复购买。
     *
     * <p>用户原话里**明确提到**就以此为准（模型可能把参数传错）；原话没提才用参数；
     * 都没有时默认允许重复（"最多能买几件"的常规理解是数量最大化）。
     */
    private boolean allowRepeatFrom(Map<String, Object> arguments, WorkflowContext context) {
        Boolean preference = QuestionSignals.repeatPreference(context.userMessage());
        if (preference != null) {
            return preference;
        }
        Object raw = arguments == null ? null : arguments.get("allowRepeat");
        if (raw instanceof Boolean value) {
            return value;
        }
        if (raw instanceof String text && !text.isBlank()) {
            return Boolean.parseBoolean(text.trim());
        }
        return true;
    }

    private String money(long cents) {
        return "¥" + String.format("%.2f", cents / 100.0);
    }
}
