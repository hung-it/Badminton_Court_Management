package com.bcm.dto.request;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;

public record ManualImportOrderRequest(
        @NotBlank @Size(max=255) String supplierName,
        @NotBlank @Size(max=255) String productName,
        @NotNull @Positive Integer quantity,
        @NotNull @DecimalMin("0") @Digits(integer=8,fraction=2) BigDecimal totalAmount) {}
