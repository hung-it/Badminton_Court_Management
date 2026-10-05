package com.bcm.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;

@Configuration
@EnableScheduling
public class BookingExpirationConfig {
    @Bean
    public Clock bookingExpirationClock() {
        // Same local TIMESTAMP convention as booking creation; isolated bean for deterministic deadline tests.
        return Clock.systemDefaultZone();
    }
}
