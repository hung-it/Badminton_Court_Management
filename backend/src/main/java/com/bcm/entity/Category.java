package com.bcm.entity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
@Entity @Table(name="categories") @Getter @Setter
public class Category extends BaseEntity {
@Column(name="category_name",nullable=false,length=255) private String categoryName;
}
