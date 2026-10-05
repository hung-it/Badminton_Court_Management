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

    public BookingExpirationService(BookingRepository bookings, BookingDetailRepository details,
                                    @Qualifier("bookingExpirationClock") Clock clock) {
        this.bookings = bookings;
        this.details = details;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<UUID> findCandidates() {
        return bookings.findExpirationCandidates(BookingStatus.PENDING, LocalDateTime.now(clock));
    }

    @Transactional
    public boolean expireBooking(UUID bookingId) {
        var booking = bookings.findByIdForExpiration(bookingId).orElse(null);
        // Candidate IDs are only hints. Read the current clock and state after acquiring the Booking lock.
        if (booking == null || booking.getStatus() != BookingStatus.PENDING
                || booking.getExpiresAt() == null || booking.getExpiresAt().isAfter(LocalDateTime.now(clock))) {
            return false;
        }
        bookings.updateExpirationStatus(bookingId, BookingStatus.EXPIRED);
        details.deleteAllForExpiredBooking(bookingId);
        return true;
    }
}
