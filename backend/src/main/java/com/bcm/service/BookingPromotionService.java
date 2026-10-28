package com.bcm.service;

import com.bcm.dto.response.BookingPromotionResponse;
import com.bcm.entity.Booking;
import com.bcm.entity.BookingStatus;
import com.bcm.exception.DuplicateResourceException;
import com.bcm.exception.ResourceNotFoundException;
import com.bcm.repository.BookingRepository;
import com.bcm.repository.PaymentTransactionRepository;
import com.bcm.security.CurrentCustomerService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.UUID;
import java.util.Set;

@Service
public class BookingPromotionService {
    // A booking header confirms the reservation; only a verified full receipt earns paid history.
    private static final String VERIFIED_RECEIPT = "p.status='SUCCESS' AND p.amount=b.final_amount "
            + "AND p.transaction_id IS NOT NULL AND p.transaction_date IS NOT NULL "
            + "AND (p.note IS NULL OR p.note NOT LIKE 'CUSTOMER_DECLARED;%')";
    private final BookingRepository bookings;
    private final PaymentTransactionRepository payments;
    private final CurrentCustomerService customer;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final CourtPromotionService courtPromotions;

    public BookingPromotionService(BookingRepository bookings, PaymentTransactionRepository payments,
            CurrentCustomerService customer, JdbcTemplate jdbc, @Qualifier("bookingExpirationClock") Clock clock, CourtPromotionService courtPromotions) {
        this.courtPromotions=courtPromotions;
        this.bookings=bookings; this.payments=payments; this.customer=customer; this.jdbc=jdbc; this.clock=clock;
    }

    public void automatic(Booking booking) {
        booking.setDiscountAmount(courtPromotions.automatic(booking.getCourtFee(),LocalDate.now(clock)));
        booking.setPromotionCode(booking.getDiscountAmount().signum()>0?"INVOICE_TOTAL":null);
        booking.setRequestedVoucher(null);
    }

    @Transactional
    public BookingPromotionResponse quote(UUID id, String code, boolean apply) {
        UUID owner=customer.requireCustomerId();
        var booking=bookings.findByIdForExpiration(id).orElseThrow(() -> new ResourceNotFoundException("Booking not found"));
        if (!owner.equals(booking.getCustomer().getId())) throw new AccessDeniedException("Booking not owned");
        requireEditable(booking);
        if (payments.existsByBookingId(id)) throw new DuplicateResourceException("PAYMENT_ATTEMPT_EXISTS: Ưu đãi đã khóa sau khi khởi tạo thanh toán.");
        var result=calculate(booking, code);
        requireEditable(booking); // Recheck after waiting on the customer lock.
        if (apply) {
            booking.setDiscountAmount(result.discountAmount()); booking.setPromotionCode(result.promotionCode());
            booking.setRequestedVoucher(result.requestedVoucher());
        }
        return result;
    }

    @Transactional
    public BookingPromotionResponse staffApply(UUID id,String code) {
        var booking=bookings.findByIdForExpiration(id).orElseThrow(()->new ResourceNotFoundException("Booking not found"));
        requireEditable(booking);
        if(payments.existsByBookingId(id)) throw new DuplicateResourceException("Ưu đãi đã khóa sau khi khởi tạo thanh toán");
        var result=calculate(booking,code); requireEditable(booking);
        booking.setDiscountAmount(result.discountAmount()); booking.setPromotionCode(result.promotionCode()); booking.setRequestedVoucher(result.requestedVoucher());
        return result;
    }

    @Transactional
    public BigDecimal staffReceive(UUID id,String method) {
        if(!Set.of("CASH","BANK_TRANSFER").contains(method)) throw new com.bcm.exception.BadRequestException("Thu tại quầy chỉ hỗ trợ tiền mặt hoặc chuyển khoản đã xác nhận");
        var booking=bookings.findByIdForExpiration(id).orElseThrow(()->new ResourceNotFoundException("Booking not found"));
        if(booking.getStatus()==BookingStatus.PAID) {
            long received=jdbc.queryForObject("SELECT count(*) FROM payment_transactions WHERE booking_id=? AND status='SUCCESS' AND note LIKE 'STAFF_RECEIVED;%'",Long.class,id);
            if(received>0) return booking.getFinalAmount();
            throw new DuplicateResourceException("Booking đã thanh toán; không thu lại tiền sân");
        }
        requireEditable(booking);
        if(payments.existsByBookingId(id)) throw new DuplicateResourceException("Booking đã có giao dịch; cần xử lý theo luồng thanh toán hiện có");
        validatePayment(booking);
        String actor=org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication().getName();
        jdbc.update("INSERT INTO payment_transactions(id,booking_id,payment_method,status,amount,transaction_id,transaction_date,note) VALUES (?,?,?,'SUCCESS',?,?,?,?)",UUID.randomUUID(),id,method,booking.getFinalAmount(),"COUNTER-"+UUID.randomUUID(),LocalDateTime.now(clock),"STAFF_RECEIVED;"+actor);
        booking.setStatus(BookingStatus.PAID);
        return booking.getFinalAmount();
    }

