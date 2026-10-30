package com.bcm.dto.request;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
public record ApproveProductPriceRequest(@NotNull @DecimalMin("0.01") @Digits(integer=8,fraction=2) BigDecimal price,
        @NotNull @Min(0) Integer version) {}
