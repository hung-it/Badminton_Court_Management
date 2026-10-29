package com.bcm.dto.response;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record PosQuoteResponse(BigDecimal productFee, BigDecimal courtFee, BigDecimal discountAmount,
        BigDecimal totalAmount, List<Line> lines) {
    public record Line(UUID productId, String name, int quantity, BigDecimal unitPrice,
            BigDecimal grossAmount, BigDecimal discountAmount, BigDecimal totalAmount,
            UUID promotionId, String promotionName, BigDecimal discountPercent) {}
}
