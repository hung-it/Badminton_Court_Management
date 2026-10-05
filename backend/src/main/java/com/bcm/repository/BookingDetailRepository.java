package com.bcm.repository;

import com.bcm.entity.BookingDetail;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface BookingDetailRepository extends JpaRepository<BookingDetail, UUID> {

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from BookingDetail d where d.booking.id = :bookingId")
    int deleteAllForExpiredBooking(@Param("bookingId") UUID bookingId);

    @Query("""
            select d.court.id as courtId, d.timeSlot.id as timeSlotId
            from BookingDetail d
            where d.bookingDate = :date
              and (:courtId is null or d.court.id = :courtId)
            """)
    List<OccupiedSlot> findOccupiedSlots(@Param("date") LocalDate date,
                                         @Param("courtId") UUID courtId);

    interface OccupiedSlot {
        UUID getCourtId();
        UUID getTimeSlotId();
    }

    @Query("""
            select d.bookingDate as bookingDate, d.court.id as courtId, d.timeSlot.id as timeSlotId
            from BookingDetail d
            where d.bookingDate in :dates and d.court.id in :courtIds and d.timeSlot.id in :slotIds
            """)
    List<OccupiedBookingSlot> findOccupiedSlotsForBooking(@Param("dates") List<LocalDate> dates,
                                                         @Param("courtIds") List<UUID> courtIds,
                                                         @Param("slotIds") List<UUID> slotIds);

    interface OccupiedBookingSlot extends OccupiedSlot {
        LocalDate getBookingDate();
    }
}
