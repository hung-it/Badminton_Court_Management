package com.bcm.dto.request;
import jakarta.validation.constraints.*;
import com.bcm.entity.*;
import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.UUID;
public record ProductRequest(@NotNull UUID categoryId, @NotBlank @Size(max=255) String name,
@NotNull ProductType type, @NotBlank @Size(max=50) String unit,
@NotNull @DecimalMin("0.00") @Digits(integer=8,fraction=2) BigDecimal price,
@NotNull @Min(0) Integer stockQuantity, @Min(0) Integer version,
@Size(max=2048) String imageUrl) {}
