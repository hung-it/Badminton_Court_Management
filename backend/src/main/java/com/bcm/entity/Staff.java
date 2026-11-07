package com.bcm.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

import java.util.UUID;

/**
 * Read-only identity reference to staffs; writes belong to its owning module.
 */
@Entity
@Table(name = "staffs")
@Immutable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Staff {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;
}
