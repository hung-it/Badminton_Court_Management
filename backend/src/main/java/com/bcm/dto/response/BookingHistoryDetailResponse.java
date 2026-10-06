package com.bcm.dto.response;

import com.bcm.entity.BookingStatus;
import com.bcm.entity.PaymentMethod;
import com.bcm.entity.PaymentStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Schema(description = "Read-only booking snapshot. Expiration deletes details but preserves courtFee and payments")
public record BookingHistoryDetailResponse(UUID bookingId, UUID customerId, BookingStatus status,
        BigDecimal courtFee, @Schema(nullable = true) LocalDateTime expiresAt, LocalDateTime createdAt, LocalDateTime updatedAt,
        @Schema(description = "Persisted details only; empty after expiration deletes them") List<Detail> details,
        @Schema(description = "Booking-target payments, createdAt DESC then paymentAttemptId DESC; empty if none")
        List<Payment> payments) {

    @Schema(name = "BookingHistorySlot", description = "Snapshot price; display names/times use current master data")
    public record Detail(UUID detailId, LocalDate bookingDate, UUID courtId, UUID timeSlotId, BigDecimal price,
                         Integer courtNumber, String courtName, java.time.LocalTime startTime,
                         java.time.LocalTime endTime) { }

    @Schema(name = "BookingPaymentHistory", description = "Actual payment status, independent of booking status")
    public record Payment(UUID paymentAttemptId, PaymentMethod paymentMethod, PaymentStatus paymentStatus,
                          BigDecimal amount,
                          @Schema(nullable = true, description = "Gateway ID; null before confirmation") String transactionId,
                          @Schema(nullable = true, description = "Provider payment timestamp when verified; MoMo remains null")
                          LocalDateTime transactionDate, LocalDateTime createdAt) { }
}
