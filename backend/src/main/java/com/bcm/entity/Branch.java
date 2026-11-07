package com.bcm.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "branches")
@Getter
@Setter
public class Branch extends BaseEntity {
    @Column(nullable = false, unique = true, length = 50)
    private String code;
    @Column(nullable = false)
    private String name;
    @Column(nullable = false, length = 150)
    private String district;
    @Column(nullable = false, length = 500)
    private String address;
    @Column(nullable = false)
    private boolean active = true;
    @Column(name = "fixture_source", length = 150)
    private String fixtureSource;
}
