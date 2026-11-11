package com.example.aiticketassistant.interfaces.rest;

import com.example.aiticketassistant.application.review.AfterSalesReviewService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 售后审核接口（2026-11-09，V13）：退款/退货申请单的**审核端**。
 *
 * <p>挂在 `/admin/**` 下 ⇒ 已被 {@code AuthWebFilter} 保护（必须带令牌）。
 * **边界**：这一版没有角色体系 —— 任何持有效令牌的调用方都能审核，审核人只用于审计记录。
 * 真实项目要引入员工身份与权限（见 §11.1）。
 *
 * <p>接口形状刻意保持"两个资源 × 三个动作"，方便前端直接用，不需要理解内部状态机。
 */
@RestController
@RequestMapping("/admin/after-sales")
public class AdminAfterSalesController {
    private final AfterSalesReviewService reviewService;

    public AdminAfterSalesController(AfterSalesReviewService reviewService) {
        this.reviewService = reviewService;
    }

    @GetMapping("/refunds")
    public List<AfterSalesReviewService.ReviewView> refunds(@RequestParam(required = false) String status) {
        return reviewService.refunds(status).stream()
                .map(AfterSalesReviewService.ReviewView::of)
                .toList();
    }

    @GetMapping("/returns")
    public List<AfterSalesReviewService.ReviewView> returns(@RequestParam(required = false) String status) {
        return reviewService.returns(status).stream()
                .map(AfterSalesReviewService.ReviewView::of)
                .toList();
    }

    @PostMapping("/refunds/{id}/approve")
    public AfterSalesReviewService.ReviewView approveRefund(
            @PathVariable String id,
            @RequestBody(required = false) Map<String, Object> body,
            @RequestHeader(value = "X-Reviewer", required = false) String reviewerHeader) {
        return AfterSalesReviewService.ReviewView.of(
                reviewService.approveRefund(id, reviewer(body, reviewerHeader), note(body)));
    }

    @PostMapping("/refunds/{id}/reject")
    public AfterSalesReviewService.ReviewView rejectRefund(
            @PathVariable String id,
            @RequestBody(required = false) Map<String, Object> body,
            @RequestHeader(value = "X-Reviewer", required = false) String reviewerHeader) {
        return AfterSalesReviewService.ReviewView.of(
                reviewService.rejectRefund(id, reviewer(body, reviewerHeader), note(body)));
    }

    @PostMapping("/returns/{id}/approve")
    public AfterSalesReviewService.ReviewView approveReturn(
            @PathVariable String id,
            @RequestBody(required = false) Map<String, Object> body,
            @RequestHeader(value = "X-Reviewer", required = false) String reviewerHeader) {
        return AfterSalesReviewService.ReviewView.of(
                reviewService.approveReturn(id, reviewer(body, reviewerHeader), note(body)));
    }

    @PostMapping("/returns/{id}/reject")
    public AfterSalesReviewService.ReviewView rejectReturn(
            @PathVariable String id,
            @RequestBody(required = false) Map<String, Object> body,
            @RequestHeader(value = "X-Reviewer", required = false) String reviewerHeader) {
        return AfterSalesReviewService.ReviewView.of(
                reviewService.rejectReturn(id, reviewer(body, reviewerHeader), note(body)));
    }

    /** 汇总数字：审核台顶部用（待审退款 / 待审退货）。 */
    @GetMapping("/summary")
    public Map<String, Object> summary() {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("pendingRefunds", reviewService.refunds("REQUESTED").size());
        summary.put("pendingReturns", reviewService.returns("REQUESTED").size());
        summary.put("approvedRefunds", reviewService.refunds("APPROVED").size());
        summary.put("rejectedRefunds", reviewService.refunds("REJECTED").size());
        return summary;
    }

    private String reviewer(Map<String, Object> body, String header) {
        Object fromBody = body == null ? null : body.get("reviewer");
        if (fromBody != null && !String.valueOf(fromBody).isBlank()) {
            return String.valueOf(fromBody);
        }
        return header == null || header.isBlank() ? "staff" : header;
    }

    private String note(Map<String, Object> body) {
        Object value = body == null ? null : body.get("note");
        return value == null ? null : String.valueOf(value);
    }
}
