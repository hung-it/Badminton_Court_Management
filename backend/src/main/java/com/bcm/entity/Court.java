package com.bcm.entity;

import jakarta.persistence.*;

import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "courts")
@Getter
@Setter
public class Court extends BaseEntity {
    @Column(name = "branch_id")
    private java.util.UUID branchId;

    @Column(name = "court_number")
    private Integer branchCourtNumber;

    @Column(name = "court_code", nullable = false, unique = true)
    private Integer courtCode;

    // Existing unassigned admin records retain their legacy display until assigned to a branch.
    public Integer getCourtNumber() {
        return branchCourtNumber != null ? branchCourtNumber : courtCode;
    }

    @Column(nullable = false, length = 255)
    private String name;

    @Column(name = "district", length = 150)
    private String district;

    @Column(length = 500)
    private String address;

    @Enumerated(EnumType.STRING)
    @Column(length = 100, nullable = false)
    private CourtType type;

    @Enumerated(EnumType.STRING)
    @Column(length = 20, nullable = false)
    private CourtStatus status;

    @Column(name = "base_price", nullable = false, precision = 10, scale = 2)
    private java.math.BigDecimal basePrice;
}
