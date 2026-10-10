package com.bcm.dto.response;

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
    private BigDecimal courtAmount;
    private BigDecimal productAmount;
    private BigDecimal totalAmount;
}