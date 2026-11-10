package com.bcm.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "bookings")
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
public class Booking {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer customer;

    @Column(name = "contact_full_name", length = 255)
    private String contactFullName;

    @Column(name = "contact_phone", length = 20)
    private String contactPhone;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private BookingStatus status = BookingStatus.PENDING;

    @Column(name = "court_fee", nullable = false, precision = 10, scale = 2)
    private BigDecimal courtFee;

    @Column(name = "discount_amount", nullable = false, precision = 10, scale = 2)
    @org.hibernate.annotations.ColumnDefault("0")
    private BigDecimal discountAmount = new BigDecimal("0.00");

    @Column(name = "promotion_code", length = 50)
    private String promotionCode;

    @Column(name = "requested_voucher", length = 50)
    private String requestedVoucher;

    public BigDecimal getFinalAmount() {
        return courtFee.subtract(discountAmount);
    }

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @Column(name = "checkout_release_at")
    private LocalDateTime checkoutReleaseAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by")
    private Staff createdBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "updated_by")
    private Staff updatedBy;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
