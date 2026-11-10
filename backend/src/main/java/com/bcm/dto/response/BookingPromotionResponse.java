package com.bcm.dto.response;

import java.math.BigDecimal;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Server quote: court rental subtotal, one discount and final payable. HALF_UP scale 2; automatic wins ties. Invalid vouchers retain automatic discount.")
public record BookingPromotionResponse(BigDecimal subtotal, BigDecimal discountAmount, BigDecimal finalAmount,
        String promotionCode, String requestedVoucher, boolean voucherValid, String message) { }
