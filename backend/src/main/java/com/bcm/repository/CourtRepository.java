package com.bcm.repository;

import com.bcm.entity.Court;
import com.bcm.entity.CourtStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CourtRepository extends JpaRepository<Court, UUID> {

    List<Court> findByDeletedAtIsNullOrderByCourtNumberAsc();

    Optional<Court> findByIdAndDeletedAtIsNull(UUID id);

    boolean existsByCourtNumberAndIdNot(Integer courtNumber, UUID id);

    // Lock existing court rows in a stable order while a booking is created.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Court c where c.id in :ids order by c.id")
    List<Court> findAllForBookingWithLock(@Param("ids") List<UUID> ids);

    @Query("""
            select c from Court c
            where c.status = :status and c.deletedAt is null
              and (:courtId is null or c.id = :courtId)
            order by c.courtNumber
            """)
    List<Court> findForAvailability(@Param("status") CourtStatus status,
                                    @Param("courtId") UUID courtId);
}
