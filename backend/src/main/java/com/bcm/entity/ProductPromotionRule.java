package com.bcm.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "discount_rules")
@Getter
@Setter
public class ProductPromotionRule {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "promotion_id", nullable = false)
    private ProductPromotion promotion;
    @Column(name = "rule_type", nullable = false, length = 20)
    private String ruleType = "PRODUCT";
    @Column(name = "target_product_id")
    private UUID productId;
    @Column(name = "min_quantity", nullable = false)
    private Integer minQuantity;
    @Column(name = "discount_type", nullable = false, length = 20)
    private String discountType = "PERCENT";
    @Column(name = "discount_value", nullable = false, precision = 10, scale = 2)
    private BigDecimal discountPercent;
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
    @Column(name="max_discount_amount", precision=10, scale=2)
    private BigDecimal maxDiscountAmount;
    @Column(name="max_total_usage")
    private Integer maxTotalUsage;
    @Column(name="min_invoice_amount", precision=10, scale=2)
    private BigDecimal minInvoiceAmount;
    @Column(name="max_usage_per_customer")
    private Integer maxUsagePerCustomer;
    @Column(name="voucher_code", length=50)
    private String voucherCode;
    @PrePersist void created() { if (createdAt == null) createdAt = LocalDateTime.now(); }
}
