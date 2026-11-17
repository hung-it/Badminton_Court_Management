package com.bcm.service;

import com.bcm.dto.request.PosSaleItemRequest;
import com.bcm.dto.request.PosSaleRequest;
import com.bcm.dto.response.PosQuoteResponse;
import com.bcm.entity.ProductPromotionRule;
import com.bcm.entity.Invoice;
import com.bcm.entity.InvoiceDetail;
import com.bcm.entity.Product;
import com.bcm.entity.ProductType;
import com.bcm.exception.BadRequestException;
import com.bcm.exception.ResourceNotFoundException;
import com.bcm.repository.CustomerRepository;
import com.bcm.repository.InvoiceDetailRepository;
import com.bcm.repository.InvoiceRepository;
import com.bcm.repository.ProductRepository;
import jakarta.persistence.OptimisticLockException;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PosService {

    private static final String INSUFFICIENT_STOCK_MESSAGE = "Kho không đủ số lượng";

    private final ProductRepository productRepository;
    private final InvoiceRepository invoiceRepository;
    private final InvoiceDetailRepository invoiceDetailRepository;
    private final CustomerRepository customerRepository;
    private final ProductPromotionService productPromotions;
    private final ProductPriceService productPrices;
    private final CourtPromotionService courtPromotions;
    private final com.bcm.repository.BookingRepository bookings;
    private final com.bcm.security.CurrentStaffService currentStaff;

    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final com.bcm.repository.PaymentTransactionRepository payments;

    public record PosInvoiceDiscountResult(UUID promotionId, UUID ruleId, String code, String name, BigDecimal discountAmount, boolean isVoucher) {}

    private PosInvoiceDiscountResult evaluateInvoiceDiscount(String rawCode, UUID customerId, UUID bookingId, BigDecimal eligibleAmount) {
        if (eligibleAmount == null || eligibleAmount.signum() <= 0) return null;
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh"));

        // 1. Evaluate automatic invoice-level discount (Theo hóa đơn)
        Map<String, Object> autoRule = courtPromotions.bestAutomaticRule(eligibleAmount, today);
        BigDecimal autoDiscount = autoRule != null ? courtPromotions.amount(autoRule, eligibleAmount) : BigDecimal.ZERO;

        // 2. If a voucher code was entered, evaluate the voucher
        if (rawCode != null && !rawCode.isBlank()) {
            String code = rawCode.trim().toUpperCase(Locale.ROOT);
            var rule = courtPromotions.voucher(code, today);
            if (rule == null) {
                throw new BadRequestException("Mã voucher không tồn tại hoặc không còn hiệu lực");
            }
            UUID ruleId = (UUID) rule.get("id");
            UUID promotionId = (UUID) rule.get("promotion_id");
            String promoName = (String) rule.get("promotion_name");

            if (customerId == null && "KMKH10".equals(code))
                throw new BadRequestException("Bạn chưa đủ điều kiện sử dụng mã KMKH10");
            if (customerId != null) {
                if ("NEWMEMBER".equals(code)) {
                    long used = jdbc.queryForObject("SELECT count(*) FROM bookings b WHERE b.customer_id=? AND b.status IN ('PAID','COMPLETED') AND EXISTS (SELECT 1 FROM payment_transactions p WHERE p.booking_id=b.id AND p.status='SUCCESS')", Long.class, customerId);
                    long posUsage = jdbc.queryForObject("SELECT count(*) FROM customer_voucher_usage u JOIN discount_rules d ON d.id=u.discount_rule_id JOIN invoices i ON i.id=u.invoice_id WHERE u.customer_id=? AND d.voucher_code='NEWMEMBER' AND " + CourtPromotionService.POS_USAGE_RESERVATION, Long.class, customerId);
                    if (used > 0 || posUsage > 0) {
                        throw new BadRequestException("Bạn không còn đủ điều kiện NEWMEMBER vì đã đặt sân thành công hoặc đã dùng ưu đãi người mới.");
                    }
                } else if ("KMKH10".equals(code)) {
                    long count = jdbc.queryForObject("SELECT count(*) FROM bookings b WHERE b.customer_id=? AND b.status='COMPLETED' AND EXISTS (SELECT 1 FROM payment_transactions p WHERE p.booking_id=b.id AND p.status='SUCCESS')", Long.class, customerId);
                    if (count < 15) {
                        throw new BadRequestException("Bạn chưa đủ điều kiện sử dụng mã KMKH10");
                    }
                }
                String limitsMsg = courtPromotions.limits(rule, customerId, null, code, LocalDateTime.now());
                if (limitsMsg != null) {
                    throw new BadRequestException(limitsMsg);
                }
            } else {
                Number maxTotal = (Number) rule.get("max_total_usage");
                if (maxTotal != null) {
                    String where = " FROM bookings b WHERE b.promotion_code=? AND ((b.status='PENDING' AND " + CourtPromotionService.PENDING_RESERVATION + ") OR (b.status IN ('PAID','COMPLETED') AND EXISTS (SELECT 1 FROM payment_transactions t WHERE t.booking_id=b.id AND t.status IN ('SUCCESS','PENDING'))))";
                    long all = jdbc.queryForObject("SELECT count(*)" + where, Long.class, code, LocalDateTime.now());
                    long pos = jdbc.queryForObject("SELECT count(*) FROM customer_voucher_usage u JOIN invoices i ON i.id=u.invoice_id WHERE u.discount_rule_id=? AND " + CourtPromotionService.POS_USAGE_RESERVATION, Long.class, ruleId);
                    if (all + pos >= maxTotal.longValue()) {
                        throw new BadRequestException("Voucher đã hết lượt sử dụng");
                    }
                }
            }

            BigDecimal minInvoice = (BigDecimal) rule.get("min_invoice_amount");
            if (minInvoice != null && eligibleAmount.compareTo(minInvoice) < 0) {
                throw new BadRequestException("Đơn hàng chưa đạt giá trị tối thiểu của voucher");
            }

            BigDecimal val = (BigDecimal) rule.get("discount_value");
            BigDecimal voucherDiscount;
            if ("PERCENT".equals(rule.get("discount_type"))) {
                voucherDiscount = eligibleAmount.multiply(val).divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
            } else {
                voucherDiscount = val;
            }
            BigDecimal maxDiscount = (BigDecimal) rule.get("max_discount_amount");
            if (maxDiscount != null) {
                voucherDiscount = voucherDiscount.min(maxDiscount);
            }
            voucherDiscount = voucherDiscount.max(BigDecimal.ZERO).min(eligibleAmount);

            // Business rule: between Theo hóa đơn and Voucher, choose the better discount
            if (voucherDiscount.compareTo(autoDiscount) >= 0) {
                return new PosInvoiceDiscountResult(promotionId, ruleId, code, promoName != null ? promoName : code, voucherDiscount, true);
            } else {
                return new PosInvoiceDiscountResult((UUID) autoRule.get("promotion_id"), (UUID) autoRule.get("id"),
                        (String) autoRule.get("promotion_code"), (String) autoRule.get("promotion_name"), autoDiscount, false);
            }
        }

        // 3. No voucher code was entered: automatically use best INVOICE_TOTAL if applicable
        if (autoDiscount.signum() > 0) {
            return new PosInvoiceDiscountResult((UUID) autoRule.get("promotion_id"), (UUID) autoRule.get("id"),
                    (String) autoRule.get("promotion_code"), (String) autoRule.get("promotion_name"), autoDiscount, false);
        }

        return null;
    }

    private UUID resolveQuoteCustomerId(PosSaleRequest request) {
        if (request.getBookingId() != null) return request.getCustomerId();
        if (request.getCustomerId() != null) return request.getCustomerId();
        if (request.getCustomerPhone() != null && !request.getCustomerPhone().isBlank()) {
            String phone = request.getCustomerPhone().replaceAll("[^0-9]", "");
            if (phone.matches("[0-9]{8,15}") && !phone.matches("0+")) {
                var matches = buyerMatches(phone);
                if (!matches.isEmpty()) return matches.get(0);
            }
        }
        return null;
    }

    @Transactional
    public Invoice sell(PosSaleRequest request) {
        UUID actor=currentStaff.requireUserId();
        if(request.getIdempotencyKey()==null) throw new BadRequestException("idempotencyKey is required");
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", rs -> { }, request.getIdempotencyKey().toString());
        String fingerprint=fingerprint(request,actor);
        var previous=invoiceRepository.findByPosRequestKey(request.getIdempotencyKey());
        if(previous.isPresent()) {
            if(!fingerprint.equals(previous.get().getRequestFingerprint()))
                throw new com.bcm.exception.DuplicateResourceException("Idempotency key reused with different sale");
            return previous.get();
        }
        validateBooking(request);
        if(request.getBookingId()==null && !request.isPaymentConfirmed())
            throw new BadRequestException("Confirm receipt of payment before creating a paid sale");
        // Booking -> campaign -> buyer -> products: match booking voucher lock order.
        // Acquire before buyer locks so a competing sale cannot deadlock on invoice FKs.
        if(request.getPromotionCode()!=null && !request.getPromotionCode().isBlank())
            courtPromotions.voucher(request.getPromotionCode().trim().toUpperCase(Locale.ROOT),
                LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh")));
        UUID customerId = resolveCustomerId(request);
        Map<UUID, Integer> quantitiesByProduct = aggregateQuantities(request.getItems());
        List<InvoiceDetail> invoiceDetails = new ArrayList<>();
        BigDecimal productFee = BigDecimal.ZERO;
        BigDecimal automaticDiscount = BigDecimal.ZERO;
        List<ProductPromotionRule> candidates = productPromotions.applicable();

        try {
            for (Map.Entry<UUID, Integer> entry : new java.util.TreeMap<>(quantitiesByProduct).entrySet()) {
                Product product = productRepository.findByIdForPriceApproval(entry.getKey())
                        .filter(p -> p.getDeletedAt() == null)
                        .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy sản phẩm"));
                productPrices.requireApproved(product);
                int quantity = entry.getValue();

                // Only GOODS products have physical inventory; SERVICE products are non-stocked
                if (product.getType() == ProductType.GOODS) {
                    if (product.getStockQuantity() < quantity) {
                        throw new BadRequestException(INSUFFICIENT_STOCK_MESSAGE);
                    }
                    product.setStockQuantity(product.getStockQuantity() - quantity);
                    productRepository.saveAndFlush(product);
                }

                BigDecimal lineAmount = product.getPrice().multiply(BigDecimal.valueOf(quantity));
                productFee = productFee.add(lineAmount);
                var discount = productPromotions.best(product, quantity, candidates);
                automaticDiscount = automaticDiscount.add(discount.amount());
                invoiceDetails.add(InvoiceDetail.builder()
                        .productId(product.getId()).productName(product.getName()).productType(product.getType())
                        .addedBy(currentStaff.currentStaffId().orElse(null)).addedByUser(actor)
                        .stockDeducted(product.getType()==ProductType.GOODS)
                        .quantity(quantity)
                        .unitPrice(product.getPrice())
                        .discountAmount(discount.amount()).promotionId(discount.promotionId())
                        .promotionName(discount.name()).discountPercent(discount.percent())
                        .build());
            }
        } catch (ObjectOptimisticLockingFailureException | OptimisticLockException exception) {
            throw new BadRequestException(INSUFFICIENT_STOCK_MESSAGE);
        }

        BigDecimal courtFee = request.getCourtFee() == null
                ? BigDecimal.ZERO
                : request.getCourtFee();
        if (courtFee.signum() < 0) throw new BadRequestException("Tiền sân không được âm");

        BigDecimal eligibleAmount = productFee.subtract(automaticDiscount).max(BigDecimal.ZERO);
        var discountResult = evaluateInvoiceDiscount(request.getPromotionCode(), customerId, request.getBookingId(), eligibleAmount);
        BigDecimal invoiceDiscount = discountResult != null ? discountResult.discountAmount() : BigDecimal.ZERO;
        BigDecimal totalDiscount = automaticDiscount.add(invoiceDiscount);

        BigDecimal manualDiscount = request.getDiscountAmount() == null ? BigDecimal.ZERO : request.getDiscountAmount();
        if (manualDiscount.signum() < 0) {
            throw new BadRequestException("Discount amount cannot be negative");
        }
        totalDiscount = totalDiscount.add(manualDiscount);
        if (totalDiscount.compareTo(courtFee.add(productFee)) > 0)
            throw new BadRequestException("Giảm giá không được vượt giá trị hóa đơn");

        String paymentMethod = request.getBookingId()==null ? manualMethod(request.getPaymentMethod()) : null;
        BigDecimal actual = courtFee.add(productFee).subtract(totalDiscount).max(BigDecimal.ZERO);
        if(request.getExpectedTotal()==null || request.getExpectedTotal().compareTo(actual)!=0)
            throw new com.bcm.exception.DuplicateResourceException("Báo giá đã thay đổi; tải lại báo giá và xác nhận lại số tiền.");

        UUID cashierId = currentStaff.currentStaffId().orElse(null);
        Invoice invoice = Invoice.builder()
                .bookingId(request.getBookingId())
                .customerId(customerId)
                .buyerPhone(request.getBookingId()==null?request.getCustomerPhone().replaceAll("[^0-9]",""):null)
                .posRequestKey(request.getIdempotencyKey()).requestFingerprint(fingerprint).createdByUser(actor)
                .paymentMethod(paymentMethod)
                .status(request.getBookingId() == null ? "PAID" : "DRAFT")
                .courtFee(courtFee)
                .productFee(productFee)
                .discountAmount(totalDiscount)
                .totalAmount(actual)
                .promotionId(discountResult != null ? discountResult.promotionId() : null)
                .createdBy(cashierId)
                .updatedBy(cashierId)
                .build();
        Invoice savedInvoice = invoiceRepository.saveAndFlush(invoice);

        invoiceDetails.forEach(detail -> detail.setInvoiceId(savedInvoice.getId()));
        invoiceDetailRepository.saveAll(invoiceDetails);
        if(request.getBookingId()==null) {
            recordReceipt(savedInvoice,null,savedInvoice.getTotalAmount(),paymentMethod);
        }
        // Reserve on DRAFT too; cancellation releases the reservation through invoice status.
        if(discountResult != null && discountResult.isVoucher()) {
            jdbc.update("INSERT INTO customer_voucher_usage (id, customer_id, discount_rule_id, invoice_id, used_at) VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)",
                UUID.randomUUID(), customerId, discountResult.ruleId(), savedInvoice.getId());
        }
        return savedInvoice;
    }

    private Map<UUID, Integer> aggregateQuantities(List<PosSaleItemRequest> items) {
        Map<UUID, Integer> quantitiesByProduct = new LinkedHashMap<>();
        for (PosSaleItemRequest item : items) {
            try {
                quantitiesByProduct.merge(item.getProductId(), item.getQuantity(), Math::addExact);
            } catch (ArithmeticException ex) { throw new BadRequestException("Số lượng vượt giới hạn cho phép"); }
        }
        return quantitiesByProduct;
    }

    @org.springframework.transaction.annotation.Transactional
    public PosQuoteResponse quote(PosSaleRequest request) {
        validateBooking(request);
        List<ProductPromotionRule> candidates = productPromotions.applicable();
        List<PosQuoteResponse.Line> lines = new ArrayList<>();
        BigDecimal gross = BigDecimal.ZERO;
        BigDecimal automaticDiscount = BigDecimal.ZERO;
        for (var entry : aggregateQuantities(request.getItems()).entrySet()) {
            Product product = productRepository.findByIdAndDeletedAtIsNull(entry.getKey())
                    .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy sản phẩm"));
            productPrices.requireApproved(product);
            int quantity = entry.getValue();
            if (product.getType() == ProductType.GOODS && product.getStockQuantity() < quantity)
                throw new BadRequestException(INSUFFICIENT_STOCK_MESSAGE);
            BigDecimal amount = product.getPrice().multiply(BigDecimal.valueOf(quantity));
            var discount = productPromotions.best(product, quantity, candidates);
            gross = gross.add(amount);
            automaticDiscount = automaticDiscount.add(discount.amount());
            lines.add(new PosQuoteResponse.Line(product.getId(), product.getName(), quantity, product.getPrice(),
                    amount, discount.amount(), amount.subtract(discount.amount()), discount.promotionId(), discount.name(), discount.percent()));
        }
        BigDecimal eligibleAmount = gross.subtract(automaticDiscount).max(BigDecimal.ZERO);
        UUID quoteCustId = resolveQuoteCustomerId(request);
        var discountResult = evaluateInvoiceDiscount(request.getPromotionCode(), quoteCustId, request.getBookingId(), eligibleAmount);
        BigDecimal invoiceDiscount = discountResult != null ? discountResult.discountAmount() : BigDecimal.ZERO;
        BigDecimal totalDiscount = automaticDiscount.add(invoiceDiscount);

        BigDecimal court = request.getCourtFee() == null ? BigDecimal.ZERO : request.getCourtFee();
        BigDecimal manual = request.getDiscountAmount() == null ? BigDecimal.ZERO : request.getDiscountAmount();
        if (court.signum() < 0 || manual.signum() < 0) throw new BadRequestException("Số tiền không được âm");
        totalDiscount = totalDiscount.add(manual);
        if (totalDiscount.compareTo(court.add(gross)) > 0) throw new BadRequestException("Giảm giá vượt giá trị hóa đơn");
        String promoName = discountResult != null ? discountResult.name() : null;
        String promoCode = discountResult != null ? discountResult.code() : null;
        return new PosQuoteResponse(gross, court, totalDiscount, court.add(gross).subtract(totalDiscount).max(BigDecimal.ZERO), lines, promoName, promoCode);
    }

    private void validateBooking(PosSaleRequest request) {
        if((request.getCourtFee()!=null && request.getCourtFee().signum()!=0) || (request.getDiscountAmount()!=null && request.getDiscountAmount().signum()!=0))
            throw new BadRequestException("POS không nhập lại tiền sân hoặc giảm giá thủ công; chỉ dùng ưu đãi sản phẩm hệ thống.");
        if(request.getBookingId()==null) return;
        var b=bookings.findByIdForExpiration(request.getBookingId()).orElseThrow(()->new ResourceNotFoundException("Không tìm thấy booking"));
        if(b.getStatus()!=com.bcm.entity.BookingStatus.PAID) throw new BadRequestException("Chỉ thêm hàng cho booking đã thanh toán, chưa kết toán");
        if((request.getCourtFee()!=null && request.getCourtFee().signum()!=0) || (request.getDiscountAmount()!=null && request.getDiscountAmount().signum()!=0)) throw new BadRequestException("Tiền sân và ưu đãi sân đã chốt trên booking; không nhập lại tại POS");
        request.setCustomerId(b.getCustomer().getId());
    }

    private UUID resolveCustomerId(PosSaleRequest request) {
        if(request.getBookingId()!=null)return request.getCustomerId();
        String phone=buyerPhone(request.getCustomerPhone());
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",rs->{},"buyer:"+phone);
        var matches=buyerMatches(phone);
        UUID selected=request.getCustomerId();
        if(selected!=null&&!matches.contains(selected))throw new com.bcm.exception.DuplicateResourceException("Khách đã thay đổi hoặc không khớp SĐT; hãy tra cứu lại");
        if(selected==null&&matches.size()>1)throw new com.bcm.exception.DuplicateResourceException("Số điện thoại có nhiều hồ sơ; hãy chọn khách trước khi bán");
        if(selected==null&&!matches.isEmpty())selected=matches.get(0);
        if(selected!=null){
            var owners=jdbc.queryForList("select user_id from customers where id=? and user_id is not null",UUID.class,selected);
            if(!owners.isEmpty())jdbc.queryForList("select id from users where id=? for update",UUID.class,owners.get(0));
            jdbc.queryForList("select id from customers where id=? for update",UUID.class,selected);
            if(!buyerMatches(phone).contains(selected))throw new com.bcm.exception.DuplicateResourceException("Khách đã thay đổi hoặc bị khóa; hãy tra cứu lại");
            return selected;
        }
        UUID id=UUID.randomUUID();
        jdbc.update("INSERT INTO customers(id,user_id,full_name,phone,created_at,updated_at) VALUES (?,NULL,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",id,"Khách vãng lai "+phone,phone);
        return id;
    }

    private String buyerPhone(String value) {
        String phone=value==null?"":value.replaceAll("[^0-9]","");
        if(!phone.matches("[0-9]{8,15}")||phone.matches("0+"))throw new BadRequestException("Nhập số điện thoại khách mua hàng hợp lệ");
        return phone;
    }

    private List<UUID> buyerMatches(String phone) {
        return jdbc.queryForList("SELECT c.id FROM customers c LEFT JOIN users u ON u.id=c.user_id WHERE regexp_replace(c.phone,'[^0-9]','','g')=? AND c.deleted_at IS NULL AND (c.user_id IS NULL OR u.deleted_at IS NULL) ORDER BY c.user_id NULLS LAST,c.id",UUID.class,phone);
    }

    @org.springframework.transaction.annotation.Transactional(readOnly=true)
    public List<Map<String,Object>> buyers(String phone) {
        String sql="SELECT c.id,c.full_name AS \"fullName\",c.phone,CASE WHEN c.user_id IS NULL THEN 'WALK_IN' ELSE 'REGISTERED' END AS kind FROM customers c LEFT JOIN users u ON u.id=c.user_id WHERE c.deleted_at IS NULL AND (c.user_id IS NULL OR u.deleted_at IS NULL) AND c.phone IS NOT NULL";
        if(phone==null)return jdbc.queryForList(sql+" ORDER BY c.full_name,c.id");
        return jdbc.queryForList(sql+" AND regexp_replace(c.phone,'[^0-9]','','g')=? ORDER BY c.user_id NULLS LAST,c.id",buyerPhone(phone));
    }

    public String manualMethod(String method) {
        String value=method==null?"":method.trim().toUpperCase(java.util.Locale.ROOT);
        if(!java.util.Set.of("CASH","VNPAY").contains(value)) throw new BadRequestException("Manual receipt requires CASH or VNPAY");
        return value;
    }

    // Caller holds the booking or POS idempotency lock and owns the transaction.
    @Transactional(Transactional.TxType.MANDATORY)
    public void recordReceipt(Invoice invoice, com.bcm.entity.Booking booking, BigDecimal amount, String method) {
        if(amount.signum()==0) return; // A free/fully prepaid invoice needs no artificial receipt.
        if(amount.signum()<0) throw new BadRequestException("Negative receipt");
        UUID actor=currentStaff.requireUserId();
        var receipt=new com.bcm.entity.PaymentTransaction();
        receipt.setInvoice(invoice); receipt.setBooking(booking);
        receipt.setPaymentMethod(com.bcm.entity.PaymentMethod.valueOf(manualMethod(method)));
        receipt.setSource("MANUAL"); receipt.setStatus(com.bcm.entity.PaymentStatus.SUCCESS);
        receipt.setAmount(amount); receipt.setConfirmedBy(currentStaff.currentStaffId().orElse(null));
        receipt.setConfirmedByUser(actor); receipt.setTransactionDate(java.time.LocalDateTime.now());
        receipt.setTransactionId("COUNTER-"+UUID.randomUUID());
        receipt.setNote("STAFF_RECEIVED; manual confirmation");
        payments.saveAndFlush(receipt);
    }

    @Transactional
    public Invoice cancelDraft(UUID id) {
        currentStaff.requireUserId();
        // Booking -> Invoice -> Products is the same ordering as POS append and Checkout.
        var headers=jdbc.query("SELECT booking_id FROM invoices WHERE id=?",(rs,n)->java.util.Optional.ofNullable(rs.getObject(1,UUID.class)),id);
        if(headers.isEmpty()) throw new ResourceNotFoundException("Invoice not found");
        headers.get(0).ifPresent(bookingId->bookings.findByIdForExpiration(bookingId).orElseThrow());
        var invoice=invoiceRepository.findForUpdate(id).orElseThrow();
        if(invoice.getParentInvoiceId()!=null) throw new BadRequestException("Merged draft cannot be cancelled separately");
        if("CANCELLED".equals(invoice.getStatus())) return invoice;
        if(!"DRAFT".equals(invoice.getStatus())) throw new BadRequestException("Only DRAFT can be cancelled");
        var lines=invoiceDetailRepository.findByInvoiceId(id);
        for(var line:lines.stream().sorted(java.util.Comparator.comparing(InvoiceDetail::getProductId)).toList()) {
            if(line.getStockDeducted()==null) throw new BadRequestException("Legacy inventory provenance requires review");
            if(Boolean.TRUE.equals(line.getStockDeducted())) {
                var product=productRepository.findByIdForPriceApproval(line.getProductId()).orElseThrow();
                if (product.getType()!=ProductType.GOODS)
                    throw new com.bcm.exception.DuplicateResourceException("Inventory type changed; reconcile draft before restoring stock");
                product.setStockQuantity(Math.addExact(product.getStockQuantity(),line.getQuantity()));
                line.setStockDeducted(false);
            }
        }
        invoice.setStatus("CANCELLED"); invoice.setUpdatedBy(currentStaff.currentStaffId().orElse(null));
        return invoice;
    }

    private String fingerprint(PosSaleRequest r,UUID actor) {
        String value=actor+"|"+r.getCustomerPhone()+"|"+r.getCustomerId()+"|"+r.getBookingId()+"|"+r.getPaymentMethod()+"|"+r.getCourtFee()+"|"+r.getDiscountAmount()+"|"+r.getPromotionCode()+"|"+r.isPaymentConfirmed()+"|"+r.getExpectedTotal()+"|"+new java.util.TreeMap<>(aggregateQuantities(r.getItems()));
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        catch(java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
}
