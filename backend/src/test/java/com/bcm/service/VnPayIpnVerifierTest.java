package com.bcm.service;

import com.bcm.entity.PaymentMethod;
import com.bcm.exception.PaymentNotificationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import static com.bcm.service.PaymentCallbackFixtures.*;
import static org.assertj.core.api.Assertions.*;

class VnPayIpnVerifierTest {
    private final com.bcm.config.BookingPaymentConfig config = config();
    private final VnPayIpnVerifier verifier = new VnPayIpnVerifier(config,
            Clock.fixed(Instant.parse("2026-10-05T05:00:00Z"), ZoneOffset.UTC));

    @Test
    void validSignatureResolvesExactAttemptAmountRealTransactionAndGmt7Date() {
        var verified = verifier.verify(vnpay(ATTEMPT, config));
        assertThat(verified.attemptId()).isEqualTo(ATTEMPT);
        assertThat(verified.method()).isEqualTo(PaymentMethod.VNPAY);
        assertThat(verified.amount()).isEqualTo(new BigDecimal("10000.01"));
        assertThat(verified.providerSuccess()).isTrue();
        assertThat(verified.transactionId()).isEqualTo("14226112");
        assertThat(verified.transactionDate()).isEqualTo(LocalDateTime.of(2026, 10, 5, 5, 0));
    }

    @Test
    void wrongSecretRejected() {
        var fields = vnpay(ATTEMPT, config);
        config.getVnpay().setHashSecret("different-test-secret");
        assertThatThrownBy(() -> verifier.verify(fields)).isInstanceOf(PaymentNotificationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"vnp_Amount", "vnp_TxnRef", "vnp_ResponseCode", "vnp_TransactionStatus", "vnp_TransactionNo", "vnp_PayDate"})
    void anySignedFieldTamperingRejected(String key) {
        var fields = vnpay(ATTEMPT, config);
        fields.put(key, fields.get(key) + "1");
        assertThatThrownBy(() -> verifier.verify(fields)).isInstanceOf(PaymentNotificationException.class)
                .extracting("reason").isEqualTo(PaymentNotificationException.Reason.INVALID_SIGNATURE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "bad", "gg", "00", "FFFFFFFF"})
    void malformedSignatureRejected(String hash) {
        var fields = vnpay(ATTEMPT, config);
        fields.put("vnp_SecureHash", hash);
        assertThatThrownBy(() -> verifier.verify(fields)).isInstanceOf(PaymentNotificationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"vnp_ResponseCode", "vnp_TransactionStatus"})
    void correctlySignedNonzeroEitherStatusIsNotProviderSuccess(String key) {
        var fields = vnpay(ATTEMPT, config);
        fields.put(key, "01");
        sign(fields, config);
        assertThat(verifier.verify(fields).providerSuccess()).isFalse();
    }

    @Test
    void wrongMerchantEvenWithValidSignatureRejected() {
        var fields = vnpay(ATTEMPT, config);
        fields.put("vnp_TmnCode", "OTHER123");
        sign(fields, config);
        assertThatThrownBy(() -> verifier.verify(fields)).isInstanceOf(PaymentNotificationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"20260230120000", "20261005126000", "not-a-date", "2026100512000"})
    void malformedPayDateDoesNotSubstituteCurrentTime(String value) {
        var fields = vnpay(ATTEMPT, config);
        fields.put("vnp_PayDate", value);
        sign(fields, config);
        assertThatThrownBy(() -> verifier.verify(fields)).isInstanceOf(PaymentNotificationException.class);
    }

    @Test
    void optionalDateIsNullAndSignatureTypeIsExcluded() {
        var fields = vnpay(ATTEMPT, config);
        fields.remove("vnp_PayDate");
        fields.put("vnp_SecureHashType", "HmacSHA512");
        sign(fields, config);
        assertThat(verifier.verify(fields).transactionDate()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "1234567890123456", "1e6", "-1", "abc"})
    void invalidSuccessfulTransactionNumberRejected(String value) {
        var fields = vnpay(ATTEMPT, config);
        fields.put("vnp_TransactionNo", value);
        sign(fields, config);
        assertThatThrownBy(() -> verifier.verify(fields)).isInstanceOf(PaymentNotificationException.class);
    }

    @Test
    void absentCredentialsDoNotAcceptEmptySecret() {
        config.getVnpay().setHashSecret("");
        assertThatThrownBy(() -> verifier.verify(java.util.Map.of())).isInstanceOf(PaymentNotificationException.class)
                .extracting("reason").isEqualTo(PaymentNotificationException.Reason.UNAVAILABLE);
    }
}
