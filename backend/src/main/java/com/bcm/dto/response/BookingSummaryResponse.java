package com.bcm.dto.response;

import com.bcm.entity.BookingStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Schema(description = "Persisted booking header; customerId is a domain reference, not authenticated ownership")
public record BookingSummaryResponse(UUID bookingId, UUID customerId, BookingStatus status,
                                     BigDecimal courtFee, @Schema(nullable = true) LocalDateTime expiresAt,
                                     LocalDateTime createdAt, LocalDateTime updatedAt) { }
