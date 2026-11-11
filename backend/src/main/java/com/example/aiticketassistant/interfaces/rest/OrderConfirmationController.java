package com.example.aiticketassistant.interfaces.rest;

import com.example.aiticketassistant.application.order.ConfirmOrderRequest;
import com.example.aiticketassistant.application.order.ConfirmOrderService;
import com.example.aiticketassistant.application.order.OrderCreationResult;
import com.example.aiticketassistant.infrastructure.web.AuthContext;
import java.util.Map;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

/**
 * 待确认单的确认/取消。
 *
 * <p>2026-11-09 起**客户身份以令牌为准**：请求体里的 customerId 只在关闭鉴权时兜底 ——
 * 否则"带上别人的 customerId 就能确认别人的待确认单"。
 */
@RestController
@RequestMapping("/assistant/order-confirmations")
public class OrderConfirmationController {
    private final ConfirmOrderService confirmOrderService;

    public OrderConfirmationController(ConfirmOrderService confirmOrderService) {
        this.confirmOrderService = confirmOrderService;
    }

    @PostMapping("/{id}/confirm")
    public OrderCreationResult confirm(@PathVariable String id, @RequestBody ConfirmOrderRequest request,
                                       ServerWebExchange exchange) {
        return confirmOrderService.confirm(id, authenticated(request, exchange));
    }

    @PostMapping("/{id}/cancel")
    public Map<String, Object> cancel(@PathVariable String id, @RequestBody ConfirmOrderRequest request,
                                      ServerWebExchange exchange) {
        return confirmOrderService.cancel(id, authenticated(request, exchange));
    }

    private ConfirmOrderRequest authenticated(ConfirmOrderRequest request, ServerWebExchange exchange) {
        String customerId = AuthContext.resolveCustomer(exchange, request.customerId());
        return new ConfirmOrderRequest(customerId, request.sessionId());
    }
}
