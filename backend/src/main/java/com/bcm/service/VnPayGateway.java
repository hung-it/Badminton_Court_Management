package com.bcm.service;

import com.bcm.config.BookingPaymentConfig;
import com.bcm.dto.response.PaymentAttemptResponse;
import com.bcm.dto.response.PaymentAttemptResponse.GatewayPreparation;
import com.bcm.entity.PaymentMethod;
import com.bcm.exception.BadRequestException;
import com.bcm.exception.PaymentInitiationException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.TreeMap;

@Component
public class VnPayGateway {
    private static final String SANDBOX_URL = "https://sandbox.vnpayment.vn/paymentv2/vpcpay.html";
    private static final ZoneId VNPAY_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private final BookingPaymentConfig config;
    private final Clock clock;

    public VnPayGateway(BookingPaymentConfig config, @Qualifier("bookingExpirationClock") Clock clock) {
        this.config = config;
        this.clock = clock;
    }

    public void validateConfiguration() {
        var settings = config.getVnpay();
        if (settings == null || !SANDBOX_URL.equals(settings.getPaymentUrl())
                || settings.getTmnCode() == null || !settings.getTmnCode().matches("[A-Za-z0-9]{8}")
                || settings.getHashSecret() == null || settings.getHashSecret().isBlank()
                || !validReturnUrl(settings.getReturnUrl())) {
            throw new PaymentInitiationException(HttpStatus.SERVICE_UNAVAILABLE, "VNPay sandbox configuration is unavailable");
        }
    }

    private boolean validReturnUrl(String value) {
        if (value == null || value.length() < 10 || value.length() > 255) { return false; }
        try {
            URI uri = URI.create(value);
            return ("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())) && uri.getHost() != null
                    && uri.getUserInfo() == null && uri.getFragment() == null;
        } catch (IllegalArgumentException ex) { return false; }
    }

    public static String providerAmount(BigDecimal amount) {
        try {
            String value = amount.movePointRight(2).toBigIntegerExact().toString();
            if (amount.signum() <= 0 || value.length() > 12) { throw new ArithmeticException(); }
            return value;
        } catch (ArithmeticException ex) {
            throw new BadRequestException("Amount is not representable by VNPay");
        }
    }

    public GatewayPreparation initiate(PaymentAttemptResponse attempt, String remoteAddress) {
        validateConfiguration();
        if (remoteAddress == null || remoteAddress.length() < 7 || remoteAddress.length() > 45
                || !remoteAddress.matches("[0-9a-fA-F:.]+")) {
            throw new BadRequestException("A valid remote IP address is required for VNPay");
        }
        var created = clock.instant().atZone(VNPAY_ZONE).truncatedTo(ChronoUnit.SECONDS);
        // Booking TIMESTAMP uses the application Clock zone; convert its instant explicitly to GMT+7.
        var deadline = attempt.bookingExpiresAt().atZone(clock.getZone()).withZoneSameInstant(VNPAY_ZONE)
                .truncatedTo(ChronoUnit.SECONDS);
        if (!deadline.isAfter(created)) {
            throw new BadRequestException("Booking deadline is too close for VNPay checkout");
        }
        var settings = config.getVnpay();
        var fields = new TreeMap<String, String>();
        fields.put("vnp_Version", "2.1.0");
        fields.put("vnp_Command", "pay");
        fields.put("vnp_TmnCode", settings.getTmnCode());
        fields.put("vnp_Amount", providerAmount(attempt.amount()));
        fields.put("vnp_CurrCode", "VND");
        fields.put("vnp_TxnRef", attempt.paymentAttemptId().toString().replace("-", ""));
        fields.put("vnp_OrderInfo", "Thanh toan booking " + attempt.paymentAttemptId().toString().replace("-", ""));
        fields.put("vnp_OrderType", "other");
        fields.put("vnp_Locale", "vn");
        fields.put("vnp_ReturnUrl", settings.getReturnUrl());
        fields.put("vnp_IpAddr", remoteAddress);
        fields.put("vnp_CreateDate", DATE_FORMAT.format(created));
        fields.put("vnp_ExpireDate", DATE_FORMAT.format(deadline));
        String data = VnPaySigner.canonicalData(fields);
        String url = settings.getPaymentUrl() + "?" + data + "&vnp_SecureHash=" + VnPaySigner.sign(settings.getHashSecret(), data);
        return new GatewayPreparation(PaymentMethod.VNPAY, attempt.paymentAttemptId().toString(), attempt.amount(),
                true, url, null, null);
    }
}
