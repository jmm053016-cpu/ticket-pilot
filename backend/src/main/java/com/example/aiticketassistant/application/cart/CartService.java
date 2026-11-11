package com.example.aiticketassistant.application.cart;

import com.example.aiticketassistant.domain.cart.CartItem;
import com.example.aiticketassistant.domain.cart.CartRepository;
import com.example.aiticketassistant.domain.catalog.Product;
import com.example.aiticketassistant.domain.catalog.ProductInventory;
import com.example.aiticketassistant.domain.catalog.ProductRepository;
import com.example.aiticketassistant.domain.shared.DomainException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 购物车（2026-11-09 新增）。
 *
 * <p>为什么要有它：路由里一直有「购物车」这一路（`isCartAction`），但工具清单里没有对应工具，
 * 实测出现"把加入购物车当成下单，还编了个不存在的商品编号"。现在加购是**真动作**：
 * 落 `cart_items`、校验商品与库存、重复加购合并数量；下单仍走既有的两阶段确认。
 *
 * <p>口径：加购**不锁库存**（和主流电商一致），只在真正创建待确认单时才 reserve。
 */
@Service
public class CartService {
    private static final int MAX_QUANTITY_PER_ITEM = 20;

    private final CartRepository cartRepository;
    private final ProductRepository productRepository;

    public CartService(CartRepository cartRepository, ProductRepository productRepository) {
        this.cartRepository = cartRepository;
        this.productRepository = productRepository;
    }

    /** 加购（可一次加多件）。返回加完之后的整车快照，方便直接把结果说给用户听。 */
    @Transactional
    public CartSnapshot add(String customerId, List<Item> items) {
        if (items == null || items.isEmpty()) {
            throw new DomainException("要加购哪件商品？可以告诉我商品名称或编号");
        }
        for (Item item : items) {
            Product product = requireSellable(item.productId());
            if (item.quantity() <= 0) {
                throw new DomainException("加购数量必须大于 0");
            }
            CartItem existing = cartRepository.findByCustomer(customerId).stream()
                    .filter(cartItem -> cartItem.productId().equals(product.id()))
                    .findFirst()
                    .orElse(null);
            int merged = (existing == null ? 0 : existing.quantity()) + item.quantity();
            if (merged > MAX_QUANTITY_PER_ITEM) {
                throw new DomainException("同一件商品最多加购 %d 件：%s".formatted(MAX_QUANTITY_PER_ITEM, product.name()));
            }
            cartRepository.upsert(new CartItem(customerId, product.id(), merged));
        }
        return snapshot(customerId);
    }

    @Transactional(readOnly = true)
    public CartSnapshot snapshot(String customerId) {
        List<Line> lines = new ArrayList<>();
        for (CartItem item : cartRepository.findByCustomer(customerId)) {
            // 商品可能已下架：加购历史保留，但快照里跳过（不制造"购物车里有件幽灵商品"）
            Product product = productRepository.findById(item.productId()).filter(Product::active).orElse(null);
            if (product == null) {
                continue;
            }
            lines.add(new Line(product.id(), product.name(), product.price(), item.quantity(),
                    product.price().multiply(BigDecimal.valueOf(item.quantity()))));
        }
        int quantity = lines.stream().mapToInt(Line::quantity).sum();
        BigDecimal total = lines.stream().map(Line::lineAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new CartSnapshot(lines, quantity, total);
    }

    /** 下单成功后清车。 */
    @Transactional
    public void clear(String customerId) {
        cartRepository.clear(customerId);
    }

    /**
     * 移除一件商品（前端购物车面板用）。
     *
     * <p>对话里不支持移除（`ViewCartTool` 会明说"请在页面操作"），所以这个入口是必需的 ——
     * 否则用户被引导到"页面"，页面却没有这个能力。
     */
    @Transactional
    public CartSnapshot remove(String customerId, String productId) {
        if (productId == null || productId.isBlank()) {
            throw new DomainException("要移除哪件商品？请提供商品编号");
        }
        cartRepository.remove(customerId, productId.trim());
        return snapshot(customerId);
    }

    /** 改数量（0 = 移除）。 */
    @Transactional
    public CartSnapshot updateQuantity(String customerId, String productId, int quantity) {
        if (quantity <= 0) {
            return remove(customerId, productId);
        }
        if (quantity > MAX_QUANTITY_PER_ITEM) {
            throw new DomainException("同一件商品最多 %d 件".formatted(MAX_QUANTITY_PER_ITEM));
        }
        Product product = requireSellable(productId);
        cartRepository.upsert(new CartItem(customerId, product.id(), quantity));
        return snapshot(customerId);
    }

    private Product requireSellable(String productId) {
        if (productId == null || productId.isBlank()) {
            throw new DomainException("请选择明确的商品 ID，AI 不能随便编商品");
        }
        Product product = productRepository.findById(productId)
                .filter(Product::active)
                .orElseThrow(() -> new DomainException("商品不存在或已下架: " + productId));
        ProductInventory inventory = productRepository.findInventory(product.id())
                .orElseThrow(() -> new DomainException("商品库存不存在: " + product.id()));
        if (inventory.availableStock() <= 0) {
            throw new DomainException("商品已售罄: " + product.name());
        }
        return product;
    }

    /** 加购请求里的一行。 */
    public record Item(String productId, int quantity) {}

    /** 购物车快照里的一行（带名字与价格，直接给人看）。 */
    public record Line(String productId, String productName, BigDecimal unitPrice, int quantity, BigDecimal lineAmount) {}

    public record CartSnapshot(List<Line> lines, int totalQuantity, BigDecimal totalAmount) {
        public boolean empty() {
            return lines.isEmpty();
        }
    }
}
