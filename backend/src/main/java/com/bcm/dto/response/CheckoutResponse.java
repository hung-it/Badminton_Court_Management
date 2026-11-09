package com.bcm.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CheckoutResponse {

    private UUID bookingId;
    private UUID invoiceId;

    @JsonProperty("court_fee")
    private BigDecimal courtFee;

    @JsonProperty("product_fee")
    private BigDecimal productFee;

    @JsonProperty("discount_amount")
    private BigDecimal discountAmount;

    @JsonProperty("total_amount")
    private BigDecimal totalAmount;
    private BigDecimal courtDiscount;
    private BigDecimal productDiscount;
    private BigDecimal paidCourtAmount;
    // Compatibility: amount to collect at this checkout, retained in replay responses.
    private BigDecimal amountDue;
    private BigDecimal collectedAmount;
    // Outstanding after the returned operation; zero after a successful checkout.
    private BigDecimal remainingDue;
    private boolean checkoutEligible;
    private String checkoutMessage;
    private java.time.LocalDateTime latestEndAt;
    private String paymentMethod;
    private java.util.List<String> paymentMethods;

    public BigDecimal getCourtAmount() {
        return courtFee;
    }

    public void setCourtAmount(BigDecimal courtAmount) {
        this.courtFee = courtAmount;
    }

    public BigDecimal getProductAmount() {
        return productFee;
    }

    public void setProductAmount(BigDecimal productAmount) {
        this.productFee = productAmount;
    }
}
