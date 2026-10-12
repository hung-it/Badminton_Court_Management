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
