package com.bcm.config;

import com.bcm.entity.PaymentMethod;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.EnumSet;
import java.util.Set;

@Configuration
@ConfigurationProperties(prefix = "booking.payment")
@Getter
@Setter
public class BookingPaymentConfig {
    private Set<PaymentMethod> enabledMethods = EnumSet.of(PaymentMethod.VNPAY, PaymentMethod.MOMO);
    private Vnpay vnpay = new Vnpay();
    private Momo momo = new Momo();

    @Bean
    public HttpClient bookingPaymentHttpClient() {
        return HttpClient.newBuilder().connectTimeout(momo.getConnectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Getter
    @Setter
    public static class Vnpay {
        private String paymentUrl = "https://sandbox.vnpayment.vn/paymentv2/vpcpay.html";
        private String tmnCode = "";
        private String hashSecret = "";
        private String returnUrl = "";
    }

    @Getter
    @Setter
    public static class Momo {
        private String createPaymentUrl = "https://test-payment.momo.vn/v2/gateway/api/create";
        private String partnerCode = "";
        private String accessKey = "";
        private String secretKey = "";
        private String redirectUrl = "";
        private String ipnUrl = "";
        private Duration connectTimeout = Duration.ofSeconds(5);
        private Duration requestTimeout = Duration.ofSeconds(30);
    }
}
