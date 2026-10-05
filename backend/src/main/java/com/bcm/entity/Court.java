package com.bcm.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Read-only master data used by Booking; court management owns writes.
 */
@Entity
@Table(name = "courts")
@Immutable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Court {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "court_number", nullable = false)
    private Integer courtNumber;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "type", length = 100)
    private String type;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private CourtStatus status;

    @Column(name = "base_price", nullable = false, precision = 10, scale = 2)
    private BigDecimal basePrice;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;
}
