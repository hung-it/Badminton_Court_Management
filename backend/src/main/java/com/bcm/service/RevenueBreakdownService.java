package com.bcm.service;

import com.bcm.dto.response.RevenueBreakdownResponse;
import com.bcm.exception.BadRequestException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Revenue broken down by staff, customer, product and court for each quarter or the whole year.
 *
 * <p>Every figure is aggregated in SQL from the same invoice population the revenue report
 * uses: PAID invoices that are not soft-deleted, bounded by {@code created_at} with an
 * inclusive start and exclusive end. Summing the staff or customer rows therefore equals the
 * revenue report for the same window.
 *
 * <p>Product revenue is reconciled against invoice revenue: for each invoice the amount left
 * after discounts ({@code total_amount}) is split between products and courts in proportion to
 * their net value after bucket-specific discounts. Products are then split by net line value and courts by their booked slot
 * price. Rounding remainders are pushed onto the largest line so the parts always add up to the
 * invoice total exactly.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
public class RevenueBreakdownService {

    private static final String PAID_STATUS = "PAID";
    private static final String UNASSIGNED_STAFF_NAME = "Online / Admin / chưa đối soát";
    private static final String WALK_IN_CUSTOMER_NAME = "Khách vãng lai";
    private static final String UNKNOWN_COURT_NAME = "Không xác định sân";
    private static final int SCALE = 2;

    private final JdbcTemplate jdbc;

    public record DashboardPoint(LocalDate from, LocalDate to, LocalDate previousFrom, LocalDate previousTo,
                                 BigDecimal current, BigDecimal previous) {}
    public record DashboardSlice(String label, BigDecimal amount, BigDecimal percent) {}
    public record Dashboard(LocalDate from, LocalDate to, LocalDate previousFrom, LocalDate previousTo,
                            BigDecimal totalRevenue, BigDecimal previousRevenue, BigDecimal growthPercent,
                            long invoiceCount, BigDecimal averageInvoice,
                            List<DashboardPoint> trend, List<DashboardSlice> composition,
                            List<DashboardSlice> payments, BigDecimal paymentUnallocated,
                            List<DashboardSlice> topCourts, List<String> warnings) {
    }

