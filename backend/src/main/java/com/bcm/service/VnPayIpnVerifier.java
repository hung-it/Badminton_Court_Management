package com.bcm.service;

import com.bcm.config.BookingPaymentConfig;
import com.bcm.entity.PaymentMethod;
import com.bcm.exception.PaymentNotificationException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import static com.bcm.exception.PaymentNotificationException.Reason.*;

/** Official PAY v2.1.0 IPN/Return wire verification. Return callers must never settle this result. */
@Component
public class VnPayIpnVerifier {
    private static final DateTimeFormatter PAY_DATE = DateTimeFormatter.ofPattern("uuuuMMddHHmmss")
            .withResolverStyle(ResolverStyle.STRICT);
    private final BookingPaymentConfig config;
    private final Clock clock;

    public VnPayIpnVerifier(BookingPaymentConfig config, @Qualifier("bookingExpirationClock") Clock clock) {
        this.config = config;
        this.clock = clock;
    }

    public PaymentNotification verify(Map<String, String> parameters) {
        var settings = config.getVnpay();
        if (settings == null || settings.getHashSecret() == null || settings.getHashSecret().isBlank()
                || settings.getTmnCode() == null || settings.getTmnCode().isBlank()) { throw error(UNAVAILABLE); }
        var fields = new TreeMap<String, String>();
        if (parameters != null) {
            parameters.forEach((key, value) -> { if (key.startsWith("vnp_")) { fields.put(key, value); } });
        }
        if (!PaymentSignature.matches(VnPaySigner.sign(settings.getHashSecret(), VnPaySigner.canonicalData(fields)),
                fields.get("vnp_SecureHash"))) { throw error(INVALID_SIGNATURE); }
        if (!settings.getTmnCode().equals(fields.get("vnp_TmnCode"))) { throw error(INVALID_DATA); }
        String reference = required(fields, "vnp_TxnRef", "[0-9a-f]{32}", UNKNOWN_REFERENCE);
        UUID attempt = UUID.fromString(reference.substring(0, 8) + "-" + reference.substring(8, 12) + "-"
                + reference.substring(12, 16) + "-" + reference.substring(16, 20) + "-" + reference.substring(20));
        String amount = required(fields, "vnp_Amount", "[0-9]{1,12}", INVALID_DATA);
        required(fields, "vnp_BankCode", "[A-Za-z0-9]{3,20}", INVALID_DATA);
        required(fields, "vnp_OrderInfo", ".{1,255}", INVALID_DATA);
        String response = required(fields, "vnp_ResponseCode", "[0-9]{2}", INVALID_DATA);
        String status = required(fields, "vnp_TransactionStatus", "[0-9]{2}", INVALID_DATA);
        String transaction = required(fields, "vnp_TransactionNo", "[0-9]{1,15}", INVALID_DATA);
        boolean success = "00".equals(response) && "00".equals(status);
        if (success && new BigDecimal(transaction).signum() <= 0) { throw error(INVALID_DATA); }
        LocalDateTime date = null;
        // vnp_PayDate is optional. Preserve NULL if omitted, never substitute the server clock.
        String payDate = fields.get("vnp_PayDate");
        if (payDate != null && !payDate.isEmpty()) {
            try {
                if (!payDate.matches("[0-9]{14}")) { throw error(INVALID_DATA); }
                date = LocalDateTime.parse(payDate, PAY_DATE).atZone(ZoneId.of("Asia/Ho_Chi_Minh"))
                        .withZoneSameInstant(clock.getZone()).toLocalDateTime();
            } catch (java.time.DateTimeException ex) { throw error(INVALID_DATA); }
        }
        return new PaymentNotification(attempt, PaymentMethod.VNPAY, new BigDecimal(amount).movePointLeft(2),
                success, transaction, date);
    }

    private String required(Map<String, String> fields, String key, String pattern,
                            PaymentNotificationException.Reason reason) {
        String value = fields.get(key);
        if (value == null || !value.matches(pattern)) { throw error(reason); }
        return value;
    }

    private PaymentNotificationException error(PaymentNotificationException.Reason reason) {
        return new PaymentNotificationException(reason);
    }
}
