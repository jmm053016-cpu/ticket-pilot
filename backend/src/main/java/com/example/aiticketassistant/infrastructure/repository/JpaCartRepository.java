package com.example.aiticketassistant.infrastructure.repository;

import com.example.aiticketassistant.domain.cart.CartItem;
import com.example.aiticketassistant.domain.cart.CartRepository;
import com.example.aiticketassistant.infrastructure.persistence.jpa.CartItemJpaEntity;
import com.example.aiticketassistant.infrastructure.persistence.jpa.SpringDataCartItemJpaRepository;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** 购物车持久化：同商品重复加购 = 数量累加（唯一约束在 DB 上也兜一层）。 */
@Repository
public class JpaCartRepository implements CartRepository {
    private final SpringDataCartItemJpaRepository repository;

    public JpaCartRepository(SpringDataCartItemJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional(readOnly = true)
    public List<CartItem> findByCustomer(String customerId) {
        return repository.findByCustomerIdOrderByIdAsc(customerId).stream()
                .map(entity -> new CartItem(entity.getCustomerId(), entity.getProductId(), entity.getQuantity()))
                .toList();
    }

    @Override
    @Transactional
    public CartItem upsert(CartItem item) {
        Instant now = Instant.now();
        CartItemJpaEntity entity = repository
                .findByCustomerIdAndProductId(item.customerId(), item.productId())
                .orElseGet(() -> {
                    CartItemJpaEntity fresh = new CartItemJpaEntity();
                    fresh.setCustomerId(item.customerId());
                    fresh.setProductId(item.productId());
                    fresh.setCreatedAt(now);
                    return fresh;
                });
        // 已存在则覆盖成"合并后的数量"（调用方负责算好）
        entity.setQuantity(item.quantity());
        entity.setUpdatedAt(now);
        CartItemJpaEntity saved = repository.save(entity);
        return new CartItem(saved.getCustomerId(), saved.getProductId(), saved.getQuantity());
    }

    @Override
    @Transactional
    public void clear(String customerId) {
        repository.deleteAll(repository.findByCustomerIdOrderByIdAsc(customerId));
    }

    @Override
    @Transactional
    public void remove(String customerId, String productId) {
        repository.findByCustomerIdAndProductId(customerId, productId).ifPresent(repository::delete);
    }
}
