package com.bcm.repository;
import com.bcm.entity.TimeSlot;
import java.util.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
public interface TimeSlotRepository extends JpaRepository<TimeSlot,UUID> {
List<TimeSlot> findByDeletedAtIsNullOrderByStartTimeAsc();
Optional<TimeSlot> findByIdAndDeletedAtIsNull(UUID id);
@Query("select count(t)>0 from TimeSlot t where t.deletedAt is null and t.id <> :id and t.startTime < :end and t.endTime > :start")
boolean overlaps(@Param("id") UUID id, @Param("start") java.time.LocalTime start, @Param("end") java.time.LocalTime end);
}
