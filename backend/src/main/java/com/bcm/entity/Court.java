package com.bcm.entity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
@Entity @Table(name="courts") @Getter @Setter
public class Court extends BaseEntity {
@Column(name="court_number",nullable=false,unique=true) private Integer courtNumber;
@Column(nullable=false,length=255) private String name;
@Enumerated(EnumType.STRING) @Column(length=100,nullable=false) private CourtType type;
@Enumerated(EnumType.STRING) @Column(length=20,nullable=false) private CourtStatus status;
@Column(name="base_price",nullable=false,precision=10,scale=2) private java.math.BigDecimal basePrice;
}
