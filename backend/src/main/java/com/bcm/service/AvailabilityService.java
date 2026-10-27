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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Clock;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Qualifier;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class AvailabilityService {

    private final CourtRepository courtRepository;
    private final TimeSlotRepository timeSlotRepository;
    private final BookingDetailRepository bookingDetailRepository;
    private final Clock clock;

    public AvailabilityService(CourtRepository courts, TimeSlotRepository slots, BookingDetailRepository details,
                               @Qualifier("bookingExpirationClock") Clock clock) {
        this.courtRepository = courts;
        this.timeSlotRepository = slots;
        this.bookingDetailRepository = details;
        this.clock = clock;
    }

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

        List<TimeSlot> timeSlots = timeSlotRepository.findByDeletedAtIsNullOrderByStartTimeAscEndTimeAscIdAsc();
        Map<UUID, Map<UUID, com.bcm.entity.BookingStatus>> occupiedByCourt = new HashMap<>();
        // A persisted detail occupies the slot even when its header is expired or inactive.
        for (BookingDetailRepository.OccupiedSlot slot : bookingDetailRepository.findOccupiedSlots(date, courtId)) {
            occupiedByCourt.computeIfAbsent(slot.getCourtId(), id -> new HashMap<>())
                    .put(slot.getTimeSlotId(), slot.getBookingStatus());
        }

        var now = LocalDateTime.ofInstant(clock.instant(), ZoneId.of("Asia/Ho_Chi_Minh"));
        List<CourtAvailability> result = courts.stream().map(court -> {
            var occupied = occupiedByCourt.getOrDefault(court.getId(), Map.of());
            List<SlotAvailability> slots = timeSlots.stream()
                    .map(slot -> new SlotAvailability(slot.getId(), slot.getStartTime(), slot.getEndTime(),
                            slot.getPriceMultiplier(), court.getBasePrice().multiply(slot.getPriceMultiplier())
                                    .setScale(2, java.math.RoundingMode.HALF_UP), date.atTime(slot.getStartTime()).isAfter(now)
                                    && !occupied.containsKey(slot.getId()), occupancy(occupied.get(slot.getId()),
                                    date.atTime(slot.getStartTime()).isAfter(now))))
                    .toList();
            return new CourtAvailability(court.getId(), court.getCourtNumber(), court.getName(), slots);
        }).toList();
        return new AvailabilityResponse(date, result);
    }

    private String occupancy(com.bcm.entity.BookingStatus status, boolean future) {
        if (status == null) return future ? "AVAILABLE" : "PAST";
        return switch (status) {
            case PENDING -> "HELD";
            case EXPIRED -> "BLOCKED"; // Never advertise a retained unique-index tuple as free.
            default -> "BOOKED";
        };
    }
}
