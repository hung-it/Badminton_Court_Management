package com.bcm.service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;

/** Only the verified captureWallet CREATE REQUEST signature, never a guessed response/IPN formula. */
public final class MoMoSigner {
    private MoMoSigner() { }

    public static String requestData(String accessKey, MoMoGateway.CreatePaymentRequest request) {
        return "accessKey=" + accessKey + "&amount=" + request.amount() + "&extraData=" + request.extraData()
                + "&ipnUrl=" + request.ipnUrl() + "&orderId=" + request.orderId() + "&orderInfo=" + request.orderInfo()
                + "&partnerCode=" + request.partnerCode() + "&redirectUrl=" + request.redirectUrl()
                + "&requestId=" + request.requestId() + "&requestType=" + request.requestType();
    }

    public static String sign(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("MoMo request signing is unavailable");
        }
    }
}
