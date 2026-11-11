package com.example.aiticketassistant.interfaces.rest;

import com.example.aiticketassistant.application.query.SearchKnowledgeQuery;
import com.example.aiticketassistant.application.query.SearchKnowledgeQueryHandler;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping("/knowledge")
public class KnowledgeController {
    private final SearchKnowledgeQueryHandler handler;

    public KnowledgeController(SearchKnowledgeQueryHandler handler) {
        this.handler = handler;
    }

    /**
     * 检索调试入口。
     *
     * <p>**必须离开 Netty 事件循环**：这条链路是阻塞的 —— 片段从 JDBC 读（`KnowledgeChunkSource.all()`），
     * 向量那一路启用语义模型时还要发 HTTP。直接在事件循环上跑会把线程卡住；
     * 而且 Reactor 在事件循环线程上禁止 `block()`，语义适配器会因此**静默降级**（2026-11-04 真机踩到）。
     */
    @GetMapping("/search")
    public Mono<List<Map<String, Object>>> search(@RequestParam String query,
                                                  @RequestParam(defaultValue = "5") int limit) {
        return Mono.fromCallable(() -> handler.handle(new SearchKnowledgeQuery(query, limit)).stream()
                        .map(result -> Map.<String, Object>of(
                                "id", result.chunk().id(),
                                "title", result.chunk().title(),
                                "source", result.chunk().source(),
                                "content", result.chunk().content(),
                                "score", result.finalScore()))
                        .toList())
                .subscribeOn(Schedulers.boundedElastic());
    }
}
