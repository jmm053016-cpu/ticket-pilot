package com.example.aiticketassistant.infrastructure.knowledge.query;

import java.util.List;
import java.util.Set;

/**
 * 改写后的查询（{@link QueryRewriter} 的产物）。
 *
 * @param text     原文 + 扩展词，BM25 与向量都用它
 * @param numbers  查询里出现的数字（中文数字已归一），供数值邻近加成使用
 * @param expansions 实际追加的同义词（日志/排障用，便于解释"为什么召回了这条"）
 */
public record RewrittenQuery(String text, List<Double> numbers, Set<String> expansions) {

    public boolean isBlank() {
        return text == null || text.isBlank();
    }
}
