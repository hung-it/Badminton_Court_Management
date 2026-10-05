package com.bcm.repository;

import com.bcm.entity.Booking;
import com.bcm.entity.BookingStatus;
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
