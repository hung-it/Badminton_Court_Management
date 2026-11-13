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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
    private final com.bcm.repository.BookingRepository bookings;
    private final com.bcm.security.CurrentStaffService currentStaff;
    private final com.bcm.security.WalkInGuestIdentity guest;

    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final com.bcm.repository.PaymentTransactionRepository payments;

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
        Map<UUID, Integer> quantitiesByProduct = aggregateQuantities(request.getItems());
        var enteredCode=productPromotions.validatePosCode(request.getPromotionCode(),quantitiesByProduct);
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
                var discount = productPromotions.best(product, quantity, candidates,enteredCode);
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
        BigDecimal discountAmount = request.getDiscountAmount() == null
                ? BigDecimal.ZERO
                : request.getDiscountAmount();
        if (discountAmount.signum() < 0) {
            throw new BadRequestException("Discount amount cannot be negative");
        }
        if (courtFee.signum() < 0) throw new BadRequestException("Tiền sân không được âm");
        if (discountAmount.signum() > 0 && automaticDiscount.signum() > 0)
            throw new BadRequestException("Không cộng dồn giảm giá khác với khuyến mãi sản phẩm");
        discountAmount = discountAmount.add(automaticDiscount);
        if (discountAmount.compareTo(courtFee.add(productFee)) > 0)
            throw new BadRequestException("Giảm giá không được vượt giá trị hóa đơn");
        String paymentMethod = request.getBookingId()==null ? manualMethod(request.getPaymentMethod()) : null;
        BigDecimal actual=courtFee.add(productFee).subtract(discountAmount);
        if(request.getExpectedTotal()==null || request.getExpectedTotal().compareTo(actual)!=0)
            throw new com.bcm.exception.DuplicateResourceException("Báo giá đã thay đổi; tải lại báo giá và xác nhận lại số tiền.");
        UUID customerId = resolveCustomerId(request.getCustomerId());

        UUID cashierId = currentStaff.currentStaffId().orElse(null);
        Invoice invoice = Invoice.builder()
                .bookingId(request.getBookingId())
                .customerId(customerId)
                .posRequestKey(request.getIdempotencyKey()).requestFingerprint(fingerprint).createdByUser(actor)
                .paymentMethod(paymentMethod)
                .status(request.getBookingId() == null ? "PAID" : "DRAFT")
                .courtFee(courtFee)
                .productFee(productFee)
                .discountAmount(discountAmount)
                .totalAmount(courtFee.add(productFee).subtract(discountAmount))
                .createdBy(cashierId)
                .updatedBy(cashierId)
                .build();
        Invoice savedInvoice = invoiceRepository.saveAndFlush(invoice);

        invoiceDetails.forEach(detail -> detail.setInvoiceId(savedInvoice.getId()));
        invoiceDetailRepository.saveAll(invoiceDetails);
        if(request.getBookingId()==null) recordReceipt(savedInvoice,null,savedInvoice.getTotalAmount(),paymentMethod,request.getReference());
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
        var enteredCode=productPromotions.validatePosCode(request.getPromotionCode(),aggregateQuantities(request.getItems()));
        List<ProductPromotionRule> candidates = productPromotions.applicable();
        List<PosQuoteResponse.Line> lines = new ArrayList<>();
        BigDecimal gross = BigDecimal.ZERO;
        BigDecimal discounts = BigDecimal.ZERO;
        for (var entry : aggregateQuantities(request.getItems()).entrySet()) {
            Product product = productRepository.findByIdAndDeletedAtIsNull(entry.getKey())
                    .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy sản phẩm"));
            productPrices.requireApproved(product);
            int quantity = entry.getValue();
            if (product.getType() == ProductType.GOODS && product.getStockQuantity() < quantity)
                throw new BadRequestException(INSUFFICIENT_STOCK_MESSAGE);
            BigDecimal amount = product.getPrice().multiply(BigDecimal.valueOf(quantity));
            var discount = productPromotions.best(product, quantity, candidates,enteredCode);
            gross = gross.add(amount);
            discounts = discounts.add(discount.amount());
            lines.add(new PosQuoteResponse.Line(product.getId(), product.getName(), quantity, product.getPrice(),
                    amount, discount.amount(), amount.subtract(discount.amount()), discount.promotionId(), discount.name(), discount.percent()));
        }
        BigDecimal court = request.getCourtFee() == null ? BigDecimal.ZERO : request.getCourtFee();
        BigDecimal manual = request.getDiscountAmount() == null ? BigDecimal.ZERO : request.getDiscountAmount();
        if (court.signum() < 0 || manual.signum() < 0) throw new BadRequestException("Số tiền không được âm");
        if (manual.signum() > 0 && discounts.signum() > 0)
            throw new BadRequestException("Không cộng dồn giảm giá khác với khuyến mãi sản phẩm");
        discounts = discounts.add(manual);
        if (discounts.compareTo(court.add(gross)) > 0) throw new BadRequestException("Giảm giá vượt giá trị hóa đơn");
        return new PosQuoteResponse(gross, court, discounts, court.add(gross).subtract(discounts), lines);
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

    private UUID resolveCustomerId(UUID requestedCustomerId) {
        if(requestedCustomerId!=null) return customerRepository.findById(requestedCustomerId)
            .filter(c->c.getDeletedAt()==null).orElseThrow(()->new ResourceNotFoundException("Customer not found")).getId();
        return guest.customerId();
    }

    public String manualMethod(String method) {
        String value=method==null?"":method.trim().toUpperCase(java.util.Locale.ROOT);
        if(!java.util.Set.of("CASH","VNPAY").contains(value)) throw new BadRequestException("Manual receipt requires CASH or VNPAY");
        return value;
    }

    // Caller holds the booking or POS idempotency lock and owns the transaction.
    @Transactional(Transactional.TxType.MANDATORY)
    public void recordReceipt(Invoice invoice, com.bcm.entity.Booking booking, BigDecimal amount, String method, String reference) {
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
        receipt.setReference(reference==null||reference.isBlank()?null:reference.trim());
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
        String value=actor+"|"+r.getCustomerId()+"|"+r.getBookingId()+"|"+r.getPaymentMethod()+"|"+r.getCourtFee()+"|"+r.getDiscountAmount()+"|"+r.getPromotionCode()+"|"+r.isPaymentConfirmed()+"|"+r.getReference()+"|"+r.getExpectedTotal()+"|"+new java.util.TreeMap<>(aggregateQuantities(r.getItems()));
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        catch(java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
}
