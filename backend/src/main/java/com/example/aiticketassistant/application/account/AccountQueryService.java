package com.example.aiticketassistant.application.account;

import com.example.aiticketassistant.domain.customer.AccountDirectory;
import com.example.aiticketassistant.domain.customer.CouponRepository;
import com.example.aiticketassistant.domain.customer.CustomerAddress;
import com.example.aiticketassistant.domain.customer.CustomerCoupon;
import com.example.aiticketassistant.domain.customer.CustomerPaymentMethod;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 账户查询（地址 / 支付方式 / 优惠券），2026-11-09 新增。
 *
 * <p>为什么要有它：路由里一直有「账户查询」这一路（`isAccountQuery`），但没有工具，
 * 用户问"我有几张优惠券"只能靠模型说"我查不到"。现在是真的从库里查。
 *
 * <p>**只读、不返回敏感原文**：支付方式只给打码后的 `displayLabel`，地址给展示串。
 */
@Service
public class AccountQueryService {
    private final AccountDirectory accountDirectory;
    private final CouponRepository couponRepository;

    public AccountQueryService(AccountDirectory accountDirectory, CouponRepository couponRepository) {
        this.accountDirectory = accountDirectory;
        this.couponRepository = couponRepository;
    }

    @Transactional(readOnly = true)
    public AccountSnapshot query(String customerId) {
        Instant now = Instant.now();
        List<AddressView> addresses = accountDirectory.findAddresses(customerId).stream()
                .map(this::toAddressView)
                .toList();
        List<PaymentMethodView> paymentMethods = accountDirectory.findPaymentMethods(customerId).stream()
                .map(this::toPaymentView)
                .toList();
        List<CustomerCoupon> allCoupons = couponRepository.findByCustomer(customerId);
        List<CouponView> coupons = allCoupons.stream()
                .filter(coupon -> coupon.usable(now))
                .map(coupon -> new CouponView(coupon.code(), coupon.title(), coupon.amountOff(),
                        coupon.minSpend(), coupon.expiresAt().toString()))
                .toList();
        return new AccountSnapshot(addresses, paymentMethods, coupons, allCoupons.size() - coupons.size());
    }

    private AddressView toAddressView(CustomerAddress address) {
        return new AddressView(address.id(), address.recipientName(), maskPhone(address.phone()),
                address.addressLine(), address.defaultAddress());
    }

    private PaymentMethodView toPaymentView(CustomerPaymentMethod method) {
        return new PaymentMethodView(method.id(), method.methodType(), method.displayLabel(), method.defaultMethod());
    }

    /** 手机号只留后 4 位（本来就打过码的数据原样透传，未打码的当场打掉）。 */
    private String maskPhone(String phone) {
        if (phone == null || phone.isBlank()) {
            return "";
        }
        if (phone.contains("*")) {
            return phone;
        }
        return phone.length() <= 4 ? phone : "****" + phone.substring(phone.length() - 4);
    }

    public record AddressView(String id, String recipientName, String phone, String addressLine, boolean isDefault) {}

    public record PaymentMethodView(String id, String methodType, String displayLabel, boolean isDefault) {}

    public record CouponView(String code, String title, BigDecimal amountOff, BigDecimal minSpend, String expiresAt) {}

    public record AccountSnapshot(List<AddressView> addresses, List<PaymentMethodView> paymentMethods,
                                  List<CouponView> coupons, int expiredCouponCount) {}
}
