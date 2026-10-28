package com.bcm.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public record ActiveBookingResponse(
        UUID bookingId,
        String shortId,
        String status,
        String customerName,
        String customerPhone,
        BigDecimal courtFee,
        BigDecimal finalAmount,
        String courtDetails,
        LocalDateTime createdAt
) {
}