    // Called under the existing Booking lock inside the payment transaction. Customer lock serializes
    // NEWMEMBER apply/retry on different bookings. No other Booking row is locked from here.
    public void validatePayment(Booking booking) {
        if (booking.getPromotionCode()==null || "INVOICE_TOTAL".equals(booking.getPromotionCode())) return;
        var quote=calculate(booking,booking.getPromotionCode());
        requireEditable(booking);
        if (!quote.voucherValid() || !java.util.Objects.equals(quote.promotionCode(),booking.getPromotionCode())
                || quote.discountAmount().compareTo(booking.getDiscountAmount())!=0)
            throw new DuplicateResourceException("Ưu đãi đã thay đổi hoặc không còn đủ điều kiện. " + quote.message());
    }

    public BookingPromotionResponse calculate(Booking booking, String raw) {
        String code=raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        BigDecimal subtotal=booking.getCourtFee(), discount=courtPromotions.automatic(subtotal,LocalDate.now(clock));
        String selected=discount.signum()>0 ? "INVOICE_TOTAL" : null;
        boolean valid=false; String message="";
        if (!code.isEmpty()) {
            var rule=courtPromotions.voucher(code,LocalDate.now(clock));
            if (rule==null) {
                message="Mã khuyến mãi không tồn tại hoặc không còn hiệu lực.";
            } else {
                message=Set.of("NEWMEMBER","KMKH10").contains(code)?eligibility(booking,code):null;
                if(message==null) message=courtPromotions.limits(rule,booking.getCustomer().getId(),booking.getId(),code,LocalDateTime.now(clock));
                if(message==null && subtotal.compareTo((BigDecimal)(rule.get("min_invoice_amount")==null?BigDecimal.ZERO:rule.get("min_invoice_amount")))<0) message="Tiền sân chưa đạt mức tối thiểu của voucher";
                valid=message==null;
                if (valid) {
                    BigDecimal amount=courtPromotions.amount(rule,subtotal);
                    if (amount.compareTo(discount)>0) { discount=amount; selected=code; message="Đã áp dụng " + code + "."; }
                    else message="Mã hợp lệ, nhưng ưu đãi theo hóa đơn đang giúp bạn tiết kiệm hơn.";
                }
            }
        }
        return new BookingPromotionResponse(subtotal,discount,subtotal.subtract(discount),selected,
                valid ? code : null,valid,message);
    }

    private String eligibility(Booking booking, String code) {
        UUID owner=booking.getCustomer().getId();
        jdbc.queryForObject("SELECT id FROM customers WHERE id=? FOR UPDATE",UUID.class,owner);
        if (code.equals("KMKH10")) {
            long count=jdbc.queryForObject("SELECT count(*) FROM bookings b WHERE b.customer_id=? AND b.id<>? AND b.status='COMPLETED' "
                    + "AND EXISTS (SELECT 1 FROM payment_transactions p WHERE p.booking_id=b.id AND "
                    + VERIFIED_RECEIPT + ")",Long.class,owner,booking.getId());
            return count>=15 ? null : "Bạn chưa đủ điều kiện sử dụng mã KMKH10";
        }
        long used=jdbc.queryForObject("SELECT count(*) FROM bookings b WHERE b.customer_id=? AND b.id<>? "
                + "AND b.status IN ('PAID','CHECKED_IN','COMPLETED','NO_SHOW') "
                + "AND EXISTS (SELECT 1 FROM payment_transactions p WHERE p.booking_id=b.id AND " + VERIFIED_RECEIPT + ")",Long.class,owner,booking.getId());
        long posUsage=jdbc.queryForObject("SELECT count(*) FROM customer_voucher_usage u JOIN discount_rules d ON d.id=u.discount_rule_id "
                + "JOIN invoices i ON i.id=u.invoice_id WHERE u.customer_id=? AND d.voucher_code='NEWMEMBER' AND i.status='PAID'",Long.class,owner);
        if (used>0 || posUsage>0) return "Bạn không còn đủ điều kiện NEWMEMBER vì đã đặt sân thành công hoặc đã dùng ưu đãi người mới.";
        long held=jdbc.queryForObject("SELECT count(*) FROM bookings b WHERE b.customer_id=? AND b.id<>? AND ((b.status='PENDING' "
                + "AND b.expires_at>? AND ((b.promotion_code='NEWMEMBER' AND NOT EXISTS "
                + "(SELECT 1 FROM payment_transactions p WHERE p.booking_id=b.id)) OR EXISTS "
                + "(SELECT 1 FROM payment_transactions p WHERE p.booking_id=b.id AND p.status='PENDING'))) "
                + "OR (b.status IN ('PAID','CHECKED_IN','COMPLETED','NO_SHOW') AND b.promotion_code='NEWMEMBER' "
                + "AND EXISTS (SELECT 1 FROM payment_transactions p WHERE p.booking_id=b.id AND p.status='PENDING')))",Long.class,owner,booking.getId(),LocalDateTime.now(clock));
        return held==0 ? null : "NEWMEMBER đang được giữ bởi giao dịch khác. Vui lòng kiểm tra trạng thái giao dịch đó.";
    }

    private void requireEditable(Booking b) {
        if (b.getStatus()!=BookingStatus.PENDING || b.getExpiresAt()==null || !b.getExpiresAt().isAfter(LocalDateTime.now(clock)))
            throw new DuplicateResourceException("BOOKING_EXPIRED: Booking không còn cho phép thay đổi ưu đãi/thanh toán.");
    }
}
