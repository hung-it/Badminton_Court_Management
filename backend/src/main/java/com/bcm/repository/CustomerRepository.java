package com.bcm.repository;

import com.bcm.entity.Customer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CustomerRepository extends JpaRepository<Customer, UUID> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Customer c where c.user.id=:userId and c.deletedAt is null")
    Optional<Customer> findActiveForUpdateByUserId(@Param("userId") UUID userId);
    @Query("select c.id from Customer c where c.user.id = :userId and c.deletedAt is null")
    Optional<UUID> findActiveCustomerIdByUserId(@Param("userId") UUID userId);

    Optional<Customer> findByPhone(String phone);

    List<Customer> findAllByDeletedAtIsNullOrderByFullNameAsc();
}
