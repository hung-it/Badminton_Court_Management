package com.bcm.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PosSaleRequest {

    @NotNull
    private UUID customerId;

    private UUID bookingId;

    private UUID staffId;

    @NotNull
    private String paymentMethod;

    @JsonProperty("court_fee")
    private BigDecimal courtFee = BigDecimal.ZERO;

    public BigDecimal getCourtAmount() {
        return courtFee;
    }

    public void setCourtAmount(BigDecimal courtAmount) {
        this.courtFee = courtAmount;
    }

    @Valid
    @NotEmpty
    private List<PosSaleItemRequest> items;
}