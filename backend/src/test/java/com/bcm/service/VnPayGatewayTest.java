package com.bcm.service;

import com.bcm.config.BookingPaymentConfig;
import com.bcm.dto.response.PaymentAttemptResponse;
import com.bcm.entity.PaymentMethod;
import com.bcm.entity.PaymentStatus;
import com.bcm.exception.BadRequestException;
import com.bcm.exception.PaymentInitiationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VnPayGatewayTest {
    private static final UUID ID = UUID.fromString("12345678-1234-4234-8234-123456789abc");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-05T05:00:00Z"), ZoneOffset.UTC);

    static BookingPaymentConfig configuration() {
        var config = new BookingPaymentConfig();
        config.getVnpay().setTmnCode("TEST1234");
        config.getVnpay().setHashSecret("unit-test-secret");
        config.getVnpay().setReturnUrl("https://merchant.example/return?label=A+B&next=/booking");
        return config;
    }

    private PaymentAttemptResponse attempt(LocalDateTime deadline) {
        return new PaymentAttemptResponse(ID, UUID.randomUUID(), PaymentMethod.VNPAY, PaymentStatus.PENDING,
                new BigDecimal("10000.00"), deadline, null);
    }

    @Test
    void canonicalDataSortsEncodesAndOmitsHashFieldsAndEmptyValues() {
        var fields = new LinkedHashMap<String, String>();
        fields.put("vnp_OrderInfo", "Thanh toan A+B & C/1");
        fields.put("vnp_SecureHash", "never-sign-this");
        fields.put("vnp_Amount", "1000000");
        fields.put("vnp_SecureHashType", "HmacSHA512");
        fields.put("vnp_BankCode", "");
        fields.put("vnp_Empty", null);
        assertThat(VnPaySigner.canonicalData(fields))
                .isEqualTo("vnp_Amount=1000000&vnp_OrderInfo=Thanh+toan+A%2BB+%26+C%2F1");
        var reordered = new LinkedHashMap<String, String>();
        fields.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> reordered.put(e.getKey(), e.getValue()));
        assertThat(VnPaySigner.canonicalData(reordered)).isEqualTo(VnPaySigner.canonicalData(fields));
    }

    @Test
    void hmacSha512MatchesKnownFixedVector() {
        assertThat(VnPaySigner.sign("key", "The quick brown fox jumps over the lazy dog"))
                .isEqualTo("b42af09057bac1e2d41708e48a902e09b5ff7f12ab428a4fe86653c73dd248fb"
                        + "82f948a549f7b791a5b41915ee4d1ec3935357e4e2317250d0372afa2ebeeb3a");
    }

    @Test
    void convertsAmountExactlyWithoutChangingDomainValue() {
        var amount = new BigDecimal("10000.00");
        assertThat(VnPayGateway.providerAmount(amount)).isEqualTo("1000000");
        assertThat(amount).isEqualTo(new BigDecimal("10000.00"));
        assertThat(VnPayGateway.providerAmount(new BigDecimal("10000.01"))).isEqualTo("1000001");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "10000.001", "10000000000"})
    void rejectsUnrepresentableAmount(String value) {
        assertThatThrownBy(() -> VnPayGateway.providerAmount(new BigDecimal(value)))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void buildsRequiredSignedSandboxUrlWithExplicitGmt7AndBoundedExpiry() {
        var config = configuration();
        var result = new VnPayGateway(config, CLOCK)
                .initiate(attempt(LocalDateTime.of(2026, 10, 5, 5, 15, 0, 999999999)), "192.0.2.10");
        assertThat(result.checkoutReady()).isTrue();
        assertThat(result.provider()).isEqualTo(PaymentMethod.VNPAY);
        assertThat(result.merchantReference()).isEqualTo(ID.toString());
        assertThat(result.checkoutUrl()).startsWith(config.getVnpay().getPaymentUrl() + "?")
                .doesNotContain(config.getVnpay().getHashSecret());
        var fields = fields(result.checkoutUrl());
        assertThat(fields).containsEntry("vnp_Version", "2.1.0").containsEntry("vnp_Command", "pay")
                .containsEntry("vnp_TmnCode", "TEST1234").containsEntry("vnp_Amount", "1000000")
                .containsEntry("vnp_CurrCode", "VND").containsEntry("vnp_TxnRef", ID.toString().replace("-", ""))
                .containsEntry("vnp_Locale", "vn").containsEntry("vnp_OrderType", "other")
                .containsEntry("vnp_ReturnUrl", config.getVnpay().getReturnUrl())
                .containsEntry("vnp_IpAddr", "192.0.2.10").containsEntry("vnp_CreateDate", "20261005120000")
                .containsEntry("vnp_ExpireDate", "20261005121500");
        assertThat(fields.get("vnp_OrderInfo")).matches("[A-Za-z0-9 ]+");
        assertThat(fields.get("vnp_SecureHash"))
                .isEqualTo(VnPaySigner.sign(config.getVnpay().getHashSecret(), VnPaySigner.canonicalData(fields)));
        assertThat(fields).hasSize(14).doesNotContainKey("vnp_SecureHashType");
        assertThat(result.toString()).doesNotContain(result.checkoutUrl(), fields.get("vnp_SecureHash"));
    }

    @Test
    void rejectsExpiryThatCannotBeRepresentedAsFutureSeconds() {
        assertThatThrownBy(() -> new VnPayGateway(configuration(), CLOCK)
                .initiate(attempt(LocalDateTime.of(2026, 10, 5, 5, 0, 0, 999999999)), "192.0.2.10"))
                .isInstanceOf(BadRequestException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"SECRET", "CODE", "RETURN", "ENDPOINT"})
    void invalidConfigurationMakesProviderUnavailableWithoutLeakingSecrets(String invalid) {
        var config = configuration();
        switch (invalid) {
            case "SECRET" -> config.getVnpay().setHashSecret("");
            case "CODE" -> config.getVnpay().setTmnCode("bad");
            case "RETURN" -> config.getVnpay().setReturnUrl("javascript:bad");
            case "ENDPOINT" -> config.getVnpay().setPaymentUrl("https://production.invalid/pay");
        }
        assertThatThrownBy(() -> new VnPayGateway(config, CLOCK).validateConfiguration())
                .isInstanceOf(PaymentInitiationException.class).hasMessage("VNPay sandbox configuration is unavailable");
    }

    @Test
    void bindsProviderConfigurationThroughSpring() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
                .withUserConfiguration(BookingPaymentConfig.class)
                .withPropertyValues("booking.payment.vnpay.tmn-code=TEST1234",
                        "booking.payment.vnpay.hash-secret=test-only",
                        "booking.payment.vnpay.return-url=https://merchant.example/return")
                .run(context -> {
                    var bound = context.getBean(BookingPaymentConfig.class);
                    assertThat(bound.getVnpay().getTmnCode()).isEqualTo("TEST1234");
                    assertThat(bound.getVnpay().getHashSecret()).isEqualTo("test-only");
                    new VnPayGateway(bound, CLOCK).validateConfiguration();
                });
    }

    static Map<String, String> fields(String url) {
        return Arrays.stream(URI.create(url).getRawQuery().split("&")).map(item -> item.split("=", 2))
                .collect(Collectors.toMap(item -> URLDecoder.decode(item[0], StandardCharsets.UTF_8),
                        item -> URLDecoder.decode(item[1], StandardCharsets.UTF_8)));
    }
}
