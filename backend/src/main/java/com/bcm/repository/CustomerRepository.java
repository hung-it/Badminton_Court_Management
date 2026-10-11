package com.bcm.repository;

import com.bcm.entity.Customer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Optional;
import java.util.UUID;

public interface CustomerRepository extends JpaRepository<Customer, UUID> {
    @Query("select c.id from Customer c where c.user.id = :userId and c.deletedAt is null")
    Optional<UUID> findActiveCustomerIdByUserId(@Param("userId") UUID userId);
}
