package com.example.aiticketassistant.domain.knowledge;

import java.util.List;

/**
 * 知识片段来源端口：一次性取出全部片段，供检索打分。
 *
 * <p>为什么不直接用 Spring Data 仓储：检索逻辑（打分、排序、截断）需要能脱离数据库做评测
 * —— {@code RetrievalEvaluationTest} 用一份固定语料断言"某问法必须召回某片段"。
 * 端口在 domain 层，实现（JPA / 内存 / 将来的向量库）可以随意替换。
 */
public interface KnowledgeChunkSource {

    /** @return 全部知识片段；无数据返回空列表，绝不返回 null。 */
    List<KnowledgeChunk> all();
}
