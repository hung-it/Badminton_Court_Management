package com.bcm.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;
import java.time.ZoneId;

@Configuration
@EnableScheduling
public class BookingExpirationConfig {
    /**
     * Pins the business clock to the application's zone so deadline logic is deterministic
     * regardless of the JVM/container TZ (stored TIMESTAMPs are naive local time in this zone).
     * Override via booking.timezone if the deployment zone ever changes.
     */
    @Bean
    public Clock bookingExpirationClock(@Value("${booking.timezone:Asia/Ho_Chi_Minh}") String timeZone) {
        return Clock.system(ZoneId.of(timeZone));
    }
}
