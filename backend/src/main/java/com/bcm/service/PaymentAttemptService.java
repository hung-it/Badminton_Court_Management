package com.bcm.service;

import com.bcm.dto.request.CreatePaymentAttemptRequest;
import com.bcm.dto.response.PaymentAttemptResponse;
import com.bcm.entity.BookingStatus;
import com.bcm.entity.Booking;
import com.bcm.entity.PaymentStatus;
import com.bcm.entity.PaymentMethod;
import jakarta.validation.Validator;
import com.bcm.entity.PaymentTransaction;
import com.bcm.exception.BadRequestException;
import com.bcm.exception.DuplicateResourceException;
import com.bcm.exception.ResourceNotFoundException;
import com.bcm.repository.BookingRepository;
import com.bcm.repository.PaymentTransactionRepository;
import com.bcm.security.CurrentCustomerService;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.UUID;

@Service
public class PaymentAttemptService {
    private final BookingRepository bookings;
    private final PaymentTransactionRepository payments;
    private final PaymentGatewayPreparation gateway;
    private final Clock clock;
    private final CurrentCustomerService currentCustomer;
    private final Validator validator;
    private final BookingPromotionService promotions;

    public PaymentAttemptService(BookingRepository bookings, PaymentTransactionRepository payments,
                                 PaymentGatewayPreparation gateway,
                                 @Qualifier("bookingExpirationClock") Clock clock,
                                 CurrentCustomerService currentCustomer, Validator validator, BookingPromotionService promotions) {
        this.bookings = bookings;
        this.payments = payments;
        this.gateway = gateway;
        this.clock = clock;
        this.currentCustomer = currentCustomer;
        this.validator = validator;
        this.promotions = promotions;
    }

    @Transactional
    public PaymentAttemptResponse createAttempt(UUID bookingId, CreatePaymentAttemptRequest request) {
        return createOrReuse(bookingId, request, false, () -> { });
    }

    @Transactional
    public PaymentAttemptResponse createOrReuseAttempt(UUID bookingId, CreatePaymentAttemptRequest request,
                                                       Runnable validateConfiguration) {
        return createOrReuse(bookingId, request, true, validateConfiguration);
    }

