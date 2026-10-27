package com.bcm.service;

import com.bcm.config.BookingPaymentConfig;
import com.bcm.security.CustomUserDetailsService;
import com.bcm.util.JwtUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real JWT filter, services, PostgreSQL schema/transactions. Test signer is not a live gateway receipt. */
@SpringBootTest(properties = {
        "booking.expiration-enabled=false",
        "booking.payment.enabled-methods=VNPAY,BANK_TRANSFER",
        "booking.payment.vnpay.tmn-code=TEST1234",
        "booking.payment.vnpay.hash-secret=lifecycle-test-key",
        "booking.payment.frontend-result-url=http://localhost:5174/payment-result",
        "booking.payment.vnpay.return-url=http://localhost/api/payments/vnpay/return"
})
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "BCM_TEST_DB_URL", matches = "jdbc:postgresql:.*")
class BookingPaymentLifecyclePostgresTest {
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("BCM_TEST_DB_URL"));
        registry.add("spring.datasource.username", () -> System.getenv("BCM_TEST_DB_USERNAME"));
        registry.add("spring.datasource.password", () -> System.getenv("BCM_TEST_DB_PASSWORD"));
    }
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired JwtUtil jwt;
    @Autowired CustomUserDetailsService users;
    @Autowired BookingPaymentConfig config;
    @Autowired BookingExpirationService expiration;
    @Autowired CheckoutService checkout;
    @Autowired PosService pos;
    @org.springframework.boot.test.mock.mockito.MockBean(name = "bookingExpirationClock")
    java.time.Clock clock;
    private static final java.time.Instant NOW = java.time.Instant.parse("2026-10-23T03:00:00Z");
    UUID user, customer, booking, court, slot;
    String token;

    @BeforeEach void fixtures() {
        org.mockito.Mockito.when(clock.getZone()).thenReturn(java.time.ZoneOffset.UTC);
        org.mockito.Mockito.when(clock.instant()).thenReturn(NOW);
        user = UUID.randomUUID(); customer = UUID.randomUUID(); booking = UUID.randomUUID();
        court = UUID.randomUUID(); slot = UUID.randomUUID();
        jdbc.update("INSERT INTO users(id,email,password_hash,full_name,phone) VALUES (?,?,?,'Lifecycle','0900000000')",
                user, user + "@lifecycle.test", "test-only");
        jdbc.update("INSERT INTO customers(id,user_id,full_name,phone) VALUES (?,?,'Lifecycle','0900000000')", customer, user);
        jdbc.update("INSERT INTO user_roles(user_id,role_id) SELECT ?,id FROM roles WHERE role_name='CUSTOMER'", user);
        jdbc.update("INSERT INTO courts(id,court_code,name,base_price) VALUES (?,999801,'Lifecycle',100000)", court);
        jdbc.update("INSERT INTO time_slots(id,start_time,end_time,price_multiplier) VALUES (?,'08:00','09:00',1.25)", slot);
        jdbc.update("INSERT INTO bookings(id,customer_id,status,court_fee,expires_at) VALUES (?,?,'PENDING',125000,?)",
                booking, customer, LocalDateTime.ofInstant(NOW, java.time.ZoneOffset.UTC).plusMinutes(10));
        jdbc.update("INSERT INTO booking_details(booking_id,court_id,time_slot_id,booking_date,price) VALUES (?,?,?,?,125000)",
                booking, court, slot, LocalDate.now().plusDays(2));
        token = jwt.generateAccessToken(users.loadUserByUsername(user + "@lifecycle.test"));
    }
    @AfterEach void cleanup() {
        config.setFrontendResultUrl("");
        jdbc.update("DELETE FROM payment_transactions WHERE booking_id IN (SELECT id FROM bookings WHERE customer_id=?)", customer);
        jdbc.update("DELETE FROM booking_details WHERE booking_id IN (SELECT id FROM bookings WHERE customer_id=?)", customer);
        jdbc.update("DELETE FROM bookings WHERE customer_id=?", customer);
        jdbc.update("DELETE FROM courts WHERE id=?", court);
        jdbc.update("DELETE FROM time_slots WHERE id=?", slot);
        jdbc.update("DELETE FROM customers WHERE id=?", customer);
        jdbc.update("DELETE FROM user_roles WHERE user_id=?", user);
        jdbc.update("DELETE FROM users WHERE id=?", user);
    }
    String bankBody() {
        return "{\"paymentMethod\":\"BANK_TRANSFER\",\"amount\":1,\"status\":\"SUCCESS\",\"bankTransfer\":{"
                + "\"bankName\":\"Payer bank\",\"accountHolder\":\"Payer name\",\"accountNumber\":\"00123456\",\"transferContent\":\"Court booking\"}}";
    }
    ResultActions pay(String body) throws Exception {
        return mvc.perform(post("/api/bookings/" + booking + "/payments").contextPath("/api")
                .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON).content(body));
    }
    JsonNode data(ResultActions response) throws Exception {
        return mapper.readTree(response.andReturn().getResponse().getContentAsString()).path("data");
    }
    @Test void transferIsPersistedPendingWithoutSensitiveDataAndReuseIsIdempotent() throws Exception {
        var first = data(pay(bankBody()).andExpect(status().isCreated()).andExpect(jsonPath("$.data.status").value("PENDING")));
        var second = data(pay(bankBody()).andExpect(status().isCreated()));
        assertThat(first.path("paymentAttemptId")).isEqualTo(second.path("paymentAttemptId"));
        var row = jdbc.queryForMap("SELECT * FROM payment_transactions WHERE booking_id=?", booking);
        assertThat(row).containsEntry("status", "PENDING").containsEntry("payment_method", "BANK_TRANSFER").containsEntry("invoice_id", null);
        assertThat(row.get("amount").toString()).isEqualTo("125000.00");
        assertThat(row.get("note").toString()).doesNotContain("00123456", "Payer name", "Payer bank");
        assertThat(jdbc.queryForObject("SELECT status FROM bookings WHERE id=?", String.class, booking)).isEqualTo("PAID");
        assertThat(row.get("note").toString()).startsWith("CUSTOMER_DECLARED;");
        assertThat(first.path("bookingStatus").asText()).isEqualTo("PAID");
        assertThat(first.path("confirmationMethod").asText()).isEqualTo("CUSTOMER_DECLARED");
        mvc.perform(get("/api/bookings/" + booking).contextPath("/api").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.payments[0].paymentStatus").value("PENDING"))
                .andExpect(jsonPath("$.data.payments[0].confirmationMethod").value("CUSTOMER_DECLARED"));
    }
    @ParameterizedTest @ValueSource(strings = {"bankName", "accountHolder", "accountNumber", "transferContent"})
    void rejectsMissingTransferFieldWithoutPersistence(String field) throws Exception {
        var body = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(bankBody());
        ((com.fasterxml.jackson.databind.node.ObjectNode) body.path("bankTransfer")).put(field, "");
        pay(body.toString()).andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_transactions WHERE booking_id=?", Integer.class, booking)).isZero();
    }
    @Test void rejectsCashInvalidAccountMissingFormAndNonPositiveAmount() throws Exception {
        pay("{\"paymentMethod\":\"CASH\"}").andExpect(status().isBadRequest());
        pay("{\"paymentMethod\":\"BANK_TRANSFER\"}").andExpect(status().isBadRequest());
        pay(bankBody().replace("00123456", "abc")).andExpect(status().isBadRequest());
        jdbc.update("UPDATE bookings SET court_fee=0 WHERE id=?", booking);
        pay(bankBody()).andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_transactions WHERE booking_id=?", Integer.class, booking)).isZero();
    }
    @Test void staffCheckoutCannotBypassBookingPaymentWithCashOrUnverifiedTransfer() {
        assertThatThrownBy(() -> checkout.checkout(booking, "CASH")).isInstanceOf(com.bcm.exception.BadRequestException.class);
        assertThatThrownBy(() -> checkout.checkout(booking, "BANK_TRANSFER")).isInstanceOf(com.bcm.exception.BadRequestException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM invoices WHERE booking_id=?", Integer.class, booking)).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM bookings WHERE id=?", String.class, booking)).isEqualTo("PENDING");
    }
    @Test void productOnlyCashSaleStillCommitsInvoiceAndStockOnPostgres() {
        UUID category = UUID.randomUUID(), product = UUID.randomUUID();
        jdbc.update("INSERT INTO categories(id,category_name) VALUES (?,'Lifecycle POS')", category);
        jdbc.update("INSERT INTO products(id,category_id,name,type,price,unit,stock_quantity) VALUES (?,?,'Lifecycle product','GOODS',10000,'piece',10)", product, category);
        try {
            var invoice = pos.sell(new com.bcm.dto.request.PosSaleRequest(customer, null, null, "CASH",
                    java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO,
                    List.of(new com.bcm.dto.request.PosSaleItemRequest(product, 1))));
            assertThat(invoice.getPaymentMethod()).isEqualTo("CASH");
            assertThat(invoice.getStatus()).isEqualTo("PAID");
            assertThat(invoice.getBookingId()).isNull();
            assertThat(jdbc.queryForObject("SELECT stock_quantity FROM products WHERE id=?", Integer.class, product)).isEqualTo(9);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_transactions WHERE booking_id=?", Integer.class, booking)).isZero();
        } finally {
            jdbc.update("DELETE FROM invoice_details WHERE product_id=?", product);
            jdbc.update("DELETE FROM invoices WHERE customer_id=?", customer);
            jdbc.update("DELETE FROM products WHERE id=?", product);
            jdbc.update("DELETE FROM categories WHERE id=?", category);
        }
    }
    @Test void declaredTransferBlocksMethodSwitchAndNeverExpires() throws Exception {
        pay(bankBody()).andExpect(status().isCreated());
        pay("{\"paymentMethod\":\"VNPAY\"}").andExpect(status().isBadRequest());
        org.mockito.Mockito.when(clock.instant()).thenReturn(NOW.plusSeconds(601));
        assertThat(expiration.expireBooking(booking)).isFalse();
        pay(bankBody()).andExpect(status().isCreated());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM booking_details WHERE booking_id=?", Integer.class, booking)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM payment_transactions WHERE booking_id=?", String.class, booking)).isEqualTo("PENDING");
    }
    @ParameterizedTest @ValueSource(longs = {600, 601})
    void rejectsAtAndAfterTenMinutes(long seconds) throws Exception {
        org.mockito.Mockito.when(clock.instant()).thenReturn(NOW.plusSeconds(seconds));
        pay(bankBody()).andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_transactions WHERE booking_id=?", Integer.class, booking)).isZero();
        assertThat(expiration.expireBooking(booking)).isTrue();
        pay(bankBody()).andExpect(status().isConflict());
    }
    @Test void acceptsOneSecondBeforeDeadlineAndRejectsForeignOwner() throws Exception {
        UUID other = UUID.randomUUID(), otherUser = UUID.randomUUID();
        jdbc.update("INSERT INTO users(id,email,password_hash,full_name,phone) VALUES (?,?,'test','Other','0900000000')", otherUser, otherUser + "@test.example");
        jdbc.update("INSERT INTO customers(id,user_id,full_name,phone) VALUES (?,?,'Other','0900000000')", other, otherUser);
        try {
            jdbc.update("UPDATE bookings SET customer_id=? WHERE id=?", other, booking);
            pay(bankBody()).andExpect(status().isForbidden());
        } finally {
            jdbc.update("UPDATE bookings SET customer_id=? WHERE id=?", customer, booking);
            jdbc.update("DELETE FROM customers WHERE id=?", other);
            jdbc.update("DELETE FROM users WHERE id=?", otherUser);
        }
        org.mockito.Mockito.when(clock.instant()).thenReturn(NOW.plusSeconds(599));
        pay(bankBody()).andExpect(status().isCreated());
    }
    @Test void bankAndExpirationRaceAtDeadlineCannotConfirmExpiredBooking() throws Exception {
        org.mockito.Mockito.when(clock.instant()).thenReturn(NOW.plusSeconds(600));
        var workers = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1); var ready = new CountDownLatch(2);
        try {
            var payment = workers.submit(() -> { ready.countDown(); start.await(); return pay(bankBody()).andReturn().getResponse().getStatus(); });
            var expiry = workers.submit(() -> { ready.countDown(); start.await(); return expiration.expireBooking(booking); });
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue(); start.countDown();
            assertThat(payment.get(20, TimeUnit.SECONDS)).isEqualTo(409);
            assertThat(expiry.get(20, TimeUnit.SECONDS)).isTrue();
            assertThat(jdbc.queryForObject("SELECT status FROM bookings WHERE id=?", String.class, booking)).isEqualTo("EXPIRED");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM booking_details WHERE booking_id=?", Integer.class, booking)).isZero();
        } finally { start.countDown(); workers.shutdownNow(); }
    }
    @Test void bankAndExpirationRaceBeforeDeadlinePreservesConfirmedSlots() throws Exception {
        org.mockito.Mockito.when(clock.instant()).thenReturn(NOW.plusSeconds(599));
        var workers = Executors.newFixedThreadPool(2);
        var ready = new CountDownLatch(2); var start = new CountDownLatch(1);
        try {
            var payment = workers.submit(() -> { ready.countDown(); start.await(); return pay(bankBody()).andReturn().getResponse().getStatus(); });
            var expiry = workers.submit(() -> { ready.countDown(); start.await(); return expiration.expireBooking(booking); });
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue(); start.countDown();
            assertThat(payment.get(20, TimeUnit.SECONDS)).isEqualTo(201);
            assertThat(expiry.get(20, TimeUnit.SECONDS)).isFalse();
            org.mockito.Mockito.when(clock.instant()).thenReturn(NOW.plusSeconds(601));
            assertThat(expiration.expireBooking(booking)).isFalse();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM booking_details WHERE booking_id=?", Integer.class, booking)).isEqualTo(1);
            assertThatThrownBy(() -> checkout.checkout(booking, "BANK_TRANSFER"))
                    .isInstanceOf(com.bcm.exception.BadRequestException.class).hasMessageContaining("not receipt of funds");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM invoices WHERE booking_id=?", Integer.class, booking)).isZero();
        } finally { start.countDown(); workers.shutdownNow(); }
    }
    @Test void pendingVnpayCannotSwitchToTransferAndLateCallbackCannotSettleDeclaredBooking() throws Exception {
        var attempt = data(pay("{\"paymentMethod\":\"VNPAY\"}").andExpect(status().isCreated()));
        UUID id = UUID.fromString(attempt.path("paymentAttemptId").asText());
        pay(bankBody()).andExpect(status().isConflict());
        // Legacy failed attempt followed by a declaration; a delayed verified receipt needs reconciliation.
        jdbc.update("UPDATE payment_transactions SET status='FAILED' WHERE id=?", id);
        pay(bankBody()).andExpect(status().isCreated());
        ipn(PaymentCallbackFixtures.vnpay(id, config), "99");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_transactions WHERE booking_id=? AND status='SUCCESS'", Integer.class, booking)).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM bookings WHERE id=?", String.class, booking)).isEqualTo("PAID");
        assertThat(jdbc.queryForObject("SELECT note FROM payment_transactions WHERE id=?", String.class, id)).startsWith("RECONCILIATION_REQUIRED");
    }
    @Test void alreadyConfirmedWithoutDeclarationCannotCreateTransfer() throws Exception {
        jdbc.update("UPDATE bookings SET status='PAID' WHERE id=?", booking);
        pay(bankBody()).andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_transactions WHERE booking_id=?", Integer.class, booking)).isZero();
    }
    @Test void checkoutClockReturnsOriginalDeadlineAndServerTimeWithoutRenewal() throws Exception {
        for (int second : new int[]{1, 270, 541, 600}) {
            org.mockito.Mockito.when(clock.instant()).thenReturn(NOW.plusSeconds(second));
            mvc.perform(get("/api/bookings/" + booking + "/checkout-clock").contextPath("/api").header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.expiresAt").value("2026-10-23T03:10:00"))
                    .andExpect(jsonPath("$.data.status").value("PENDING"));
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_transactions WHERE booking_id=?", Integer.class, booking)).isZero();
    }
    @Test void successfulPaymentWithInconsistentPendingHeaderCannotStartAnotherAttempt() throws Exception {
        jdbc.update("INSERT INTO payment_transactions(booking_id,payment_method,status,amount) VALUES (?,'VNPAY','SUCCESS',125000)", booking);
        pay(bankBody()).andExpect(status().isConflict());
        pay("{\"paymentMethod\":\"VNPAY\"}").andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_transactions WHERE booking_id=?", Integer.class, booking)).isEqualTo(1);
    }
    @Test void callbackCannotConfirmSecondPaymentWithInconsistentPendingHeader() throws Exception {
        UUID id = UUID.fromString(data(pay("{\"paymentMethod\":\"VNPAY\"}").andExpect(status().isCreated())).path("paymentAttemptId").asText());
        // A deliberately inconsistent legacy fixture, not a simulated integration success.
        jdbc.update("INSERT INTO payment_transactions(booking_id,payment_method,status,amount) VALUES (?,'VNPAY','SUCCESS',125000)", booking);
        ipn(PaymentCallbackFixtures.vnpay(id, config), "99");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_transactions WHERE booking_id=? AND status='SUCCESS'", Integer.class, booking)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM payment_transactions WHERE id=?", String.class, id)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT note FROM payment_transactions WHERE id=?", String.class, id)).contains("RECONCILIATION_REQUIRED");
    }
    @Test void concurrentTransferRequestsProduceOnePendingAttempt() throws Exception {
        var workers = Executors.newFixedThreadPool(2);
        var ready = new CountDownLatch(2); var start = new CountDownLatch(1);
        Callable<String> request = () -> { ready.countDown(); assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
            return data(pay(bankBody()).andExpect(status().isCreated())).path("paymentAttemptId").asText(); };
        try {
            var a = workers.submit(request); var b = workers.submit(request);
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue(); start.countDown();
            assertThat(a.get(20, TimeUnit.SECONDS)).isEqualTo(b.get(20, TimeUnit.SECONDS));
            assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_transactions WHERE booking_id=?", Integer.class, booking)).isEqualTo(1);
        } finally { start.countDown(); workers.shutdownNow(); }
    }
    @Test void verifiedFailureAllowsNewAttemptAndCannotDowngradeSuccess() throws Exception {
        var attempt = data(pay("{\"paymentMethod\":\"VNPAY\"}").andExpect(status().isCreated()));
        UUID id = UUID.fromString(attempt.path("paymentAttemptId").asText());
        var fields = PaymentCallbackFixtures.vnpay(id, config);
        fields.put("vnp_ResponseCode", "24"); fields.put("vnp_TransactionStatus", "02");
        PaymentCallbackFixtures.sign(fields, config);
        ipn(fields, "00");
        assertThat(jdbc.queryForObject("SELECT status FROM payment_transactions WHERE id=?", String.class, id)).isEqualTo("FAILED");
        var retry = data(pay("{\"paymentMethod\":\"VNPAY\"}").andExpect(status().isCreated()));
        UUID next = UUID.fromString(retry.path("paymentAttemptId").asText()); assertThat(next).isNotEqualTo(id);
        ipn(PaymentCallbackFixtures.vnpay(next, config), "00");
        fields = PaymentCallbackFixtures.vnpay(next, config);
        fields.put("vnp_ResponseCode", "24"); fields.put("vnp_TransactionStatus", "02"); PaymentCallbackFixtures.sign(fields, config);
        ipn(fields, "00");
        assertThat(jdbc.queryForObject("SELECT status FROM payment_transactions WHERE id=?", String.class, next)).isEqualTo("SUCCESS");
        assertThat(jdbc.queryForObject("SELECT status FROM bookings WHERE id=?", String.class, booking)).isEqualTo("PAID");
    }
    void ipn(Map<String, String> fields, String code) throws Exception {
        fields.put("vnp_Amount", "12500000");
        PaymentCallbackFixtures.sign(fields, config);
        var request = get("/api/payments/vnpay/ipn").contextPath("/api"); fields.forEach(request::param);
        mvc.perform(request).andExpect(status().isOk()).andExpect(jsonPath("$.RspCode").value(code));
    }
    @Test void verifiedReturnRedirectsToFrontendAndDisplayOnlyDoesNotSettle() throws Exception {
        UUID id = UUID.fromString(data(pay("{\"paymentMethod\":\"VNPAY\"}").andExpect(status().isCreated())).path("paymentAttemptId").asText());
        var fields = PaymentCallbackFixtures.vnpay(id, config);
        fields.put("vnp_Amount", "12500000"); PaymentCallbackFixtures.sign(fields, config);
        var redirect = get("/api/payments/vnpay/return").contextPath("/api"); fields.forEach(redirect::param);
        mvc.perform(redirect).andExpect(status().isSeeOther()).andExpect(header().string("Location", org.hamcrest.Matchers.startsWith("http://localhost:5174/payment-result?")));
        var display = get("/api/payments/vnpay/return").contextPath("/api").param("displayOnly", "true"); fields.forEach(display::param);
        mvc.perform(display).andExpect(status().isOk()).andExpect(jsonPath("$.data.providerSuccess").value(true));
        assertThat(jdbc.queryForObject("SELECT status FROM payment_transactions WHERE id=?", String.class, id)).isEqualTo("PENDING");
    }
    @Test void rejectsPastBookingAndAvailabilityDoesNotOfferPastSlots() throws Exception {
        var yesterday = LocalDate.ofInstant(clock.instant(), java.time.ZoneId.of("Asia/Ho_Chi_Minh")).minusDays(1);
        var body = mapper.createObjectNode().put("customerId", customer.toString());
        body.putArray("details").addObject().put("courtId", court.toString()).put("timeSlotId", slot.toString()).put("bookingDate", yesterday.toString());
        mvc.perform(post("/api/bookings").contextPath("/api").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(body.toString())).andExpect(status().isBadRequest());
        var grid = data(mvc.perform(get("/api/availability").contextPath("/api").header("Authorization", "Bearer " + token)
                .param("date", yesterday.toString()).param("courtId", court.toString())).andExpect(status().isOk()));
        grid.path("courts").get(0).path("slots").forEach(s -> assertThat(s.path("available").asBoolean()).isFalse());
    }
}
