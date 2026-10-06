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
        // No policy maps failure/intermediate results to FAILED. Never downgrade a confirmed payment.
        if (!notification.providerSuccess()) { return Result.IGNORED; }
        if (payment.getStatus() == PaymentStatus.SUCCESS) {
            boolean same = Objects.equals(payment.getTransactionId(), notification.transactionId())
                    && Objects.equals(payment.getTransactionDate(), notification.transactionDate());
            return same && booking.getStatus() != BookingStatus.PENDING && booking.getStatus() != BookingStatus.EXPIRED
                    ? Result.ALREADY_CONFIRMED : Result.CONFLICT;
        }
        if (payment.getStatus() != PaymentStatus.PENDING || payment.getTransactionId() != null
                || payment.getTransactionDate() != null) { return Result.NOT_PAYABLE; }
        if (booking.getStatus() == BookingStatus.EXPIRED
                || (booking.getStatus() == BookingStatus.PENDING && booking.getExpiresAt() != null
                && !booking.getExpiresAt().isAfter(LocalDateTime.now(clock)))) {
            // Expected domain outcome, not an infrastructure failure. Never restore details or settle late.
            return Result.LATE_EXPIRED;
        }
        if (booking.getStatus() != BookingStatus.PENDING || booking.getExpiresAt() == null) { return Result.NOT_PAYABLE; }
        payment.setStatus(PaymentStatus.SUCCESS);
        payment.setTransactionId(notification.transactionId());
        payment.setTransactionDate(notification.transactionDate());
        booking.setStatus(BookingStatus.PAID);
        payments.flush(); // unique transaction ID/any persistence failure rolls back BOTH managed mutations.
        return Result.SUCCESS;
    }

    private Result validate(PaymentTransaction payment, PaymentNotification notification) {
        if (payment == null || payment.getBooking() == null || payment.getInvoice() != null
                || payment.getPaymentMethod() != notification.method()) { return Result.NOT_FOUND; }
        if (payment.getAmount().compareTo(notification.amount()) != 0) { return Result.INVALID_AMOUNT; }
        return null;
    }
}