    public Dashboard dashboard(LocalDate from, LocalDate to) {
        if (from == null || to == null || from.isAfter(to) || from.getYear()<2 || to.getYear()>9998) {
            throw new BadRequestException("Chọn Từ ngày và Đến ngày hợp lệ, Từ ngày không sau Đến ngày");
        }
        long days=java.time.temporal.ChronoUnit.DAYS.between(from,to)+1;
        if(days>3660 || from.minusDays(days).getYear()<1)
            throw new BadRequestException("Khoảng thời gian tối đa 3660 ngày và phải có kỳ trước hợp lệ");
        LocalDate previousFrom=from.minusDays(days), previousTo=from.minusDays(1);
        var start=from.atStartOfDay();var end=to.plusDays(1).atStartOfDay();
        String population=" FROM invoices i WHERE i.status='PAID' AND i.deleted_at IS NULL AND i.created_at>=? AND i.created_at<?";
        BigDecimal total=dashboardTotal(from,to.plusDays(1));
        BigDecimal previous=dashboardTotal(previousFrom,from);
        long count=jdbc.queryForObject("SELECT COUNT(*)"+population,Long.class,start,end);
        BigDecimal growth=previous.signum()==0?null:total.subtract(previous).multiply(BigDecimal.valueOf(100)).divide(previous,2,RoundingMode.HALF_UP);
        List<DashboardPoint> trend=new ArrayList<>();
        // Read both periods once; fill gaps and group calendar months without an N+1 query.
        Map<LocalDate,BigDecimal> daily=new LinkedHashMap<>();
        jdbc.query("SELECT CAST(i.created_at AS DATE) AS revenue_day,SUM(i.total_amount) AS amount"+population+" GROUP BY CAST(i.created_at AS DATE)",
                (org.springframework.jdbc.core.RowCallbackHandler) rs->daily.put(rs.getDate("revenue_day").toLocalDate(),rs.getBigDecimal("amount")),previousFrom.atStartOfDay(),end);
        for(LocalDate day=from;!day.isAfter(to);) {
            LocalDate next=days<=62?day.plusDays(1):day.withDayOfMonth(1).plusMonths(1);
            if(next.isAfter(to.plusDays(1)))next=to.plusDays(1);
            BigDecimal now=BigDecimal.ZERO,before=BigDecimal.ZERO;
            for(LocalDate d=day;d.isBefore(next);d=d.plusDays(1)) {
                now=now.add(daily.getOrDefault(d,BigDecimal.ZERO));
                before=before.add(daily.getOrDefault(d.minusDays(days),BigDecimal.ZERO));
            }
            trend.add(new DashboardPoint(day,next.minusDays(1),day.minusDays(days),next.minusDays(days).minusDays(1),now,before));day=next;
        }
        List<String> warnings=new ArrayList<>();
        List<DashboardSlice> composition=new ArrayList<>(), top=new ArrayList<>();
        try {
            var allocation=allocate(start,end,Map.of(),Map.of(),Map.of(),courtNames());
            composition.add(slice("Tiền sân",allocation.courtTotal(),total));
            composition.add(slice("Hàng hóa",allocation.categories().get("GOODS"),total));
            composition.add(slice("Dịch vụ",allocation.categories().get("SERVICE"),total));
            if(allocation.categories().get("UNKNOWN").signum()!=0)
                composition.add(slice("Chưa phân loại",allocation.categories().get("UNKNOWN"),total));
            if(allocation.unknownLines()>0)warnings.add("Chưa phân loại: "+allocation.unknownLines()+" dòng, "+allocation.categories().get("UNKNOWN").toPlainString()+" VND.");
            allocation.courts().stream().filter(r->r.courtId()!=null && r.total().signum()>0)
                    .sorted(Comparator.comparing(RevenueBreakdownResponse.CourtRow::total).reversed().thenComparing(RevenueBreakdownResponse.CourtRow::courtName))
                    .limit(5).forEach(r->top.add(slice(r.courtName(),r.total(),allocation.courtTotal())));
            if(allocation.courts().stream().anyMatch(r->r.courtId()==null && r.total().signum()!=0))warnings.add("Một phần tiền sân thiếu chi tiết booking, chưa thể gán vào Top sân.");
        }catch(BadRequestException ex) {
            warnings.add("Snapshot hóa đơn chưa đủ nhất quán để phân bổ cơ cấu và Top sân; tổng doanh thu vẫn lấy từ hóa đơn PAID.");
        }
        BigDecimal[] methods={BigDecimal.ZERO,BigDecimal.ZERO};
        var receipts=jdbc.query("SELECT i.total_amount,COALESCE(SUM(CASE WHEN p.payment_method='CASH' THEN p.amount ELSE 0 END),0) AS cash,COALESCE(SUM(CASE WHEN p.payment_method='VNPAY' THEN p.amount ELSE 0 END),0) AS vnpay "
                +"FROM invoices i LEFT JOIN payment_transactions p ON p.status='SUCCESS' AND (p.invoice_id=i.id OR (p.booking_id=i.booking_id AND i.parent_invoice_id IS NULL AND i.court_fee>0)) "
                +"WHERE i.status='PAID' AND i.deleted_at IS NULL AND i.created_at>=? AND i.created_at<? GROUP BY i.id,i.total_amount",
                (rs,n)->List.of(rs.getBigDecimal("total_amount"),rs.getBigDecimal("cash"),rs.getBigDecimal("vnpay")),start,end);
        BigDecimal unallocated=BigDecimal.ZERO;
        for(var r:receipts) {
            if(r.get(1).signum()>=0 && r.get(2).signum()>=0 && r.get(1).add(r.get(2)).compareTo(r.get(0))==0) {
                methods[0]=methods[0].add(r.get(1));methods[1]=methods[1].add(r.get(2));
            }else unallocated=unallocated.add(r.get(0));
        }
        if(unallocated.signum()!=0)warnings.add("Một số hóa đơn chưa có khoản thu SUCCESS khớp tổng tiền; phần này chưa gán CASH/VNPAY.");
        return new Dashboard(from,to,previousFrom,previousTo,total,previous,growth,count,
                count==0?BigDecimal.ZERO:total.divide(BigDecimal.valueOf(count),2,RoundingMode.HALF_UP),trend,composition,
                List.of(slice("CASH",methods[0],total),slice("VNPAY",methods[1],total)),unallocated,top,warnings);
    }


    private BigDecimal dashboardTotal(LocalDate from,LocalDate end) {
        return jdbc.queryForObject("SELECT COALESCE(SUM(i.total_amount),0) FROM invoices i WHERE i.status='PAID' AND i.deleted_at IS NULL AND i.created_at>=? AND i.created_at<?",
                BigDecimal.class,from.atStartOfDay(),end.atStartOfDay());
    }
    private DashboardSlice slice(String label,BigDecimal amount,BigDecimal total) {
        return new DashboardSlice(label,amount,total.signum()==0?BigDecimal.ZERO:amount.multiply(BigDecimal.valueOf(100)).divide(total,2,RoundingMode.HALF_UP));
    }

