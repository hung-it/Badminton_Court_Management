package com.bcm.service;

import com.bcm.repository.BookingRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;

@Component
@Slf4j
@ConditionalOnProperty(name="booking.auto-checkout.enabled",havingValue="true",matchIfMissing=true)
public class BookingAutoCheckoutJob {
    private final BookingRepository bookings;
    private final CheckoutService checkout;
    private final Clock clock;
    private final Duration grace;

    public BookingAutoCheckoutJob(BookingRepository bookings, CheckoutService checkout, Clock clock,
            @Value("${booking.auto-checkout.grace:PT3H}") Duration grace) {
        if(grace.isNegative() || grace.isZero()) throw new IllegalArgumentException("Auto checkout grace must be positive");
        this.bookings=bookings;this.checkout=checkout;this.clock=clock;this.grace=grace;
    }

    @Scheduled(fixedDelayString="${booking.auto-checkout.interval:PT5M}",initialDelayString="${booking.auto-checkout.interval:PT5M}")
    public void run() {
        var cutoff=LocalDateTime.now(clock.withZone(ZoneId.of("Asia/Ho_Chi_Minh"))).minus(grace);
        for(var id:bookings.findAutoCheckoutCandidates(cutoff)) {
            try {
                if(checkout.autoCheckout(id,grace)) log.info("Automatically completed prepaid booking {}",id);
            } catch(com.bcm.exception.BadRequestException ex) {
                log.warn("Auto checkout requires reconciliation for booking {}: {}",id,ex.getMessage());
            } catch(Exception ex) {
                log.error("Auto checkout failed for booking {}",id,ex);
            }
        }
    }
}
