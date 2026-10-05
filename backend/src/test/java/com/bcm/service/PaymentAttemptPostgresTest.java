package com.bcm.service;

import com.bcm.dto.request.CreatePaymentAttemptRequest;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
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
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Dedicated existing PostgreSQL/schema; committed fixtures and independent worker transactions. */
@SpringBootTest(properties = {
        "booking.payment.enabled-methods=VNPAY,MOMO",
        "spring.datasource.hikari.connection-init-sql=SET statement_timeout = '15s'"
})
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "BCM_TEST_DB_URL", matches = "jdbc:postgresql:.*")
class PaymentAttemptPostgresTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 5, 12, 0);
    private static final BigDecimal FEE = new BigDecimal("123456.78");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("BCM_TEST_DB_URL"));
        registry.add("spring.datasource.username", () -> Objects.requireNonNull(System.getenv("BCM_TEST_DB_USERNAME")));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("BCM_TEST_DB_PASSWORD", ""));
    }

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;
    @Autowired private PaymentAttemptService service;
    @Autowired private BookingExpirationService expiration;
    @MockBean(name = "bookingExpirationClock") private Clock clock;
    // This suite retains the Phase 5A create-only policy. Real initiation/retry is tested separately.
    @MockBean private PaymentInitiationService initiation;

    private UUID userId;
    private UUID customerId;
    private UUID bookingId;

    @BeforeEach
    void fixtures() {
        when(initiation.initiate(any(UUID.class), any(CreatePaymentAttemptRequest.class), anyString()))
                .thenAnswer(call -> service.createAttempt(call.getArgument(0), call.getArgument(1)));
        when(clock.getZone()).thenReturn(ZoneId.systemDefault());
        setTime(NOW);
        userId = UUID.randomUUID();
        customerId = UUID.randomUUID();
        bookingId = UUID.randomUUID();
        jdbc.update("INSERT INTO users(id,email,password) VALUES (?,?,?)", userId, userId + "@payment.test", "test-only");
        jdbc.update("INSERT INTO customers(id,user_id,full_name,phone) VALUES (?,?,?,?)",
                customerId, userId, "Payment test", "0900000000");
        jdbc.update("INSERT INTO bookings(id,customer_id,status,court_fee,expires_at) VALUES (?,?,'PENDING',?,?)",
                bookingId, customerId, FEE, NOW.plusMinutes(5));
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM payment_transactions WHERE booking_id = ?", bookingId);
        jdbc.update("DELETE FROM bookings WHERE id = ?", bookingId);
        jdbc.update("DELETE FROM customers WHERE id = ?", customerId);
        jdbc.update("DELETE FROM users WHERE id = ?", userId);
    }

    @ParameterizedTest
    @EnumSource(value = PaymentMethod.class, names = {"VNPAY", "MOMO"})
    void createsOnlyServerAuthoritativePendingBookingPayment(PaymentMethod method) throws Exception {
        var before = jdbc.queryForMap("SELECT * FROM bookings WHERE id = ?", bookingId);
        // These extra fields are not DTO inputs and must never control persistence or gateway preparation.
        String body = mapper.createObjectNode().put("paymentMethod", method.name())
                .put("amount", 1).put("courtFee", 2).put("status", "SUCCESS")
                .put("bookingId", UUID.randomUUID().toString()).put("invoiceId", UUID.randomUUID().toString())
                .put("transactionId", "client-fake").put("transactionDate", "2099-01-01T00:00:00")
                .put("paymentUrl", "https://example.invalid").toString();
        var response = mvc.perform(post(path()).contextPath("/api").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.amount").value(123456.78))
                .andExpect(jsonPath("$.data.gatewayPreparation.checkoutReady").value(false))
                .andExpect(jsonPath("$.data.gatewayPreparation.provider").value(method.name()))
                .andExpect(jsonPath("$.data.gatewayPreparation.amount").value(123456.78))
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.bookingExpiresAt").value(NOW.plusMinutes(5).toString() + ":00"))
                .andExpect(jsonPath("$.data.booking").doesNotExist()).andReturn();
        var data = mapper.readTree(response.getResponse().getContentAsString()).path("data");
        UUID id = UUID.fromString(data.path("paymentAttemptId").asText());
        assertThat(data.path("gatewayPreparation").path("merchantReference").asText()).isEqualTo(id.toString());
        var row = jdbc.queryForMap("SELECT * FROM payment_transactions WHERE id = ?", id);
        assertThat(row.get("booking_id")).isEqualTo(bookingId);
        assertThat(row.get("invoice_id")).isNull();
        assertThat(row.get("payment_method")).isEqualTo(method.name());
        assertThat(row.get("status")).isEqualTo("PENDING");
        assertThat(row.get("amount")).isEqualTo(FEE);
        assertThat(row.get("transaction_id")).isNull();
        assertThat(row.get("transaction_date")).isNull();
        assertThat(row.get("created_at")).isNotNull();
        assertThat(jdbc.queryForMap("SELECT * FROM bookings WHERE id = ?", bookingId)).isEqualTo(before);
        assertCount(1);
    }

    @Test
    void missingBookingReturns404WithoutPayment() throws Exception {
        mvc.perform(post("/api/bookings/" + UUID.randomUUID() + "/payments").contextPath("/api")
                        .contentType(MediaType.APPLICATION_JSON).content(body(PaymentMethod.VNPAY)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.success").value(false));
        assertCount(0);
    }

    @ParameterizedTest
    @EnumSource(value = BookingStatus.class, names = "PENDING", mode = EnumSource.Mode.EXCLUDE)
    void rejectsEveryNonPendingBooking(BookingStatus state) throws Exception {
        jdbc.update("UPDATE bookings SET status = ? WHERE id = ?", state.name(), bookingId);
        assertThat(perform(PaymentMethod.VNPAY)).isEqualTo(400);
        assertCount(0);
        assertThat(jdbc.queryForObject("SELECT status FROM bookings WHERE id = ?", String.class, bookingId))
                .isEqualTo(state.name());
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0})
    void rejectsDeadlineBeforeAndExactlyNowWithoutExpiringBooking(int seconds) throws Exception {
        jdbc.update("UPDATE bookings SET expires_at = ? WHERE id = ?", NOW.plusSeconds(seconds), bookingId);
        var before = jdbc.queryForMap("SELECT * FROM bookings WHERE id = ?", bookingId);
        assertThat(perform(PaymentMethod.MOMO)).isEqualTo(400);
        assertCount(0);
        assertThat(jdbc.queryForMap("SELECT * FROM bookings WHERE id = ?", bookingId)).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "null", "{}", "{", "{\"paymentMethod\":null}",
            "{\"paymentMethod\":\"OTHER\"}", "{\"paymentMethod\":\"CASH\"}", "{\"paymentMethod\":\"BANK_TRANSFER\"}"})
    void invalidInputReturns400WithoutPartialPayment(String input) throws Exception {
        mvc.perform(post(path()).contextPath("/api").contentType(MediaType.APPLICATION_JSON).content(input))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.success").value(false));
        assertCount(0);
    }

    @Test
    void validatesDirectServiceInputs() {
        assertThatThrownBy(() -> service.createAttempt(null, new CreatePaymentAttemptRequest(PaymentMethod.MOMO)))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.createAttempt(bookingId, null)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.createAttempt(bookingId, new CreatePaymentAttemptRequest(null)))
                .isInstanceOf(BadRequestException.class);
        assertCount(0);
    }

    @ParameterizedTest
    @EnumSource(value = PaymentMethod.class, names = {"VNPAY", "MOMO"})
    void rejectsExistingPendingEvenAcrossProvidersAndWithGatewayTransactionId(PaymentMethod method) throws Exception {
        UUID existing = seedPayment(PaymentStatus.PENDING);
        jdbc.update("UPDATE payment_transactions SET transaction_id = ? WHERE id = ?", UUID.randomUUID().toString(), existing);
        var before = jdbc.queryForMap("SELECT * FROM payment_transactions WHERE id = ?", existing);
        assertThat(perform(method)).isEqualTo(409);
        assertCount(1);
        assertThat(jdbc.queryForMap("SELECT * FROM payment_transactions WHERE id = ?", existing)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT status FROM bookings WHERE id = ?", String.class, bookingId)).isEqualTo("PENDING");
    }

    @ParameterizedTest
    @EnumSource(value = PaymentStatus.class, names = "PENDING", mode = EnumSource.Mode.EXCLUDE)
    void existingNonPendingHistoryIsNotMutatedOrTreatedAsBookingPaymentResult(PaymentStatus state) throws Exception {
        UUID history = seedPayment(state);
        var before = jdbc.queryForMap("SELECT * FROM payment_transactions WHERE id = ?", history);
        assertThat(perform(PaymentMethod.MOMO)).isEqualTo(201);
        assertCount(2);
        assertThat(jdbc.queryForMap("SELECT * FROM payment_transactions WHERE id = ?", history)).isEqualTo(before);
    }

    @ParameterizedTest
    @EnumSource(value = PaymentMethod.class, names = {"VNPAY", "MOMO"})
    void concurrentRequestsCreateExactlyOnePendingAttempt(PaymentMethod second) throws Exception {
        var workers = Executors.newFixedThreadPool(2);
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try {
                lockBooking(holder);
                var results = List.of(PaymentMethod.VNPAY, second).stream().map(method -> workers.submit(() -> {
                    ready.countDown();
                    assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                    return perform(method);
                })).toList();
                assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
                start.countDown();
                awaitBookingWaiters(2);
                holder.commit();
                assertThat(List.of(results.get(0).get(20, TimeUnit.SECONDS), results.get(1).get(20, TimeUnit.SECONDS)))
                        .containsExactlyInAnyOrder(201, 409);
                assertCount(1);
            } finally {
                start.countDown();
                holder.rollback();
                workers.shutdownNow();
                assertThat(workers.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"EXPIRED", "PAID", "DEADLINE", "CLOCK"})
    void rechecksBookingAndClockAfterWaitingForLock(String change) throws Exception {
        var worker = Executors.newSingleThreadExecutor();
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try {
                lockBooking(holder);
                var result = worker.submit(() -> perform(PaymentMethod.MOMO));
                awaitBookingWaiters(1);
                if (change.equals("CLOCK")) {
                    setTime(NOW.plusMinutes(5));
                } else {
                    String sql = change.equals("DEADLINE") ? "UPDATE bookings SET expires_at = ? WHERE id = ?"
                            : "UPDATE bookings SET status = ? WHERE id = ?";
                    try (var statement = holder.prepareStatement(sql)) {
                        statement.setObject(1, change.equals("DEADLINE") ? NOW : change);
                        statement.setObject(2, bookingId);
                        statement.executeUpdate();
                    }
                }
                holder.commit();
                assertThat(result.get(20, TimeUnit.SECONDS)).isEqualTo(400);
                assertCount(0);
            } finally {
                holder.rollback();
                worker.shutdownNow();
                assertThat(worker.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
            }
        }
    }

    @Test
    void expirationStillExpiresBookingAndPreservesCreatedAttempt() throws Exception {
        assertThat(perform(PaymentMethod.VNPAY)).isEqualTo(201);
        var before = jdbc.queryForMap("SELECT * FROM payment_transactions WHERE booking_id = ?", bookingId);
        setTime(NOW.plusMinutes(5));
        assertThat(expiration.expireBooking(bookingId)).isTrue();
        assertThat(perform(PaymentMethod.MOMO)).isEqualTo(400);
        assertThat(jdbc.queryForMap("SELECT * FROM payment_transactions WHERE booking_id = ?", bookingId)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT status FROM bookings WHERE id = ?", String.class, bookingId)).isEqualTo("EXPIRED");
    }

    @Test
    void openApiDescribesFoundationAndLimitedRequest() throws Exception {
        mvc.perform(get("/api/api-docs").contextPath("/api")).andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/bookings/{bookingId}/payments'].post.responses['201']").exists())
                .andExpect(jsonPath("$.paths['/bookings/{bookingId}/payments'].post.responses['409']").exists())
                .andExpect(jsonPath("$.paths['/bookings/{bookingId}/payments'].post.description").value(
                        org.hamcrest.Matchers.containsString("checkoutReady=true")))
                .andExpect(jsonPath("$.components.schemas.CreatePaymentAttemptRequest.properties.amount").doesNotExist())
                .andExpect(jsonPath("$.components.schemas.CreatePaymentAttemptRequest.properties.invoiceId").doesNotExist());
    }

    private void setTime(LocalDateTime time) {
        when(clock.instant()).thenReturn(time.atZone(ZoneId.systemDefault()).toInstant());
    }

    private UUID seedPayment(PaymentStatus status) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO payment_transactions(id,booking_id,payment_method,status,amount) VALUES (?,?,'VNPAY',?,?)",
                id, bookingId, status.name(), FEE);
        return id;
    }

    private String path() { return "/api/bookings/" + bookingId + "/payments"; }

    private String body(PaymentMethod method) throws Exception {
        return mapper.writeValueAsString(new CreatePaymentAttemptRequest(method));
    }

    private int perform(PaymentMethod method) throws Exception {
        var result = mvc.perform(post(path()).contextPath("/api").contentType(MediaType.APPLICATION_JSON).content(body(method)))
                .andReturn();
        int code = result.getResponse().getStatus();
        assertThat(mapper.readTree(result.getResponse().getContentAsString()).path("success").asBoolean()).isEqualTo(code == 201);
        return code;
    }

    private void assertCount(long expected) {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_transactions WHERE booking_id = ?", Long.class, bookingId))
                .isEqualTo(expected);
    }

    private void lockBooking(Connection holder) throws Exception {
        try (var statement = holder.prepareStatement("SELECT id FROM bookings WHERE id = ? FOR UPDATE")) {
            statement.setObject(1, bookingId);
            statement.executeQuery().close();
        }
    }

    private void awaitBookingWaiters(int expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            Long count = jdbc.queryForObject("""
                    SELECT count(*) FROM pg_stat_activity WHERE datname = current_database()
                    AND pid <> pg_backend_pid() AND wait_event_type = 'Lock' AND state = 'active'
                    AND lower(query) LIKE '%from%bookings%' AND lower(query) LIKE '%for%update%'
                    """, Long.class);
            if (count != null && count == expected) { return; }
            Thread.sleep(20);
        }
        throw new AssertionError("Expected " + expected + " independent Booking lock waiters");
    }
}
