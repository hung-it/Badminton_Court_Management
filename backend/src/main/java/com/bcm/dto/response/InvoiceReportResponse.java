package com.bcm.dto.response;

import java.math.BigDecimal;
import java.time.*;
import java.util.List;
import java.util.UUID;

public record InvoiceReportResponse(Summary invoice, List<Item> items, List<CourtLine> courts, List<Receipt> receipts,
        UUID createdBy, UUID createdByUser, String creatorName) {
    public InvoiceReportResponse(Summary invoice, List<Item> items, List<CourtLine> courts, List<Receipt> receipts,
            UUID createdBy, UUID createdByUser) {
        this(invoice, items, courts, receipts, createdBy, createdByUser, null);
    }
    public InvoiceReportResponse(Summary invoice, List<Item> items, List<CourtLine> courts) {
        this(invoice, items, courts, List.of(), null, null, null);
    }
    public record Receipt(UUID id,String target,String method,String source,String status,BigDecimal amount,
        UUID confirmedBy,UUID confirmedByUser,String actorName,LocalDateTime confirmedAt,String reference) {}
    public record Summary(UUID id, UUID bookingId, String customerName, LocalDateTime createdAt,
            String paymentMethod, BigDecimal courtFee, BigDecimal productFee,
            BigDecimal discountAmount, BigDecimal totalAmount, String invoiceStatus, String bookingStatus, List<String> paymentMethods,
            UUID customerId, String customerPhone, String customerEmail) {
        public Summary(UUID id, UUID bookingId, String customerName, LocalDateTime createdAt,
                String paymentMethod, BigDecimal courtFee, BigDecimal productFee,
                BigDecimal discountAmount, BigDecimal totalAmount, String invoiceStatus, String bookingStatus, List<String> paymentMethods,
                UUID customerId, String customerPhone) {
            this(id, bookingId, customerName, createdAt, paymentMethod, courtFee, productFee,
                 discountAmount, totalAmount, invoiceStatus, bookingStatus, paymentMethods, customerId, customerPhone, null);
        }
    }
    public record Item(UUID productId, String name, int quantity, BigDecimal unitPrice,
            BigDecimal discountAmount, String promotionName, BigDecimal totalAmount, String productType) {}
    public record CourtLine(String name, LocalDate date, LocalTime startTime, LocalTime endTime, BigDecimal price) {}
    public record History(List<Summary> invoices, int page, int totalPages, long totalElements) {}
}
