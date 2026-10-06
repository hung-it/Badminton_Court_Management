package com.bcm.service;

import com.bcm.config.BookingPaymentConfig;
import com.bcm.entity.PaymentMethod;
import com.bcm.exception.PaymentNotificationException;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.util.UUID;
import static com.bcm.exception.PaymentNotificationException.Reason.*;

/** Official captureWallet payment-result formula, independent of the ambiguous CREATE response. */
@Component
public class MoMoIpnVerifier {
    private final BookingPaymentConfig config;
    public MoMoIpnVerifier(BookingPaymentConfig config) { this.config = config; }

    public PaymentNotification verify(JsonNode body) {
        var settings = config.getMomo();
        if (settings == null || blank(settings.getAccessKey()) || blank(settings.getSecretKey())
                || blank(settings.getPartnerCode())) { throw new PaymentNotificationException(UNAVAILABLE); }
        String raw = signatureData(settings.getAccessKey(), body);
        if (!PaymentSignature.matches(MoMoSigner.sign(settings.getSecretKey(), raw), text(body, "signature"))) {
            throw new PaymentNotificationException(INVALID_SIGNATURE);
        }
        if (!settings.getPartnerCode().equals(text(body, "partnerCode"))
                || !"momo_wallet".equals(text(body, "orderType"))) { throw new PaymentNotificationException(INVALID_DATA); }
        String orderId = text(body, "orderId");
        if (!orderId.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
                || !orderId.equals(text(body, "requestId"))) { throw new PaymentNotificationException(UNKNOWN_REFERENCE); }
        long amount = number(body, "amount");
        long transaction = number(body, "transId");
        long responseTime = number(body, "responseTime");
        long result = number(body, "resultCode");
        if (amount <= 0 || responseTime <= 0 || result < Integer.MIN_VALUE || result > Integer.MAX_VALUE
                || (result == 0 && transaction <= 0)) { throw new PaymentNotificationException(INVALID_DATA); }
        // responseTime is notification response time, not a documented payment time; transaction_date stays NULL.
        return new PaymentNotification(UUID.fromString(orderId), PaymentMethod.MOMO, BigDecimal.valueOf(amount),
                result == 0, Long.toString(transaction), null);
    }

    public static String signatureData(String accessKey, JsonNode body) {
        return "accessKey=" + accessKey + "&amount=" + number(body, "amount") + "&extraData=" + text(body, "extraData")
                + "&message=" + text(body, "message") + "&orderId=" + text(body, "orderId")
                + "&orderInfo=" + text(body, "orderInfo") + "&orderType=" + text(body, "orderType")
                + "&partnerCode=" + text(body, "partnerCode") + "&payType=" + text(body, "payType")
                + "&requestId=" + text(body, "requestId") + "&responseTime=" + number(body, "responseTime")
                + "&resultCode=" + number(body, "resultCode") + "&transId=" + number(body, "transId");
    }

    private static String text(JsonNode body, String field) {
        if (body == null || !body.isObject() || !body.path(field).isTextual()) {
            throw new PaymentNotificationException(INVALID_DATA);
        }
        return body.path(field).textValue();
    }

    private static long number(JsonNode body, String field) {
        if (body == null || !body.isObject() || !body.path(field).isIntegralNumber() || !body.path(field).canConvertToLong()) {
            throw new PaymentNotificationException(INVALID_DATA);
        }
        return body.path(field).longValue();
    }

    private boolean blank(String value) { return value == null || value.isBlank(); }
}
