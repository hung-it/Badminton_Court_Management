package com.bcm.service;

import com.bcm.config.BookingPaymentConfig;
import com.bcm.dto.request.CreatePaymentAttemptRequest;
import com.bcm.dto.response.PaymentAttemptResponse;
import com.bcm.entity.BookingStatus;
import com.bcm.entity.PaymentMethod;
import com.bcm.entity.PaymentStatus;
import com.bcm.exception.BadRequestException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Real orchestration/controller, PostgreSQL transactions and local VNPay signing; no internet calls. */
@SpringBootTest(properties = {
        "booking.payment.vnpay.tmn-code=TEST1234",
        "booking.payment.vnpay.hash-secret=postgres-test-secret",
        "booking.payment.vnpay.return-url=https://merchant.example/return",
        "spring.datasource.hikari.connection-init-sql=SET statement_timeout = '15s'"
})
@AutoConfigureMockMvc
@org.springframework.context.annotation.Import(com.bcm.service.BookingWebTestConfiguration.class)
@EnabledIfEnvironmentVariable(named = "BCM_TEST_DB_URL", matches = "jdbc:postgresql:.*")
class PaymentInitiationPostgresTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 5, 12, 0);

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("BCM_TEST_DB_URL"));
        registry.add("spring.datasource.username", () -> Objects.requireNonNull(System.getenv("BCM_TEST_DB_USERNAME")));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("BCM_TEST_DB_PASSWORD", ""));
    }

    @Autowired private PaymentInitiationService initiation;
    @Autowired private PaymentAttemptService attempts;
    @Autowired private BookingPaymentConfig config;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private PlatformTransactionManager transactionManager;
    @SpyBean private VnPayGateway gateway;
    @MockBean(name = "bookingExpirationClock") private Clock clock;
    private UUID userId;
    private UUID customerId;
    // Lifecycle/concurrency regression fixture; real JWT ownership is covered separately.
    @org.springframework.boot.test.mock.mockito.MockBean
    private com.bcm.security.CurrentCustomerService currentCustomer;
    private UUID bookingId;

    @BeforeEach
    void fixtures() throws Exception {
        var zone = ZoneId.systemDefault();
        when(clock.getZone()).thenReturn(zone);
        when(clock.instant()).thenReturn(NOW.atZone(zone).toInstant());
        userId = UUID.randomUUID();
        customerId = UUID.randomUUID();
        org.mockito.Mockito.when(currentCustomer.requireCustomerId()).thenReturn(customerId);
        bookingId = UUID.randomUUID();
        jdbc.update("INSERT INTO users(id,email,password_hash,full_name,phone) VALUES (?,?,?,'Booking fixture','0900000000')", userId, userId + "@initiation.test", "test-only");
        jdbc.update("INSERT INTO customers(id,user_id,full_name,phone) VALUES (?,?,?,?)", customerId, userId, "Initiation", "0900000000");
        jdbc.update("INSERT INTO bookings(id,customer_id,status,court_fee,expires_at) VALUES (?,?,'PENDING',10000.00,?)",
                bookingId, customerId, NOW.plusMinutes(5));
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM payment_transactions WHERE booking_id = ?", bookingId);
        jdbc.update("DELETE FROM bookings WHERE id = ?", bookingId);
        jdbc.update("DELETE FROM customers WHERE id = ?", customerId);
        jdbc.update("DELETE FROM users WHERE id = ?", userId);
    }

    @Test
    void signedCheckoutUsesServerSnapshotAndRemoteIpAndKeepsPendingState() throws Exception {
        var header = jdbc.queryForMap("SELECT * FROM bookings WHERE id = ?", bookingId);
        var data = perform(201, "192.0.2.10", "{\"paymentMethod\":\"VNPAY\",\"amount\":1,\"signature\":\"fake\","
                + "\"returnUrl\":\"https://client.invalid\",\"merchantReference\":\"client-fake\"}");
        assertThat(data.path("gatewayPreparation").path("checkoutReady").asBoolean()).isTrue();
        var fields = VnPayGatewayTest.fields(data.path("gatewayPreparation").path("checkoutUrl").asText());
        assertThat(fields).containsEntry("vnp_Amount", "1000000").containsEntry("vnp_IpAddr", "192.0.2.10")
                .containsEntry("vnp_ReturnUrl", "https://merchant.example/return");
        UUID id = UUID.fromString(data.path("paymentAttemptId").asText());
        assertThat(fields.get("vnp_TxnRef")).isEqualTo(id.toString().replace("-", ""));
        var payment = jdbc.queryForMap("SELECT * FROM payment_transactions WHERE id = ?", id);
        assertThat(payment.get("status")).isEqualTo("PENDING");
        assertThat(payment.get("amount")).isEqualTo(new BigDecimal("10000.00"));
        assertThat(payment.get("booking_id")).isEqualTo(bookingId);
        assertThat(payment.get("invoice_id")).isNull();
        assertThat(payment.get("transaction_id")).isNull();
        assertThat(payment.get("transaction_date")).isNull();
        assertThat(jdbc.queryForMap("SELECT * FROM bookings WHERE id = ?", bookingId)).isEqualTo(header);
        assertThat(data.toString()).doesNotContain("postgres-test-secret", "hashSecret");
        assertCount(1);
    }

    @Test
    void retryReusesSameAttemptWithoutChangingPaymentHistory() throws Exception {
        var first = perform(201, "192.0.2.10", body());
        var before = jdbc.queryForMap("SELECT * FROM payment_transactions WHERE booking_id = ?", bookingId);
        var second = perform(201, "192.0.2.10", body());
        assertThat(second.path("paymentAttemptId")).isEqualTo(first.path("paymentAttemptId"));
        assertThat(second.path("gatewayPreparation").path("checkoutUrl"))
                .isEqualTo(first.path("gatewayPreparation").path("checkoutUrl"));
        assertThat(jdbc.queryForMap("SELECT * FROM payment_transactions WHERE booking_id = ?", bookingId)).isEqualTo(before);
        assertCount(1);
    }

    @Test
    void failureAfterCommitPreservesPendingAttemptAndAllowsRetry() throws Exception {
        perform(400, "invalid-hostname", body());
        var before = jdbc.queryForMap("SELECT * FROM payment_transactions WHERE booking_id = ?", bookingId);
        var result = perform(201, "192.0.2.10", body());
        assertThat(result.path("paymentAttemptId").asText()).isEqualTo(before.get("id").toString());
        assertThat(jdbc.queryForMap("SELECT * FROM payment_transactions WHERE booking_id = ?", bookingId)).isEqualTo(before);
        assertCount(1);
    }

    @ParameterizedTest
    @EnumSource(value = BookingStatus.class, names = "PENDING", mode = EnumSource.Mode.EXCLUDE)
    void ineligibleBookingNeverInitiatesCheckout(BookingStatus state) throws Exception {
        jdbc.update("UPDATE bookings SET status = ? WHERE id = ?", state.name(), bookingId);
        perform(400, "192.0.2.10", body());
        verify(gateway, never()).initiate(any(), anyString());
        assertCount(0);
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0})
    void pastAndExactDeadlineNeverInitiateCheckout(int seconds) throws Exception {
        jdbc.update("UPDATE bookings SET expires_at = ? WHERE id = ?", NOW.plusSeconds(seconds), bookingId);
        perform(400, "192.0.2.10", body());
        verify(gateway, never()).initiate(any(), anyString());
        assertCount(0);
    }

    @ParameterizedTest
    @EnumSource(value = PaymentStatus.class, names = "PENDING", mode = EnumSource.Mode.EXCLUDE)
    void recheckRejectsNonPendingPaymentWithoutMutatingHistory(PaymentStatus state) {
        var payment = attempts.createAttempt(bookingId, new CreatePaymentAttemptRequest(PaymentMethod.VNPAY));
        jdbc.update("UPDATE payment_transactions SET status = ? WHERE id = ?", state.name(), payment.paymentAttemptId());
        assertThatThrownBy(() -> attempts.recheckForInitiation(payment.paymentAttemptId())).isInstanceOf(BadRequestException.class);
        assertThat(jdbc.queryForObject("SELECT status FROM payment_transactions WHERE id = ?", String.class,
                payment.paymentAttemptId())).isEqualTo(state.name());
    }

    @Test
    void providerWorkHasNoDbTransactionAndBookingLockHasBeenReleased() throws Exception {
        doAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            try (var connection = dataSource.getConnection()) {
                connection.setAutoCommit(false);
                try (var statement = connection.prepareStatement("SELECT id FROM bookings WHERE id = ? FOR UPDATE NOWAIT")) {
                    statement.setObject(1, bookingId);
                    statement.executeQuery().close(); // would fail if orchestration still held the Booking lock
                } finally { connection.rollback(); }
            }
            return call.callRealMethod();
        }).when(gateway).initiate(any(PaymentAttemptResponse.class), anyString());
        perform(201, "192.0.2.10", body());
    }

    @Test
    void stateChangeDuringProviderWorkSuppressesCheckoutButPreservesAttempt() throws Exception {
        doAnswer(call -> {
            var result = call.callRealMethod();
            jdbc.update("UPDATE bookings SET expires_at = ? WHERE id = ?", NOW, bookingId);
            return result;
        }).when(gateway).initiate(any(PaymentAttemptResponse.class), anyString());
        perform(400, "192.0.2.10", body());
        assertCount(1);
        assertThat(jdbc.queryForObject("SELECT status FROM payment_transactions WHERE booking_id = ?", String.class, bookingId))
                .isEqualTo("PENDING");
    }

    @Test
    void refusesAnOuterTransactionInsteadOfHoldingItsLocksThroughProviderWork() {
        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(tx ->
                initiation.initiate(bookingId, new CreatePaymentAttemptRequest(PaymentMethod.VNPAY), "192.0.2.10")))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertCount(0);
    }

    @Test
    void unavailableConfigurationReturnsSafe503AndCreatesNoAttempt() throws Exception {
        String previous = config.getVnpay().getHashSecret();
        try {
            config.getVnpay().setHashSecret("");
            perform(503, "192.0.2.10", body());
            assertCount(0);
        } finally { config.getVnpay().setHashSecret(previous); }
    }

    @Test
    void simultaneousRetryRequestsReuseExactlyOneAttempt() throws Exception {
        var workers = Executors.newFixedThreadPool(2);
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try {
                try (var statement = holder.prepareStatement("SELECT id FROM bookings WHERE id = ? FOR UPDATE")) {
                    statement.setObject(1, bookingId);
                    statement.executeQuery().close();
                }
                var results = List.of(1, 2).stream().map(i -> workers.submit(() -> {
                    ready.countDown();
                    assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                    return perform(201, "192.0.2.10", body()).path("paymentAttemptId").asText();
                })).toList();
                assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
                start.countDown();
                awaitBookingWaiters();
                holder.commit();
                assertThat(results.get(0).get(20, TimeUnit.SECONDS)).isEqualTo(results.get(1).get(20, TimeUnit.SECONDS));
                assertCount(1);
            } finally {
                start.countDown();
                holder.rollback();
                workers.shutdownNow();
                assertThat(workers.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
            }
        }
    }

    private void awaitBookingWaiters() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            Long count = jdbc.queryForObject("""
                    SELECT count(*) FROM pg_stat_activity WHERE datname = current_database()
                    AND pid <> pg_backend_pid() AND wait_event_type = 'Lock' AND state = 'active'
                    AND lower(query) LIKE '%from%bookings%' AND lower(query) LIKE '%for%update%'
                    """, Long.class);
            if (count != null && count == 2) { return; }
            Thread.sleep(20);
        }
        throw new AssertionError("Expected two independent initiation requests waiting on Booking lock");
    }

    private String body() { return "{\"paymentMethod\":\"VNPAY\"}"; }
    private com.fasterxml.jackson.databind.JsonNode perform(int expected, String ip, String body) throws Exception {
        var result = mvc.perform(post("/api/bookings/" + bookingId + "/payments").contextPath("/api")
                .contentType(MediaType.APPLICATION_JSON).content(body).with(request -> {
                    request.setRemoteAddr(ip);
                    request.addHeader("X-Forwarded-For", "8.8.8.8");
                    return request;
                })).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(expected);
        var envelope = mapper.readTree(result.getResponse().getContentAsString());
        assertThat(envelope.path("success").asBoolean()).isEqualTo(expected == 201);
        return envelope.path("data");
    }

    private void assertCount(long expected) {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_transactions WHERE booking_id = ?", Long.class, bookingId))
                .isEqualTo(expected);
    }
}
