package com.bcm.dto.request;

import com.bcm.entity.*;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;

public record CourtRequest(
        @NotNull @Min(1) Integer courtNumber,
        @NotBlank @Size(max = 255) String name,
        @NotNull CourtType type,
        @NotNull CourtStatus status,
        @NotNull @DecimalMin("0.00") @Digits(integer = 8, fraction = 2) BigDecimal basePrice,
        @Size(max = 150) @Pattern(regexp = "[^\\p{Cntrl}]*") String district,
        @Size(max = 500) @Pattern(regexp = "[^\\p{Cntrl}]*") String address) {
    // Preserve source compatibility for existing callers that omit the optional address.
    public CourtRequest(Integer courtNumber, String name, CourtType type, CourtStatus status,
                        BigDecimal basePrice, String district) {
        this(courtNumber, name, type, status, basePrice, district, null);
    }
}
