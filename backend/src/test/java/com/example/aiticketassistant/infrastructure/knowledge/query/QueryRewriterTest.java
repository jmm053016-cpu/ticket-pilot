package com.example.aiticketassistant.infrastructure.knowledge.query;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 查询改写的口径（RAG 召回的第一道关口）。
 *
 * <p>锁两件事：① 中文口语数字能变成可检索的数字；② 同义词只扩召回、不吞原文，
 * 而且**不误伤普通词**（「一共」「两件」不许被改写成"1共""2件"，否则 BM25 反而更差）。
 */
class QueryRewriterTest {
    private final QueryRewriter rewriter = new QueryRewriter();

    @Test
    void chineseNumbersBecomeDigits() {
        assertThat(rewriter.rewrite("七百块的衣服").text()).contains("700");
        assertThat(rewriter.rewrite("六百九十九元的衣服").text()).contains("699");
        assertThat(rewriter.rewrite("一千元以内").text()).contains("1000");
        assertThat(rewriter.rewrite("一千五的衣服").text()).contains("1500");
        assertThat(rewriter.rewrite("两百五的帽子").text()).contains("250");
        assertThat(rewriter.rewrite("一万二预算").text()).contains("12000");
    }

    @Test
    void ordinaryWordsAreNotRewritten() {
        // 没有单位的"一/两"是普通词，改了会把 BM25 的关键词打碎
        assertThat(rewriter.rewrite("一共多少钱").text()).doesNotContain("1共");
        assertThat(rewriter.rewrite("两件衣服").text()).doesNotContain("2件");
        assertThat(rewriter.rewrite("谢谢，明白了").text()).isEqualTo("谢谢，明白了");
    }

    @Test
    void synonymsExpandTheColloquialWording() {
        RewrittenQuery outerwear = rewriter.rewrite("外套多少钱");
        assertThat(outerwear.expansions()).contains("大衣", "夹克", "风衣");
        assertThat(outerwear.text()).contains("外套").contains("大衣");

        assertThat(rewriter.rewrite("帽衫多少钱").expansions()).contains("卫衣");
        assertThat(rewriter.rewrite("裙子有哪些").expansions()).contains("半身裙", "连衣裙");
        assertThat(rewriter.rewrite("鞋子多少钱").expansions()).contains("运动鞋", "帆布鞋");
    }

    @Test
    void alreadyPresentSynonymsAreNotDuplicated() {
        RewrittenQuery rewritten = rewriter.rewrite("大衣外套多少钱");

        assertThat(rewritten.expansions()).doesNotContain("大衣");
        assertThat(rewritten.text()).isEqualTo("大衣外套多少钱 夹克 风衣 羽绒服 开衫");
    }

    @Test
    void numbersAreExposedForProximityMatching() {
        // 「七百」→ 700：BM25 靠这个数字做 ±2% 邻近加成，才能匹配到 ¥699
        assertThat(rewriter.rewrite("七百块的衣服").numbers()).containsExactly(700.0);
        assertThat(rewriter.rewrite("谢谢").numbers()).isEmpty();
    }

    @Test
    void blankQueryStaysBlank() {
        assertThat(rewriter.rewrite(null).isBlank()).isTrue();
        assertThat(rewriter.rewrite("   ").isBlank()).isTrue();
    }
}
