package com.bcm.service;

import com.bcm.dto.response.BookingHistoryDetailResponse;
import com.bcm.dto.response.BookingHistoryResponse;
import com.bcm.entity.BookingStatus;
import com.bcm.exception.BadRequestException;
import com.bcm.exception.ResourceNotFoundException;
import com.bcm.repository.BookingDetailRepository;
import com.bcm.repository.BookingRepository;
import com.bcm.repository.PaymentTransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class BookingHistoryService {
    private final BookingRepository bookings;
    private final BookingDetailRepository details;
    private final PaymentTransactionRepository payments;
    private final com.bcm.security.CurrentCustomerService currentCustomer;

    public BookingHistoryResponse history(UUID customerId, BookingStatus status, int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new BadRequestException("page must be >= 0 and size must be 1..100");
        }
        UUID trustedCustomerId = currentCustomer.requireCustomerId();
        if (customerId != null && !customerId.equals(trustedCustomerId)) {
            throw new org.springframework.security.access.AccessDeniedException("Customer mismatch");
        }
        var result = bookings.findHistory(trustedCustomerId, status, PageRequest.of(page, size));
        return new BookingHistoryResponse(result.getContent(), page, size,
                result.getTotalElements(), result.getTotalPages());
    }

    public BookingHistoryDetailResponse detail(UUID bookingId) {
        if (bookingId == null) { throw new BadRequestException("bookingId is required"); }
        UUID trustedCustomerId = currentCustomer.requireCustomerId();
        var booking = bookings.findById(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found: " + bookingId));
        if (!booking.getCustomer().getId().equals(trustedCustomerId)) {
            throw new org.springframework.security.access.AccessDeniedException("Booking not owned");
        }
        // One consistent PostgreSQL snapshot across header, details and payments; no lifecycle locks.
        var slots = details.findHistoryDetails(bookingId).stream().map(d ->
                new BookingHistoryDetailResponse.Detail(d.getId(), d.getBookingDate(), d.getCourt().getId(),
                        d.getTimeSlot().getId(), d.getPrice(), d.getCourt().getCourtNumber(), d.getCourt().getName(),
                        d.getTimeSlot().getStartTime(), d.getTimeSlot().getEndTime())).toList();
        var transactions = payments.findBookingHistory(bookingId).stream().map(p ->
                new BookingHistoryDetailResponse.Payment(p.getId(), p.getPaymentMethod(), p.getStatus(),
                        p.getAmount(), p.getTransactionId(), p.getTransactionDate(), p.getCreatedAt())).toList();
        return new BookingHistoryDetailResponse(booking.getId(), booking.getCustomer().getId(), booking.getStatus(),
                booking.getCourtFee(), booking.getExpiresAt(), booking.getCreatedAt(), booking.getUpdatedAt(),
                slots, transactions);
    }
}
