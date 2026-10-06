package com.bcm.service;

import com.bcm.exception.PaymentNotificationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static com.bcm.service.PaymentCallbackFixtures.*;
import static org.assertj.core.api.Assertions.*;

class MoMoIpnVerifierTest {
    private final com.bcm.config.BookingPaymentConfig config = config();
    private final MoMoIpnVerifier verifier = new MoMoIpnVerifier(config);

    @Test
    void rawFormulaMatchesOfficialPaymentResultOrderExactly() {
        assertThat(MoMoIpnVerifier.signatureData("unit-test-access", momo(ATTEMPT, config)))
                .isEqualTo("accessKey=unit-test-access&amount=10000&extraData=&message=Successful."
                        + "&orderId=" + ATTEMPT + "&orderInfo=Booking payment " + ATTEMPT
                        + "&orderType=momo_wallet&partnerCode=TESTPARTNER&payType=qr&requestId=" + ATTEMPT
                        + "&responseTime=1791176400000&resultCode=0&transId=4088878653");
    }

    @Test
    void validIpnAcceptedWithoutResolvingCreateResponseBlocker() {
        var result = verifier.verify(momo(ATTEMPT, config));
        assertThat(result.attemptId()).isEqualTo(ATTEMPT);
        assertThat(result.providerSuccess()).isTrue();
        assertThat(result.transactionId()).isEqualTo("4088878653");
        assertThat(result.transactionDate()).isNull();
        assertThat(MoMoGateway.CHECKOUT_BLOCKER).isNotBlank();
    }

    @Test
    void wrongSecretRejected() {
        var body = momo(ATTEMPT, config);
        config.getMomo().setSecretKey("wrong-test-secret");
        assertThatThrownBy(() -> verifier.verify(body)).isInstanceOf(PaymentNotificationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"amount", "orderId", "requestId", "resultCode", "transId", "responseTime", "message", "extraData", "partnerCode", "orderInfo", "orderType", "payType"})
    void tamperedSignedFieldRejected(String key) {
        var body = momo(ATTEMPT, config);
        if (body.get(key).isNumber()) { body.put(key, body.get(key).longValue() + 1); }
        else { body.put(key, body.get(key).asText() + "-tampered"); }
        assertThatThrownBy(() -> verifier.verify(body)).isInstanceOf(PaymentNotificationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"amount", "orderId", "requestId", "resultCode", "transId", "responseTime", "message", "extraData", "partnerCode", "orderInfo", "orderType", "payType", "signature"})
    void missingMandatoryFieldRejected(String key) {
        var body = momo(ATTEMPT, config);
        body.remove(key);
        assertThatThrownBy(() -> verifier.verify(body)).isInstanceOf(PaymentNotificationException.class);
    }

    @Test
    void signedPartnerMismatchRejected() {
        var body = momo(ATTEMPT, config);
        body.put("partnerCode", "OTHERPARTNER");
        sign(body, config);
        assertThatThrownBy(() -> verifier.verify(body)).isInstanceOf(PaymentNotificationException.class);
    }

    @Test
    void signedRequestReferenceMismatchRejected() {
        var body = momo(ATTEMPT, config);
        body.put("requestId", java.util.UUID.randomUUID().toString());
        sign(body, config);
        assertThatThrownBy(() -> verifier.verify(body)).isInstanceOf(PaymentNotificationException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {1006, 9000, 7000})
    void failureAndAuthorizedIntermediateResultsAreNotSuccess(int code) {
        var body = momo(ATTEMPT, config);
        body.put("resultCode", code);
        sign(body, config);
        assertThat(verifier.verify(body).providerSuccess()).isFalse();
    }

    @Test
    void longTransactionIdPreservedExactly() {
        var body = momo(ATTEMPT, config);
        body.put("transId", Long.MAX_VALUE);
        sign(body, config);
        assertThat(verifier.verify(body).transactionId()).isEqualTo("9223372036854775807");
    }

    @ParameterizedTest
    @ValueSource(strings = {"amount", "resultCode", "transId", "responseTime"})
    void stringOrFractionalNumbersAreNotCoerced(String key) {
        var body = momo(ATTEMPT, config);
        body.put(key, "10000");
        assertThatThrownBy(() -> verifier.verify(body)).isInstanceOf(PaymentNotificationException.class);
        body.put(key, 10000.1);
        assertThatThrownBy(() -> verifier.verify(body)).isInstanceOf(PaymentNotificationException.class);
    }

    @Test
    void absentCredentialsDoNotBlockBuildButRejectIpn() {
        var absent = new MoMoIpnVerifier(new com.bcm.config.BookingPaymentConfig());
        assertThatThrownBy(() -> absent.verify(momo(ATTEMPT, config))).isInstanceOf(PaymentNotificationException.class)
                .extracting("reason").isEqualTo(PaymentNotificationException.Reason.UNAVAILABLE);
    }
}
