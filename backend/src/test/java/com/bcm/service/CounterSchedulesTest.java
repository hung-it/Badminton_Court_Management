package com.bcm.service;

import com.bcm.entity.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class CounterSchedulesTest {
    BookingDetail detail(Court court,String date,String start,String end) {
        var d=new BookingDetail();d.setCourt(court);d.setBookingDate(LocalDate.parse(date));
        var slot=new TimeSlot();slot.setStartTime(LocalTime.parse(start));slot.setEndTime(LocalTime.parse(end));d.setTimeSlot(slot);return d;
    }
    Court court(int number){var c=new Court();c.setId(UUID.randomUUID());c.setCourtCode(number);c.setName("Court "+number);return c;}
    @Test void groupsOnlyAdjacentSlotsOnSameCourtAndDateInStableOrder() {
        var a=court(1);var b=court(2);
        var rows=List.of(detail(a,"2030-01-02","10:00","11:00"),detail(b,"2030-01-02","06:00","07:00"),
            detail(a,"2030-01-03","07:00","08:00"),detail(a,"2030-01-02","07:00","08:00"),detail(a,"2030-01-02","06:00","07:00"));
        var result=CheckoutService.courtSchedules(rows);
        assertThat(result).hasSize(4);
        assertThat(result.get(0).courtId()).isEqualTo(a.getId());
        assertThat(result.get(0).startTime()).isEqualTo(LocalTime.of(6,0));
        assertThat(result.get(0).endTime()).isEqualTo(LocalTime.of(8,0));
        assertThat(result.get(1).courtId()).isEqualTo(b.getId());
        assertThat(result.get(2).startTime()).isEqualTo(LocalTime.of(10,0));
        assertThat(result.get(3).bookingDate()).isEqualTo(LocalDate.of(2030,1,3));
        assertThat(CheckoutService.courtSchedules(List.of())).isEmpty();
    }
}
