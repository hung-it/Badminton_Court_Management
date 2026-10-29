package com.bcm.dto.request;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
public record CourtPromotionRequest(
 @NotBlank @Size(max=255) String name,
 @NotBlank @Pattern(regexp="[A-Za-z0-9_-]{2,50}") String code,
 @NotBlank @Pattern(regexp="INVOICE_TOTAL|VOUCHER") String ruleType,
 @NotBlank @Pattern(regexp="PERCENT|AMOUNT") String discountType,
 @NotNull @DecimalMin("0.01") @Digits(integer=8,fraction=2) BigDecimal discountValue,
 @NotNull @DecimalMin("0") @Digits(integer=8,fraction=2) BigDecimal minInvoiceAmount,
 @DecimalMin("0.01") @Digits(integer=8,fraction=2) BigDecimal maxDiscountAmount,
 @Min(1) Integer maxTotalUsage, @Min(1) Integer maxUsagePerCustomer,
 @NotNull LocalDate validFrom, LocalDate validTo, boolean active) {}
