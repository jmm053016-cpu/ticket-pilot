package com.example.aiticketassistant.infrastructure.knowledge.query;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 检索前的查询改写：**中文数字归数字** + **口语同义词扩展**。
 *
 * <p>存在的理由：项目里最常被吐槽的召回失败都是"问法不像文档"——
 * 「七百块的衣服」检不到 ¥699、「外套多少钱」检不到「羊毛混纺大衣」。
 * 这两类问题都不是排序算法能救的，必须在**查询侧**把说法对齐到语料：
 *
 * <ul>
 *   <li><b>数字归一</b>：中文数字转阿拉伯数字（七百 → 700、一千五 → 1500、六百九十九 → 699），
 *       这样 BM25 的 term 能与片段里的价格数字对齐；</li>
 *   <li><b>同义扩展</b>：把口语词扩展成语料里的说法（外套 → 大衣/夹克/风衣/羽绒服/开衫），
 *       扩展词同时喂给 BM25 与向量两路，所以同义改写对两路都生效（向量本身只是字面哈希）。</li>
 * </ul>
 *
 * <p>**边界（重要）**：这张同义词表只服务于"检索召回"，与 {@code QuestionSignals}（路由/意图判定）**互不相干**。
 * 别把它搬进 QuestionSignals：那张表动一下会影响路由、意图、兜底、工具，而这里扩大召回是安全的。
 * 也正因为只是"扩大召回"，扩展是**只增不减**的（原文照旧参与打分）。
 */
@Component
public class QueryRewriter {
    /** 中文数字字符（含"两"这个口语说法）。 */
    private static final String CN_DIGITS = "零一二两三四五六七八九";
    private static final Map<Character, Integer> DIGIT_VALUE = Map.ofEntries(
            Map.entry('零', 0), Map.entry('一', 1), Map.entry('二', 2), Map.entry('两', 2), Map.entry('三', 3),
            Map.entry('四', 4), Map.entry('五', 5), Map.entry('六', 6), Map.entry('七', 7), Map.entry('八', 8),
            Map.entry('九', 9));
    private static final Map<Character, Integer> UNIT_VALUE = Map.of(
            '十', 10, '百', 100, '千', 1000, '万', 10000);

    /**
     * 候选中文数字串：连续的"数字 + 单位"字符。
     *
     * <p>**不带后置断言**，改由 {@link #chineseNumberToDigits} 里的两条纪律把普通词排除：
     * ① 没有单位（十/百/千/万）不算数字 —— 所以「一共」「两件」不会被改写成"1共""2件"；
     * ② 停用词（「十分」「千万」「万一」）保留原样 —— 它们是日常词，不是数量。
     */
    private static final Pattern CN_NUMBER = Pattern.compile("[" + CN_DIGITS + "十百千万]+");

    /** 日常词里长得像数字的串，一律不改。 */
    private static final Set<String> NUMBER_STOPWORDS = Set.of("十", "千万", "万一", "一二");

    /**
     * 口语 → 语料说法。只放"确定性高、语料里确实有对应商品/文档"的词，
     * 不做泛化（否则"衣服"会扩成 30 个词，等于没扩）。加词时请同步 {@code QueryRewriterTest} 与 §6.5 ⑤。
     */
    private static final Map<String, List<String>> SYNONYMS = Map.ofEntries(
            Map.entry("外套", List.of("大衣", "夹克", "风衣", "羽绒服", "开衫")),
            Map.entry("大衣", List.of("外套")),
            Map.entry("夹克", List.of("外套", "风衣")),
            Map.entry("风衣", List.of("外套", "大衣")),
            Map.entry("羽绒服", List.of("羽绒", "外套")),
            Map.entry("帽衫", List.of("卫衣", "连帽卫衣")),
            Map.entry("卫衣", List.of("连帽卫衣")),
            Map.entry("裙子", List.of("半身裙", "连衣裙")),
            Map.entry("半身裙", List.of("裙子")),
            Map.entry("连衣裙", List.of("裙子")),
            Map.entry("裤子", List.of("牛仔裤", "休闲裤")),
            Map.entry("牛仔裤", List.of("裤子")),
            Map.entry("毛衣", List.of("针织毛衣", "高领毛衣")),
            Map.entry("针织", List.of("毛衣", "开衫")),
            Map.entry("鞋子", List.of("运动鞋", "帆布鞋")),
            Map.entry("鞋", List.of("运动鞋", "帆布鞋")),
            Map.entry("包", List.of("双肩包")),
            Map.entry("双肩包", List.of("包")),
            Map.entry("围巾", List.of("羊毛围巾")),
            Map.entry("短袖", List.of("T恤")),
            Map.entry("体恤", List.of("T恤")),
            Map.entry("t恤", List.of("短袖", "圆领")));

