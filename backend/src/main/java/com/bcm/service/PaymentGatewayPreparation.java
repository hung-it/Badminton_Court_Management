package com.bcm.service;

import com.bcm.config.BookingPaymentConfig;
import com.bcm.dto.response.PaymentAttemptResponse.GatewayPreparation;
import com.bcm.entity.PaymentMethod;
import com.bcm.exception.BadRequestException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.UUID;

/** Local request-building boundary only. Provider-specific signing/HTTP belongs outside DB transactions. */
@Component
@RequiredArgsConstructor
public class PaymentGatewayPreparation {
    private final BookingPaymentConfig config;

    public void validateMethod(PaymentMethod method) {
        if (method != PaymentMethod.VNPAY) {
            throw new BadRequestException("Online booking payment supports VNPAY only");
        }
        if (config.getEnabledMethods() == null || !config.getEnabledMethods().contains(method)) {
            throw new BadRequestException("Payment method is disabled");
        }
    }

    public GatewayPreparation prepare(PaymentMethod method, UUID attemptId, BigDecimal amount) {
        validateMethod(method);
        // Merchant reference is internal correlation, never a gateway transaction_id.
        // VNPay wire signing belongs to VnPayGateway after this service transaction.
        return new GatewayPreparation(method, attemptId.toString(), amount, false);
    }
}
