package com.bcm.service;

import com.bcm.dto.response.AvailabilityResponse;
import com.bcm.dto.response.AvailabilityResponse.CourtAvailability;
import com.bcm.dto.response.AvailabilityResponse.SlotAvailability;
import com.bcm.entity.Court;
import com.bcm.entity.CourtStatus;
import com.bcm.entity.TimeSlot;
import com.bcm.exception.BadRequestException;
import com.bcm.exception.ResourceNotFoundException;
import com.bcm.repository.BookingDetailRepository;
import com.bcm.repository.CourtRepository;
import com.bcm.repository.TimeSlotRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AvailabilityService {

    private final CourtRepository courtRepository;
    private final TimeSlotRepository timeSlotRepository;
    private final BookingDetailRepository bookingDetailRepository;

    @Transactional(readOnly = true)
    public AvailabilityResponse getAvailability(LocalDate date, UUID courtId) {
        if (date == null) {
            throw new BadRequestException("date is required (YYYY-MM-DD)");
        }

        List<Court> courts = courtRepository.findForAvailability(CourtStatus.AVAILABLE, courtId);
        if (courtId != null && courts.isEmpty()) {
            throw new ResourceNotFoundException("Court is not available for lookup: " + courtId);
        }
        if (courts.isEmpty()) {
            return new AvailabilityResponse(date, List.of());
        }

        List<TimeSlot> timeSlots = timeSlotRepository.findAllByOrderByStartTimeAscEndTimeAscIdAsc();
        Map<UUID, Set<UUID>> occupiedByCourt = new HashMap<>();
        // A persisted detail occupies the slot even when its header is expired or inactive.
        for (BookingDetailRepository.OccupiedSlot slot : bookingDetailRepository.findOccupiedSlots(date, courtId)) {
            occupiedByCourt.computeIfAbsent(slot.getCourtId(), id -> new HashSet<>()).add(slot.getTimeSlotId());
        }

        List<CourtAvailability> result = courts.stream().map(court -> {
            Set<UUID> occupied = occupiedByCourt.getOrDefault(court.getId(), Set.of());
            List<SlotAvailability> slots = timeSlots.stream()
                    .map(slot -> new SlotAvailability(slot.getId(), slot.getStartTime(), slot.getEndTime(),
                            slot.getPriceMultiplier(), !occupied.contains(slot.getId())))
                    .toList();
            return new CourtAvailability(court.getId(), court.getCourtNumber(), court.getName(), slots);
        }).toList();
        return new AvailabilityResponse(date, result);
    }
}
