package com.example.aiticketassistant.infrastructure.repository;

import com.example.aiticketassistant.domain.order.OrderConfirmation;
import com.example.aiticketassistant.domain.order.OrderConfirmationItem;
import com.example.aiticketassistant.domain.order.OrderConfirmationRepository;
import com.example.aiticketassistant.domain.order.OrderConfirmationStatus;
import com.example.aiticketassistant.infrastructure.persistence.jpa.OrderConfirmationItemJpaEntity;
import com.example.aiticketassistant.infrastructure.persistence.jpa.OrderConfirmationJpaEntity;
import com.example.aiticketassistant.infrastructure.persistence.jpa.SpringDataOrderConfirmationItemJpaRepository;
import com.example.aiticketassistant.infrastructure.persistence.jpa.SpringDataOrderConfirmationJpaRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * 待确认订单的 JPA 实现（主表 + 明细表）。
 *
 * <p>明细创建后**不再变化**（确认/取消只改主表状态），所以 {@link #save} 里"没有明细才写"就够了 ——
 * 否则每次状态更新都要把明细删了重插。
 */
@Repository
public class JpaOrderConfirmationRepository implements OrderConfirmationRepository {
    private final SpringDataOrderConfirmationJpaRepository repository;
    private final SpringDataOrderConfirmationItemJpaRepository itemRepository;

    public JpaOrderConfirmationRepository(SpringDataOrderConfirmationJpaRepository repository,
                                          SpringDataOrderConfirmationItemJpaRepository itemRepository) {
        this.repository = repository;
        this.itemRepository = itemRepository;
    }

    @Override
    public OrderConfirmation save(OrderConfirmation confirmation) {
        OrderConfirmationJpaEntity saved = repository.save(toEntity(confirmation));
        if (itemRepository.findByConfirmationIdOrderByIdAsc(saved.getId()).isEmpty()) {
            itemRepository.saveAll(toItemEntities(confirmation));
        }
        return toDomain(saved, confirmation.items());
    }

    @Override
    public Optional<OrderConfirmation> findById(String id) {
        return repository.findById(id).map(entity -> toDomain(entity, loadItems(entity)));
    }

    private List<OrderConfirmationItem> loadItems(OrderConfirmationJpaEntity entity) {
        return itemRepository.findByConfirmationIdOrderByIdAsc(entity.getId()).stream()
                .map(item -> new OrderConfirmationItem(
                        item.getProductId(), item.getProductName(), item.getQuantity(), item.getUnitPrice()))
                .toList();
    }

    private OrderConfirmation toDomain(OrderConfirmationJpaEntity entity, List<OrderConfirmationItem> items) {
        return new OrderConfirmation(
                entity.getId(),
                entity.getTraceId(),
                entity.getSessionId(),
                entity.getCustomerId(),
                items,
                entity.getAddressId(),
                entity.getPaymentMethodId(),
                OrderConfirmationStatus.valueOf(entity.getStatus()),
                entity.getExpiresAt(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }

    private OrderConfirmationJpaEntity toEntity(OrderConfirmation confirmation) {
        OrderConfirmationJpaEntity entity = new OrderConfirmationJpaEntity();
        entity.setId(confirmation.id());
        entity.setTraceId(confirmation.traceId());
        entity.setSessionId(confirmation.sessionId());
        entity.setCustomerId(confirmation.customerId());
        entity.setTotalAmount(confirmation.totalAmount());
        entity.setAddressId(confirmation.addressId());
        entity.setPaymentMethodId(confirmation.paymentMethodId());
        entity.setStatus(confirmation.status().name());
        entity.setExpiresAt(confirmation.expiresAt());
        entity.setCreatedAt(confirmation.createdAt());
        entity.setUpdatedAt(confirmation.updatedAt());
        return entity;
    }

    private List<OrderConfirmationItemJpaEntity> toItemEntities(OrderConfirmation confirmation) {
        return confirmation.items().stream().map(item -> {
            OrderConfirmationItemJpaEntity entity = new OrderConfirmationItemJpaEntity();
            entity.setConfirmationId(confirmation.id());
            entity.setProductId(item.productId());
            entity.setProductName(item.productName());
            entity.setQuantity(item.quantity());
            entity.setUnitPrice(item.unitPrice());
            entity.setCreatedAt(confirmation.createdAt());
            entity.setUpdatedAt(confirmation.updatedAt());
            return entity;
        }).toList();
    }
}
