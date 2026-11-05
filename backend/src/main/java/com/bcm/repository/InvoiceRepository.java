package com.bcm.repository;

import com.bcm.entity.Invoice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public interface InvoiceRepository extends JpaRepository<Invoice, UUID> {
    java.util.Optional<Invoice> findByPosRequestKey(UUID key);
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from Invoice i where i.id=:id")
    java.util.Optional<Invoice> findForUpdate(@Param("id") UUID id);


    @Query("select i from Invoice i where i.status='PAID' and i.deletedAt is null and i.createdAt>=:from and i.createdAt<:to and (:method='' or exists (select p.id from PaymentTransaction p where p.status=com.bcm.entity.PaymentStatus.SUCCESS and cast(p.paymentMethod as string)=:method and (p.invoice.id=i.id or (p.booking.id=i.bookingId and i.parentInvoiceId is null)))) and (:search='%%' or exists (select c.id from Customer c where c.id=i.customerId and lower(c.fullName) like :search) or lower(cast(i.id as string)) like :search or lower(cast(i.bookingId as string)) like :search)")
    org.springframework.data.domain.Page<Invoice> searchPaidHistory(@Param("from") LocalDateTime from,@Param("to") LocalDateTime to,@Param("search") String search,@Param("method") String method,org.springframework.data.domain.Pageable pageable);

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

    java.util.List<Invoice> findByBookingIdAndStatus(UUID bookingId, String status);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from Invoice i where i.bookingId=:bookingId and i.status=:status and i.deletedAt is null order by i.id")
    java.util.List<Invoice> findByBookingIdAndStatusForUpdate(@Param("bookingId") UUID bookingId, @Param("status") String status);


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
