package com.example.aiticketassistant.smoke;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.aiticketassistant.domain.knowledge.EmbeddingPort;
import com.example.aiticketassistant.domain.knowledge.KnowledgeSearchRepository;
import com.example.aiticketassistant.domain.knowledge.KnowledgeSearchResult;
import com.example.aiticketassistant.infrastructure.knowledge.embedding.HashingEmbeddingAdapter;
import com.example.aiticketassistant.infrastructure.knowledge.embedding.OpenAiCompatibleEmbeddingAdapter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 「语义向量」开关在**真容器里**的装配验证。
 *
 * <p>单测（{@code OpenAiCompatibleEmbeddingAdapterTest}）证明的是适配器自己会发请求、会缓存、会降级；
 * 这里证明的是 Spring 那一半：把 `assistant.retrieval.embedding-provider` 设成 `openai-compatible` 之后，
 * 容器里注入的 {@link EmbeddingPort} 必须换成真语义适配器（不是默认的哈希实现），而且**检索链路真的走到了它**
 * （桩服务收到了语料预嵌入 + 查询请求）。
 *
 * <p>桩服务用 JDK 自带的 {@link HttpServer}，端口随机分配后通过 {@link DynamicPropertySource} 注入 ——
 * 不联网、不依赖任何外部进程。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "assistant.ai.provider=fallback",
                "spring.jpa.hibernate.ddl-auto=create-drop",
                "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
                // 关键：打开语义向量开关（哈希适配器同时被 @ConditionalOnProperty 排除）
                "assistant.retrieval.embedding-provider=openai-compatible",
                "assistant.retrieval.embedding-model=stub-embedding",
                "assistant.retrieval.embedding-api-key=sk-stub",
                "assistant.retrieval.embedding-dimensions=3"
})
@ActiveProfiles("test")
class SemanticEmbeddingWiringTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final AtomicInteger REQUESTS = new AtomicInteger();
    private static HttpServer stub;

    /** 桩服务：按文本关键词给 3 维向量，形状与 OpenAI /embeddings 一致。 */
    @DynamicPropertySource
    static void embeddingStubProperties(DynamicPropertyRegistry registry) throws IOException {
        if (stub == null) {
            stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            stub.createContext("/embeddings", exchange -> {
                REQUESTS.incrementAndGet();
                JsonNode request = MAPPER.readTree(exchange.getRequestBody().readAllBytes());
                List<String> inputs = new ArrayList<>();
                request.path("input").forEach(node -> inputs.add(node.asText()));
                List<String> data = new ArrayList<>();
                for (int i = 0; i < inputs.size(); i++) {
                    String text = inputs.get(i);
                    double coat = text.contains("大衣") || text.contains("冬天") ? 1.0 : 0.05;
                    data.add("{\"index\":%d,\"embedding\":[%s,0.3,0.2]}".formatted(i, coat));
                }
                byte[] body = ("{\"data\":[" + String.join(",", data) + "]}").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            stub.start();
        }
        registry.add("assistant.retrieval.embedding-base-url",
                () -> "http://127.0.0.1:" + stub.getAddress().getPort());
    }

    @Autowired
    private EmbeddingPort embeddingPort;

    @Autowired
    private KnowledgeSearchRepository searchRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void semanticAdapterIsSelectedInsteadOfTheHashingOne() {
        assertThat(embeddingPort).isInstanceOf(OpenAiCompatibleEmbeddingAdapter.class);
        assertThat(embeddingPort).isNotInstanceOf(HashingEmbeddingAdapter.class);
    }

    @Test
    void searchActuallyGoesThroughTheSemanticService() {
        // 种一条片段，然后问一句"字面不像"的话：只有语义那一路能把它捞出来
        jdbcTemplate.update("delete from knowledge_chunks");
        jdbcTemplate.update("""
                insert into knowledge_chunks (id, document_id, title, category, source, content)
                values ('CHK-COAT-1', 'DOC-COAT', '服装商品目录：羊毛混纺大衣', 'CATALOG', 'internal://c',
                        '商品编号 CLOTH-COAT-009，商品名称 羊毛混纺大衣，服装商品，价格 ¥699.00。')
                """);

        int before = REQUESTS.get();
        List<KnowledgeSearchResult> results = searchRepository.search("冬天穿的那件长款的多少钱", 3);

        assertThat(REQUESTS.get())
                .as("检索必须真的调用语义服务（语料预嵌入 + 查询向量）")
                .isGreaterThan(before);
        assertThat(results).isNotEmpty();
        assertThat(results.get(0).chunk().id()).isEqualTo("CHK-COAT-1");
        assertThat(results.get(0).matchReason()).contains("vec#");
    }
}
