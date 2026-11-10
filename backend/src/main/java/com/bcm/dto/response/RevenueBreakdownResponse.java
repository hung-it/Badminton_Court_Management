package com.bcm.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record RevenueBreakdownResponse(
        String period,
        int year,
        List<Period> periods,
        List<Integer> availableYears,
        List<StaffRow> staff,
        List<CustomerRow> customers,
        List<ProductRow> products,
        List<ProductDetailRow> productDetails,
        List<CourtRow> courts,
        List<PeriodDetail> details) {

    public record Period(String key, String label, LocalDate from, LocalDate to) {}

    public record StaffRow(UUID staffId, String staffName, Map<String, BigDecimal> values, BigDecimal total) {}

    public record CustomerRow(UUID customerId, String customerName, Map<String, BigDecimal> values, BigDecimal total) {}

    public record ProductRow(UUID productId, String productName, Map<String, BigDecimal> quantities,
                             Map<String, BigDecimal> values, BigDecimal totalQuantity, BigDecimal total) {}

    public record ProductDetailRow(UUID productId, String productName, UUID staffId, String staffName,
                                   UUID customerId, String customerName, Map<String, BigDecimal> quantities,
                                   Map<String, BigDecimal> values, BigDecimal totalQuantity, BigDecimal total) {}

    public record CourtRow(UUID courtId, String courtName, Map<String, BigDecimal> values, BigDecimal total) {}

    public record PeriodDetail(String periodKey, String label, List<ProductDetailRow> products,
                               BigDecimal productTotal, List<CourtRow> courts, BigDecimal courtTotal,
                               BigDecimal invoiceTotal) {}
}
