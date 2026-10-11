package com.bcm.dto.request;

import com.bcm.entity.*;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.LocalTime;

public record TimeSlotRequest(
        @NotNull LocalTime startTime,
        @NotNull LocalTime endTime,
        @DecimalMin("0.01") @Digits(integer = 1, fraction = 2) BigDecimal priceMultiplier) {}
