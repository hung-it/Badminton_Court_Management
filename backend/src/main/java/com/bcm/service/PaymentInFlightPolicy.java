package com.bcm.service;

import com.bcm.config.BookingPaymentConfig;
import com.bcm.entity.Booking;
import com.bcm.entity.PaymentStatus;
import com.bcm.repository.PaymentTransactionRepository;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * Single source of truth for "is this booking in an in-flight payment window".
 *
 * <p>While a PENDING attempt exists and the wall clock is within {@code inFlightGrace} of the
 * booking's payment deadline, the booking must NOT be released to the expiry job (so the slot is
 * not sold to someone else) and a provider callback that lands late is still allowed to settle it
 * (so a customer who actually paid is not left holding a rejected receipt). The window is bounded
 * by {@link BookingPaymentConfig#getInFlightGrace()} so an abandoned attempt cannot hold a court
 * forever; once it passes, normal expiry and the documented no-refund path apply.
 */
@Component
public class PaymentInFlightPolicy {
    private final PaymentTransactionRepository payments;
    private final BookingPaymentConfig config;

    public PaymentInFlightPolicy(PaymentTransactionRepository payments, BookingPaymentConfig config) {
        this.payments = payments;
        this.config = config;
    }

    /** True while a PENDING attempt is in flight for this booking. */
    public boolean inFlight(Booking booking, LocalDateTime now) {
        if (!payments.existsByBookingIdAndStatus(booking.getId(), PaymentStatus.PENDING)) return false;
        var deadline = booking.getExpiresAt();
        // A PENDING booking always carries a deadline; if it ever doesn't, keep it held rather
        // than release a payment that is mid-flight.
        return deadline == null || !now.isAfter(deadline.plus(config.getInFlightGrace()));
    }
}
