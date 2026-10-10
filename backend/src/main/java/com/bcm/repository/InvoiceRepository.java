package com.bcm.repository;

import com.bcm.entity.Invoice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public interface InvoiceRepository extends JpaRepository<Invoice, UUID> {

	@Query("select coalesce(sum(i.productAmount), 0) from Invoice i "
	    + "where i.bookingId = :bookingId and i.status = :status")
	BigDecimal sumProductAmountByBookingIdAndStatus(
	    @Param("bookingId") UUID bookingId,
	    @Param("status") String status);

    java.util.List<Invoice> findByBookingIdAndStatus(UUID bookingId, String status);

	    @Query("select coalesce(sum(i.courtAmount), 0), coalesce(sum(i.productAmount), 0) "
		    + "from Invoice i where i.status = :status and i.deletedAt is null "
		    + "and i.createdAt >= :from and i.createdAt < :to")
	    Object[] sumRevenueByPeriod(
		    @Param("status") String status,
		    @Param("from") LocalDateTime from,
		    @Param("to") LocalDateTime to);
}