    public RevenueBreakdownResponse report(String period, int year) {
        return report(period, year, null, false);
    }

    /**
     * @param quarter when set (1..4) only the detail of that quarter is returned
     * @param withDetails whether per-period product/court details should be returned
     */
    public RevenueBreakdownResponse report(String period, int year, Integer quarter, boolean withDetails) {
        String mode = period == null ? "QUARTER" : period.trim().toUpperCase(Locale.ROOT);
        if (!mode.equals("QUARTER") && !mode.equals("YEAR")) {
            throw new BadRequestException("Kỳ thống kê phải là QUARTER hoặc YEAR");
        }
        if (year < 1 || year > 9998) {
            throw new BadRequestException("Năm không hợp lệ");
        }
        if (quarter != null && (quarter < 1 || quarter > 4)) {
            throw new BadRequestException("Quý phải nằm trong khoảng 1 đến 4");
        }
        if (quarter != null && mode.equals("YEAR")) {
            throw new BadRequestException("Không thể chọn quý khi thống kê theo năm");
        }

        List<RevenueBreakdownResponse.Period> periods = mode.equals("YEAR")
                ? List.of(new RevenueBreakdownResponse.Period("Y" + year, "Năm " + year,
                        LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31)))
                : quarters(year);

        Map<UUID, String> staffNames = staffNames();
        Map<UUID, String> customerNames = customerNames();
        Map<UUID, String> productNames = productNames();
        Map<UUID, String> courtNames = courtNames();

        Map<UUID, Map<String, BigDecimal>> staffValues = new LinkedHashMap<>();
        Map<UUID, Map<String, BigDecimal>> customerValues = new LinkedHashMap<>();
        Map<UUID, Map<String, BigDecimal>> productValues = new LinkedHashMap<>();
        Map<UUID, Map<String, BigDecimal>> productQuantities = new LinkedHashMap<>();
        Map<UUID, Map<String, BigDecimal>> courtValues = new LinkedHashMap<>();
        Map<String, List<RevenueBreakdownResponse.ProductDetailRow>> detailProducts = new LinkedHashMap<>();
        Map<String, List<RevenueBreakdownResponse.CourtRow>> detailCourts = new LinkedHashMap<>();

        Map<String, BigDecimal> detailProductTotals = new LinkedHashMap<>();
        Map<String, BigDecimal> detailCourtTotals = new LinkedHashMap<>();
        Map<String, BigDecimal> detailInvoiceTotals = new LinkedHashMap<>();

        for (RevenueBreakdownResponse.Period p : periods) {
            LocalDateTime from = p.from().atStartOfDay();
            LocalDateTime to = p.to().plusDays(1).atStartOfDay();

            for (RevenueByKey row : staffTotals(from, to)) {
                UUID staffId = staffNames.containsKey(row.key()) ? row.key() : null;
                staffValues.computeIfAbsent(staffId, k -> new LinkedHashMap<>())
                        .merge(p.key(), row.revenue(), BigDecimal::add);
            }
            for (RevenueByKey row : customerTotals(from, to)) {
                UUID customerId = customerNames.containsKey(row.key()) ? row.key() : null;
                customerValues.computeIfAbsent(customerId, k -> new LinkedHashMap<>())
                        .merge(p.key(), row.revenue(), BigDecimal::add);
            }

            PeriodAllocation allocation = allocate(from, to, staffNames, customerNames, productNames, courtNames);
            for (RevenueBreakdownResponse.ProductDetailRow row : allocation.products()) {
                if (withDetails || quarter != null) {
                    detailProducts.computeIfAbsent(p.key(), k -> new ArrayList<>()).add(row);
                }
                productValues.computeIfAbsent(row.productId(), k -> new LinkedHashMap<>())
                        .merge(p.key(), row.total(), BigDecimal::add);
                productQuantities.computeIfAbsent(row.productId(), k -> new LinkedHashMap<>())
                        .merge(p.key(), row.totalQuantity(), BigDecimal::add);
            }
            for (RevenueBreakdownResponse.CourtRow row : allocation.courts()) {
                if (withDetails || quarter != null) {
                    detailCourts.computeIfAbsent(p.key(), k -> new ArrayList<>()).add(row);
                }
                courtValues.computeIfAbsent(row.courtId(), k -> new LinkedHashMap<>())
                        .merge(p.key(), row.total(), BigDecimal::add);
            }
            if (withDetails || quarter != null) {
                detailProductTotals.put(p.key(), allocation.productTotal());
                detailCourtTotals.put(p.key(), allocation.courtTotal());
                detailInvoiceTotals.put(p.key(), allocation.invoiceTotal());
            }
        }

