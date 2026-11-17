package com.bcm.dto.request;

import com.bcm.entity.CourtStatus;
import com.bcm.entity.CourtType;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.UUID;

/** branchId selects an existing branch; null creates a branch with the supplied metadata. */
public record CourtBatchRequest(
        UUID branchId,
        @Size(max=200) @Pattern(regexp="[^\\p{Cntrl}]*") String branchName,
        @Size(max=150) @Pattern(regexp="[^\\p{Cntrl}]*") String district,
        @Size(max=500) @Pattern(regexp="[^\\p{Cntrl}]*") String address,
        @NotNull @Min(1) @Max(1000) Integer courtCount,
        @NotNull CourtType type,
        @NotNull CourtStatus status,
        @NotNull @DecimalMin("0.01") @Digits(integer=8,fraction=2) BigDecimal basePrice) { }
