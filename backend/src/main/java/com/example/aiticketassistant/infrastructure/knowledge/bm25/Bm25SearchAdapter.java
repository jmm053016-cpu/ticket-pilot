package com.example.aiticketassistant.infrastructure.knowledge.bm25;

import com.example.aiticketassistant.application.routing.QuestionSignals;
import com.example.aiticketassistant.domain.knowledge.KnowledgeChunk;
import com.example.aiticketassistant.infrastructure.knowledge.query.RewrittenQuery;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 本地 BM25 风格打分（中文按 2~8 元 n-gram + 数字 token）。
 *
 * <p>三个加成分：
 * <ul>
 *   <li>{@code 命中率}：查询词在片段里命中的比例，是基础分；</li>
 *   <li>{@code phraseBoost}：整句命中时额外加分（精确问法）；</li>
 *   <li>{@code summaryBoost}：**汇总型问题**（预算/最多/几件/搭配/哪几种…）给**汇总型片段**（整张价格表）加分。
 *       没有它，328 字的汇总片段会被 57 字的单品片段压过去 —— 于是模型只看到 3 个单品，
 *       算出"最低价 ¥229"，而真实最低价是 ¥99。判定见 {@link QuestionSignals#isAggregateQuestion}。</li>
 * </ul>
 *
 * <p>**summaryBoost 的两个前提缺一不可**（2026-11-04 修）：问题既要是"汇总型"（预算/哪些/最便宜…），
 * 又要是"商品/预算类"（{@link QuestionSignals#isCatalogOrBudgetQuestion}）。
 * 只用前者的话，"支持哪些支付方式"这种售后问题会因为含"哪些"白拿 +0.4，
 * 把整张服装价格表顶到第一位（实测 0.4 vs 真实命中 0.036）—— 直答路径据此判断"有没有资料"就会被彻底带偏。
 */
@Component
public class Bm25SearchAdapter {
    private static final Pattern NUMBER_PATTERN = Pattern.compile("\\d+(?:\\.\\d+)?");
    private static final Pattern PRICE_MARKER_PATTERN = Pattern.compile("¥\\s*\\d+(?:\\.\\d+)?");
    /** 片段里出现这么多个价格标记，就认为它是"汇总型"（整张价格表）而不是单品描述。 */
    private static final int SUMMARY_PRICE_MARKER_THRESHOLD = 5;
    /** 数值邻近加成的幅度：小于 phraseBoost(0.5)，大于纯命中率的噪声。 */
    private static final double NUMBER_PROXIMITY_BOOST = 0.15;
    /** 太小的数字（件数、尺码）不做邻近判断，免得"2 件"匹配到"¥2xx"。 */
    private static final double NUMBER_PROXIMITY_MIN = 50.0;

    public double score(String query, KnowledgeChunk chunk) {
        return score(query, extractNumbers(query), chunk);
    }

    /** 改写后的查询（已做中文数字归一 + 同义扩展），会用 {@code numbers} 做数值邻近加成。 */
    public double score(RewrittenQuery query, KnowledgeChunk chunk) {
        return score(query.text(), query.numbers(), chunk);
    }

    private double score(String query, List<Double> numbers, KnowledgeChunk chunk) {
        String searchable = normalize(chunk.title() + " " + chunk.content());
        Set<String> terms = tokenize(query);
        Set<String> contentTerms = tokenize(searchable);
        if (terms.isEmpty()) {
            return 0.0;
        }
        long matches = terms.stream().filter(term -> contentTerms.contains(term) || searchable.contains(term)).count();
        String normalizedQuery = normalize(query);
        boolean phraseBoost = searchable.contains(normalizedQuery);
        // 汇总片段只能给"商品/预算类"的汇总型问题加分（见类注释：售后问题含"哪些"不该拿这个加成）
        boolean summaryBoost = isSummaryChunk(chunk)
                && QuestionSignals.isAggregateQuestion(normalizedQuery)
                && QuestionSignals.isCatalogOrBudgetQuestion(normalizedQuery);
        boolean numberBoost = numbers.isEmpty() ? false : hasCloseNumber(numbers, searchable);
        return matches / (double) terms.size()
                + (phraseBoost ? 0.5 : 0.0)
                + (summaryBoost ? 0.4 : 0.0)
                + (numberBoost ? NUMBER_PROXIMITY_BOOST : 0.0);
    }

    /**
     * 数值邻近：口语价格是"约数"，「七百块的衣服」指的是 ¥699。
     *
     * <p>查询数字与片段里任一数字的差在 {@code max(10, 2%)} 以内就算命中，加一个**小于** phraseBoost 的分
     * （0.15：足以把这一条从同类噪音里拉出来，不足以让"约数"盖过精确命中）。
     * 数字归一把「七百」变成 700 之后仍然对不上 699，这一步就是补这个 gap 的。
     */
    private boolean hasCloseNumber(List<Double> numbers, String searchable) {
        Matcher matcher = NUMBER_PATTERN.matcher(searchable);
        List<Double> chunkNumbers = new ArrayList<>();
        while (matcher.find()) {
            chunkNumbers.add(Double.parseDouble(matcher.group()));
        }
        if (chunkNumbers.isEmpty()) {
            return false;
        }
        for (double query : numbers) {
            if (query < NUMBER_PROXIMITY_MIN) {
                continue;
            }
            double tolerance = Math.max(10.0, query * 0.02);
            for (double candidate : chunkNumbers) {
                if (Math.abs(query - candidate) <= tolerance) {
                    return true;
                }
            }
        }
        return false;
    }

    private List<Double> extractNumbers(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<Double> numbers = new ArrayList<>();
        Matcher matcher = NUMBER_PATTERN.matcher(normalize(text));
        while (matcher.find()) {
            numbers.add(Double.parseDouble(matcher.group()));
        }
        return numbers;
    }

    /** 汇总型片段：标题点明"完整价格表"，或内容里价格标记足够多（不依赖标题写法）。 */
    boolean isSummaryChunk(KnowledgeChunk chunk) {
        String title = chunk.title() == null ? "" : chunk.title();
        if (title.contains("完整价格表")) {
            return true;
        }
        String content = chunk.content() == null ? "" : chunk.content();
        Matcher matcher = PRICE_MARKER_PATTERN.matcher(content);
        int count = 0;
        while (matcher.find()) {
            if (++count >= SUMMARY_PRICE_MARKER_THRESHOLD) {
                return true;
            }
        }
        return false;
    }

    private Set<String> tokenize(String text) {
        if (text == null || text.isBlank()) {
            return Set.of();
        }
        String normalized = normalize(text);
        Set<String> tokens = new HashSet<>(Arrays.stream(normalized.split("\\s+"))
                .filter(token -> token.length() > 1)
                .toList());
        NUMBER_PATTERN.matcher(normalized).results()
                .map(match -> match.group().replaceAll("\\.0+$", ""))
                .forEach(tokens::add);
        chineseNgrams(normalized).forEach(tokens::add);
        return tokens;
    }

    private String normalize(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT).replaceAll("[^\\p{IsHan}a-z0-9.]+", " ").trim();
    }

    private Set<String> chineseNgrams(String text) {
        Set<String> grams = new LinkedHashSet<>();
        String han = text.replaceAll("[^\\p{IsHan}]", "");
        for (int size : new int[]{2, 3, 4, 5, 6, 7, 8}) {
            for (int i = 0; i + size <= han.length(); i++) {
                grams.add(han.substring(i, i + size));
            }
        }
        return grams;
    }
}
