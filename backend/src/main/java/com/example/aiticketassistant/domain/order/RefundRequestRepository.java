package com.example.aiticketassistant.domain.order;

import java.util.List;
import java.util.Optional;

public interface RefundRequestRepository {
    RefundRequest save(RefundRequest request);

    /** 同一订单只允许一条进行中的申请（防重复提交）。 */
    boolean existsByOrderNo(String orderNo);

    List<RefundRequest> findRecentByCustomer(String customerId, int limit);

    /** 按订单号取最新一条申请（退款进度查询用）。 */
    Optional<RefundRequest> findLatestByOrderNo(String orderNo);

    /** 审核台：按状态列出申请（新的在前）。 */
    List<RefundRequest> findByStatus(String status);

    Optional<RefundRequest> findById(String id);
}
