package com.bcm.dto.request;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class CheckoutRequest {

    @jakarta.validation.constraints.Pattern(regexp="(?i)CASH|VNPAY")
    private String paymentMethod;
    private boolean paymentConfirmed;
    @jakarta.validation.constraints.Size(max=255)
    private String reference;
    @jakarta.validation.constraints.DecimalMin("0.00")
    private java.math.BigDecimal expectedAmountDue;
    public CheckoutRequest(String paymentMethod) { this.paymentMethod=paymentMethod; }

}
