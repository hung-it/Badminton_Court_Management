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

        @Schema(description = "True only when the slot starts strictly after server time in Asia/Ho_Chi_Minh "
                + "and no booking detail occupies it. Equality is already started. Persisted details block "
                + "regardless of header status or expiry. Historical templates remain readable; this is not a reservation.")
        private final boolean available;
    }
}
