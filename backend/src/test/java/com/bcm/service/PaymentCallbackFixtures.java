package com.bcm.service;

import com.bcm.config.BookingPaymentConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

final class PaymentCallbackFixtures {
    static final UUID ATTEMPT = UUID.fromString("12345678-1234-4234-8234-123456789abc");
    static BookingPaymentConfig config() {
        var config = VnPayGatewayTest.configuration();
        config.getMomo().setPartnerCode("TESTPARTNER");
        config.getMomo().setAccessKey("unit-test-access");
        config.getMomo().setSecretKey("unit-test-secret");
        return config;
    }

    static Map<String, String> vnpay(UUID attempt, BookingPaymentConfig config) {
        var fields = new TreeMap<String, String>();
        fields.put("vnp_TmnCode", config.getVnpay().getTmnCode());
        fields.put("vnp_TxnRef", attempt.toString().replace("-", ""));
        fields.put("vnp_Amount", "1000001");
        fields.put("vnp_BankCode", "NCB");
        fields.put("vnp_OrderInfo", "Thanh toan A+B & C/1");
        fields.put("vnp_ResponseCode", "00");
        fields.put("vnp_TransactionStatus", "00");
        fields.put("vnp_TransactionNo", "14226112");
        fields.put("vnp_PayDate", "20261005120000");
        sign(fields, config);
        return fields;
    }

    static void sign(Map<String, String> fields, BookingPaymentConfig config) {
        fields.put("vnp_SecureHash", VnPaySigner.sign(config.getVnpay().getHashSecret(), VnPaySigner.canonicalData(fields)));
    }

    static ObjectNode momo(UUID attempt, BookingPaymentConfig config) {
        var body = new ObjectMapper().createObjectNode();
        body.put("partnerCode", config.getMomo().getPartnerCode());
        body.put("orderId", attempt.toString());
        body.put("requestId", attempt.toString());
        body.put("amount", 10000L);
        body.put("orderInfo", "Booking payment " + attempt);
        body.put("orderType", "momo_wallet");
        body.put("transId", 4088878653L);
        body.put("resultCode", 0);
        body.put("message", "Successful.");
        body.put("payType", "qr");
        body.put("responseTime", 1791176400000L);
        body.put("extraData", "");
        sign(body, config);
        return body;
    }

    static void sign(ObjectNode body, BookingPaymentConfig config) {
        body.put("signature", MoMoSigner.sign(config.getMomo().getSecretKey(),
                MoMoIpnVerifier.signatureData(config.getMomo().getAccessKey(), body)));
    }
}