    /**
     * 改写查询。返回的 {@code text} 是"原文 + 扩展词"，{@code numbers} 供打分做数值邻近判断。
     */
    public RewrittenQuery rewrite(String query) {
        if (query == null || query.isBlank()) {
            return new RewrittenQuery("", List.of(), Set.of());
        }
        String withDigits = normalizeChineseNumbers(query);
        List<Double> numbers = extractNumbers(withDigits);
        Set<String> expansions = new LinkedHashSet<>();
        String lowered = withDigits.toLowerCase(java.util.Locale.ROOT);
        SYNONYMS.forEach((word, synonyms) -> {
            if (lowered.contains(word)) {
                synonyms.stream().filter(synonym -> !lowered.contains(synonym)).forEach(expansions::add);
            }
        });
        String text = expansions.isEmpty() ? withDigits : withDigits + " " + String.join(" ", expansions);
        return new RewrittenQuery(text, numbers, Set.copyOf(expansions));
    }

    /** 中文数字 → 阿拉伯数字；只处理带单位的数字串，普通词原样保留。 */
    String normalizeChineseNumbers(String text) {
        Matcher matcher = CN_NUMBER.matcher(text);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String run = matcher.group();
            String digits = chineseNumberToDigits(run);
            matcher.appendReplacement(result, Matcher.quoteReplacement(digits == null ? run : digits));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    /**
     * 解析一段中文数字；不合法的组合（如「十一二三」）返回 null，调用方保留原文。
     *
     * <p>支持的口语写法：六百九十九 → 699、七百 → 700、一千五 → 1500（末位是数字且最高单位是千/百时按"缺省下级单位"处理）、
     * 两千五 → 2500、一万二 → 12000。
     */
    String chineseNumberToDigits(String run) {
        if (run == null || run.isEmpty() || NUMBER_STOPWORDS.contains(run)) {
            return null;
        }
        boolean hasUnit = run.chars().anyMatch(c -> UNIT_VALUE.containsKey((char) c));
        if (!hasUnit) {
            return null;
        }
        int total = 0;
        int section = 0;
        int lastDigit = -1;
        int lastUnit = Integer.MAX_VALUE;
        boolean lastWasDigit = false;
        for (char ch : run.toCharArray()) {
            Integer digit = DIGIT_VALUE.get(ch);
            if (digit != null) {
                lastDigit = digit;
                lastWasDigit = true;
                continue;
            }
            Integer unit = UNIT_VALUE.get(ch);
            if (unit == null) {
                return null;
            }
            if (unit == 10000) {
                section += lastWasDigit && lastDigit >= 0 ? lastDigit : 0;
                total += (section == 0 ? 1 : section) * 10000;
                section = 0;
                // 记下"万"这一级：后面若只跟一个裸数字（"一万二"），按 万/10 = 千 处理
                lastUnit = 10000;
            } else {
                int value = lastWasDigit && lastDigit >= 0 ? lastDigit : 1;   // "十五" = 15
                section += value * unit;
                lastUnit = unit;
            }
            lastDigit = -1;
            lastWasDigit = false;
        }
        if (lastWasDigit && lastDigit >= 0) {
            // "一千五"=1500、"两百五"=250：末位数字按上一级单位的十分之一
            section += lastUnit == Integer.MAX_VALUE ? lastDigit : lastDigit * (lastUnit / 10);
        }
        int value = total + section;
        return value <= 0 ? null : String.valueOf(value);
    }

    private List<Double> extractNumbers(String text) {
        List<Double> numbers = new ArrayList<>();
        Matcher matcher = Pattern.compile("\\d+(?:\\.\\d+)?").matcher(text);
        while (matcher.find()) {
            try {
                numbers.add(Double.parseDouble(matcher.group()));
            } catch (NumberFormatException ignored) {
                // 超长数字串忽略即可，不影响召回
            }
        }
        return numbers;
    }
}
