package com.bcm.entity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
@Entity @Table(name="products") @Getter @Setter
public class Product extends BaseEntity {
@Column(name="category_id",nullable=false) private java.util.UUID categoryId;
@Column(nullable=false,length=255) private String name;
@Enumerated(EnumType.STRING) @Column(nullable=false,length=20) private ProductType type;
@Column(nullable=false,precision=10,scale=2) private java.math.BigDecimal price;
@Column(nullable=false,length=50) private String unit;
@Column(name="stock_quantity",nullable=false) private Integer stockQuantity;
@Version @Column(nullable=false) private Integer version;
@Column(name="image_url",length=2048) private String imageUrl;
}
