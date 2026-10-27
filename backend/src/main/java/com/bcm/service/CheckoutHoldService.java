package com.bcm.service;

import com.bcm.entity.Booking;
import com.bcm.entity.BookingStatus;
import com.bcm.entity.PaymentStatus;
import com.bcm.exception.DuplicateResourceException;
import com.bcm.exception.ResourceNotFoundException;
import com.bcm.repository.BookingRepository;
import com.bcm.repository.PaymentTransactionRepository;
import com.bcm.security.CurrentCustomerService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.UUID;

@Service
public class CheckoutHoldService {
    private final BookingRepository bookings;
    private final PaymentTransactionRepository payments;
    private final CurrentCustomerService customer;
    private final BookingExpirationService expiration;
    private final Clock clock;
    private final com.bcm.repository.BookingDetailRepository details;

    public CheckoutHoldService(BookingRepository bookings, PaymentTransactionRepository payments,
            CurrentCustomerService customer, BookingExpirationService expiration,
            @Qualifier("bookingExpirationClock") Clock clock, com.bcm.repository.BookingDetailRepository details) {
        this.bookings = bookings; this.payments = payments; this.customer = customer;
        this.expiration = expiration; this.clock = clock;
        this.details = details;
    }

    @Transactional
    public State leave(UUID id) {
        var booking = lockOwned(id);
        var now = LocalDateTime.now(clock);
        if (booking.getStatus() == BookingStatus.PENDING && !protectedPayment(id)
                && booking.getCheckoutReleaseAt() == null) {
            booking.setCheckoutReleaseAt(now.plusSeconds(60).isBefore(booking.getExpiresAt())
                    ? now.plusSeconds(60) : booking.getExpiresAt());
        }
        // Repeated leave calls preserve the first deadline, including after it has elapsed.
        return state(booking);
    }

    @Transactional
    public State resume(UUID id) {
        var booking = lockOwned(id);
        if (expiration.expireBooking(id)) {
            return new State(id, BookingStatus.EXPIRED, booking.getExpiresAt(), booking.getCheckoutReleaseAt());
        }
        if (booking.getStatus() == BookingStatus.PENDING) booking.setCheckoutReleaseAt(null);
        return state(booking);
    }

    // Legacy reference assertion only. Creating a new booking must never release this hold.
    @Transactional
    public Booking lockReplacement(UUID id) {
        var booking = lockOwned(id);
        // Already released bookings need no mutation. Keep even a late payment attempt's
        // history intact; the callback's existing EXPIRED guard prevents resurrection.
        if (booking.getStatus() == BookingStatus.EXPIRED) return booking;
        if (booking.getStatus() != BookingStatus.PENDING || protectedPayment(id)
                || booking.getCheckoutReleaseAt() == null) {
            throw new DuplicateResourceException("Booking is not eligible for hold replacement");
        }
        return booking;
    }

    @Transactional
    public ReplacementHold replacementHold(UUID id) {
        var booking = lockOwned(id);
        var held = booking.getStatus() == BookingStatus.PENDING ? details.findHistoryDetails(id).stream().map(d ->
                new com.bcm.dto.response.BookingResponse.Detail(d.getBookingDate(), d.getCourt().getId(),
                        d.getTimeSlot().getId(), d.getPrice())).toList()
                : java.util.List.<com.bcm.dto.response.BookingResponse.Detail>of();
        // Compatibility for older clients: never authorize a private availability overlay.
        return new ReplacementHold(id, false, held);
    }

    private Booking lockOwned(UUID id) {
        var owner = customer.requireCustomerId();
        var booking = bookings.findByIdForExpiration(id)
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found: " + id));
        if (!booking.getCustomer().getId().equals(owner)) throw new AccessDeniedException("Booking not owned");
        return booking;
    }

    private boolean protectedPayment(UUID id) {
        return payments.existsByBookingIdAndStatus(id, PaymentStatus.PENDING)
                || payments.existsByBookingIdAndStatus(id, PaymentStatus.SUCCESS);
    }

    private State state(Booking booking) {
        return new State(booking.getId(), booking.getStatus(), booking.getExpiresAt(), booking.getCheckoutReleaseAt());
    }

    public record State(UUID bookingId, BookingStatus status, LocalDateTime expiresAt, LocalDateTime checkoutReleaseAt) { }
    public record ReplacementHold(UUID bookingId, boolean replaceable,
                                  java.util.List<com.bcm.dto.response.BookingResponse.Detail> details) { }
}
