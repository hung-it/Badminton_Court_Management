package com.bcm.repository;

import com.bcm.entity.Invoice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public interface InvoiceRepository extends JpaRepository<Invoice, UUID> {

    @Query("select i from Invoice i where i.status='PAID' and i.deletedAt is null and i.createdAt>=:from and i.createdAt<:to and (:method='' or i.paymentMethod=:method) and (:search='%%' or exists (select c.id from Customer c where c.id=i.customerId and lower(c.fullName) like :search) or lower(cast(i.id as string)) like :search or lower(cast(i.bookingId as string)) like :search)")
    org.springframework.data.domain.Page<Invoice> searchPaidHistory(@Param("from") LocalDateTime from,@Param("to") LocalDateTime to,@Param("search") String search,@Param("method") String method,org.springframework.data.domain.Pageable pageable);

    @Query("select i from Invoice i where i.status='PAID' and i.deletedAt is null and i.createdAt>=:from and i.createdAt<:to")
    org.springframework.data.domain.Page<Invoice> findPaidHistory(@Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to, org.springframework.data.domain.Pageable pageable);

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
            BigDecimal getDiscountAmount();
        }

	    @Query("select coalesce(sum(i.courtFee), 0) as courtFee, coalesce(sum(i.productFee), 0) as productFee, coalesce(sum(i.totalAmount), 0) as totalAmount, coalesce(sum(i.discountAmount), 0) as discountAmount "
		    + "from Invoice i where i.status = :status and i.deletedAt is null "
		    + "and i.createdAt >= :from and i.createdAt < :to")
	    RevenueTotals sumRevenueByPeriod(
		    @Param("status") String status,
		    @Param("from") LocalDateTime from,
		    @Param("to") LocalDateTime to);
}
