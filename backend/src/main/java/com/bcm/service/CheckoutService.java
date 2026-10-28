package com.bcm.service;

import com.bcm.dto.response.ApiResponse;
import com.bcm.dto.response.CheckoutResponse;
import com.bcm.entity.Booking;
import com.bcm.entity.BookingStatus;
import com.bcm.entity.Invoice;
import com.bcm.entity.InvoiceDetail;
import com.bcm.exception.BadRequestException;
import com.bcm.exception.ResourceNotFoundException;
import com.bcm.repository.BookingRepository;
import com.bcm.repository.InvoiceDetailRepository;
import com.bcm.repository.InvoiceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CheckoutService {

    private static final String PENDING_POS_STATUS = "DRAFT";
    private static final String PAID_STATUS = "PAID";

    private final BookingRepository bookingRepository;
    private final InvoiceRepository invoiceRepository;
    private final InvoiceDetailRepository invoiceDetailRepository;
    private final com.bcm.repository.PaymentTransactionRepository bookingPayments;

    @Transactional
    public ApiResponse<CheckoutResponse> checkout(UUID bookingId, String paymentMethod) {
        Booking booking = bookingRepository.findByIdForExpiration(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking", "id", bookingId));

        String normalizedPaymentMethod = normalizePaymentMethod(paymentMethod);
        if (booking.getStatus() != BookingStatus.PAID && booking.getStatus() != BookingStatus.CHECKED_IN) {
            throw new BadRequestException("Booking must already be paid before staff checkout");
        }
        if (bookingPayments.findBookingHistory(bookingId).stream().anyMatch(p ->
                p.getPaymentMethod() == com.bcm.entity.PaymentMethod.BANK_TRANSFER
                        && p.getStatus() == com.bcm.entity.PaymentStatus.PENDING
                        && p.getNote() != null && p.getNote().startsWith("CUSTOMER_DECLARED;"))) {
            throw new BadRequestException("Customer declaration confirms the reservation, not receipt of funds; reconcile before issuing a paid invoice");
        }
        BigDecimal courtFee = valueOrZero(booking.getCourtFee());
        BigDecimal productFee = valueOrZero(
                invoiceRepository.sumProductFeeByBookingIdAndStatus(bookingId, PENDING_POS_STATUS));
        BigDecimal discountAmount = valueOrZero(
                invoiceRepository.sumDiscountAmountByBookingIdAndStatus(bookingId, PENDING_POS_STATUS))
                .add(booking.getDiscountAmount());
        BigDecimal totalAmount = courtFee.add(productFee).subtract(discountAmount);
        UUID customerId = booking.getCustomer() != null ? booking.getCustomer().getId() : null;

        Invoice invoice = Invoice.builder()
                .bookingId(bookingId)
                .customerId(customerId)
                .paymentMethod(normalizedPaymentMethod)
                .status(PAID_STATUS)
                .courtFee(courtFee)
                .productFee(productFee)
                .discountAmount(discountAmount)
                .totalAmount(totalAmount)
                .build();
        Invoice savedInvoice = invoiceRepository.saveAndFlush(invoice);

        List<Invoice> pendingPosInvoices = invoiceRepository
                .findByBookingIdAndStatus(bookingId, PENDING_POS_STATUS);
        List<UUID> pendingInvoiceIds = pendingPosInvoices.stream().map(Invoice::getId).toList();
        if (!pendingInvoiceIds.isEmpty()) {
            List<InvoiceDetail> details = invoiceDetailRepository.findByInvoiceIdIn(pendingInvoiceIds);
            for (InvoiceDetail detail : details) {
                detail.setInvoiceId(savedInvoice.getId());
            }
            invoiceDetailRepository.saveAll(details);
        }

        pendingPosInvoices.forEach(posInvoice -> posInvoice.setStatus("CANCELLED"));
        invoiceRepository.saveAll(pendingPosInvoices);

        booking.setStatus(BookingStatus.COMPLETED);
        bookingRepository.save(booking);

        CheckoutResponse response = CheckoutResponse.builder()
                .bookingId(bookingId)
                .invoiceId(savedInvoice.getId())
                .courtFee(courtFee)
                .productFee(productFee)
                .discountAmount(discountAmount)
                .totalAmount(totalAmount)
                .courtDiscount(booking.getDiscountAmount())
                .productDiscount(discountAmount.subtract(booking.getDiscountAmount()))
                .paidCourtAmount(booking.getFinalAmount())
                .amountDue(totalAmount.subtract(booking.getFinalAmount()).max(BigDecimal.ZERO))
                .build();
        return ApiResponse.success("Checkout thành công", response);
    }

    @Transactional(readOnly=true)
    public CheckoutResponse preview(UUID id) {
        Booking b=bookingRepository.findById(id).orElseThrow(()->new ResourceNotFoundException("Booking not found"));
        if(b.getStatus()!=BookingStatus.PAID && b.getStatus()!=BookingStatus.CHECKED_IN) throw new BadRequestException("Booking phải đã thanh toán và chưa kết toán");
        BigDecimal products=valueOrZero(invoiceRepository.sumProductFeeByBookingIdAndStatus(id,PENDING_POS_STATUS));
        BigDecimal productDiscount=valueOrZero(invoiceRepository.sumDiscountAmountByBookingIdAndStatus(id,PENDING_POS_STATUS));
        BigDecimal total=b.getFinalAmount().add(products).subtract(productDiscount);
        return CheckoutResponse.builder().bookingId(id).courtFee(b.getCourtFee()).productFee(products)
            .discountAmount(b.getDiscountAmount().add(productDiscount)).courtDiscount(b.getDiscountAmount())
            .productDiscount(productDiscount).totalAmount(total).paidCourtAmount(b.getFinalAmount())
            .amountDue(products.subtract(productDiscount)).build();
    }

    private BigDecimal valueOrZero(BigDecimal amount) {
        return amount == null ? BigDecimal.ZERO : amount;
    }

    private String normalizePaymentMethod(String paymentMethod) {
        String normalized = paymentMethod == null ? "CASH" : paymentMethod.trim().toUpperCase();
        if ("QR".equals(normalized)) {
            normalized = "BANK_TRANSFER";
        }
        if (!normalized.equals("CASH") && !normalized.equals("BANK_TRANSFER") && !normalized.equals("VNPAY")) {
            throw new BadRequestException("Phương thức thanh toán phải là CASH, BANK_TRANSFER hoặc VNPAY");
        }
        return normalized;
    }
}
