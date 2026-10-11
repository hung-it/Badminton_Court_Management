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
            where b.status = :status and b.expiresAt is not null and b.expiresAt <= :now
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
}
