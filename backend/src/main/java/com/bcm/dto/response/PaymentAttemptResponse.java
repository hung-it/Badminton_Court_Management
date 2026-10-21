package com.bcm.dto.response;

import com.bcm.entity.PaymentMethod;
import com.bcm.entity.PaymentStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public record PaymentAttemptResponse(UUID paymentAttemptId, UUID bookingId, PaymentMethod paymentMethod,
                                     PaymentStatus status, BigDecimal amount, LocalDateTime bookingExpiresAt,
                                     GatewayPreparation gatewayPreparation) {
    @Schema(description = "Runtime checkout artifacts. Checkout readiness means initiation only; payment remains PENDING.")
    public record GatewayPreparation(PaymentMethod provider, String merchantReference, BigDecimal amount,
                                     boolean checkoutReady, String checkoutUrl, String qrCodeData, String deeplink,
                                     String checkoutBlocker) {
        public GatewayPreparation(PaymentMethod provider, String merchantReference, BigDecimal amount, boolean checkoutReady) {
            this(provider, merchantReference, amount, checkoutReady, null, null, null, null);
        }

        public GatewayPreparation(PaymentMethod provider, String merchantReference, BigDecimal amount,
                                  boolean checkoutReady, String checkoutUrl, String qrCodeData, String deeplink) {
            this(provider, merchantReference, amount, checkoutReady, checkoutUrl, qrCodeData, deeplink, null);
        }

        // Spring MVC DEBUG response logging must not print signed checkout URLs or provider artifacts.
        @Override
        public String toString() {
            return "GatewayPreparation[provider=" + provider + ", checkoutReady=" + checkoutReady + "]";
        }
    }
}
