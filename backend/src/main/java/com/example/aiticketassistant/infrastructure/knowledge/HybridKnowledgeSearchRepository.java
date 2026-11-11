package com.example.aiticketassistant.infrastructure.knowledge;

import com.example.aiticketassistant.domain.knowledge.EmbeddingPort;
import com.example.aiticketassistant.domain.knowledge.KnowledgeChunk;
import com.example.aiticketassistant.domain.knowledge.KnowledgeChunkSource;
import com.example.aiticketassistant.domain.knowledge.KnowledgeSearchRepository;
import com.example.aiticketassistant.domain.knowledge.KnowledgeSearchResult;
import com.example.aiticketassistant.infrastructure.knowledge.bm25.Bm25SearchAdapter;
import com.example.aiticketassistant.infrastructure.knowledge.query.QueryRewriter;
import com.example.aiticketassistant.infrastructure.knowledge.query.RewrittenQuery;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Repository;

/**
 * 真正的混合检索：**向量召回 + BM25 召回 → RRF 融合**。
 *
 * <p>以前这里是个"假混合"：{@code ChromaClient.searchIds()} 恒返回空列表，{@code finalScore} 就等于 BM25 分数，
 * 于是同义改写一律召回不到（「七百」≠「699」、「外套」≠「大衣」）。现在的三条腿：
 *
 * <ol>
 *   <li><b>查询改写</b>（{@link QueryRewriter}）：中文数字归一 + 同义扩展，两路召回共用改写后的查询；</li>
 *   <li><b>向量召回</b>（{@link EmbeddingPort}，默认本地哈希向量）：字符 n-gram 余弦，抓"换个说法但字面相近"；</li>
 *   <li><b>BM25 召回</b>（{@link Bm25SearchAdapter}）：精确词/短语/汇总片段加成 + 数值邻近加成。</li>
 * </ol>
 *
 * <p><b>排序与阈值都用融合分，RRF 只做并列裁决</b>（这个分工是实测出来的）：
 * <ul>
 *   <li>{@code finalScore = 0.7*饱和(BM25) + 0.3*余弦}：
 *       BM25 权重更高是因为它带"精确词/短语/数值邻近"这类高置信信号；余弦那一路是字面哈希，
 *       适合补召回、不适合定冠军（实测等权时「价格刚好699的衣服是什么」的冠军会被一条噪音片段抢走）。</li>
 *   <li>BM25 是 0~2 的启发式分数，**用饱和函数** {@code x/(x+0.25)} 压到 0~1，
 *       而不是"按本次查询的最大值归一" —— 后者会让"整库只有一条弱命中"的查询也拿到 1.0，
 *       于是 {@code finalScore} 失去绝对意义（直答路径拿它当相关性门槛，会被这种相对化骗到）。</li>
 *   <li>RRF（{@code Σ 1/(K+rank)}）只用来裁决融合分并列的候选。语料只有几十条时 RRF 值几乎是一条水平线
 *       （实测全在 0.0296~0.0330），单靠它排不出差别；语料大、两路差异明显时它才发挥"名次比分数稳"的优点，
 *       所以保留实现并写进 {@code matchReason} 便于排障。</li>
 * </ul>
 *
 * <p><b>为什么"内存里算"而不上向量库</b>：语料是几百条以内的知识片段，全量扫描 + 缓存
 * （{@code vectorCache} + 一次性 {@code fit}）成本可忽略；向量库会多一个部署依赖和一堆一致性/运维问题。
 * 语料长到十万级时，把本类换成"向量库召回 + 本类融合"即可，对外接口不变。
 */
@Repository
public class HybridKnowledgeSearchRepository implements KnowledgeSearchRepository {
    /** RRF 的平滑常数（文献默认 60）：越大越"平权"，越小越偏爱两路冠军。 */
    private static final int RRF_K = 60;
    /** 每路先取这么多候选再融合（对几百条语料等于全量，留给"以后语料变大"的余量）。 */
    private static final int RECALL_LIMIT = 50;
    /** 融合权重：BM25 主导（高置信信号），向量补召回。 */
    private static final double BM25_WEIGHT = 0.7;
    private static final double VECTOR_WEIGHT = 0.3;
    /** BM25 饱和参数：分数等于它时压到 0.5（语料里"命中一半查询词"大致落在这个量级）。 */
    private static final double BM25_SATURATION = 0.25;

