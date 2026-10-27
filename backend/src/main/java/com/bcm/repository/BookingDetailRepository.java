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
    @Query("select distinct d.court.id from BookingDetail d where d.booking.id = :bookingId")
    List<UUID> findCourtIdsForBooking(@Param("bookingId") UUID bookingId);

    @Modifying(flushAutomatically = true)
    @Query("delete from BookingDetail d where d.booking.id = :bookingId")
    int deleteForReplacement(@Param("bookingId") UUID bookingId);

    @Query("""
            select d.timeSlot.id from BookingDetail d
            where d.bookingDate = :date and d.court.id = :courtId
              and d.timeSlot.id in :slotIds and d.booking.id <> :bookingId
            """)
    List<UUID> findConflictingReplacementSlots(@Param("bookingId") UUID bookingId,
            @Param("date") LocalDate date, @Param("courtId") UUID courtId,
            @Param("slotIds") List<UUID> slotIds);
    @Query("""
            select d from BookingDetail d join fetch d.court join fetch d.timeSlot
            where d.booking.id = :bookingId
            order by d.bookingDate, d.timeSlot.startTime, d.court.branchId,
                     d.court.branchCourtNumber, d.court.courtCode, d.court.id, d.id
            """)
    List<BookingDetail> findHistoryDetails(@Param("bookingId") UUID bookingId);


    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from BookingDetail d where d.booking.id = :bookingId")
    int deleteAllForExpiredBooking(@Param("bookingId") UUID bookingId);

    @Query("""
            select d.court.id as courtId, d.timeSlot.id as timeSlotId, d.booking.status as bookingStatus
            from BookingDetail d
            where d.bookingDate = :date
              and (:courtId is null or d.court.id = :courtId)
            """)
    List<OccupiedSlot> findOccupiedSlots(@Param("date") LocalDate date,
                                         @Param("courtId") UUID courtId);

    interface OccupiedSlot {
        UUID getCourtId();
        UUID getTimeSlotId();
        com.bcm.entity.BookingStatus getBookingStatus();
    }

    @Query("""
            select d.bookingDate as bookingDate, d.court.id as courtId, d.timeSlot.id as timeSlotId,
                   d.booking.status as bookingStatus
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
