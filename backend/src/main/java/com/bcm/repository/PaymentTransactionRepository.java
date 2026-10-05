package com.bcm.repository;

import com.bcm.entity.PaymentTransaction;
import com.bcm.entity.PaymentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface PaymentTransactionRepository extends JpaRepository<PaymentTransaction, UUID> {
    boolean existsByBookingIdAndStatus(UUID bookingId, PaymentStatus status);
    Optional<PaymentTransaction> findFirstByBookingIdAndStatusOrderByCreatedAtAscIdAsc(UUID bookingId, PaymentStatus status);

    @Query("select p.booking.id from PaymentTransaction p where p.id = :id")
    Optional<UUID> findBookingIdForInitiation(@Param("id") UUID id);
}