    private final KnowledgeChunkSource chunkSource;
    private final Bm25SearchAdapter bm25;
    private final QueryRewriter queryRewriter;
    private final EmbeddingPort embeddingPort;
    /** 片段向量缓存：key 含标题/正文长度，迁移里改了正文就会自然重新算。 */
    private final Map<String, float[]> vectorCache = new ConcurrentHashMap<>();
    /** 上次 fit 时的语料指纹（数量 + 总长度）：语料变了就重新拟合 IDF 并清空缓存。 */
    private volatile String fittedCorpusFingerprint;

    public HybridKnowledgeSearchRepository(KnowledgeChunkSource chunkSource,
                                           Bm25SearchAdapter bm25,
                                           QueryRewriter queryRewriter,
                                           EmbeddingPort embeddingPort) {
        this.chunkSource = chunkSource;
        this.bm25 = bm25;
        this.queryRewriter = queryRewriter;
        this.embeddingPort = embeddingPort;
    }

    @Override
    public List<KnowledgeSearchResult> search(String query, int limit) {
        RewrittenQuery rewritten = queryRewriter.rewrite(query);
        if (rewritten.isBlank()) {
            return List.of();
        }
        List<KnowledgeChunk> chunks = chunkSource.all();
        if (chunks.isEmpty()) {
            return List.of();
        }
        fitIfCorpusChanged(chunks);
        float[] queryVector = embeddingPort.embed(rewritten.text());

        // 两路召回：各自算分 → 各自排序 → 各取前 N
        List<Scored> byVector = new ArrayList<>();
        List<Scored> byBm25 = new ArrayList<>();
        for (KnowledgeChunk chunk : chunks) {
            double cosine = cosine(queryVector, vectorOf(chunk));
            if (cosine > 0.0) {
                byVector.add(Scored.vector(chunk, cosine));
            }
            double lexical = bm25.score(rewritten, chunk);
            if (lexical > 0.0) {
                byBm25.add(Scored.lexical(chunk, lexical));
            }
        }
        byVector.sort(Comparator.comparingDouble(Scored::cosine).reversed());
        byBm25.sort(Comparator.comparingDouble(Scored::bm25).reversed());
        byVector = head(byVector, RECALL_LIMIT);
        byBm25 = head(byBm25, RECALL_LIMIT);

        // RRF：名次融合（决定排序）
        Map<String, Double> rrf = new HashMap<>();
        Map<String, Integer> vectorRank = new HashMap<>();
        Map<String, Integer> bm25Rank = new HashMap<>();
        for (int i = 0; i < byVector.size(); i++) {
            String id = byVector.get(i).chunk().id();
            rrf.merge(id, 1.0 / (RRF_K + i + 1), Double::sum);
            vectorRank.put(id, i + 1);
        }
        for (int i = 0; i < byBm25.size(); i++) {
            String id = byBm25.get(i).chunk().id();
            rrf.merge(id, 1.0 / (RRF_K + i + 1), Double::sum);
            bm25Rank.put(id, i + 1);
        }

        // 融合分：BM25 饱和 + 余弦（都是绝对量级、跨查询可比 —— 直答路径拿它当相关性门槛）
        Map<String, Scored> merged = new HashMap<>();
        byVector.forEach(scored -> merged.merge(scored.chunk().id(), scored, Scored::withCosineOf));
        byBm25.forEach(scored -> merged.merge(scored.chunk().id(), scored, Scored::withBm25Of));

        List<KnowledgeSearchResult> results = new ArrayList<>();
        for (Map.Entry<String, Scored> entry : merged.entrySet()) {
            Scored scored = entry.getValue();
            // 余弦本身就是 0~1 的绝对量级；BM25 用饱和函数压到 0~1（见类注释）
            double fused = VECTOR_WEIGHT * Math.max(0.0, orZero(scored.cosine()))
                    + BM25_WEIGHT * saturate(scored.bm25());
            results.add(new KnowledgeSearchResult(
                    scored.chunk(),
                    round(scored.cosine()),
                    round(scored.bm25()),
                    round(fused),
                    reason(vectorRank.get(entry.getKey()), bm25Rank.get(entry.getKey()),
                            rrf.getOrDefault(entry.getKey(), 0.0))));
        }
        Map<String, Double> rrfByChunk = rrf;
        return results.stream()
                // 主序：融合分；并列时用 RRF 裁决（名次信号比分数更稳）
                .sorted(Comparator.comparingDouble(KnowledgeSearchResult::finalScore).reversed()
                        .thenComparing(Comparator.comparingDouble(
                                (KnowledgeSearchResult result) -> rrfByChunk.getOrDefault(result.chunk().id(), 0.0)).reversed()))
                .limit(Math.max(1, limit))
                .toList();
    }

