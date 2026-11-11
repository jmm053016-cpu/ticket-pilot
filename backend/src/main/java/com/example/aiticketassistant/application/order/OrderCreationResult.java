package com.example.aiticketassistant.application.order;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public record OrderCreationResult(
        String orderNo,
        String orderStatus,
        String paymentStatus,
        BigDecimal amount,
        List<Map<String, Object>> items,
        String message) {}
