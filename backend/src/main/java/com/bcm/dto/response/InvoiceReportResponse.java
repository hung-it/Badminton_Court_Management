package com.bcm.dto.response;

import java.math.BigDecimal;
import java.time.*;
import java.util.List;
import java.util.UUID;

public record InvoiceReportResponse(Summary invoice, List<Item> items, List<CourtLine> courts) {
    public record Summary(UUID id, UUID bookingId, String customerName, LocalDateTime createdAt,
            String paymentMethod, BigDecimal courtFee, BigDecimal productFee,
            BigDecimal discountAmount, BigDecimal totalAmount) {}
    public record Item(UUID productId, String name, int quantity, BigDecimal unitPrice,
            BigDecimal discountAmount, String promotionName, BigDecimal totalAmount) {}
    public record CourtLine(String name, LocalDate date, LocalTime startTime, LocalTime endTime, BigDecimal price) {}
    public record History(List<Summary> invoices, int page, int totalPages, long totalElements) {}
}