    /** 语料变了（数量或总长度变化）就重新拟合 IDF 并清空向量缓存。 */
    private void fitIfCorpusChanged(List<KnowledgeChunk> chunks) {
        String fingerprint = chunks.size() + ":" + chunks.stream()
                .mapToInt(chunk -> (chunk.title() == null ? 0 : chunk.title().length())
                        + (chunk.content() == null ? 0 : chunk.content().length()))
                .sum();
        if (fingerprint.equals(fittedCorpusFingerprint)) {
            return;
        }
        synchronized (this) {
            if (fingerprint.equals(fittedCorpusFingerprint)) {
                return;
            }
            embeddingPort.fit(chunks);
            vectorCache.clear();
            fittedCorpusFingerprint = fingerprint;
        }
    }

    /** 片段向量（带缓存）。 */
    private float[] vectorOf(KnowledgeChunk chunk) {
        String title = chunk.title() == null ? "" : chunk.title();
        String content = chunk.content() == null ? "" : chunk.content();
        String key = chunk.id() + ":" + title.length() + ":" + content.length();
        return vectorCache.computeIfAbsent(key, ignored -> embeddingPort.embed(title + " " + content));
    }

    private static double cosine(float[] left, float[] right) {
        if (left.length != right.length) {
            return 0.0;
        }
        double dot = 0.0;
        for (int i = 0; i < left.length; i++) {
            dot += left[i] * right[i];
        }
        return dot;   // 两边都已 L2 归一化
    }

    /** 单路缺失记为 0（NaN 表示"这条路没看见它"）。 */
    private static double orZero(double value) {
        return Double.isNaN(value) ? 0.0 : value;
    }

    /** BM25 饱和：把 0~2 的启发式分数压到 0~1，保留绝对量级（见类注释）。 */
    private static double saturate(double value) {
        double score = orZero(value);
        return score <= 0.0 ? 0.0 : score / (score + BM25_SATURATION);
    }

    private static List<Scored> head(List<Scored> scored, int limit) {
        return scored.size() <= limit ? scored : scored.subList(0, limit);
    }

    private static double round(double value) {
        if (Double.isNaN(value)) {
            return 0.0;
        }
        return Math.round(value * 1000.0) / 1000.0;
    }

    /** 排障口径：这条靠哪一路进来、RRF 多少。 */
    private static String reason(Integer vectorRank, Integer bm25Rank, double rrf) {
        String route;
        if (vectorRank != null && bm25Rank != null) {
            route = "both(vec#" + vectorRank + ",bm25#" + bm25Rank + ")";
        } else if (bm25Rank != null) {
            route = "bm25#" + bm25Rank;
        } else {
            route = "vec#" + vectorRank;
        }
        return "hybrid:" + route + ",rrf=" + Math.round(rrf * 10000) / 10000.0;
    }

    /** 单条候选：某一路没命中时该路分数为 NaN（表示"这条路没看见它"）。 */
    private record Scored(KnowledgeChunk chunk, double cosine, double bm25) {
        static Scored vector(KnowledgeChunk chunk, double cosine) {
            return new Scored(chunk, cosine, Double.NaN);
        }

        static Scored lexical(KnowledgeChunk chunk, double bm25) {
            return new Scored(chunk, Double.NaN, bm25);
        }

        /** 合并：保留原有 cosine，补上 BM25 分。 */
        Scored withBm25Of(Scored other) {
            return new Scored(chunk, cosine, other.bm25);
        }

        /** 合并：保留原有 BM25 分，补上 cosine。 */
        Scored withCosineOf(Scored other) {
            return new Scored(chunk, other.cosine, bm25);
        }
    }
}
