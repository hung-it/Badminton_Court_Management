package com.bcm.dto.response;

import java.time.Instant;
import java.time.LocalDate;

/** Public clock snapshot for selecting future slots, independent of client timezone/clock. */
public record BookingClockResponse(Instant serverTime, String timeZone, LocalDate today) { }
