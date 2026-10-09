package com.bcm.dto.request;
import jakarta.validation.constraints.*;
import com.bcm.entity.*;
import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.UUID;
public record TimeSlotRequest(@NotNull LocalTime startTime, @NotNull LocalTime endTime,
@DecimalMin("0.01") @Digits(integer=1,fraction=2) BigDecimal priceMultiplier) {}