        List<RevenueBreakdownResponse.StaffRow> staffRows = new ArrayList<>();
        for (Map.Entry<UUID, Map<String, BigDecimal>> entry : staffValues.entrySet()) {
            fillMissingPeriods(entry.getValue(), periods);
            BigDecimal total = total(entry.getValue());
            String name = entry.getKey() == null ? UNASSIGNED_STAFF_NAME
                    : staffNames.getOrDefault(entry.getKey(), UNASSIGNED_STAFF_NAME);
            staffRows.add(new RevenueBreakdownResponse.StaffRow(entry.getKey(), name,
                    entry.getValue(), total));
        }
        staffRows.sort(Comparator.comparing(RevenueBreakdownResponse.StaffRow::total).reversed()
                .thenComparing(RevenueBreakdownResponse.StaffRow::staffName));

        List<RevenueBreakdownResponse.CustomerRow> customerRows = new ArrayList<>();
        for (Map.Entry<UUID, Map<String, BigDecimal>> entry : customerValues.entrySet()) {
            fillMissingPeriods(entry.getValue(), periods);
            BigDecimal total = total(entry.getValue());
            String name = entry.getKey() == null ? WALK_IN_CUSTOMER_NAME
                    : customerNames.getOrDefault(entry.getKey(), WALK_IN_CUSTOMER_NAME);
            customerRows.add(new RevenueBreakdownResponse.CustomerRow(entry.getKey(), name,
                    entry.getValue(), total));
        }
        customerRows.sort(Comparator.comparing(RevenueBreakdownResponse.CustomerRow::total).reversed()
                .thenComparing(RevenueBreakdownResponse.CustomerRow::customerName));

        List<RevenueBreakdownResponse.ProductRow> productRows = new ArrayList<>();
        for (Map.Entry<UUID, Map<String, BigDecimal>> entry : productValues.entrySet()) {
            Map<String, BigDecimal> quantities = productQuantities.getOrDefault(entry.getKey(), Map.of());
            fillMissingPeriods(entry.getValue(), periods);
            fillMissingPeriods(quantities, periods);
            productRows.add(new RevenueBreakdownResponse.ProductRow(entry.getKey(),
                    productNames.getOrDefault(entry.getKey(), "Sản phẩm đã xóa"),
                    quantities, entry.getValue(), total(quantities), total(entry.getValue())));
        }
        productRows.sort(Comparator.comparing(RevenueBreakdownResponse.ProductRow::total).reversed()
                .thenComparing(RevenueBreakdownResponse.ProductRow::productName));

        List<RevenueBreakdownResponse.CourtRow> courtRows = new ArrayList<>();
        for (Map.Entry<UUID, Map<String, BigDecimal>> entry : courtValues.entrySet()) {
            fillMissingPeriods(entry.getValue(), periods);
            BigDecimal total = total(entry.getValue());
            String name = entry.getKey() == null ? UNKNOWN_COURT_NAME
                    : courtNames.getOrDefault(entry.getKey(), UNKNOWN_COURT_NAME);
            courtRows.add(new RevenueBreakdownResponse.CourtRow(entry.getKey(), name,
                    entry.getValue(), total));
        }
        courtRows.sort(Comparator.comparing(RevenueBreakdownResponse.CourtRow::total).reversed()
                .thenComparing(RevenueBreakdownResponse.CourtRow::courtName));

        List<RevenueBreakdownResponse.ProductDetailRow> detailProductRows = new ArrayList<>();
        for (Map.Entry<String, List<RevenueBreakdownResponse.ProductDetailRow>> entry : detailProducts.entrySet()) {
            if (quarter == null || entry.getKey().equals(quarterKey(year, quarter))) {
                detailProductRows.addAll(entry.getValue());
            }
        }
        detailProductRows.sort(Comparator.comparing(RevenueBreakdownResponse.ProductDetailRow::total).reversed()
                .thenComparing(RevenueBreakdownResponse.ProductDetailRow::productName)
                .thenComparing(row -> row.staffName() == null ? "" : row.staffName())
                .thenComparing(row -> row.customerName() == null ? "" : row.customerName()));

        List<RevenueBreakdownResponse.PeriodDetail> details = new ArrayList<>();
        for (RevenueBreakdownResponse.Period p : periods) {
            if (quarter != null && !p.key().equals(quarterKey(year, quarter))) {
                continue;
            }
            List<RevenueBreakdownResponse.CourtRow> periodCourts = detailCourts.getOrDefault(p.key(), List.of());
            List<RevenueBreakdownResponse.ProductDetailRow> periodProducts =
                    detailProducts.getOrDefault(p.key(), List.of());
            details.add(new RevenueBreakdownResponse.PeriodDetail(p.key(), p.label(),
                    periodProducts,
                    detailProductTotals.getOrDefault(p.key(), BigDecimal.ZERO),
                    periodCourts,
                    detailCourtTotals.getOrDefault(p.key(), BigDecimal.ZERO),
                    detailInvoiceTotals.getOrDefault(p.key(), BigDecimal.ZERO)));
        }

