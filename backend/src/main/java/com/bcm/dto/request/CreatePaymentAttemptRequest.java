package com.bcm.dto.request;

import com.bcm.entity.PaymentMethod;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreatePaymentAttemptRequest {
    @NotNull
    @Schema(type = "string", allowableValues = {"VNPAY"}, description = "VNPay Sandbox is the only payment method")
    private PaymentMethod paymentMethod;
}
