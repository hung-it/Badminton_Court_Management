package com.bcm.repository;

import com.bcm.entity.TimeSlot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TimeSlotRepository extends JpaRepository<TimeSlot, UUID> {

    // Booking holds Court locks first; shared slot locks allow unrelated courts to book concurrently.
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_READ)
    @Query("select t from TimeSlot t where t.id in :ids and t.deletedAt is null order by t.id")
    List<TimeSlot> findForBookingWithLock(@Param("ids") Iterable<UUID> ids);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from TimeSlot t where t.id=:id and t.deletedAt is null")
    Optional<TimeSlot> findForUpdate(@Param("id") UUID id);

    List<TimeSlot> findByDeletedAtIsNullOrderByStartTimeAsc();

    Optional<TimeSlot> findByIdAndDeletedAtIsNull(UUID id);

    List<TimeSlot> findByIdInAndDeletedAtIsNull(Iterable<UUID> ids);

    List<TimeSlot> findByDeletedAtIsNullOrderByStartTimeAscEndTimeAscIdAsc();

    @Query(
            "select count(t)>0 from TimeSlot t where t.deletedAt is null and t.id <> :id and"
                + " t.startTime < :end and t.endTime > :start")
    boolean overlaps(
            @Param("id") UUID id,
            @Param("start") java.time.LocalTime start,
            @Param("end") java.time.LocalTime end);
}
