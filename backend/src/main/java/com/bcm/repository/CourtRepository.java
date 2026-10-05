package com.bcm.repository;

import com.bcm.entity.Court;
import com.bcm.entity.CourtStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface CourtRepository extends Repository<Court, UUID> {

    // PostgreSQL locks the rows in this immutable primary-key order, not the IN-list order.
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
