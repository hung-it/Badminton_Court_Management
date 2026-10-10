package com.bcm.service;

import com.bcm.dto.response.ApiResponse;
import com.bcm.dto.response.CheckoutResponse;
import com.bcm.entity.Booking;
import com.bcm.entity.Invoice;
import com.bcm.exception.BadRequestException;
import com.bcm.exception.ResourceNotFoundException;
import com.bcm.repository.BookingRepository;
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

    @Transactional
        public ApiResponse<CheckoutResponse> checkout(UUID bookingId, String paymentMethod) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking", "id", bookingId));

        String normalizedPaymentMethod = normalizePaymentMethod(paymentMethod);
        BigDecimal courtAmount = valueOrZero(booking.getCourtAmount());
        BigDecimal productAmount = valueOrZero(
                invoiceRepository.sumProductAmountByBookingIdAndStatus(bookingId, PENDING_POS_STATUS));
        BigDecimal totalAmount = courtAmount.add(productAmount);

        Invoice invoice = Invoice.builder()
                .bookingId(bookingId)
                .customerId(booking.getCustomerId())
                .paymentMethod(normalizedPaymentMethod)
                .status(PAID_STATUS)
                .courtAmount(courtAmount)
                .productAmount(productAmount)
                .discountAmount(BigDecimal.ZERO)
                .totalAmount(totalAmount)
                .build();
        Invoice savedInvoice = invoiceRepository.saveAndFlush(invoice);

        List<Invoice> pendingPosInvoices = invoiceRepository
                .findByBookingIdAndStatus(bookingId, PENDING_POS_STATUS);
        pendingPosInvoices.forEach(posInvoice -> posInvoice.setStatus("CANCELLED"));
        invoiceRepository.saveAll(pendingPosInvoices);

        CheckoutResponse response = CheckoutResponse.builder()
                .bookingId(bookingId)
                .invoiceId(savedInvoice.getId())
                .courtAmount(courtAmount)
                .productAmount(productAmount)
                .totalAmount(totalAmount)
                .build();
        return ApiResponse.success("Checkout thành công", response);
    }

    private BigDecimal valueOrZero(BigDecimal amount) {
        return amount == null ? BigDecimal.ZERO : amount;
    }

        private String normalizePaymentMethod(String paymentMethod) {
                String normalized = paymentMethod == null ? "CASH" : paymentMethod.trim().toUpperCase();
                if (!normalized.equals("CASH") && !normalized.equals("QR")) {
                            throw new BadRequestException("Phương thức thanh toán phải là CASH hoặc QR");
                }
                return normalized;
        }
}