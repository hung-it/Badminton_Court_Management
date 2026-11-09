package com.bcm.config;

import com.bcm.entity.PaymentMethod;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.EnumSet;
import java.util.Set;

@Configuration
@ConfigurationProperties(prefix = "booking.payment")
@Getter
@Setter
public class BookingPaymentConfig {
    private Set<PaymentMethod> enabledMethods = EnumSet.of(PaymentMethod.VNPAY);
    private String frontendResultUrl = "";
    private Vnpay vnpay = new Vnpay();
    /**
     * How long a PENDING payment attempt keeps a booking alive past its payment deadline.
     * A verified provider callback arriving inside this window still settles the booking, so a
     * customer who started paying is never charged without a reservation. Must be positive.
     */
    private Duration inFlightGrace = Duration.ofMinutes(5);

    @Getter
    @Setter
    public static class Vnpay {
        private String paymentUrl = "https://sandbox.vnpayment.vn/paymentv2/vpcpay.html";
        private String tmnCode = "";
        private String hashSecret = "";
        private String returnUrl = "";
    }

}
