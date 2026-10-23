package com.bcm.service;

import com.bcm.dto.request.UpdateBookingNoteRequest;
import com.bcm.dto.request.UpdateBookingRequest;
import com.bcm.dto.response.BookingHistoryDetailResponse;
import com.bcm.entity.*;
import com.bcm.exception.*;
import com.bcm.repository.*;
import com.bcm.security.CurrentCustomerService;
import jakarta.validation.Validator;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class BookingModificationService {
    private final BookingRepository bookings;
    private final BookingDetailRepository details;
    private final CourtRepository courts;
    private final TimeSlotRepository slots;
    private final PaymentTransactionRepository payments;
    private final CurrentCustomerService customer;
    private final BookingHistoryService history;
    private final Validator validator;
    private final Clock clock;

    public BookingModificationService(BookingRepository bookings, BookingDetailRepository details,
            CourtRepository courts, TimeSlotRepository slots, PaymentTransactionRepository payments,
            CurrentCustomerService customer, BookingHistoryService history, Validator validator,
            @Qualifier("bookingExpirationClock") Clock clock) {
        this.bookings = bookings; this.details = details; this.courts = courts; this.slots = slots;
        this.payments = payments; this.customer = customer; this.history = history;
        this.validator = validator; this.clock = clock;
    }

    @Transactional
    public BookingHistoryDetailResponse updateNote(UUID bookingId, UpdateBookingNoteRequest request) {
        validate(request);
        var booking = lockOwned(bookingId);
        requireEditable(booking);
        booking.setCustomerNote(request.note().isBlank() ? null : request.note().strip());
        bookings.flush();
        return history.detail(bookingId);
    }

    @Transactional
    public BookingHistoryDetailResponse updateDetails(UUID bookingId, UpdateBookingRequest request) {
        validate(request);
        var booking = lockOwned(bookingId);
        requireEditable(booking);
        // Conservative rule: no repricing after ANY attempt, including terminal ones. Old signed
        // URLs must never be correlated to a booking whose amount or slots changed.
        if (payments.existsByBookingId(bookingId)) {
            throw new DuplicateResourceException("PAYMENT_ATTEMPT_EXISTS: Booking details are locked after payment initiation");
        }
        // Read IDs only: preloading Court through details would leave stale managed fields
        // when a concurrent master-data update commits while this request waits for its lock.
        var courtIds = details.findCourtIdsForBooking(bookingId);
        if (courtIds.size() != 1) {
            throw new DuplicateResourceException("Only a single-court booking can be edited in checkout");
        }
        if (new HashSet<>(request.timeSlotIds()).size() != request.timeSlotIds().size()) {
            throw new BadRequestException("Duplicate time slots are not allowed");
        }
        // Same Court locks as createBooking; Booking -> Court, never lock another existing Booking.
        var court = courts.findAllForBookingWithLock(courtIds).get(0);
        requireEditable(booking); // The wait for a Court lock must not allow a late edit.
        if (court.getStatus() != CourtStatus.AVAILABLE || court.getDeletedAt() != null) {
            throw new BadRequestException("Court is not available for booking");
        }
        var original = details.findHistoryDetails(bookingId);
        var selected = slots.findByIdInAndDeletedAtIsNull(request.timeSlotIds());
        if (selected.size() != request.timeSlotIds().size()) throw new BadRequestException("Invalid time slot");
        if (!details.findConflictingReplacementSlots(bookingId, request.bookingDate(), court.getId(), request.timeSlotIds()).isEmpty()) {
            throw new DuplicateResourceException("This time slot is already booked");
        }
        var retained = original.stream().filter(d -> d.getBookingDate().equals(request.bookingDate()))
                .collect(Collectors.toMap(d -> d.getTimeSlot().getId(), BookingDetail::getPrice));
        var replacements = new ArrayList<BookingDetail>();
        BigDecimal total = new BigDecimal("0.00");
        for (var slot : selected) {
            if (!request.bookingDate().atTime(slot.getStartTime()).isAfter(
                    LocalDateTime.ofInstant(clock.instant(), ZoneId.of("Asia/Ho_Chi_Minh")))) {
                throw new BadRequestException("Booking time must be in the future (Asia/Ho_Chi_Minh)");
            }
            // Retain existing snapshot prices for unchanged tuples; only new tuples use current master prices.
            var price = retained.getOrDefault(slot.getId(), court.getBasePrice().multiply(slot.getPriceMultiplier())
                    .setScale(2, RoundingMode.HALF_UP));
            requireMoneyFits(price);
            total = total.add(price);
            var detail = new BookingDetail();
            detail.setBooking(booking); detail.setCourt(court); detail.setTimeSlot(slot);
            detail.setBookingDate(request.bookingDate()); detail.setPrice(price);
            replacements.add(detail);
        }
        requireMoneyFits(total);
        requireEditable(booking);
        // Delete only after the replacement has been validated under locks. Both deletion and
        // insertion roll back together on unique-slot errors at flush/commit.
        details.deleteForReplacement(bookingId);
        booking.setCourtFee(total);
        details.saveAllAndFlush(replacements);
        return history.detail(bookingId);
    }

    private Booking lockOwned(UUID bookingId) {
        UUID trustedCustomer = customer.requireCustomerId();
        var booking = bookings.findByIdForExpiration(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found"));
        if (!trustedCustomer.equals(booking.getCustomer().getId())) throw new AccessDeniedException("Booking not owned");
        return booking;
    }

    private void requireEditable(Booking booking) {
        if (booking.getStatus() == BookingStatus.EXPIRED || (booking.getStatus() == BookingStatus.PENDING
                && (booking.getExpiresAt() == null || !booking.getExpiresAt().isAfter(LocalDateTime.now(clock))))) {
            throw new DuplicateResourceException("BOOKING_EXPIRED: Booking deadline has passed");
        }
        if (booking.getStatus() != BookingStatus.PENDING) throw new DuplicateResourceException("Only PENDING bookings can be edited");
    }

    private void validate(Object request) {
        if (request == null || !validator.validate(request).isEmpty()) throw new BadRequestException("Invalid booking update");
    }

    private void requireMoneyFits(BigDecimal amount) {
        if (amount.signum() < 0 || amount.precision() > 10) throw new BadRequestException("Booking price exceeds NUMERIC(10,2) capacity");
    }
}
