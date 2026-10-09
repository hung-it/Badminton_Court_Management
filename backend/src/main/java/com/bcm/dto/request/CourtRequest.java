package com.bcm.dto.request;
import jakarta.validation.constraints.*;
import com.bcm.entity.*;
import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.UUID;
public record CourtRequest(@NotNull @Min(1) Integer courtNumber,
@NotBlank @Size(max=255) String name,
@NotNull CourtType type,
@NotNull CourtStatus status,
@NotNull @DecimalMin("0.00") @Digits(integer=8,fraction=2) BigDecimal basePrice) {}
