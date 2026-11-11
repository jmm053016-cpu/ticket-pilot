package com.example.aiticketassistant.domain.order;

import java.util.List;
import java.util.Optional;

public interface ReturnRequestRepository {
    ReturnRequest save(ReturnRequest request);

    Optional<ReturnRequest> findLatestByOrderNo(String orderNo);

    boolean existsByOrderNo(String orderNo);

    List<ReturnRequest> findRecentByCustomer(String customerId, int limit);

    /** 审核台：按状态列出申请（新的在前）。 */
    List<ReturnRequest> findByStatus(String status);

    Optional<ReturnRequest> findById(String id);
}
