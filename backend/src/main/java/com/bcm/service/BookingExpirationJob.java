package com.bcm.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "booking.expiration-enabled", havingValue = "true", matchIfMissing = true)
public class BookingExpirationJob {
    private final BookingExpirationService expirationService;

    @Scheduled(fixedDelayString = "${booking.expiration-check-interval}",
            initialDelayString = "${booking.expiration-check-interval}")
    public void expireDueBookings() {
        // No outer transaction: each call crosses the service proxy and commits/rolls back one booking.
        for (var id : expirationService.findCandidates()) {
            try {
                expirationService.expireBooking(id);
            } catch (RuntimeException ex) {
                // A failed booking remains eligible for retry; continue with other candidates.
                log.warn("Could not expire booking {}; will retry on next poll", id, ex);
            }
        }
    }
}
