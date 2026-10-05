package com.bcm.service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/** VNPAY Payment Gateway 2.1.0: sorted, URL-encoded query fields; not a JSON signature. */
public final class VnPaySigner {
    private VnPaySigner() { }

    public static String canonicalData(Map<String, String> fields) {
        return new TreeMap<>(fields).entrySet().stream()
                .filter(e -> !e.getKey().equals("vnp_SecureHash") && !e.getKey().equals("vnp_SecureHashType"))
                .filter(e -> e.getValue() != null && !e.getValue().isEmpty())
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
    }

    public static String sign(String secret, String canonicalData) {
        try {
            Mac mac = Mac.getInstance("HmacSHA512");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
            return HexFormat.of().formatHex(mac.doFinal(canonicalData.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("VNPay signing is unavailable");
        }
    }
}
