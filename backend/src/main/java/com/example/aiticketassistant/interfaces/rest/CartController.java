package com.example.aiticketassistant.interfaces.rest;

import com.example.aiticketassistant.application.cart.CartService;
import com.example.aiticketassistant.infrastructure.web.AuthContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

/**
 * 购物车的页面侧接口（2026-11-09）。
 *
 * <p>为什么需要：对话侧只支持"加购 / 查看 / 结算"，移除与改数量会被引导到"购物车页面" ——
 * 那么页面必须真的有这个能力，否则就是把人推到一个不存在的入口。
 *
 * <p>客户身份同样以令牌为准（关闭鉴权时退回参数，供测试/演示）。
 */
@RestController
@RequestMapping("/assistant/cart")
public class CartController {
    private final CartService cartService;

    public CartController(CartService cartService) {
        this.cartService = cartService;
    }

    @GetMapping
    public Map<String, Object> view(@RequestParam(defaultValue = "CUST-1001") String customerId,
                                    ServerWebExchange exchange) {
        return toView(cartService.snapshot(AuthContext.resolveCustomer(exchange, customerId)));
    }

    /** 改数量；`quantity=0` 等价于移除。 */
    @PatchMapping("/items/{productId}")
    public Map<String, Object> updateQuantity(@PathVariable String productId,
                                              @RequestBody Map<String, Object> body,
                                              @RequestParam(defaultValue = "CUST-1001") String customerId,
                                              ServerWebExchange exchange) {
        Object raw = body == null ? null : body.get("quantity");
        int quantity = raw instanceof Number number ? number.intValue() : Integer.parseInt(String.valueOf(raw));
        return toView(cartService.updateQuantity(AuthContext.resolveCustomer(exchange, customerId), productId, quantity));
    }

    @DeleteMapping("/items/{productId}")
    public Map<String, Object> remove(@PathVariable String productId,
                                      @RequestParam(defaultValue = "CUST-1001") String customerId,
                                      ServerWebExchange exchange) {
        return toView(cartService.remove(AuthContext.resolveCustomer(exchange, customerId), productId));
    }

    @DeleteMapping
    public Map<String, Object> clear(@RequestParam(defaultValue = "CUST-1001") String customerId,
                                     ServerWebExchange exchange) {
        String owner = AuthContext.resolveCustomer(exchange, customerId);
        cartService.clear(owner);
        return toView(cartService.snapshot(owner));
    }

    private Map<String, Object> toView(CartService.CartSnapshot snapshot) {
        List<Map<String, Object>> lines = new ArrayList<>();
        for (CartService.Line line : snapshot.lines()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("productId", line.productId());
            row.put("productName", line.productName());
            row.put("unitPrice", line.unitPrice());
            row.put("quantity", line.quantity());
            row.put("lineAmount", line.lineAmount());
            lines.add(row);
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("lines", lines);
        response.put("totalQuantity", snapshot.totalQuantity());
        response.put("totalAmount", snapshot.totalAmount());
        response.put("empty", snapshot.empty());
        return response;
    }
}
