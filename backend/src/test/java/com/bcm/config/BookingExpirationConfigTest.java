package com.bcm.config;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

/** P2.1 regression: the business clock must be pinned to the configured zone, not the JVM TZ. */
class BookingExpirationConfigTest {

    @Test
    void clockIsPinnedToDefaultBusinessZone() {
        assertThat(new BookingExpirationConfig().bookingExpirationClock("Asia/Ho_Chi_Minh").getZone())
                .isEqualTo(ZoneId.of("Asia/Ho_Chi_Minh"));
    }

    @Test
    void clockHonorsTimeZoneOverride() {
        assertThat(new BookingExpirationConfig().bookingExpirationClock("UTC").getZone())
                .isEqualTo(ZoneId.of("UTC"));
        Clock clock = new BookingExpirationConfig().bookingExpirationClock("Asia/Ho_Chi_Minh");
        assertThat(clock.withZone(ZoneId.of("UTC")).getZone()).isEqualTo(ZoneId.of("UTC"));
        // withZone must not mutate the pinned clock.
        assertThat(clock.getZone()).isEqualTo(ZoneId.of("Asia/Ho_Chi_Minh"));
    }
}
