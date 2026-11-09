package com.bcm.service;

import com.bcm.dto.response.ActiveBookingResponse;
import com.bcm.dto.response.ApiResponse;
import com.bcm.dto.response.CheckoutResponse;
import com.bcm.entity.Booking;
import com.bcm.entity.BookingDetail;
import com.bcm.entity.BookingStatus;
import com.bcm.entity.Invoice;
import com.bcm.entity.InvoiceDetail;
import com.bcm.exception.BadRequestException;
import com.bcm.exception.ResourceNotFoundException;
import com.bcm.repository.BookingDetailRepository;
import com.bcm.repository.BookingRepository;
import com.bcm.repository.InvoiceDetailRepository;
import com.bcm.repository.InvoiceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CheckoutService {

    private static final String PENDING_POS_STATUS = "DRAFT";
    private static final String PAID_STATUS = "PAID";
    private static final Pattern UUID_PATTERN = Pattern.compile("([a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12})");

    private final BookingRepository bookingRepository;
    private final BookingDetailRepository bookingDetailRepository;
    private final InvoiceRepository invoiceRepository;
    private final InvoiceDetailRepository invoiceDetailRepository;
    private final com.bcm.repository.PaymentTransactionRepository bookingPayments;
    private final com.bcm.security.CurrentStaffService currentStaff;
    private final PosService pos;
    private final java.time.Clock clock;

    public UUID resolveBookingId(String input) {
        if (input == null || input.isBlank()) {
            throw new BadRequestException("Mã booking không được để trống");
        }
        String trimmed = input.trim();
        Matcher matcher = UUID_PATTERN.matcher(trimmed);
        if (matcher.find()) {
            return UUID.fromString(matcher.group(1));
        }

        // Remove any dashes or special chars for prefix search
        String clean = trimmed.replaceAll("[^a-zA-Z0-9-]", "").toLowerCase();
        if (clean.length() >= 6) {
            List<UUID> matches = bookingRepository.findIdsByPrefix(clean);
            if (matches.size() == 1) {
                return matches.get(0);
            } else if (matches.size() > 1) {
                throw new BadRequestException("Tìm thấy nhiều hơn 1 booking khớp tiền tố '" + clean + "'. Vui lòng nhập thêm ký tự.");
            }
        }
        try {
            return UUID.fromString(trimmed);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("Mã booking không hợp lệ (không tìm thấy booking khớp với: " + trimmed + ")");
        }
    }

    @Transactional(readOnly = true)
    public List<ActiveBookingResponse> getActiveBookings() {
        List<Booking> bookings = bookingRepository.findByStatusInOrderByCreatedAtDesc(
                List.of(BookingStatus.PAID, BookingStatus.PENDING));

        DateTimeFormatter timeFmt = DateTimeFormatter.ofPattern("HH:mm");
        DateTimeFormatter dateFmt = DateTimeFormatter.ofPattern("dd/MM/yyyy");

        List<ActiveBookingResponse> list = new ArrayList<>();
        for (Booking b : bookings) {
            List<BookingDetail> details = bookingDetailRepository.findHistoryDetails(b.getId());
            String courtDetailsStr = details.stream()
                    .map(d -> {
                        String courtName = d.getCourt() != null ? d.getCourt().getName() : "Sân";
                        String dateStr = d.getBookingDate() != null ? d.getBookingDate().format(dateFmt) : "";
                        String timeStr = d.getTimeSlot() != null
                                ? (d.getTimeSlot().getStartTime().format(timeFmt) + "-" + d.getTimeSlot().getEndTime().format(timeFmt))
                                : "";
                        return courtName + " (" + dateStr + " " + timeStr + ")";
                    })
                    .collect(Collectors.joining("; "));

            String customerName = b.getCustomer() != null ? b.getCustomer().getFullName() : "Khách vãng lai";
            String customerPhone = b.getCustomer() != null ? b.getCustomer().getPhone() : "";
            String fullId = b.getId().toString();
            String shortId = fullId.substring(0, 8);

            list.add(new ActiveBookingResponse(
                    b.getId(),
                    shortId,
                    b.getStatus().name(),
                    customerName,
                    customerPhone,
                    b.getCourtFee(),
                    b.getFinalAmount(),
                    courtDetailsStr,
                    b.getCreatedAt()
            ));
        }
        return list;
    }

    @Transactional
    public ApiResponse<CheckoutResponse> checkout(UUID bookingId, String paymentMethod) {
        return checkout(bookingId,new com.bcm.dto.request.CheckoutRequest(paymentMethod));
    }

    @Transactional
    public ApiResponse<CheckoutResponse> checkout(UUID bookingId, com.bcm.dto.request.CheckoutRequest request) {
        UUID actor=currentStaff.requireUserId();
        Booking booking=bookingRepository.findByIdForExpiration(bookingId)
            .orElseThrow(()->new ResourceNotFoundException("Booking not found"));
        if(booking.getStatus()==BookingStatus.COMPLETED) {
            var paid=invoiceRepository.findByBookingIdAndStatusForUpdate(bookingId,PAID_STATUS).stream()
                .filter(i->i.getParentInvoiceId()==null).toList();
            if(paid.size()!=1) throw new BadRequestException("Completed booking requires invoice reconciliation");
            return ApiResponse.success("Checkout already completed", response(booking,paid.get(0),verifiedCourtReceipt(booking)));
        }
        if(booking.getStatus()!=BookingStatus.PAID)
            throw new BadRequestException("Booking must already be paid before staff checkout");
        if(invoiceRepository.findByBookingIdAndStatusForUpdate(bookingId,PAID_STATUS).stream().anyMatch(i->i.getParentInvoiceId()==null))
            throw new BadRequestException("Booking already has a paid main invoice; reconciliation required");
        var end=latestEnd(bookingId);
        if(end==null) throw new BadRequestException("Không đủ chi tiết lịch chơi để xác minh giờ kết thúc.");
        if(nowVietnam().isBefore(end)) throw new BadRequestException("Chưa đến giờ kết thúc lịch chơi, không thể kết toán.");
        BigDecimal paidCourt=verifiedCourtReceipt(booking);
        var drafts=invoiceRepository.findByBookingIdAndStatusForUpdate(bookingId,PENDING_POS_STATUS);
        for(var draft:drafts) {
            if(draft.getParentInvoiceId()!=null || bookingPayments.existsByInvoiceId(draft.getId()))
                throw new BadRequestException("Draft already linked or has payment transactions; reconciliation required");
        }
        BigDecimal productFee=drafts.stream().map(Invoice::getProductFee).reduce(BigDecimal.ZERO,BigDecimal::add);
        BigDecimal productDiscount=drafts.stream().map(Invoice::getDiscountAmount).reduce(BigDecimal.ZERO,BigDecimal::add);
        BigDecimal total=booking.getFinalAmount().add(productFee).subtract(productDiscount);
        BigDecimal due=total.subtract(paidCourt);
        if(due.signum()<0) throw new BadRequestException("Receipt exceeds invoice; reconciliation required");
        if(request.getExpectedAmountDue()==null || due.compareTo(request.getExpectedAmountDue())!=0)
            throw new com.bcm.exception.DuplicateResourceException("Bill changed; reload and confirm the current amount due");
        String method=null;
        if(due.signum()>0) {
            if(!request.isPaymentConfirmed()) throw new BadRequestException("Staff must confirm receipt of the remaining amount");
            method=pos.manualMethod(request.getPaymentMethod());
        }
        var methods=new java.util.HashSet<String>();
        bookingPayments.findBookingHistory(bookingId).stream().filter(p->p.getStatus()==com.bcm.entity.PaymentStatus.SUCCESS)
            .forEach(p->methods.add(p.getPaymentMethod().name()));
        if(method!=null) methods.add(method);
        // Zero-value invoices still carry a valid summary, but never a fabricated payment.
        String summary=methods.size()==1?methods.iterator().next():null;
        Invoice invoice=Invoice.builder().bookingId(bookingId).customerId(booking.getCustomer().getId())
            .status(PAID_STATUS).paymentMethod(summary).courtFee(booking.getCourtFee()).productFee(productFee)
            .discountAmount(booking.getDiscountAmount().add(productDiscount)).totalAmount(total)
            .createdBy(currentStaff.currentStaffId().orElse(null)).updatedBy(currentStaff.currentStaffId().orElse(null))
            .createdByUser(actor).build();
        invoice=invoiceRepository.saveAndFlush(invoice);
        for(var draft:drafts) {
            for(var line:invoiceDetailRepository.findByInvoiceId(draft.getId())) line.setInvoiceId(invoice.getId());
            draft.setParentInvoiceId(invoice.getId()); draft.setStatus("CANCELLED");
        }
        pos.recordReceipt(invoice,null,due,method,request.getReference());
        booking.setStatus(BookingStatus.COMPLETED);
        return ApiResponse.success("Checkout successful",response(booking,invoice,paidCourt));
    }

    private CheckoutResponse response(Booking booking,Invoice invoice,BigDecimal paidCourt) {
        var receipts=bookingPayments.findInvoiceReceipts(invoice.getId(),booking.getId()).stream()
            .filter(p->p.getStatus()==com.bcm.entity.PaymentStatus.SUCCESS).toList();
        boolean valid=receipts.stream().allMatch(p->p.getAmount().signum()>0
            && p.getTransactionDate()!=null && p.getTransactionId()!=null
            && (("GATEWAY".equals(p.getSource()) && p.getPaymentMethod()==com.bcm.entity.PaymentMethod.VNPAY)
                || ("MANUAL".equals(p.getSource()) && p.getConfirmedByUser()!=null)));
        BigDecimal received=receipts.stream().map(com.bcm.entity.PaymentTransaction::getAmount).reduce(BigDecimal.ZERO,BigDecimal::add);
        if(!valid || received.compareTo(invoice.getTotalAmount())!=0)
            throw new BadRequestException("Invoice receipts do not reconcile with paid total");
        return CheckoutResponse.builder().bookingId(booking.getId()).invoiceId(invoice.getId())
            .courtFee(invoice.getCourtFee()).productFee(invoice.getProductFee()).discountAmount(invoice.getDiscountAmount())
            .totalAmount(invoice.getTotalAmount()).courtDiscount(booking.getDiscountAmount())
            .productDiscount(invoice.getDiscountAmount().subtract(booking.getDiscountAmount()))
            .paidCourtAmount(paidCourt).amountDue(invoice.getTotalAmount().subtract(paidCourt))
            .collectedAmount(invoice.getTotalAmount().subtract(paidCourt)).remainingDue(BigDecimal.ZERO)
            .checkoutEligible(false).paymentMethod(invoice.getPaymentMethod())
            .paymentMethods(receipts.stream().map(p->p.getPaymentMethod().name()).distinct().toList()).build();
    }

    @Transactional(readOnly=true)
    public CheckoutResponse preview(UUID id) {
        Booking b=bookingRepository.findById(id).orElseThrow(()->new ResourceNotFoundException("Booking not found"));
        if(b.getStatus()!=BookingStatus.PAID) throw new BadRequestException("Booking phải đã thanh toán và chưa kết toán");
        BigDecimal products=valueOrZero(invoiceRepository.sumProductFeeByBookingIdAndStatus(id,PENDING_POS_STATUS));
        BigDecimal productDiscount=valueOrZero(invoiceRepository.sumDiscountAmountByBookingIdAndStatus(id,PENDING_POS_STATUS));
        BigDecimal total=b.getFinalAmount().add(products).subtract(productDiscount);
        BigDecimal paidCourt=verifiedCourtReceipt(b);
        var end=latestEnd(id);
        boolean eligible=end!=null && !nowVietnam().isBefore(end);
        return CheckoutResponse.builder().bookingId(id).courtFee(b.getCourtFee()).productFee(products)
            .discountAmount(b.getDiscountAmount().add(productDiscount)).courtDiscount(b.getDiscountAmount())
            .productDiscount(productDiscount).totalAmount(total).paidCourtAmount(paidCourt)
            .amountDue(total.subtract(paidCourt)).collectedAmount(BigDecimal.ZERO).remainingDue(total.subtract(paidCourt))
            .latestEndAt(end).checkoutEligible(eligible).checkoutMessage(eligible?null:end==null?"Không đủ chi tiết lịch chơi để xác minh giờ kết thúc.":"Chưa đến giờ kết thúc lịch chơi, không thể kết toán.").build();
    }

    private java.time.LocalDateTime nowVietnam() {
        return java.time.LocalDateTime.now(clock.withZone(java.time.ZoneId.of("Asia/Ho_Chi_Minh")));
    }
    private java.time.LocalDateTime latestEnd(UUID id) {
        var details=bookingDetailRepository.findHistoryDetails(id);
        if(details.isEmpty()) return null;
        java.time.LocalDateTime end=null;
        for(var d:details) {
            if(d.getBookingDate()==null || d.getTimeSlot()==null || d.getTimeSlot().getEndTime()==null) return null;
            var candidate=d.getBookingDate().atTime(d.getTimeSlot().getEndTime());
            if(end==null || candidate.isAfter(end)) end=candidate;
        }
        return end;
    }

    // A PAID booking header alone is not evidence of receipt. Never collect its court fee again
    // or silently treat an unverified payment as received; require reconciliation instead.
    private BigDecimal verifiedCourtReceipt(Booking booking) {
        var all=bookingPayments.findBookingHistory(booking.getId());
        if(booking.getFinalAmount().signum()==0 && all.stream().noneMatch(p->p.getStatus()==com.bcm.entity.PaymentStatus.SUCCESS)) return BigDecimal.ZERO;
        var receipts = all.stream()
                .filter(p -> p.getStatus() == com.bcm.entity.PaymentStatus.SUCCESS
                        && p.getTransactionId() != null && p.getTransactionDate() != null
                        && (("GATEWAY".equals(p.getSource()) && p.getPaymentMethod()==com.bcm.entity.PaymentMethod.VNPAY)
                            || ("MANUAL".equals(p.getSource()) && p.getConfirmedByUser()!=null))
                        && p.getAmount().compareTo(booking.getFinalAmount()) == 0).toList();
        if (receipts.size() != 1 || all.stream().filter(p->p.getStatus()==com.bcm.entity.PaymentStatus.SUCCESS).count()!=1) {
            throw new BadRequestException("Booking requires exactly one verified full court receipt before checkout");
        }
        return receipts.get(0).getAmount();
    }

    private BigDecimal valueOrZero(BigDecimal amount) {
        return amount == null ? BigDecimal.ZERO : amount;
    }

}
