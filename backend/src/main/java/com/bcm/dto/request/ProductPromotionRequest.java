package com.bcm.dto.request;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record ProductPromotionRequest(
        @NotBlank @Size(max = 255) String name,
        @NotNull UUID productId,
        @NotNull @Min(1) Integer minQuantity,
        @NotNull @DecimalMin("0.01") @DecimalMax("100.00") @Digits(integer=3, fraction=2) BigDecimal discountPercent,
        @NotNull LocalDate validFrom,
        @NotNull LocalDate validTo,
        @NotNull Boolean active) {}
