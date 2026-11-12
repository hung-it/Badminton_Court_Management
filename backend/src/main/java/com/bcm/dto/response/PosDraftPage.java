package com.bcm.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record PosDraftPage(List<Draft> content, int page, int size, long totalElements, long totalPages) {
    public record Draft(UUID id, UUID bookingId, String customerName, UUID createdByUser,
                        String createdByName, LocalDateTime createdAt, BigDecimal totalAmount, List<Item> items) { }
    public record Item(UUID productId, String productName, int quantity, BigDecimal unitPrice,
                       BigDecimal discountAmount, BigDecimal lineAmount) { }
}
