package com.bcm.service;

import com.bcm.config.BookingPaymentConfig;
import com.bcm.entity.BookingStatus;
import com.bcm.entity.PaymentMethod;
import com.bcm.entity.PaymentStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import static com.bcm.service.PaymentCallbackFixtures.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Dedicated PostgreSQL/schema-original, committed fixtures, real service transactions and constraints. */
@SpringBootTest(properties = {
        "booking.payment.enabled-methods=VNPAY",
        "booking.payment.vnpay.tmn-code=TEST1234",
        "booking.payment.vnpay.hash-secret=unit-test-secret",
        "spring.datasource.hikari.connection-init-sql=SET statement_timeout = '15s'"
})
@AutoConfigureMockMvc
@org.springframework.context.annotation.Import(com.bcm.service.BookingWebTestConfiguration.class)
@EnabledIfEnvironmentVariable(named = "BCM_TEST_DB_URL", matches = "jdbc:postgresql:.*")
class PaymentCallbackPostgresTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 5, 12, 0);
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("BCM_TEST_DB_URL"));
        registry.add("spring.datasource.username", () -> Objects.requireNonNull(System.getenv("BCM_TEST_DB_USERNAME")));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("BCM_TEST_DB_PASSWORD", ""));
    }
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MockMvc mvc;
    @Autowired private BookingPaymentConfig config;
    @Autowired private ObjectMapper mapper;
    @Autowired private BookingExpirationService expiration;
    @MockBean(name = "bookingExpirationClock") private Clock clock;
    private UUID user, customer, booking, court, slot, detail;

    @BeforeEach
    void fixtures() {
        var zone = ZoneId.systemDefault();
        when(clock.getZone()).thenReturn(zone);
        when(clock.instant()).thenReturn(NOW.atZone(zone).toInstant());
        user = UUID.randomUUID(); customer = UUID.randomUUID(); booking = UUID.randomUUID();
        court = UUID.randomUUID(); slot = UUID.randomUUID(); detail = UUID.randomUUID();
        jdbc.update("INSERT INTO users(id,email,password_hash,full_name,phone) VALUES (?,?,?,'Booking fixture','0900000000')", user, user + "@callback.test", "test-only");
        jdbc.update("INSERT INTO customers(id,user_id,full_name,phone) VALUES (?,?,?,?)", customer, user, "Callback", "0900000000");
        jdbc.update("INSERT INTO bookings(id,customer_id,status,court_fee,expires_at) VALUES (?,?,'PENDING',10000.01,?)",
                booking, customer, NOW.plusMinutes(5));
        jdbc.update("INSERT INTO courts(id,court_number,name,base_price) VALUES (?,?,?,10000.01)",
                court, 999001, "Callback test court");
        jdbc.update("INSERT INTO time_slots(id,start_time,end_time) VALUES (?,'01:01','01:02')", slot);
        jdbc.update("INSERT INTO booking_details(id,booking_id,court_id,time_slot_id,booking_date,price) "
                + "VALUES (?,?,?,?,'2026-10-05',10000.01)", detail, booking, court, slot);
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM payment_transactions WHERE booking_id=?", booking);
        jdbc.update("DELETE FROM booking_details WHERE booking_id=?", booking);
        jdbc.update("DELETE FROM bookings WHERE id=?", booking);
        jdbc.update("DELETE FROM courts WHERE id=?", court);
        jdbc.update("DELETE FROM time_slots WHERE id=?", slot);
        jdbc.update("DELETE FROM customers WHERE id=?", customer);
        jdbc.update("DELETE FROM users WHERE id=?", user);
    }

    private UUID attempt(PaymentMethod method) {
        UUID id = UUID.randomUUID();

        jdbc.update("INSERT INTO payment_transactions(id,booking_id,payment_method,status,amount) VALUES (?,?,?,'PENDING',?)",
                id, booking, method.name(), amount(method));
        return id;
    }

    private BigDecimal amount(PaymentMethod method) { return new BigDecimal("10000.01"); }

    private void send(PaymentMethod method, UUID attempt, String vnpayCode) throws Exception {
        send(vnpay(attempt, config), vnpayCode);
    }
    private void send(Map<String, String> fields, String code) throws Exception {
        var request = get("/payments/vnpay/ipn"); fields.forEach(request::param);
        mvc.perform(request).andExpect(status().isOk()).andExpect(jsonPath("$.RspCode").value(code))
                .andExpect(jsonPath("$.success").doesNotExist());
    }

    private Map<String, Object> payment(UUID id) { return jdbc.queryForMap("SELECT * FROM payment_transactions WHERE id=?", id); }
    private Map<String, Object> header() { return jdbc.queryForMap("SELECT * FROM bookings WHERE id=?", booking); }

    @ParameterizedTest
    @EnumSource(value = PaymentMethod.class, names = "VNPAY")
    void verifiedSuccessCommitsTogetherAndPreservesDetailsAndPrices(PaymentMethod method) throws Exception {
        UUID id = attempt(method);
        var original = payment(id);
        var originalHeader = header();
        var originalDetails = jdbc.queryForList("SELECT * FROM booking_details WHERE booking_id=?", booking);
        send(method, id, "00");
        assertThat(payment(id)).containsEntry("status", "SUCCESS").containsEntry("amount", original.get("amount"))
                .containsEntry("transaction_id", "14226112");
        assertThat(header()).containsEntry("status", "PAID").containsEntry("court_fee", originalHeader.get("court_fee"));
        var expected = LocalDateTime.of(2026, 10, 5, 12, 0).atZone(ZoneId.of("Asia/Ho_Chi_Minh"))
                .withZoneSameInstant(clock.getZone()).toLocalDateTime();
        assertThat(((java.sql.Timestamp) payment(id).get("transaction_date")).toLocalDateTime()).isEqualTo(expected);
        assertThat(jdbc.queryForList("SELECT * FROM booking_details WHERE booking_id=?", booking)).isEqualTo(originalDetails);
    }

    @ParameterizedTest
    @EnumSource(value = PaymentMethod.class, names = "VNPAY")
    void sequentialDuplicatePreservesConfirmedRows(PaymentMethod method) throws Exception {
        UUID id = attempt(method);
        send(method, id, "00");
        var confirmedPayment = payment(id); var confirmedBooking = header();
        send(method, id, "02");
        assertThat(payment(id)).isEqualTo(confirmedPayment); assertThat(header()).isEqualTo(confirmedBooking);
    }

    @ParameterizedTest
    @CsvSource({"VNPAY,0", "VNPAY,-1"})
    void deadlineEqualityAndPastCannotSettle(PaymentMethod method, int seconds) throws Exception {
        UUID id = attempt(method);
        jdbc.update("UPDATE bookings SET expires_at=? WHERE id=?", NOW.plusSeconds(seconds), booking);
        var before = payment(id); var beforeHeader = header();
        send(method, id, "99");
        assertThat(payment(id)).isEqualTo(before); assertThat(header()).isEqualTo(beforeHeader);
    }

    @ParameterizedTest
    @EnumSource(value = PaymentMethod.class, names = "VNPAY")
    void expiredBookingRemainsExpiredAndDetailsAreNotRecreated(PaymentMethod method) throws Exception {
        UUID id = attempt(method);
        jdbc.update("UPDATE bookings SET expires_at=? WHERE id=?", NOW.minusSeconds(1), booking);
        assertThat(expiration.expireBooking(booking)).isTrue();
        send(method, id, "99");
        assertThat(header()).containsEntry("status", "EXPIRED");
        assertThat(payment(id)).containsEntry("status", "PENDING");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM booking_details WHERE booking_id=?", Integer.class, booking)).isZero();
    }

    @ParameterizedTest
    @EnumSource(value = BookingStatus.class, names = {"PAID", "CHECKED_IN", "COMPLETED", "NO_SHOW", "EXPIRED"})
    void nonPendingBookingNeverTransitions(BookingStatus state) throws Exception {
        UUID id = attempt(PaymentMethod.VNPAY);
        jdbc.update("UPDATE bookings SET status=? WHERE id=?", state.name(), booking);
        var before = header();
        send(PaymentMethod.VNPAY, id, "99");
        assertThat(header()).isEqualTo(before); assertThat(payment(id)).containsEntry("status", "PENDING");
    }

    @ParameterizedTest
    @EnumSource(value = PaymentStatus.class, names = {"SUCCESS", "FAILED", "REFUNDED"})
    void nonPendingPaymentCannotBeOverwritten(PaymentStatus state) throws Exception {
        UUID id = attempt(PaymentMethod.VNPAY);
        jdbc.update("UPDATE payment_transactions SET status=?,transaction_id='existing' WHERE id=?", state.name(), id);
        var before = payment(id); var beforeHeader = header();
        send(PaymentMethod.VNPAY, id, "99");
        assertThat(payment(id)).isEqualTo(before); assertThat(header()).isEqualTo(beforeHeader);
    }

    @ParameterizedTest
    @CsvSource({"VNPAY,signature", "VNPAY,unknown", "VNPAY,amount", "VNPAY,failure", "VNPAY,transaction", "VNPAY,date", "VNPAY,merchant", "VNPAY,reference"})
    void invalidCallbacksNeverMutate(PaymentMethod method, String problem) throws Exception {
        UUID id = attempt(method);
        var before = payment(id); var beforeHeader = header();
        UUID reference = problem.equals("unknown") ? UUID.randomUUID() : id;
        var fields = vnpay(reference, config);
        String code = "99";
        switch (problem) {
            case "signature" -> { fields.put("vnp_Amount", "1"); code = "97"; }
            case "unknown" -> code = "01";
            case "amount" -> { fields.put("vnp_Amount", "1000000"); code = "04"; }
            case "failure" -> { fields.put("vnp_TransactionStatus", "01"); code = "00"; }
            case "transaction" -> fields.put("vnp_TransactionNo", "broken");
            case "date" -> fields.put("vnp_PayDate", "20260230120000");
            case "merchant" -> fields.put("vnp_TmnCode", "OTHER123");
            case "reference" -> { fields.put("vnp_TxnRef", "bad"); code = "01"; }
        }
        if (!problem.equals("signature")) { sign(fields, config); }
        send(fields, code);
        assertThat(payment(id)).isEqualTo(before); assertThat(header()).isEqualTo(beforeHeader);
    }

    @ParameterizedTest
    @EnumSource(value = PaymentMethod.class, names = "VNPAY")
    void uniqueGatewayTransactionConflictRollsBackBothEntities(PaymentMethod method) throws Exception {
        UUID id = attempt(method); UUID other = attempt(method);
        jdbc.update("UPDATE payment_transactions SET status='FAILED',transaction_id=? WHERE id=?",
                "14226112", other);
        var before = payment(id); var beforeHeader = header(); var otherBefore = payment(other);
        // Test-only observation on the disposable DB: sequence changes survive transaction rollback.
        // Proves Booking's SQL UPDATE happened before the real unique constraint aborted Payment's UPDATE.
        jdbc.execute("CREATE SEQUENCE phase5b_rollback_probe");
        jdbc.execute("CREATE FUNCTION phase5b_observe_paid() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
                + "IF NEW.id = '" + booking + "'::uuid AND NEW.status = 'PAID' THEN PERFORM nextval('phase5b_rollback_probe'); END IF; "
                + "RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER phase5b_observe_paid AFTER UPDATE ON bookings FOR EACH ROW EXECUTE FUNCTION phase5b_observe_paid()");
        try {
            send(method, id, "99");
            assertThat(jdbc.queryForObject("SELECT is_called FROM phase5b_rollback_probe", Boolean.class)).isTrue();
            assertThat(payment(id)).isEqualTo(before); assertThat(header()).isEqualTo(beforeHeader);
            assertThat(payment(other)).isEqualTo(otherBefore);
        } finally {
            jdbc.execute("DROP TRIGGER phase5b_observe_paid ON bookings");
            jdbc.execute("DROP FUNCTION phase5b_observe_paid()");
            jdbc.execute("DROP SEQUENCE phase5b_rollback_probe");
        }
    }

    @Test
    void browserReturnCannotSettleEvenValidSuccess() throws Exception {
        UUID id = attempt(PaymentMethod.VNPAY); var before = payment(id); var beforeHeader = header();
        var request = get("/payments/vnpay/return"); vnpay(id, config).forEach(request::param);
        mvc.perform(request).andExpect(status().isOk()).andExpect(jsonPath("$.data.providerSuccess").value(true));
        assertThat(payment(id)).isEqualTo(before); assertThat(header()).isEqualTo(beforeHeader);
    }

    @Test
    void exactAttemptIsUsedWhenOtherHistoryHasSameAmountAndBooking() throws Exception {
        UUID older = attempt(PaymentMethod.VNPAY); UUID selected = attempt(PaymentMethod.VNPAY);
        jdbc.update("UPDATE payment_transactions SET status='FAILED' WHERE id=?", older);
        var history = payment(older);
        send(PaymentMethod.VNPAY, selected, "00");
        assertThat(payment(selected)).containsEntry("status", "SUCCESS"); assertThat(payment(older)).isEqualTo(history);
    }

    @Test
    void sequentialContradictorySuccessDoesNotOverwriteConfirmedInfo() throws Exception {
        UUID id = attempt(PaymentMethod.VNPAY); send(PaymentMethod.VNPAY, id, "00");
        var before = payment(id); var beforeHeader = header();
        var fields = vnpay(id, config); fields.put("vnp_TransactionNo", "14226113"); sign(fields, config);
        send(fields, "99"); assertThat(payment(id)).isEqualTo(before); assertThat(header()).isEqualTo(beforeHeader);
        fields.put("vnp_ResponseCode", "01"); sign(fields, config);
        send(fields, "00"); assertThat(payment(id)).isEqualTo(before); assertThat(header()).isEqualTo(beforeHeader);
    }
}
