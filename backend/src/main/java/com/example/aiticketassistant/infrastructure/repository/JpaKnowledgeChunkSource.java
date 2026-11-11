package com.example.aiticketassistant.infrastructure.repository;

import com.example.aiticketassistant.domain.knowledge.KnowledgeChunk;
import com.example.aiticketassistant.domain.knowledge.KnowledgeChunkSource;
import com.example.aiticketassistant.infrastructure.persistence.jpa.KnowledgeChunkJpaEntity;
import com.example.aiticketassistant.infrastructure.persistence.jpa.SpringDataKnowledgeChunkJpaRepository;
import java.util.List;
import org.springframework.stereotype.Repository;

/** {@link KnowledgeChunkSource} 的 JPA 实现。 */
@Repository
public class JpaKnowledgeChunkSource implements KnowledgeChunkSource {
    private final SpringDataKnowledgeChunkJpaRepository chunkRepository;

    public JpaKnowledgeChunkSource(SpringDataKnowledgeChunkJpaRepository chunkRepository) {
        this.chunkRepository = chunkRepository;
    }

    @Override
    public List<KnowledgeChunk> all() {
        return chunkRepository.findTop100ByOrderByIdAsc().stream()
                .map(JpaKnowledgeChunkSource::toDomain)
                .toList();
    }

    static KnowledgeChunk toDomain(KnowledgeChunkJpaEntity entity) {
        return new KnowledgeChunk(
                entity.getId(),
                entity.getDocumentId(),
                entity.getTitle(),
                entity.getCategory(),
                entity.getSource(),
                entity.getContent());
    }
}
