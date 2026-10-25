package com.bcm.repository;

import com.bcm.entity.Invoice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public interface InvoiceRepository extends JpaRepository<Invoice, UUID> {

	@Query("select coalesce(sum(i.productFee), 0) from Invoice i "
	    + "where i.bookingId = :bookingId and i.status = :status")
	BigDecimal sumProductFeeByBookingIdAndStatus(
	    @Param("bookingId") UUID bookingId,
	    @Param("status") String status);

	@Query("select coalesce(sum(i.discountAmount), 0) from Invoice i "
	    + "where i.bookingId = :bookingId and i.status = :status")
	BigDecimal sumDiscountAmountByBookingIdAndStatus(
	    @Param("bookingId") UUID bookingId,
	    @Param("status") String status);

	default BigDecimal sumProductAmountByBookingIdAndStatus(UUID bookingId, String status) {
		return sumProductFeeByBookingIdAndStatus(bookingId, status);
	}

    java.util.List<Invoice> findByBookingIdAndStatus(UUID bookingId, String status);

	    interface RevenueTotals {
            BigDecimal getCourtFee();
            BigDecimal getProductFee();
            BigDecimal getTotalAmount();
        }

	    @Query("select coalesce(sum(i.courtFee), 0) as courtFee, coalesce(sum(i.productFee), 0) as productFee, coalesce(sum(i.totalAmount), 0) as totalAmount "
		    + "from Invoice i where i.status = :status and i.deletedAt is null "
		    + "and i.createdAt >= :from and i.createdAt < :to")
	    RevenueTotals sumRevenueByPeriod(
		    @Param("status") String status,
		    @Param("from") LocalDateTime from,
		    @Param("to") LocalDateTime to);
}
