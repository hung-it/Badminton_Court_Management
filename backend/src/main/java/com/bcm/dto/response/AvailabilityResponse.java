package com.bcm.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

@Getter
@AllArgsConstructor
public class AvailabilityResponse {

    private final LocalDate date;
    private final List<CourtAvailability> courts;

    @Getter
    @AllArgsConstructor
    public static class CourtAvailability {
        private final UUID courtId;
        private final Integer courtNumber;
        private final String name;
        private final List<SlotAvailability> slots;
    }

    @Getter
    @AllArgsConstructor
    public static class SlotAvailability {
        private final UUID timeSlotId;
        private final LocalTime startTime;
        private final LocalTime endTime;
        private final BigDecimal priceMultiplier;

        @Schema(description = "True when no booking detail exists for this date, court and time slot. "
                + "False means occupied, regardless of booking status or expiry. This is not a reservation.")
        private final boolean available;
    }
}