        return new RevenueBreakdownResponse(mode, year, periods, availableYears(), staffRows, customerRows,
                productRows, detailProductRows, courtRows, details);
    }

    /**
     * Splits every paid invoice of the window into product lines and court lines so that both
     * sides add up to the invoice total.
     */
    public record AllocationValue(UUID id,String label,BigDecimal amount) {}
    public List<AllocationValue> allocationValues(LocalDateTime from,LocalDateTime to,boolean court) {
        var a=allocate(from,to,staffNames(),customerNames(),productNames(),courtNames());
        if(court)return a.courts().stream().map(r->new AllocationValue(r.courtId(),r.courtName(),r.total())).toList();
        return a.products().stream().map(r->new AllocationValue(r.productId(),r.productName(),r.total())).toList();
    }

    private PeriodAllocation allocate(LocalDateTime from, LocalDateTime to,
                                       Map<UUID, String> staffNames, Map<UUID, String> customerNames,
                                       Map<UUID, String> productNames, Map<UUID, String> courtNames) {
        List<InvoiceRow> invoices = jdbc.query(
                "SELECT i.id, i.booking_id, i.created_by AS group_key, i.customer_id, i.court_fee, i.product_fee, i.total_amount, "
                        + "CASE WHEN i.parent_invoice_id IS NULL AND i.court_fee>0 THEN COALESCE(b.discount_amount,0) ELSE 0 END AS court_discount "
                        + "FROM invoices i LEFT JOIN bookings b ON b.id=i.booking_id WHERE i.status = '" + PAID_STATUS + "' AND i.deleted_at IS NULL "
                        + "AND i.created_at >= ? AND i.created_at < ?",
                (rs, rowNum) -> new InvoiceRow(rs.getObject("id", UUID.class),
                        rs.getObject("booking_id", UUID.class),
                        rs.getObject("group_key", UUID.class),
                        rs.getObject("customer_id", UUID.class),
                        rs.getBigDecimal("court_fee"),
                        rs.getBigDecimal("total_amount"),rs.getBigDecimal("product_fee"),rs.getBigDecimal("court_discount")),
                from, to);

        Map<UUID, List<LineRow>> linesByInvoice = new LinkedHashMap<>();
        for (LineRow row : jdbc.query(
                "SELECT d.invoice_id, d.product_id, d.quantity, d.unit_price, d.discount_amount, d.added_by,COALESCE(d.product_type,CASE WHEN d.stock_deducted=TRUE THEN 'GOODS' ELSE NULL END) AS product_type "
                        + "FROM invoice_details d JOIN invoices i ON i.id = d.invoice_id "
                        + "WHERE i.status = '" + PAID_STATUS + "' AND i.deleted_at IS NULL "
                        + "AND i.created_at >= ? AND i.created_at < ?",
                (rs, rowNum) -> new LineRow(rs.getObject("invoice_id", UUID.class),
                        rs.getObject("product_id", UUID.class),
                        rs.getBigDecimal("quantity"),
                        rs.getBigDecimal("unit_price"),
                        rs.getBigDecimal("discount_amount"),rs.getObject("added_by",UUID.class),rs.getString("product_type")),
                from, to)) {
            linesByInvoice.computeIfAbsent(row.invoiceId(), k -> new ArrayList<>()).add(row);
        }

        Map<UUID, List<CourtSlot>> slotsByBooking = new LinkedHashMap<>();
        for (CourtSlot slot : jdbc.query(
                "SELECT bd.booking_id, bd.court_id, bd.price FROM booking_details bd "
                        + "JOIN invoices i ON i.booking_id = bd.booking_id "
                        + "WHERE i.status = '" + PAID_STATUS + "' AND i.deleted_at IS NULL "
                        + "AND i.created_at >= ? AND i.created_at < ?",
                (rs, rowNum) -> new CourtSlot(rs.getObject("booking_id", UUID.class),
                        rs.getObject("court_id", UUID.class), rs.getBigDecimal("price")),
                from, to)) {
            slotsByBooking.computeIfAbsent(slot.bookingId(), k -> new ArrayList<>()).add(slot);
        }

        Map<DetailKey, DetailAccumulator> products = new LinkedHashMap<>();
        Map<UUID, DetailAccumulator> courts = new LinkedHashMap<>();
        BigDecimal invoiceTotal = BigDecimal.ZERO;
        Map<String,BigDecimal> categories=new LinkedHashMap<>(Map.of("GOODS",BigDecimal.ZERO,"SERVICE",BigDecimal.ZERO,"UNKNOWN",BigDecimal.ZERO));
        long unknownLines=0;

        for (InvoiceRow invoice : invoices) {
            invoiceTotal = invoiceTotal.add(invoice.totalAmount());
            List<LineRow> lines = linesByInvoice.getOrDefault(invoice.id(), List.of());
            List<CourtSlot> slots = invoice.bookingId() == null
                    ? List.of()
                    : slotsByBooking.getOrDefault(invoice.bookingId(), List.of());

            List<LineShare> lineShares = new ArrayList<>();
            for (LineRow line : lines) {
                BigDecimal gross = line.quantity().multiply(line.unitPrice()).subtract(line.discountAmount());
                lineShares.add(new LineShare(line, gross));
            }

            BigDecimal courtFee = invoice.courtFee() == null ? BigDecimal.ZERO : invoice.courtFee();
            BigDecimal pool = invoice.totalAmount();

            // Preserve court and product discounts in their own buckets first.
            // Any remaining header discount is invoice-wide and is shared by net values;
            // distribute() places cent rounding residue on the largest bucket.
            BigDecimal lineDiscount=lines.stream().map(LineRow::discountAmount).reduce(BigDecimal.ZERO,BigDecimal::add);
            BigDecimal productBase=invoice.productFee().subtract(lineDiscount);
            BigDecimal courtBase=courtFee.subtract(invoice.courtDiscount());
            if(productBase.signum()<0 || courtBase.signum()<0 || pool.compareTo(productBase.add(courtBase))>0)
                throw new BadRequestException("Invoice discount snapshots require reconciliation: "+invoice.id());
            List<BigDecimal> parts = distribute(pool, List.of(productBase, courtBase));
            BigDecimal productPool = parts.get(0);
            BigDecimal courtPool = parts.get(1);

            List<BigDecimal> productAmounts = distribute(productPool, lineShares.stream()
                    .map(share -> share.weight()).toList());
            if (lineShares.isEmpty() && productPool.signum() != 0) {
                // An invoice without product lines still carries value; keep it visible so the
                // product total keeps matching the invoice total.
                DetailKey key = new DetailKey(null, invoice.staffId(), invoice.customerId());
                DetailAccumulator accumulator = products.computeIfAbsent(key, k -> new DetailAccumulator());
                accumulator.amount = accumulator.amount.add(productPool);
                categories.merge("UNKNOWN",productPool,BigDecimal::add);
            }
            for (int index = 0; index < lineShares.size(); index++) {
                LineShare share = lineShares.get(index);
                String category=share.line().productType();
                if(category==null || !categories.containsKey(category)){category="UNKNOWN";unknownLines++;}
                categories.merge(category,productAmounts.get(index),BigDecimal::add);
                DetailKey key = new DetailKey(share.line().productId(), share.line().addedBy(),
                        invoice.customerId());
                DetailAccumulator accumulator = products.computeIfAbsent(key, k -> new DetailAccumulator());
                accumulator.quantity = accumulator.quantity.add(share.line().quantity());
                accumulator.amount = accumulator.amount.add(productAmounts.get(index));
            }

            List<BigDecimal> courtAmounts;
            List<UUID> courtIds = new ArrayList<>();
            if (slots.isEmpty()) {
                if (courtPool.signum() != 0) {
                    courtAmounts = List.of(courtPool);
                    courtIds.add(null);
                } else {
                    courtAmounts = List.of();
                }
            } else {
                List<BigDecimal> weights = slots.stream().map(slot -> slot.price()).toList();
                courtAmounts = distribute(courtPool, weights);
                slots.forEach(slot -> courtIds.add(slot.courtId()));
            }
            for (int index = 0; index < courtAmounts.size(); index++) {
                UUID courtId = courtIds.get(index);
                DetailAccumulator accumulator = courts.computeIfAbsent(courtId, k -> new DetailAccumulator());
                accumulator.amount = accumulator.amount.add(courtAmounts.get(index));
            }
        }

        List<RevenueBreakdownResponse.ProductDetailRow> productRows = new ArrayList<>();
        BigDecimal productTotal = BigDecimal.ZERO;
        for (Map.Entry<DetailKey, DetailAccumulator> entry : products.entrySet()) {
            DetailKey key = entry.getKey();
            DetailAccumulator accumulator = entry.getValue();
            productTotal = productTotal.add(accumulator.amount);
            productRows.add(new RevenueBreakdownResponse.ProductDetailRow(key.productId(),
                    key.productId() == null ? "Không có dòng hàng"
                            : productNames.getOrDefault(key.productId(), "Sản phẩm đã xóa"),
                    key.staffId(),
                    key.staffId() == null ? UNASSIGNED_STAFF_NAME
                            : staffNames.getOrDefault(key.staffId(), UNASSIGNED_STAFF_NAME),
                    key.customerId(),
                    key.customerId() == null ? WALK_IN_CUSTOMER_NAME
                            : customerNames.getOrDefault(key.customerId(), WALK_IN_CUSTOMER_NAME),
                    Map.of(), Map.of(), accumulator.quantity, accumulator.amount));
        }

        List<RevenueBreakdownResponse.CourtRow> courtRows = new ArrayList<>();
        BigDecimal courtTotal = BigDecimal.ZERO;
        for (Map.Entry<UUID, DetailAccumulator> entry : courts.entrySet()) {
            UUID courtId = entry.getKey();
            DetailAccumulator accumulator = entry.getValue();
            courtTotal = courtTotal.add(accumulator.amount);
            courtRows.add(new RevenueBreakdownResponse.CourtRow(courtId,
                    courtId == null ? UNKNOWN_COURT_NAME
                            : courtNames.getOrDefault(courtId, UNKNOWN_COURT_NAME),
                    Map.of(), accumulator.amount));
        }

        return new PeriodAllocation(productRows, courtRows, productTotal, courtTotal, invoiceTotal,categories,unknownLines);
    }

    /**
     * Splits {@code pool} across the given weights, pushing rounding remainders onto the
     * largest weight so the parts add up to the pool exactly.
     */
    private List<BigDecimal> distribute(BigDecimal pool, List<BigDecimal> weights) {
        if (weights.isEmpty()) {
            return List.of();
        }
        BigDecimal weightTotal = BigDecimal.ZERO;
        for (BigDecimal weight : weights) {
            weightTotal = weightTotal.add(weight.max(BigDecimal.ZERO));
        }
        List<BigDecimal> amounts = new ArrayList<>();
        if (weightTotal.signum() == 0) {
            BigDecimal even = pool.divide(BigDecimal.valueOf(weights.size()), SCALE, RoundingMode.HALF_UP);
            for (int index = 0; index < weights.size(); index++) {
                amounts.add(even);
            }
        } else {
            for (BigDecimal weight : weights) {
                BigDecimal share = pool.multiply(weight.max(BigDecimal.ZERO))
                        .divide(weightTotal, SCALE, RoundingMode.HALF_UP);
                amounts.add(share);
            }
        }
        BigDecimal allocated = BigDecimal.ZERO;
        for (BigDecimal amount : amounts) {
            allocated = allocated.add(amount);
        }
        BigDecimal remainder = pool.subtract(allocated);
        if (remainder.signum() != 0 && !amounts.isEmpty()) {
            int largest = 0;
            if (weightTotal.signum() != 0) {
                for (int index = 1; index < amounts.size(); index++) {
                    if (weights.get(index).compareTo(weights.get(largest)) > 0) {
                        largest = index;
                    }
                }
            }
            amounts.set(largest, amounts.get(largest).add(remainder));
        }
        return amounts;
    }

    private List<Integer> availableYears() {
        List<Integer> years = new ArrayList<>();
        jdbc.query("SELECT DISTINCT SUBSTRING(CAST(created_at AS VARCHAR), 1, 4) AS report_year "
                        + "FROM invoices WHERE status = '" + PAID_STATUS + "' AND deleted_at IS NULL "
                        + "ORDER BY report_year DESC",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> years.add(Integer.parseInt(rs.getString("report_year"))));
        return years;
    }

    private List<RevenueBreakdownResponse.Period> quarters(int year) {
        List<RevenueBreakdownResponse.Period> periods = new ArrayList<>();
        for (int quarter = 1; quarter <= 4; quarter++) {
            int firstMonth = (quarter - 1) * 3 + 1;
            LocalDate from = LocalDate.of(year, firstMonth, 1);
            LocalDate to = from.plusMonths(3).minusDays(1);
            periods.add(new RevenueBreakdownResponse.Period(quarterKey(year, quarter),
                    "Quý " + quarter + "/" + year, from, to));
        }
        return periods;
    }

    private String quarterKey(int year, int quarter) {
        return "Q" + quarter + "/" + year;
    }

    private List<RevenueByKey> staffTotals(LocalDateTime from, LocalDateTime to) {
        // Invoice period matches Statistics. Cashier attribution follows receipts, never invoice.created_by.
        // Online receipts and legacy unexplained balances remain visible in the unassigned row.
        return jdbc.query("""
            WITH paid AS (
                SELECT id,booking_id,parent_invoice_id,total_amount FROM invoices
                WHERE status='PAID' AND deleted_at IS NULL AND created_at>=? AND created_at<?
            ), receipts AS (
                SELECT i.id,p.confirmed_by,p.amount FROM paid i JOIN payment_transactions p
                  ON (p.invoice_id=i.id OR (p.booking_id=i.booking_id AND i.parent_invoice_id IS NULL))
                WHERE p.status='SUCCESS'
            ), parts AS (
                SELECT confirmed_by AS group_key,amount AS revenue FROM receipts
                UNION ALL
                SELECT NULL,i.total_amount-COALESCE(SUM(r.amount),0) FROM paid i
                LEFT JOIN receipts r ON r.id=i.id GROUP BY i.id,i.total_amount
                HAVING i.total_amount<>COALESCE(SUM(r.amount),0)
            ) SELECT group_key,SUM(revenue) AS revenue FROM parts GROUP BY group_key
            """, (rs,n)->new RevenueByKey(rs.getObject("group_key",UUID.class),rs.getBigDecimal("revenue")),from,to);
    }

    private List<RevenueByKey> customerTotals(LocalDateTime from, LocalDateTime to) {
        return jdbc.query(
                "SELECT customer_id AS group_key, SUM(total_amount) AS revenue FROM invoices "
                        + "WHERE status = '" + PAID_STATUS + "' AND deleted_at IS NULL "
                        + "AND created_at >= ? AND created_at < ? "
                        + "GROUP BY customer_id",
                (rs, rowNum) -> new RevenueByKey(rs.getObject("group_key", UUID.class), rs.getBigDecimal("revenue")),
                from, to);
    }

    private Map<UUID, String> staffNames() {
        Map<UUID, String> names = new LinkedHashMap<>();
        jdbc.query("SELECT id, full_name FROM staffs",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> names.put(rs.getObject("id", UUID.class), rs.getString("full_name")));
        return names;
    }

    private Map<UUID, String> customerNames() {
        Map<UUID, String> names = new LinkedHashMap<>();
        jdbc.query("SELECT id, full_name FROM customers WHERE deleted_at IS NULL",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> names.put(rs.getObject("id", UUID.class), rs.getString("full_name")));
        return names;
    }

    private Map<UUID, String> productNames() {
        Map<UUID, String> names = new LinkedHashMap<>();
        jdbc.query("SELECT id, name FROM products WHERE deleted_at IS NULL",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> names.put(rs.getObject("id", UUID.class), rs.getString("name")));
        return names;
    }

    private Map<UUID, String> courtNames() {
        Map<UUID, String> names = new LinkedHashMap<>();
        jdbc.query("SELECT id, name FROM courts WHERE deleted_at IS NULL",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> names.put(rs.getObject("id", UUID.class), rs.getString("name")));
        return names;
    }

    private void fillMissingPeriods(Map<String, BigDecimal> values,
                                    List<RevenueBreakdownResponse.Period> periods) {
        for (RevenueBreakdownResponse.Period period : periods) {
            values.putIfAbsent(period.key(), BigDecimal.ZERO);
        }
    }

    private BigDecimal total(Map<String, BigDecimal> values) {
        BigDecimal sum = BigDecimal.ZERO;
        for (BigDecimal value : values.values()) {
            if (value != null) sum = sum.add(value);
        }
        return sum;
    }

    private record RevenueByKey(UUID key, BigDecimal revenue) {}

    private record InvoiceRow(UUID id, UUID bookingId, UUID staffId, UUID customerId, BigDecimal courtFee,
                              BigDecimal totalAmount, BigDecimal productFee, BigDecimal courtDiscount) {}

    private record LineRow(UUID invoiceId, UUID productId, BigDecimal quantity, BigDecimal unitPrice,
                           BigDecimal discountAmount, UUID addedBy,String productType) {}

    private record LineShare(LineRow line, BigDecimal weight) {}

    private record CourtSlot(UUID bookingId, UUID courtId, BigDecimal price) {}

    private record DetailKey(UUID productId, UUID staffId, UUID customerId) {}

    private static final class DetailAccumulator {
        private BigDecimal quantity = BigDecimal.ZERO;
        private BigDecimal amount = BigDecimal.ZERO;
    }

    private record PeriodAllocation(List<RevenueBreakdownResponse.ProductDetailRow> products,
                                    List<RevenueBreakdownResponse.CourtRow> courts,
                                    BigDecimal productTotal, BigDecimal courtTotal, BigDecimal invoiceTotal,Map<String,BigDecimal> categories,long unknownLines) {}
}
