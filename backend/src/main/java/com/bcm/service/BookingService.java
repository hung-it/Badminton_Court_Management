package com.bcm.service;

import com.bcm.dto.request.CreateBookingRequest;
import com.bcm.dto.response.BookingResponse;
import com.bcm.entity.*;
import com.bcm.exception.BadRequestException;
import com.bcm.exception.DuplicateResourceException;
import com.bcm.exception.ResourceNotFoundException;
import com.bcm.repository.*;
import jakarta.validation.Validator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class BookingService {
    private final BookingRepository bookings;
    private final BookingDetailRepository details;
    private final CustomerRepository customers;
    private final CourtRepository courts;
    private final TimeSlotRepository timeSlots;
    private final Validator validator;
    private final Duration holdDuration;

    public BookingService(BookingRepository bookings, BookingDetailRepository details,
                          CustomerRepository customers, CourtRepository courts,
                          TimeSlotRepository timeSlots, Validator validator,
                          @Value("${booking.hold-duration}") String holdDuration) {
        this.bookings = bookings;
        this.details = details;
        this.customers = customers;
        this.courts = courts;
        this.timeSlots = timeSlots;
        this.validator = validator;
        this.holdDuration = Duration.parse(holdDuration);
        if (this.holdDuration.isZero() || this.holdDuration.isNegative()) {
            throw new IllegalArgumentException("booking.hold-duration must be positive");
        }
    }

    @Transactional
    public BookingResponse createBooking(CreateBookingRequest request) {
        if (request == null) {
            throw new BadRequestException("Booking request is required");
        }
        if (!validator.validate(request).isEmpty()) {
            throw new BadRequestException("customerId and at least one complete booking detail are required");
        }
        var requestedSlots = new HashSet<SlotKey>();
        for (var item : request.getDetails()) {
            if (!requestedSlots.add(new SlotKey(item.getBookingDate(), item.getCourtId(), item.getTimeSlotId()))) {
                throw new BadRequestException("Duplicate date/court/time slot in request");
            }
        }

        // Canonical UUID text order matches PostgreSQL UUID order. SQL ORDER BY controls lock acquisition.
        var courtIds = request.getDetails().stream().map(CreateBookingRequest.Detail::getCourtId)
                .distinct().sorted(Comparator.comparing(UUID::toString)).toList();
        var courtById = courts.findAllForBookingWithLock(courtIds)
                .stream().collect(Collectors.toMap(Court::getId, Function.identity()));
        // No Court is loaded before this lock query; validation below uses the locked state.
        // Domain reference only; Auth must supply/authorize this identity when integrated.
        Customer customer = customers.findById(request.getCustomerId())
                .orElseThrow(() -> new ResourceNotFoundException("Customer not found: " + request.getCustomerId()));
        var slotById = timeSlots.findAllById(request.getDetails().stream()
                        .map(CreateBookingRequest.Detail::getTimeSlotId).distinct().toList())
                .stream().collect(Collectors.toMap(TimeSlot::getId, Function.identity()));

        Booking booking = new Booking();
        booking.setCustomer(customer);
        booking.setStatus(BookingStatus.PENDING);
        var bookingDetails = new ArrayList<BookingDetail>();
        BigDecimal total = new BigDecimal("0.00");
        for (var item : request.getDetails()) {
            Court court = courtById.get(item.getCourtId());
            if (court == null) {
                throw new ResourceNotFoundException("Court not found: " + item.getCourtId());
            }
            if (court.getStatus() != CourtStatus.AVAILABLE || court.getDeletedAt() != null) {
                throw new BadRequestException("Court is not available for booking: " + court.getId());
            }
            TimeSlot slot = slotById.get(item.getTimeSlotId());
            if (slot == null) {
                throw new ResourceNotFoundException("Time slot not found: " + item.getTimeSlotId());
            }
            // Snapshot policy: round each detail HALF_UP to scale 2 before summing.
            BigDecimal price = court.getBasePrice().multiply(slot.getPriceMultiplier())
                    .setScale(2, RoundingMode.HALF_UP);
            requireMoneyFits(price);
            BookingDetail detail = new BookingDetail();
            detail.setBooking(booking);
            detail.setCourt(court);
            detail.setTimeSlot(slot);
            detail.setBookingDate(item.getBookingDate());
            detail.setPrice(price);
            bookingDetails.add(detail);
            total = total.add(price);
        }
        requireMoneyFits(total);
        // Re-check after acquiring all Court locks. Existing details block regardless of header status/expiry.
        var dates = request.getDetails().stream().map(CreateBookingRequest.Detail::getBookingDate).distinct().toList();
        var slotIds = request.getDetails().stream().map(CreateBookingRequest.Detail::getTimeSlotId).distinct().toList();
        for (var occupied : details.findOccupiedSlotsForBooking(dates, courtIds, slotIds)) {
            if (requestedSlots.contains(new SlotKey(occupied.getBookingDate(), occupied.getCourtId(), occupied.getTimeSlotId()))) {
                throw new DuplicateResourceException("This time slot is already booked");
            }
        }
        booking.setCourtFee(total);
        // TIMESTAMP without timezone: use the application local clock, as existing audit fields do.
        booking.setExpiresAt(LocalDateTime.now().plus(holdDuration));
        bookings.save(booking);
        // Flush verifies the unique-index fallback before returning; any failure rolls back header and details.
        details.saveAllAndFlush(bookingDetails);
        return new BookingResponse(booking.getId(), booking.getStatus(), total, booking.getExpiresAt(),
                bookingDetails.stream().map(d -> new BookingResponse.Detail(d.getBookingDate(),
                        d.getCourt().getId(), d.getTimeSlot().getId(), d.getPrice())).toList());
    }

    private void requireMoneyFits(BigDecimal amount) {
        if (amount.precision() > 10) {
            throw new BadRequestException("Booking price exceeds NUMERIC(10,2) capacity");
        }
    }

    private record SlotKey(LocalDate date, UUID courtId, UUID timeSlotId) { }
}
