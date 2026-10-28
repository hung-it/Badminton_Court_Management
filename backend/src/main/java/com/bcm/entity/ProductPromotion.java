package com.bcm.entity;

import jakarta.persistence.*;
import java.time.LocalDate;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "promotions")
@Getter
@Setter
public class ProductPromotion extends BaseEntity {
    @Column(nullable = false, unique = true, length = 50)
    private String code;
    @Column(nullable = false, length = 255)
    private String name;
    @Column(name = "valid_from", nullable = false)
    private LocalDate validFrom;
    @Column(name = "valid_to", nullable = false)
    private LocalDate validTo;
    @Column(name = "is_active", nullable = false)
    private boolean active;
}
