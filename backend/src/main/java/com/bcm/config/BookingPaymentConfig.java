package com.bcm.config;

import com.bcm.entity.PaymentMethod;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.EnumSet;
import java.util.Set;

@Configuration
@ConfigurationProperties(prefix = "booking.payment")
@Getter
@Setter
public class BookingPaymentConfig {
    private Set<PaymentMethod> enabledMethods = EnumSet.of(PaymentMethod.VNPAY);
    private Vnpay vnpay = new Vnpay();

    @Getter
    @Setter
    public static class Vnpay {
        private String paymentUrl = "https://sandbox.vnpayment.vn/paymentv2/vpcpay.html";
        private String tmnCode = "";
        private String hashSecret = "";
        private String returnUrl = "";
    }

}
