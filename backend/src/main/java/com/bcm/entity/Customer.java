package com.bcm.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/** Customer identity and profile; user and customer IDs are distinct. */
@Entity
@Table(name = "customers")
@Getter
@Setter
public class Customer extends BaseEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;
    @Column(name = "full_name", nullable = false)
    private String fullName;
    @Column(name = "phone", nullable = false, length = 20)
    private String phone;
    @Column(name = "address", columnDefinition = "text")
    private String address;
}
