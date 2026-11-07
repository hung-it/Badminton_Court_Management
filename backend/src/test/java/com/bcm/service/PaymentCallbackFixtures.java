package com.bcm.service;

import com.bcm.config.BookingPaymentConfig;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

final class PaymentCallbackFixtures {
    static final UUID ATTEMPT = UUID.fromString("12345678-1234-4234-8234-123456789abc");
    static BookingPaymentConfig config() {
        var config = VnPayGatewayTest.configuration();
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

}
