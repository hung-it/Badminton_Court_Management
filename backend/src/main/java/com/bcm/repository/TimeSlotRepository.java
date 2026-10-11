package com.bcm.repository;

import com.bcm.entity.TimeSlot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TimeSlotRepository extends JpaRepository<TimeSlot, UUID> {

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
