package com.example.aiticketassistant.infrastructure.repository;

import com.example.aiticketassistant.domain.order.RefundRequest;
import com.example.aiticketassistant.domain.order.RefundRequestRepository;
import com.example.aiticketassistant.infrastructure.persistence.jpa.RefundRequestJpaEntity;
import com.example.aiticketassistant.infrastructure.persistence.jpa.SpringDataRefundRequestJpaRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JpaRefundRequestRepository implements RefundRequestRepository {
    private final SpringDataRefundRequestJpaRepository repository;

    public JpaRefundRequestRepository(SpringDataRefundRequestJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional
    public RefundRequest save(RefundRequest request) {
        Instant now = request.createdAt() == null ? Instant.now() : request.createdAt();
        RefundRequestJpaEntity entity = new RefundRequestJpaEntity();
        entity.setId(request.id());
        entity.setOrderNo(request.orderNo());
        entity.setCustomerId(request.customerId());
        entity.setAmount(request.amount());
        entity.setReason(request.reason());
        entity.setStatus(request.status());
        entity.setTicketId(request.ticketId());
        entity.setReviewNote(request.reviewNote());
        entity.setReviewedBy(request.reviewedBy());
        entity.setReviewedAt(request.reviewedAt());
        entity.setCreatedAt(request.createdAt() == null ? now : request.createdAt());
        entity.setUpdatedAt(now);
        RefundRequestJpaEntity saved = repository.save(entity);
        return toDomain(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean existsByOrderNo(String orderNo) {
        return repository.existsByOrderNo(orderNo);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<RefundRequest> findLatestByOrderNo(String orderNo) {
        return repository.findFirstByOrderNoOrderByCreatedAtDesc(orderNo).map(this::toDomain);
    }

    private RefundRequest toDomain(RefundRequestJpaEntity entity) {
        return new RefundRequest(entity.getId(), entity.getOrderNo(), entity.getCustomerId(),
                entity.getAmount(), entity.getReason(), entity.getStatus(),
                entity.getTicketId(), entity.getReviewNote(), entity.getReviewedBy(), entity.getReviewedAt(),
                entity.getCreatedAt());
    }

    @Override
    @Transactional(readOnly = true)
    public java.util.Optional<RefundRequest> findById(String id) {
        return repository.findById(id).map(this::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public java.util.List<RefundRequest> findByStatus(String status) {
        return repository.findByStatusOrderByCreatedAtDesc(status).stream().map(this::toDomain).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<RefundRequest> findRecentByCustomer(String customerId, int limit) {
        return repository.findTop10ByCustomerIdOrderByIdDesc(customerId).stream()
                .limit(Math.max(1, limit))
                .map(entity -> new RefundRequest(entity.getId(), entity.getOrderNo(), entity.getCustomerId(),
                        entity.getAmount(), entity.getReason(), entity.getStatus(), entity.getCreatedAt()))
                .toList();
    }
}
