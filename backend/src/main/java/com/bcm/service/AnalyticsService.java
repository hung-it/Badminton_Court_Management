package com.bcm.service;

import com.bcm.dto.response.RevenueReportResponse;
import com.bcm.exception.BadRequestException;
import com.bcm.repository.InvoiceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AnalyticsService {

    private static final String PAID_STATUS = "PAID";

    private final InvoiceRepository invoiceRepository;

    public RevenueReportResponse getRevenue(String period, LocalDate date) {
        String normalizedPeriod = normalizePeriod(period);
        LocalDate referenceDate = date == null ? LocalDate.now() : date;
        DateRange range = createDateRange(normalizedPeriod, referenceDate);

        Object[] totals = invoiceRepository.sumRevenueByPeriod(
                PAID_STATUS,
                range.from().atStartOfDay(),
                range.toExclusive().atStartOfDay());
        BigDecimal courtAmount = valueOrZero((BigDecimal) totals[0]);
        BigDecimal productAmount = valueOrZero((BigDecimal) totals[1]);

        return RevenueReportResponse.builder()
                .period(normalizedPeriod)
                .from(range.from())
                .to(range.toInclusive())
                .courtAmount(courtAmount)
                .productAmount(productAmount)
                .totalAmount(courtAmount.add(productAmount))
                .build();
    }

    private String normalizePeriod(String period) {
        String normalized = period == null ? "DAY" : period.trim().toUpperCase();
        if (!normalized.equals("DAY")
                && !normalized.equals("WEEK")
                && !normalized.equals("MONTH")) {
            throw new BadRequestException("period phải là DAY, WEEK hoặc MONTH");
        }
        return normalized;
    }

    private DateRange createDateRange(String period, LocalDate date) {
        return switch (period) {
            case "WEEK" -> {
                LocalDate from = date.with(DayOfWeek.MONDAY);
                yield new DateRange(from, from.plusWeeks(1), from.plusDays(6));
            }
            case "MONTH" -> {
                LocalDate from = date.withDayOfMonth(1);
                yield new DateRange(from, from.plusMonths(1), from.plusMonths(1).minusDays(1));
            }
            default -> new DateRange(date, date.plusDays(1), date);
        };
    }

    private BigDecimal valueOrZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private record DateRange(LocalDate from, LocalDate toExclusive, LocalDate toInclusive) {
    }
}