package com.bcm.repository;

import com.bcm.entity.TimeSlot;
import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.UUID;

public interface TimeSlotRepository extends Repository<TimeSlot, UUID> {

    List<TimeSlot> findAllById(Iterable<UUID> ids);

    List<TimeSlot> findAllByOrderByStartTimeAscEndTimeAscIdAsc();
}
