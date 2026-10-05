package com.bcm.service;

import com.bcm.config.BookingPaymentConfig;
import com.bcm.dto.response.PaymentAttemptResponse;
import com.bcm.exception.BadRequestException;
import com.bcm.exception.PaymentInitiationException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** MoMo One-Time Wallet captureWallet. Response is parsed/correlated, but NOT cryptographically verified. */
@Component
public class MoMoGateway {
    public static final String CHECKOUT_BLOCKER = "MoMo Create Payment response signature contract is ambiguous in the official EN/VI documentation.";
    private static final String SANDBOX_URL = "https://test-payment.momo.vn/v2/gateway/api/create";
    private final BookingPaymentConfig config;
    private final ObjectMapper mapper;
    private final HttpClient http;

    public MoMoGateway(BookingPaymentConfig config, ObjectMapper mapper,
                       @Qualifier("bookingPaymentHttpClient") HttpClient http) {
        this.config = config;
        this.mapper = mapper;
        this.http = http;
    }

    public void validateConfiguration() {
        var settings = config.getMomo();
        if (settings == null || !SANDBOX_URL.equals(settings.getCreatePaymentUrl())
                || !present(settings.getPartnerCode()) || settings.getPartnerCode().length() > 50
                || !present(settings.getAccessKey()) || !present(settings.getSecretKey())
                || !webUrl(settings.getRedirectUrl()) || !webUrl(settings.getIpnUrl())
                || !positive(settings.getConnectTimeout()) || !positive(settings.getRequestTimeout())) {
            throw new PaymentInitiationException(HttpStatus.SERVICE_UNAVAILABLE, "MoMo sandbox configuration is unavailable");
        }
    }

    private boolean present(String value) { return value != null && !value.isBlank(); }
    private boolean positive(Duration value) { return value != null && !value.isNegative() && !value.isZero(); }
    private boolean webUrl(String value) {
        if (!present(value)) { return false; }
        try {
            URI uri = URI.create(value);
            return ("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())) && uri.getHost() != null
                    && uri.getUserInfo() == null && uri.getFragment() == null;
        } catch (IllegalArgumentException ex) { return false; }
    }

    public static long providerAmount(BigDecimal amount) {
        try {
            long value = amount.longValueExact();
            if (value < 1000 || value > 50000000) { throw new ArithmeticException(); }
            return value;
        } catch (ArithmeticException ex) {
            throw new BadRequestException("MoMo requires whole VND between 1000 and 50000000");
        }
    }

    public CreatePaymentRequest buildRequest(PaymentAttemptResponse attempt) {
        validateConfiguration();
        var settings = config.getMomo();
        String reference = attempt.paymentAttemptId().toString();
        var unsigned = new CreatePaymentRequest(settings.getPartnerCode(), reference, providerAmount(attempt.amount()),
                reference, "Booking payment " + reference, settings.getRedirectUrl(), settings.getIpnUrl(),
                "captureWallet", "", "vi", null);
        return new CreatePaymentRequest(unsigned.partnerCode(), unsigned.requestId(), unsigned.amount(), unsigned.orderId(),
                unsigned.orderInfo(), unsigned.redirectUrl(), unsigned.ipnUrl(), unsigned.requestType(), unsigned.extraData(),
                unsigned.lang(), MoMoSigner.sign(settings.getSecretKey(), MoMoSigner.requestData(settings.getAccessKey(), unsigned)));
    }

    public CheckoutBootstrap initiate(PaymentAttemptResponse attempt) {
        var payload = buildRequest(attempt);
        try {
            var request = HttpRequest.newBuilder(URI.create(config.getMomo().getCreatePaymentUrl()))
                    .timeout(config.getMomo().getRequestTimeout())
                    .header("Content-Type", "application/json; charset=UTF-8")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload), StandardCharsets.UTF_8)).build();
            // Exactly one send. Explicit caller retry reuses the same UUID requestId/orderId (MoMo idempotency key).
            var response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw failure("MoMo Create Payment HTTP request failed");
            }
            return parseResponse(response.body(), payload);
        } catch (HttpTimeoutException ex) {
            throw failure("MoMo Create Payment timed out; retry the same attempt");
        } catch (IOException ex) {
            throw failure("MoMo Create Payment response could not be read");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw failure("MoMo Create Payment request was interrupted");
        }
    }

    CheckoutBootstrap parseResponse(String body, CreatePaymentRequest request) throws IOException {
        JsonNode response = mapper.readTree(body);
        if (response == null || !response.isObject() || !response.path("resultCode").isIntegralNumber()
                || !response.path("resultCode").canConvertToInt()) { throw failure("Malformed MoMo Create Payment response"); }
        if (response.path("resultCode").intValue() != 0) { throw failure("MoMo rejected Create Payment initiation"); }
        if (!request.partnerCode().equals(text(response, "partnerCode"))
                || !request.requestId().equals(text(response, "requestId"))
                || !request.orderId().equals(text(response, "orderId"))
                || !response.path("amount").isIntegralNumber() || !response.path("amount").canConvertToLong()
                || response.path("amount").longValue() != request.amount()) {
            throw failure("MoMo Create Payment response did not match the attempt");
        }
        String payUrl = text(response, "payUrl");
        if (!webUrl(payUrl) || !"https".equals(URI.create(payUrl).getScheme())
                || !"test-payment.momo.vn".equals(URI.create(payUrl).getHost())) {
            throw failure("MoMo Create Payment response is missing a valid sandbox payUrl");
        }
        // Signature is deliberately NOT verified: official EN/VI formulas disagree. Do not expose artifacts to clients.
        return new CheckoutBootstrap(payUrl, optionalText(response, "qrCodeUrl"), optionalText(response, "deeplink"));
    }

    private String text(JsonNode response, String key) {
        if (!response.path(key).isTextual() || response.path(key).textValue().isBlank()) {
            throw failure("Malformed MoMo Create Payment response");
        }
        return response.path(key).textValue();
    }

    private String optionalText(JsonNode response, String key) {
        if (!response.hasNonNull(key)) { return null; }
        if (!response.path(key).isTextual()) { throw failure("Malformed MoMo Create Payment response"); }
        return response.path(key).textValue().isBlank() ? null : response.path(key).textValue();
    }

    private PaymentInitiationException failure(String message) {
        return new PaymentInitiationException(HttpStatus.BAD_GATEWAY, message);
    }

    public record CreatePaymentRequest(String partnerCode, String requestId, long amount, String orderId,
                                       String orderInfo, String redirectUrl, String ipnUrl, String requestType,
                                       String extraData, String lang, String signature) {
        @Override public String toString() { return "MoMo CreatePaymentRequest[redacted]"; }
    }

    public record CheckoutBootstrap(String payUrl, String qrCodeData, String deeplink) {
        @Override public String toString() { return "MoMo CheckoutBootstrap[unverified, redacted]"; }
    }
}
