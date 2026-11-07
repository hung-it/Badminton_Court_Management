package com.bcm.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Court and customer identity always come from the locked booking. */
public record UpdateBookingRequest(@NotNull LocalDate bookingDate,
        @NotNull @Size(min = 1, max = 48) List<@NotNull UUID> timeSlotIds) { }
