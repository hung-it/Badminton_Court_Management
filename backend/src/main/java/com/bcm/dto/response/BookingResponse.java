package com.bcm.dto.response;

import com.bcm.entity.BookingStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Getter
@AllArgsConstructor
public class BookingResponse {
    private final UUID bookingId;
    private final BookingStatus status;
    private final BigDecimal courtFee;
    private final LocalDateTime expiresAt;
    private final List<Detail> details;

    @Getter
    @AllArgsConstructor
    @Schema(name = "BookingDetailResponse")
    public static class Detail {
        private final LocalDate bookingDate;
        private final UUID courtId;
        private final UUID timeSlotId;
        private final BigDecimal price;
    }
}
