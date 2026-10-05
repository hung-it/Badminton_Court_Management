package com.bcm.repository;

import com.bcm.entity.Customer;
import org.springframework.data.repository.Repository;

import java.util.Optional;
import java.util.UUID;

public interface CustomerRepository extends Repository<Customer, UUID> {
    Optional<Customer> findById(UUID id);
}
