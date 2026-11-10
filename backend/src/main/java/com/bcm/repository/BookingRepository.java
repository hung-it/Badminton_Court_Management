package com.bcm.repository;

import com.bcm.entity.Booking;
import com.bcm.entity.BookingStatus;
import com.bcm.dto.response.BookingSummaryResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BookingRepository extends JpaRepository<Booking, UUID> {
    @Query(value="""
        select b.id from bookings b
        join booking_details d on d.booking_id=b.id join time_slots t on t.id=d.time_slot_id
        where b.status='PAID'
          and not exists(select 1 from invoices i where i.booking_id=b.id and i.status in ('DRAFT','PAID'))
          and (b.final_amount=0 or (
            (select count(*) from payment_transactions p where p.booking_id=b.id and p.status='SUCCESS')=1
            and exists(select 1 from payment_transactions p where p.booking_id=b.id and p.status='SUCCESS'
              and p.amount=b.final_amount and p.transaction_id is not null and p.transaction_date is not null
              and ((p.source='GATEWAY' and p.payment_method='VNPAY') or (p.source='MANUAL' and p.confirmed_by_user is not null)))))
        group by b.id having max(d.booking_date + t.end_time)<=:cutoff
        order by max(d.booking_date + t.end_time),b.id limit 100
        """,nativeQuery=true)
    List<UUID> findAutoCheckoutCandidates(@Param("cutoff") LocalDateTime cutoff);
    @Query(value = """
            select new com.bcm.dto.response.BookingSummaryResponse(
                b.id, b.customer.id, b.status, b.courtFee, b.expiresAt, b.createdAt, b.updatedAt)
            from Booking b where b.customer.id = :customerId
              and (:status is null or b.status = :status)
            order by b.createdAt desc, b.id desc
            """, countQuery = """
            select count(b) from Booking b where b.customer.id = :customerId
              and (:status is null or b.status = :status)
            """)
    Page<BookingSummaryResponse> findHistory(@Param("customerId") UUID customerId,
                                           @Param("status") BookingStatus status, Pageable pageable);

    @Query("""
            select b.id from Booking b
            where b.status = :status and ((b.expiresAt is not null and b.expiresAt <= :now)
              or (b.checkoutReleaseAt is not null and b.checkoutReleaseAt <= :now))
            order by b.expiresAt, b.id
            """)
    List<UUID> findExpirationCandidates(@Param("status") BookingStatus status, @Param("now") LocalDateTime now);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from Booking b where b.id = :id")
    Optional<Booking> findByIdForExpiration(@Param("id") UUID id);

    // Expiration deliberately preserves existing audit timestamps and all other header fields.
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Booking b set b.status = :status where b.id = :id")
    int updateExpirationStatus(@Param("id") UUID id, @Param("status") BookingStatus status);

    @Query(value = "select id from bookings where cast(id as text) like lower(concat(:prefix, '%')) limit 2", nativeQuery = true)
    List<UUID> findIdsByPrefix(@Param("prefix") String prefix);

    @Query("select b from Booking b where b.status in :statuses order by b.createdAt desc")
    List<Booking> findByStatusInOrderByCreatedAtDesc(@Param("statuses") List<BookingStatus> statuses);
}
