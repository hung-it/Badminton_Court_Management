package com.bcm.service;

import com.bcm.config.BookingPaymentConfig;
import com.bcm.dto.response.PaymentAttemptResponse;
import com.bcm.entity.PaymentMethod;
import com.bcm.entity.PaymentStatus;
import com.bcm.exception.BadRequestException;
import com.bcm.exception.PaymentInitiationException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MoMoGatewayTest {
    private static final UUID ID = UUID.fromString("12345678-1234-4234-8234-123456789abc");
    private final ObjectMapper mapper = new ObjectMapper();
    private final BookingPaymentConfig config = configuration();
    private final HttpClient http = mock(HttpClient.class);
    private final MoMoGateway gateway = new MoMoGateway(config, mapper, http);

    static BookingPaymentConfig configuration() {
        var result = new BookingPaymentConfig();
        result.getMomo().setPartnerCode("TESTPARTNER");
        result.getMomo().setAccessKey("test-access");
        result.getMomo().setSecretKey("test-secret");
        result.getMomo().setRedirectUrl("https://merchant.example/return");
        result.getMomo().setIpnUrl("https://merchant.example/ipn");
        return result;
    }

    private PaymentAttemptResponse attempt() {
        return new PaymentAttemptResponse(ID, UUID.randomUUID(), PaymentMethod.MOMO, PaymentStatus.PENDING,
                new BigDecimal("10000.00"), LocalDateTime.of(2026, 10, 5, 12, 15), null);
    }

    @Test
    void requestUsesVerifiedFieldOrderStableUuidIdsExactAmountAndConfiguredUrls() {
        var request = gateway.buildRequest(attempt());
        assertThat(request.requestType()).isEqualTo("captureWallet");
        assertThat(request.requestId()).isEqualTo(ID.toString()).hasSize(36);
        assertThat(request.orderId()).isEqualTo(ID.toString());
        assertThat(request.amount()).isEqualTo(10000);
        assertThat(request.redirectUrl()).isEqualTo(config.getMomo().getRedirectUrl());
        assertThat(request.ipnUrl()).isEqualTo(config.getMomo().getIpnUrl());
        String raw = "accessKey=test-access&amount=10000&extraData=&ipnUrl=https://merchant.example/ipn"
                + "&orderId=" + ID + "&orderInfo=Booking payment " + ID + "&partnerCode=TESTPARTNER"
                + "&redirectUrl=https://merchant.example/return&requestId=" + ID + "&requestType=captureWallet";
        assertThat(MoMoSigner.requestData("test-access", request)).isEqualTo(raw);
        assertThat(request.signature()).isEqualTo(MoMoSigner.sign("test-secret", raw));
        assertThat(gateway.buildRequest(attempt())).isEqualTo(request); // no random IDs or timestamps across retries
        assertThat(request.toString()).doesNotContain("test-access", "test-secret", request.signature());
    }

    @Test
    void hmacSha256MatchesFixedKnownVector() {
        assertThat(MoMoSigner.sign("key", "The quick brown fox jumps over the lazy dog"))
                .isEqualTo("f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8");
    }

    @ParameterizedTest
    @ValueSource(strings = {"1000.00", "10000.00", "50000000.00"})
    void acceptsExactWholeVndWithinOfficialLimits(String amount) {
        assertThat(MoMoGateway.providerAmount(new BigDecimal(amount))).isEqualTo(new BigDecimal(amount).longValueExact());
    }

    @ParameterizedTest
    @ValueSource(strings = {"999.00", "50000001.00", "10000.01", "-1", "99999999999999999999999999"})
    void rejectsFractionsAndAmountsOutsideOfficialLimits(String amount) {
        assertThatThrownBy(() -> MoMoGateway.providerAmount(new BigDecimal(amount)))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("whole VND");
    }

    @Test
    void serializesVerifiedRequestAsJsonWithoutSendingAccessOrSecretKey() throws Exception {
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenAnswer(call -> response(200, successBody(mapper, gateway.buildRequest(attempt()), true)));
        var result = gateway.initiate(attempt());
        var captured = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http, times(1)).send(captured.capture(), any(HttpResponse.BodyHandler.class));
        HttpRequest request = captured.getValue();
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.uri().toString()).isEqualTo("https://test-payment.momo.vn/v2/gateway/api/create");
        assertThat(request.timeout()).contains(Duration.ofSeconds(30));
        assertThat(request.headers().firstValue("Content-Type")).contains("application/json; charset=UTF-8");
        String body = requestBody(request);
        assertThat(body).doesNotContain("test-secret", "test-access", "accessKey", "secretKey");
        var json = mapper.readTree(body);
        assertThat(json.path("amount").isIntegralNumber()).isTrue();
        assertThat(json.path("requestType").asText()).isEqualTo("captureWallet");
        assertThat(json.path("signature").asText()).isEqualTo(gateway.buildRequest(attempt()).signature());
        assertThat(json.size()).isEqualTo(11);
        assertThat(result.payUrl()).isEqualTo("https://test-payment.momo.vn/v2/gateway/pay?t=test");
        assertThat(result.qrCodeData()).isEqualTo("000201-test-qr-data");
        assertThat(result.deeplink()).isEqualTo("momo://app?action=pay");
        assertThat(result.toString()).doesNotContain(result.payUrl(), result.qrCodeData());
    }

    @Test
    void parsesOptionalArtifactsOnlyWhenProviderReturnsThem() throws Exception {
        var request = gateway.buildRequest(attempt());
        var result = gateway.parseResponse(successBody(mapper, request, false), request);
        assertThat(result.payUrl()).isNotBlank();
        assertThat(result.qrCodeData()).isNull();
        assertThat(result.deeplink()).isNull();
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 500, 503})
    void non2xxFailsWithoutBlindHttpRetryOrProviderBodyLeak(int code) throws Exception {
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenAnswer(call -> response(code, "sensitive-provider-body"));
        assertThatThrownBy(() -> gateway.initiate(attempt())).isInstanceOf(PaymentInitiationException.class)
                .hasMessage("MoMo Create Payment HTTP request failed");
        verify(http, times(1)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    void timeoutFailsSafelyAndSendsOnce() throws Exception {
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new HttpTimeoutException("provider-sensitive-detail"));
        assertThatThrownBy(() -> gateway.initiate(attempt())).isInstanceOf(PaymentInitiationException.class)
                .hasMessage("MoMo Create Payment timed out; retry the same attempt");
        verify(http, times(1)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{", "null", "[]", "{}", "{\"resultCode\":\"0\"}"})
    void malformedJsonAndWrongTypesFailSafely(String body) throws Exception {
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenAnswer(call -> response(200, body));
        assertThatThrownBy(() -> gateway.initiate(attempt())).isInstanceOf(PaymentInitiationException.class);
    }

    @Test
    void providerResultCodeFailureDoesNotCountAsAcceptedInitiation() throws Exception {
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenAnswer(call -> response(200, "{\"resultCode\":1002,\"message\":\"sensitive-provider-message\"}"));
        assertThatThrownBy(() -> gateway.initiate(attempt())).isInstanceOf(PaymentInitiationException.class)
                .hasMessage("MoMo rejected Create Payment initiation");
    }

    @ParameterizedTest
    @ValueSource(strings = {"partnerCode", "requestId", "orderId", "amount", "payUrl"})
    void rejectsMismatchedOrMissingCheckoutData(String field) throws Exception {
        var request = gateway.buildRequest(attempt());
        var json = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(successBody(mapper, request, false));
        if (field.equals("payUrl")) { json.remove(field); }
        else if (field.equals("amount")) { json.put(field, 1); }
        else { json.put(field, "wrong-value"); }
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenAnswer(call -> response(200, json.toString()));
        assertThatThrownBy(() -> gateway.initiate(attempt())).isInstanceOf(PaymentInitiationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PARTNER", "ACCESS", "SECRET", "REDIRECT", "IPN", "ENDPOINT", "TIMEOUT"})
    void missingOrInvalidConfigurationMakesProviderUnavailable(String field) {
        switch (field) {
            case "PARTNER" -> config.getMomo().setPartnerCode("");
            case "ACCESS" -> config.getMomo().setAccessKey("");
            case "SECRET" -> config.getMomo().setSecretKey("");
            case "REDIRECT" -> config.getMomo().setRedirectUrl("javascript:bad");
            case "IPN" -> config.getMomo().setIpnUrl("");
            case "ENDPOINT" -> config.getMomo().setCreatePaymentUrl("https://payment.momo.vn/v2/gateway/api/create");
            case "TIMEOUT" -> config.getMomo().setRequestTimeout(Duration.ZERO);
        }
        assertThatThrownBy(gateway::validateConfiguration).isInstanceOf(PaymentInitiationException.class)
                .hasMessage("MoMo sandbox configuration is unavailable");
    }

    @Test
    void bindsConfigurationAndBuildsBoundedHttpClientWithoutRedirects() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
                .withUserConfiguration(BookingPaymentConfig.class)
                .withPropertyValues("booking.payment.momo.partner-code=TESTPARTNER", "booking.payment.momo.access-key=test-access",
                        "booking.payment.momo.secret-key=test-secret", "booking.payment.momo.redirect-url=https://merchant.example/return",
                        "booking.payment.momo.ipn-url=https://merchant.example/ipn", "booking.payment.momo.connect-timeout=PT3S",
                        "booking.payment.momo.request-timeout=PT30S")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var bound = context.getBean(BookingPaymentConfig.class);
                    assertThat(bound.getMomo().getPartnerCode()).isEqualTo("TESTPARTNER");
                    var client = context.getBean("bookingPaymentHttpClient", HttpClient.class);
                    assertThat(client.connectTimeout()).contains(Duration.ofSeconds(3));
                    assertThat(client.followRedirects()).isEqualTo(HttpClient.Redirect.NEVER);
                    new MoMoGateway(bound, mapper, client).validateConfiguration();
                });
    }

    @SuppressWarnings("unchecked")
    static HttpResponse<String> response(int code, String body) {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(code);
        when(response.body()).thenReturn(body);
        return response;
    }

    static String successBody(ObjectMapper mapper, MoMoGateway.CreatePaymentRequest request, boolean artifacts) {
        var body = mapper.createObjectNode().put("partnerCode", request.partnerCode()).put("requestId", request.requestId())
                .put("orderId", request.orderId()).put("amount", request.amount()).put("responseTime", 1791176400000L)
                .put("message", "Successful.").put("resultCode", 0)
                .put("payUrl", "https://test-payment.momo.vn/v2/gateway/pay?t=test");
        if (artifacts) { body.put("qrCodeUrl", "000201-test-qr-data").put("deeplink", "momo://app?action=pay"); }
        // No guessed response signature. The official sample likewise omits it; checkout remains blocked.
        return body.toString();
    }

    static String requestBody(HttpRequest request) throws Exception {
        var result = new CompletableFuture<String>();
        var bytes = new ByteArrayOutputStream();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<ByteBuffer>() {
            @Override public void onSubscribe(Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
            @Override public void onNext(ByteBuffer item) {
                byte[] chunk = new byte[item.remaining()];
                item.get(chunk);
                bytes.writeBytes(chunk);
            }
            @Override public void onError(Throwable error) { result.completeExceptionally(error); }
            @Override public void onComplete() { result.complete(bytes.toString(StandardCharsets.UTF_8)); }
        });
        return result.get(5, TimeUnit.SECONDS);
    }
}
