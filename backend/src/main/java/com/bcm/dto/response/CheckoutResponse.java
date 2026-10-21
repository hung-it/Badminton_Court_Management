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