    private PaymentAttemptResponse createOrReuse(UUID bookingId, CreatePaymentAttemptRequest request, boolean reuse,
                                                Runnable validateConfiguration) {
        if (bookingId == null || request == null) {
            throw new BadRequestException("Booking ID and payment method are required");
        }
        gateway.validateMethod(request.getPaymentMethod());
        if (!validator.validate(request).isEmpty()
                || (request.getPaymentMethod() == PaymentMethod.BANK_TRANSFER && request.getBankTransfer() == null)) {
            throw new BadRequestException("Complete valid bank transfer information is required");
        }
        UUID trustedCustomerId = currentCustomer.requireCustomerId();
        // Reuse the existing Booking row lock without changing expiration's query or lock order.
        var booking = bookings.findByIdForExpiration(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found: " + bookingId));
        requireOwner(booking, trustedCustomerId);
        // Configuration validation is local only, before persistence and after ownership.
        validateConfiguration.run();
        if (request.getPaymentMethod() == PaymentMethod.BANK_TRANSFER) {
            var declared = payments.findBookingHistory(bookingId).stream()
                    .filter(p -> p.getPaymentMethod() == PaymentMethod.BANK_TRANSFER
                            && p.getStatus() == PaymentStatus.PENDING
                            && p.getNote() != null && p.getNote().startsWith("CUSTOMER_DECLARED;"))
                    .findFirst();
            if (booking.getStatus() != BookingStatus.PENDING && booking.getStatus() != BookingStatus.EXPIRED
                    && declared.isPresent()) {
                return response(declared.get(), booking);
            }
            if (booking.getStatus() == BookingStatus.EXPIRED || (booking.getStatus() == BookingStatus.PENDING
                    && (booking.getExpiresAt() == null || !booking.getExpiresAt().isAfter(LocalDateTime.now(clock))))) {
                throw new DuplicateResourceException("BOOKING_EXPIRED: Booking payment deadline has passed");
            }
        }
        requireEligible(booking);
        if (reuse) {
            var pending = payments.findFirstByBookingIdAndStatusOrderByCreatedAtAscIdAsc(bookingId, PaymentStatus.PENDING);
            if (pending.isPresent()) {
                var existing = pending.get();
                if (existing.getInvoice() != null || existing.getPaymentMethod() != request.getPaymentMethod()) {
                    throw new DuplicateResourceException("A PENDING payment attempt exists with a different method or target");
                }
                confirmDeclaredTransfer(existing, booking);
                return response(existing, booking);
            }
        }
        // The Booking lock serializes competing payment attempts.
        if (payments.existsByBookingIdAndStatus(bookingId, PaymentStatus.PENDING)) {
            throw new DuplicateResourceException("A PENDING payment attempt already exists for this booking");
        }
        var payment = new PaymentTransaction();
        payment.setBooking(booking);
        payment.setPaymentMethod(request.getPaymentMethod());
        payment.setStatus(PaymentStatus.PENDING);
        promotions.validatePayment(booking);
        payment.setAmount(booking.getFinalAmount());
        // invoice, transaction ID and transaction date remain NULL; no gateway result exists yet.
        confirmDeclaredTransfer(payment, booking);
        payments.saveAndFlush(payment);
        return response(payment, booking);
    }

    private void confirmDeclaredTransfer(PaymentTransaction payment, Booking booking) {
        if (payment.getPaymentMethod() != PaymentMethod.BANK_TRANSFER) return;
        // Booking confirmation is a business policy; PENDING still means money is unverified.
        payment.setNote("CUSTOMER_DECLARED; receipt=UNVERIFIED; declaredAt=" + LocalDateTime.now(clock));
        booking.setStatus(BookingStatus.PAID);
    }

    @Transactional
    public PaymentAttemptResponse recheckForInitiation(UUID attemptId) {
        UUID trustedCustomerId = currentCustomer.requireCustomerId();
        // Read only the target ID before locking; load the payment's current state AFTER the Booking lock.
        UUID bookingId = payments.findBookingIdForInitiation(attemptId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking payment attempt not found"));
        var booking = bookings.findByIdForExpiration(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found"));
        requireOwner(booking, trustedCustomerId);
        requireEligible(booking);
        var payment = payments.findById(attemptId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment attempt not found"));
        if (payment.getStatus() != PaymentStatus.PENDING || payment.getInvoice() != null) {
            throw new BadRequestException("Payment attempt must be PENDING and target a booking");
        }
        return response(payment, booking);
    }

    private void requireOwner(Booking booking, UUID trustedCustomerId) {
        if (!booking.getCustomer().getId().equals(trustedCustomerId)) {
            throw new AccessDeniedException("Booking not owned");
        }
    }

    private void requireEligible(Booking booking) {
        if (booking.getCheckoutReleaseAt() != null
                && !booking.getCheckoutReleaseAt().isAfter(LocalDateTime.now(clock))
                && !payments.existsByBookingIdAndStatus(booking.getId(), PaymentStatus.PENDING)) {
            throw new DuplicateResourceException("BOOKING_EXPIRED: Checkout release deadline has passed");
        }
        if (booking.getCourtFee() == null || booking.getCourtFee().signum() <= 0) {
            throw new BadRequestException("Booking amount must be positive");
        }
        if (booking.getStatus() != BookingStatus.PENDING || booking.getExpiresAt() == null
                || !booking.getExpiresAt().isAfter(LocalDateTime.now(clock))) {
            throw new BadRequestException("Booking must be PENDING and before its payment deadline");
        }
        if (payments.existsByBookingIdAndStatus(booking.getId(), PaymentStatus.SUCCESS)) {
            throw new DuplicateResourceException("Booking already has a successful payment; reconciliation required");
        }
        // Starting/continuing payment cancels an early release under the same Booking lock.
        // The original ten-minute deadline remains unchanged.
        booking.setCheckoutReleaseAt(null);
    }

    private PaymentAttemptResponse response(PaymentTransaction payment, Booking booking) {
        var preparation = gateway.prepare(payment.getPaymentMethod(), payment.getId(), payment.getAmount());
        return new PaymentAttemptResponse(payment.getId(), booking.getId(), payment.getPaymentMethod(),
                payment.getStatus(), payment.getAmount(), booking.getExpiresAt(), preparation,
                booking.getStatus(), payment.getPaymentMethod() == PaymentMethod.BANK_TRANSFER
                        && payment.getNote() != null && payment.getNote().startsWith("CUSTOMER_DECLARED;")
                        ? "CUSTOMER_DECLARED" : null);
    }
}
