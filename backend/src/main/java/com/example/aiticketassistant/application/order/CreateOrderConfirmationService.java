package com.example.aiticketassistant.application.order;

import com.example.aiticketassistant.application.workflow.WorkflowContext;
import com.example.aiticketassistant.domain.catalog.Product;
import com.example.aiticketassistant.domain.catalog.ProductInventory;
import com.example.aiticketassistant.domain.catalog.ProductRepository;
import com.example.aiticketassistant.domain.customer.CustomerAddress;
import com.example.aiticketassistant.domain.customer.CustomerPaymentMethod;
import com.example.aiticketassistant.domain.customer.CustomerTrustRepository;
import com.example.aiticketassistant.domain.order.OrderConfirmation;
import com.example.aiticketassistant.domain.order.OrderConfirmationItem;
import com.example.aiticketassistant.domain.order.OrderConfirmationRepository;
import com.example.aiticketassistant.domain.order.OrderConfirmationStatus;
import com.example.aiticketassistant.domain.shared.DomainException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CreateOrderConfirmationService {
    private static final Duration CONFIRMATION_TTL = Duration.ofMinutes(10);

    private final ProductRepository productRepository;
    private final CustomerTrustRepository customerTrustRepository;
    private final OrderConfirmationRepository confirmationRepository;

    public CreateOrderConfirmationService(ProductRepository productRepository,
                                          CustomerTrustRepository customerTrustRepository,
                                          OrderConfirmationRepository confirmationRepository) {
        this.productRepository = productRepository;
        this.customerTrustRepository = customerTrustRepository;
        this.confirmationRepository = confirmationRepository;
    }

    /**
     * 创建待确认订单（**一单多明细**）。
     *
     * <p>逐行校验：商品 ID 非空 → 商品存在且在售 → 库存足够；总价 = 各行小计之和，由领域对象自己算。
     * 任一行校验失败就整单失败（不会出现"下了一半"的确认单）。
     */
    @Transactional
    public OrderConfirmationView create(WorkflowContext context, List<ItemRequest> items, String addressId, String paymentMethodId) {
        if (items == null || items.isEmpty()) {
            throw new DomainException("请选择明确的商品 ID，AI 不能随便编商品下单");
        }
        List<OrderConfirmationItem> confirmationItems = new ArrayList<>();
        for (ItemRequest item : items) {
            if (item == null || item.productId() == null || item.productId().isBlank()) {
                throw new DomainException("请选择明确的商品 ID，AI 不能随便编商品下单");
            }
            if (item.quantity() <= 0) {
                throw new DomainException("购买数量必须大于 0");
            }
            Product product = productRepository.findById(item.productId())
                    .filter(Product::active)
                    .orElseThrow(() -> new DomainException("商品不存在或已下架: " + item.productId()));
            ProductInventory inventory = productRepository.findInventory(product.id())
                    .orElseThrow(() -> new DomainException("商品库存不存在: " + product.id()));
            if (inventory.availableStock() < item.quantity()) {
                throw new DomainException("商品库存不足: " + product.name());
            }
            confirmationItems.add(new OrderConfirmationItem(
                    product.id(), product.name(), item.quantity(), product.price()));
        }
        CustomerAddress address = resolveAddress(context.customerId(), addressId);
        CustomerPaymentMethod paymentMethod = resolvePaymentMethod(context.customerId(), paymentMethodId);
        Instant now = Instant.now();
        OrderConfirmation confirmation = new OrderConfirmation(
                "OCF-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(),
                context.trace().traceId(),
                context.sessionId(),
                context.customerId(),
                confirmationItems,
                address.id(),
                paymentMethod.id(),
                OrderConfirmationStatus.PENDING,
                now.plus(CONFIRMATION_TTL),
                now,
                now);
        OrderConfirmation saved = confirmationRepository.save(confirmation);
        return toView(saved, address, paymentMethod);
    }

    /** 单商品入口（工具的老参数形态仍走这里）。 */
    @Transactional
    public OrderConfirmationView create(WorkflowContext context, String productId, int quantity,
                                        String addressId, String paymentMethodId) {
        return create(context, List.of(new ItemRequest(productId, quantity)), addressId, paymentMethodId);
    }

    public OrderConfirmationView toView(OrderConfirmation confirmation, CustomerAddress address,
                                        CustomerPaymentMethod paymentMethod) {
        List<OrderConfirmationItemView> items = confirmation.items().stream()
                .map(item -> new OrderConfirmationItemView(
                        item.productId(), item.productName(), item.quantity(), item.unitPrice(), item.lineAmount()))
                .toList();
        String names = items.stream()
                .map(item -> item.productName() + " × " + item.quantity())
                .collect(Collectors.joining("、"));
        return new OrderConfirmationView(
                confirmation.id(),
                confirmation.sessionId(),
                confirmation.customerId(),
                "确认购买 %s（合计 ¥%s）吗？".formatted(names, confirmation.totalAmount().stripTrailingZeros().toPlainString()),
                items,
                confirmation.totalQuantity(),
                confirmation.totalAmount(),
                Map.of("id", address.id(), "display", address.display()),
                Map.of("id", paymentMethod.id(), "display", paymentMethod.displayLabel(), "methodType", paymentMethod.methodType()),
                confirmation.expiresAt());
    }

    /** 下单请求里的一行：商品 + 数量（来自工具参数 / 意图）。 */
    public record ItemRequest(String productId, int quantity) {}
    private CustomerAddress resolveAddress(String customerId, String addressId) {
        if (addressId != null && !addressId.isBlank()) {
            return customerTrustRepository.findAddressForCustomer(addressId, customerId)
                    .orElseThrow(() -> new DomainException("收货地址不属于当前客户或不可用"));
        }
        return customerTrustRepository.findDefaultAddress(customerId)
                .orElseThrow(() -> new DomainException("当前客户没有可信默认收货地址"));
    }

    private CustomerPaymentMethod resolvePaymentMethod(String customerId, String paymentMethodId) {
        if (paymentMethodId != null && !paymentMethodId.isBlank()) {
            return customerTrustRepository.findPaymentMethodForCustomer(paymentMethodId, customerId)
                    .orElseThrow(() -> new DomainException("支付方式不属于当前客户或不可用"));
        }
        return customerTrustRepository.findDefaultPaymentMethod(customerId)
                .orElseThrow(() -> new DomainException("当前客户没有可信默认支付方式"));
    }
}
