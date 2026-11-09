package com.bcm.repository;

import com.bcm.entity.PaymentTransaction;
import com.bcm.entity.PaymentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.List;
import java.util.UUID;

public interface PaymentTransactionRepository extends JpaRepository<PaymentTransaction, UUID> {
    @Query("select p from PaymentTransaction p where p.invoice.id=:invoiceId or (p.booking.id=:bookingId and p.invoice is null and exists (select i.id from Invoice i where i.id=:invoiceId and i.bookingId=:bookingId and i.parentInvoiceId is null)) order by case when p.booking is not null then 0 else 1 end, p.createdAt, p.id")
    List<PaymentTransaction> findInvoiceReceipts(@Param("invoiceId") UUID invoiceId, @Param("bookingId") UUID bookingId);

    @Query("""
            select p from PaymentTransaction p
            where p.booking.id = :bookingId and p.invoice is null
            order by p.createdAt desc, p.id desc
            """)
    List<PaymentTransaction> findBookingHistory(@Param("bookingId") UUID bookingId);

    boolean existsByInvoiceId(UUID invoiceId);

    boolean existsByBookingIdAndStatus(UUID bookingId, PaymentStatus status);
    boolean existsByBookingId(UUID bookingId);
    Optional<PaymentTransaction> findFirstByBookingIdAndStatusOrderByCreatedAtAscIdAsc(UUID bookingId, PaymentStatus status);

    @Query("select p.booking.id from PaymentTransaction p where p.id = :id")
    Optional<UUID> findBookingIdForInitiation(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PaymentTransaction p where p.id = :id")
    Optional<PaymentTransaction> findByIdForCallback(@Param("id") UUID id);
}
