package com.bcm.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Data
@NoArgsConstructor
public class PosSaleRequest {

    private UUID customerId;

    private UUID bookingId;

    private UUID staffId;

    @jakarta.validation.constraints.Pattern(regexp="(?i)CASH|VNPAY", message="Payment method must be CASH or VNPAY")
    private String paymentMethod;
    private UUID idempotencyKey;
    private boolean paymentConfirmed;
    @jakarta.validation.constraints.DecimalMin("0.00")
    private BigDecimal expectedTotal;
    @jakarta.validation.constraints.Size(max=255)
    private String reference;

    @JsonProperty("court_fee")
    private BigDecimal courtFee = BigDecimal.ZERO;

    @JsonProperty("discount_amount")
    private BigDecimal discountAmount = BigDecimal.ZERO;

    public BigDecimal getCourtAmount() {
        return courtFee;
    }

    public void setCourtAmount(BigDecimal courtAmount) {
        this.courtFee = courtAmount;
    }

    @Valid
    @NotEmpty
    private List<PosSaleItemRequest> items;

    @jakarta.validation.constraints.Size(max=50)
    private String promotionCode;

    public PosSaleRequest(UUID customerId, UUID bookingId, UUID staffId, String paymentMethod,
            BigDecimal courtFee, BigDecimal discountAmount, List<PosSaleItemRequest> items, String promotionCode) {
        this.customerId=customerId; this.bookingId=bookingId; this.staffId=staffId;
        this.paymentMethod=paymentMethod; this.courtFee=courtFee; this.discountAmount=discountAmount;
        this.items=items; this.promotionCode=promotionCode;
    }

    public PosSaleRequest(UUID customerId, UUID bookingId, UUID staffId, String paymentMethod,
            BigDecimal courtFee, BigDecimal discountAmount, List<PosSaleItemRequest> items) {
        this(customerId,bookingId,staffId,paymentMethod,courtFee,discountAmount,items,null);
    }
}
