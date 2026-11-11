package com.example.aiticketassistant.infrastructure.repository;

import com.example.aiticketassistant.domain.order.ReturnRequest;
import com.example.aiticketassistant.domain.order.ReturnRequestRepository;
import com.example.aiticketassistant.infrastructure.persistence.jpa.ReturnRequestJpaEntity;
import com.example.aiticketassistant.infrastructure.persistence.jpa.SpringDataReturnRequestJpaRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JpaReturnRequestRepository implements ReturnRequestRepository {
    private final SpringDataReturnRequestJpaRepository repository;

    public JpaReturnRequestRepository(SpringDataReturnRequestJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional
    public ReturnRequest save(ReturnRequest request) {
        Instant now = request.createdAt() == null ? Instant.now() : request.createdAt();
        ReturnRequestJpaEntity entity = new ReturnRequestJpaEntity();
        entity.setId(request.id());
        entity.setOrderNo(request.orderNo());
        entity.setCustomerId(request.customerId());
        entity.setReason(request.reason());
        entity.setStatus(request.status());
        entity.setTicketId(request.ticketId());
        entity.setReviewNote(request.reviewNote());
        entity.setReviewedBy(request.reviewedBy());
        entity.setReviewedAt(request.reviewedAt());
        entity.setCreatedAt(request.createdAt() == null ? now : request.createdAt());
        entity.setUpdatedAt(now);
        return toDomain(repository.save(entity));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ReturnRequest> findLatestByOrderNo(String orderNo) {
        return repository.findFirstByOrderNoOrderByCreatedAtDesc(orderNo).map(this::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean existsByOrderNo(String orderNo) {
        return repository.existsByOrderNo(orderNo);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ReturnRequest> findRecentByCustomer(String customerId, int limit) {
        return repository.findTop10ByCustomerIdOrderByIdDesc(customerId).stream()
                .limit(Math.max(1, limit))
                .map(this::toDomain)
                .toList();
    }

    private ReturnRequest toDomain(ReturnRequestJpaEntity entity) {
        return new ReturnRequest(entity.getId(), entity.getOrderNo(), entity.getCustomerId(),
                entity.getReason(), entity.getStatus(), entity.getTicketId(), entity.getReviewNote(),
                entity.getReviewedBy(), entity.getReviewedAt(), entity.getCreatedAt());
    }

    @Override
    @Transactional(readOnly = true)
    public java.util.Optional<ReturnRequest> findById(String id) {
        return repository.findById(id).map(this::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public java.util.List<ReturnRequest> findByStatus(String status) {
        return repository.findByStatusOrderByCreatedAtDesc(status).stream().map(this::toDomain).toList();
    }
}
