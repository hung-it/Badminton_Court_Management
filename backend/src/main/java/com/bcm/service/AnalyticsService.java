package com.bcm.service;

import com.bcm.dto.response.RevenueReportResponse;
import com.bcm.dto.response.InvoiceReportResponse;
import com.bcm.entity.Invoice;
import com.bcm.entity.InvoiceDetail;
import com.bcm.entity.Customer;
import com.bcm.entity.Product;
import com.bcm.exception.ResourceNotFoundException;
import com.bcm.repository.InvoiceDetailRepository;
import com.bcm.repository.ProductRepository;
import com.bcm.repository.CustomerRepository;
import com.bcm.repository.BookingDetailRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import java.util.UUID;
import java.util.List;
import java.util.stream.Collectors;

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
    private final InvoiceDetailRepository invoiceDetails;
    private final ProductRepository products;
    private final CustomerRepository customers;
    private final BookingDetailRepository bookingDetails;

    public InvoiceReportResponse.History history(String period, LocalDate date, int page) {
        return history(period,date,page,null,null);
    }

    public InvoiceReportResponse.History history(String period, LocalDate date, int page, LocalDate from, LocalDate to) {
        if(page<0) throw new BadRequestException("Trang phải từ 0 trở lên");
        var range=resolveRange(period,date,from,to);
        var result=invoiceRepository.findPaidHistory(range.from().atStartOfDay(),range.toExclusive().atStartOfDay(),
            PageRequest.of(page,20,Sort.by(
                Sort.Order.desc("createdAt"),Sort.Order.desc("id"))));
        return new InvoiceReportResponse.History(result.stream().map(this::summary).toList(),page,result.getTotalPages(),result.getTotalElements());
    }

    public InvoiceReportResponse invoice(UUID id) {
        var invoice=invoiceRepository.findById(id).filter(i->i.getDeletedAt()==null && PAID_STATUS.equals(i.getStatus()))
            .orElseThrow(()->new ResourceNotFoundException("Không tìm thấy hóa đơn doanh thu"));
        var details=invoiceDetails.findByInvoiceId(id);
        var names=products.findAllById(details.stream().map(InvoiceDetail::getProductId).distinct().toList())
            .stream().collect(Collectors.toMap(Product::getId,Product::getName));
        var items=details.stream().map(d->new InvoiceReportResponse.Item(d.getProductId(),names.getOrDefault(d.getProductId(),"Sản phẩm"),
            d.getQuantity(),d.getUnitPrice(),d.getDiscountAmount(),d.getPromotionName(),d.getUnitPrice().multiply(BigDecimal.valueOf(d.getQuantity())).subtract(d.getDiscountAmount()))).toList();
        var courts=invoice.getBookingId()==null?List.<InvoiceReportResponse.CourtLine>of():
            bookingDetails.findHistoryDetails(invoice.getBookingId()).stream().map(d->new InvoiceReportResponse.CourtLine(
                d.getCourt().getName(),d.getBookingDate(),d.getTimeSlot().getStartTime(),d.getTimeSlot().getEndTime(),d.getPrice())).toList();
        return new InvoiceReportResponse(summary(invoice),items,courts);
    }

    private InvoiceReportResponse.Summary summary(Invoice i) {
        String name=customers.findById(i.getCustomerId()).map(Customer::getFullName).orElse("Khách vãng lai");
        return new InvoiceReportResponse.Summary(i.getId(),i.getBookingId(),name,i.getCreatedAt(),i.getPaymentMethod(),
            valueOrZero(i.getCourtFee()),i.getProductFee(),i.getDiscountAmount(),i.getTotalAmount());
    }

    public RevenueReportResponse getRevenue(String period, LocalDate date) {
        return getRevenue(period,date,null,null);
    }

    public RevenueReportResponse getRevenue(String period, LocalDate date, LocalDate from, LocalDate to) {
        DateRange range = resolveRange(period,date,from,to);
        String normalizedPeriod = from == null ? normalizePeriod(period) : "RANGE";

        InvoiceRepository.RevenueTotals totals = invoiceRepository.sumRevenueByPeriod(
                PAID_STATUS,
                range.from().atStartOfDay(),
                range.toExclusive().atStartOfDay());
        BigDecimal courtFee = valueOrZero(totals.getCourtFee());
        BigDecimal productFee = valueOrZero(totals.getProductFee());
        BigDecimal discount = valueOrZero(totals.getDiscountAmount());

        return RevenueReportResponse.builder()
                .period(normalizedPeriod)
                .from(range.from())
                .to(range.toInclusive())
                .courtFee(courtFee)
                .productFee(productFee)
                .discountAmount(discount)
                .totalAmount(valueOrZero(totals.getTotalAmount()))
                .build();
    }

    private DateRange resolveRange(String period, LocalDate date, LocalDate from, LocalDate to) {
        if (from == null && to == null) return createDateRange(normalizePeriod(period),date == null ? LocalDate.now() : date);
        if (from == null || to == null) throw new BadRequestException("Vui lòng chọn đủ từ ngày và đến ngày");
        if (from.isAfter(to)) throw new BadRequestException("Từ ngày không được sau đến ngày");
        if (to.equals(LocalDate.MAX)) throw new BadRequestException("Ngày kết thúc không hợp lệ");
        return new DateRange(from,to.plusDays(1),to);
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
