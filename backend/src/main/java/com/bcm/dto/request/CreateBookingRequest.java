package com.bcm.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreateBookingRequest {
    @NotNull
    @Schema(description = "Own customers.id consistency assertion; must match the authenticated CUSTOMER profile or return 403")
    private UUID customerId;

    @NotEmpty
    @Valid
    private List<@NotNull Detail> details;

    @Schema(description = "Optional branch assertion. Every detail must belong to this active branch. Existing legacy requests remain supported.")
    private UUID branchId;

    @Schema(description = "Optional legacy ownership assertion for a booking left at checkout. Never releases, renews or reuses that booking. Existing occupied slots conflict, including the owner's slots; the scheduled release runs independently.")
    private UUID replacesBookingId;

    public CreateBookingRequest(UUID customerId, List<Detail> details, UUID branchId) {
        this.customerId = customerId;
        this.details = details;
        this.branchId = branchId;
    }

    public CreateBookingRequest(UUID customerId, List<Detail> details) {
        this.customerId = customerId;
        this.details = details;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(name = "CreateBookingDetail")
    public static class Detail {
        @NotNull
        private LocalDate bookingDate;
        @NotNull
        private UUID courtId;
        @NotNull
        private UUID timeSlotId;
    }
}
