package com.bcm.service;

import com.bcm.entity.BookingStatus;
import com.bcm.repository.BookingDetailRepository;
import com.bcm.repository.BookingRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class BookingExpirationService {
    private final BookingRepository bookings;
    private final BookingDetailRepository details;
    private final Clock clock;
    private final com.bcm.repository.PaymentTransactionRepository payments;
    private final PaymentInFlightPolicy inFlightPolicy;

    public BookingExpirationService(BookingRepository bookings, BookingDetailRepository details,
                                    @Qualifier("bookingExpirationClock") Clock clock,
                                    com.bcm.repository.PaymentTransactionRepository payments,
                                    PaymentInFlightPolicy inFlightPolicy) {
        this.bookings = bookings;
        this.details = details;
        this.clock = clock;
        this.payments = payments;
        this.inFlightPolicy = inFlightPolicy;
    }

    @Transactional(readOnly = true)
    public List<UUID> findCandidates() {
        return bookings.findExpirationCandidates(BookingStatus.PENDING, LocalDateTime.now(clock));
    }

    @Transactional
    public boolean expireBooking(UUID bookingId) {
        var booking = bookings.findByIdForExpiration(bookingId).orElse(null);
        // Candidate IDs are only hints. Read the current clock and state after acquiring the Booking lock.
        if (booking == null || booking.getStatus() != BookingStatus.PENDING) {
            return false;
        }
        var now = LocalDateTime.now(clock);
        boolean originalDue = booking.getExpiresAt() != null && !booking.getExpiresAt().isAfter(now);
        boolean checkoutDue = booking.getCheckoutReleaseAt() != null && !booking.getCheckoutReleaseAt().isAfter(now);
        if (payments.existsByBookingIdAndStatus(bookingId, com.bcm.entity.PaymentStatus.SUCCESS)) return false;
        if (!originalDue && (!checkoutDue || payments.existsByBookingIdAndStatus(bookingId, com.bcm.entity.PaymentStatus.PENDING)
                || payments.existsByBookingIdAndStatus(bookingId, com.bcm.entity.PaymentStatus.SUCCESS))) return false;
        // A payment in flight past the original deadline must not be dropped: within the in-flight
        // window keep the slot held so a late provider callback can still settle it (money kept = a
        // reservation). Once the window passes, a stale attempt no longer holds the court.
        if (originalDue && inFlightPolicy.inFlight(booking, now)) return false;
        bookings.updateExpirationStatus(bookingId, BookingStatus.EXPIRED);
        details.deleteAllForExpiredBooking(bookingId);
        return true;
    }
}
