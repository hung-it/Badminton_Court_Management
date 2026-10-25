package com.bcm.service;

import com.bcm.entity.BookingStatus;
import com.bcm.entity.PaymentStatus;
import com.bcm.entity.PaymentTransaction;
import com.bcm.repository.BookingRepository;
import com.bcm.repository.PaymentTransactionRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

@Service
public class PaymentCallbackTransactionService {
    public enum Result {
        SUCCESS, ALREADY_CONFIRMED, NOT_FOUND, INVALID_AMOUNT, IGNORED,
        LATE_EXPIRED, NOT_PAYABLE, CONFLICT, TRANSACTION_ID_CONFLICT,
        INVALID_SIGNATURE, INVALID_DATA, UNAVAILABLE, SYSTEM_ERROR
    }
    public record Resolution(UUID bookingId, Result rejection) { }
    private final PaymentTransactionRepository payments;
    private final BookingRepository bookings;
    private final Clock clock;

    public PaymentCallbackTransactionService(PaymentTransactionRepository payments, BookingRepository bookings,
                                            @Qualifier("bookingExpirationClock") Clock clock) {
        this.payments = payments;
        this.bookings = bookings;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Resolution resolve(PaymentNotification notification) {
        var payment = payments.findById(notification.attemptId()).orElse(null);
        Result invalid = validate(payment, notification);
        return invalid != null ? new Resolution(null, invalid) : new Resolution(payment.getBooking().getId(), null);
    }

    @Transactional
    public Result settle(PaymentNotification notification, UUID bookingId) {
        // Lock order: Booking (same query as expiration), then Payment. Neither entity is preloaded here.
        var booking = bookings.findByIdForExpiration(bookingId).orElse(null);
        if (booking == null) { return Result.NOT_FOUND; }
        var payment = payments.findByIdForCallback(notification.attemptId()).orElse(null);
        Result invalid = validate(payment, notification);
        if (invalid != null) { return invalid; }
        if (!bookingId.equals(payment.getBooking().getId())) { return Result.NOT_FOUND; }
        if (!notification.providerSuccess()) {
            if (notification.terminalFailure() && payment.getStatus() == PaymentStatus.PENDING) {
                payment.setStatus(PaymentStatus.FAILED);
                if (payment.getNote() == null || !payment.getNote().startsWith("RECONCILIATION_REQUIRED")) {
                    payment.setNote("Verified VNPay terminal failure/cancellation; no receipt confirmed");
                }
                payments.flush();
            }
            return Result.IGNORED;
        }
        if (payment.getStatus() == PaymentStatus.SUCCESS) {
            boolean same = Objects.equals(payment.getTransactionId(), notification.transactionId())
                    && Objects.equals(payment.getTransactionDate(), notification.transactionDate());
            return same && booking.getStatus() != BookingStatus.PENDING && booking.getStatus() != BookingStatus.EXPIRED
                    ? Result.ALREADY_CONFIRMED : Result.CONFLICT;
        }
        if (booking.getStatus() == BookingStatus.EXPIRED
                || (booking.getStatus() == BookingStatus.PENDING && booking.getExpiresAt() != null
                && !booking.getExpiresAt().isAfter(LocalDateTime.now(clock)))) {
            recordUnsettledReceipt(payment, notification);
            return Result.LATE_EXPIRED;
        }
        if (payment.getStatus() != PaymentStatus.PENDING || payment.getTransactionId() != null
                || payment.getTransactionDate() != null) {
            recordUnsettledReceipt(payment, notification);
            return Result.NOT_PAYABLE;
        }
        if (booking.getStatus() != BookingStatus.PENDING || booking.getExpiresAt() == null) {
            recordUnsettledReceipt(payment, notification);
            return Result.NOT_PAYABLE;
        }
        if (payment.getAmount().compareTo(booking.getFinalAmount()) != 0) { return Result.INVALID_AMOUNT; }
        if (payments.existsByBookingIdAndStatus(bookingId, PaymentStatus.SUCCESS)) {
            recordUnsettledReceipt(payment, notification);
            return Result.CONFLICT;
        }
        payment.setStatus(PaymentStatus.SUCCESS);
        payment.setTransactionId(notification.transactionId());
        payment.setTransactionDate(notification.transactionDate());
        booking.setStatus(BookingStatus.PAID);
        payments.flush(); // unique transaction ID/any persistence failure rolls back BOTH managed mutations.
        return Result.SUCCESS;
    }

    private void recordUnsettledReceipt(PaymentTransaction payment, PaymentNotification notification) {
        payment.setNote("RECONCILIATION_REQUIRED: verified VNPay receipt; transaction=" + notification.transactionId()
                + "; amount=" + notification.amount() + "; date=" + notification.transactionDate()
                + "; booking not confirmed. Manual investigation required; no automatic refund.");
        payments.flush();
    }

    private Result validate(PaymentTransaction payment, PaymentNotification notification) {
        if (payment == null || payment.getBooking() == null || payment.getInvoice() != null
                || payment.getPaymentMethod() != notification.method()) { return Result.NOT_FOUND; }
        if (payment.getAmount().compareTo(notification.amount()) != 0) { return Result.INVALID_AMOUNT; }
        return null;
    }
}
