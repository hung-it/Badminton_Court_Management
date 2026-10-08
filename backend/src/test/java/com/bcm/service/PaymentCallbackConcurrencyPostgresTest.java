package com.bcm.service;

import com.bcm.config.BookingPaymentConfig;
import com.bcm.entity.PaymentMethod;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.bcm.service.PaymentCallbackFixtures.sign;
import static com.bcm.service.PaymentCallbackTransactionService.Result.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** Real PostgreSQL locks/unique waits/commits, independent worker sessions, no outer test transaction or sleeps.
 * Test spies pause real services AFTER SQL mutations, inside the transaction but BEFORE the proxy commits.
 * pg_stat_activity confirms actual contention; latches control ordering, timeouts only diagnose failures.
 */
@SpringBootTest(properties = {
        "booking.payment.enabled-methods=VNPAY",
        "booking.payment.vnpay.tmn-code=TEST1234",
        "booking.payment.vnpay.hash-secret=unit-test-secret",
        "spring.datasource.hikari.connection-init-sql=SET statement_timeout = '15s'"
})
@AutoConfigureMockMvc
@org.springframework.context.annotation.Import(com.bcm.service.BookingWebTestConfiguration.class)
@EnabledIfEnvironmentVariable(named = "BCM_TEST_DB_URL", matches = "jdbc:postgresql:.*")
class PaymentCallbackConcurrencyPostgresTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 5, 12, 0);
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("BCM_TEST_DB_URL"));
        registry.add("spring.datasource.username", () -> Objects.requireNonNull(System.getenv("BCM_TEST_DB_USERNAME")));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("BCM_TEST_DB_PASSWORD", ""));
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private BookingPaymentConfig config;
    @SpyBean private PaymentCallbackTransactionService settlement;
    @SpyBean private BookingExpirationService expiration;
    @MockBean(name = "bookingExpirationClock") private Clock clock;
    private final AtomicReference<Instant> instant = new AtomicReference<>();
    private final ConcurrentLinkedQueue<Integer> callbackPids = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<PaymentCallbackTransactionService.Result> callbackOutcomes = new ConcurrentLinkedQueue<>();
    private UUID user, customer, slot;
    private UUID[] bookings, payments, courts;
    private volatile Gate callbackGate, expirationGate;

    @BeforeEach
    void fixturesAndTransactionProbes() {
        ZoneId zone = ZoneId.systemDefault();
        when(clock.getZone()).thenReturn(zone);
        instant.set(NOW.atZone(zone).toInstant());
        when(clock.instant()).thenAnswer(call -> instant.get());
        callbackPids.clear(); callbackOutcomes.clear(); callbackGate = null; expirationGate = null;
        doAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            int pid = jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class);
            callbackPids.add(pid);
            var result = (PaymentCallbackTransactionService.Result) call.callRealMethod();
            callbackOutcomes.add(result);
            Gate gate = callbackGate;
            if (gate != null && gate.identity.equals(call.getArgument(0, PaymentNotification.class).attemptId())) {
                gate.pause(pid);
            }
            return result;
        }).when(settlement).settle(any(), any());
        doAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            int pid = jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class);
            boolean result = (boolean) call.callRealMethod();
            Gate gate = expirationGate;
            if (gate != null && gate.identity.equals(call.getArgument(0))) { gate.pause(pid); }
            return result;
        }).when(expiration).expireBooking(any());

        user = UUID.randomUUID(); customer = UUID.randomUUID(); slot = UUID.randomUUID();
        bookings = new UUID[] { UUID.randomUUID(), UUID.randomUUID() };
        payments = new UUID[] { UUID.randomUUID(), UUID.randomUUID() };
        courts = new UUID[] { UUID.randomUUID(), UUID.randomUUID() };
        jdbc.update("INSERT INTO users(id,email,password_hash,full_name,phone) VALUES (?,?,?,'Booking fixture','0900000000')", user, user + "@callback-race.test", "test-only");
        jdbc.update("INSERT INTO customers(id,user_id,full_name,phone) VALUES (?,?,?,?)", customer, user, "Callback race", "0900000000");
        jdbc.update("INSERT INTO time_slots(id,start_time,end_time) VALUES (?,'02:01','02:02')", slot);
        for (int i = 0; i < 2; i++) {
            jdbc.update("INSERT INTO bookings(id,customer_id,status,court_fee,expires_at) VALUES (?,?,'PENDING',10000.00,?)",
                    bookings[i], customer, NOW.plusMinutes(5));
            jdbc.update("INSERT INTO courts(id,court_number,name,base_price) VALUES (?,?,?,10000.00)",
                    courts[i], 999501 + i, "Callback race court " + i);
            jdbc.update("INSERT INTO booking_details(id,booking_id,court_id,time_slot_id,booking_date,price) "
                    + "VALUES (?,?,?,?,'2026-10-05',10000.00)", UUID.randomUUID(), bookings[i], courts[i], slot);
            jdbc.update("INSERT INTO payment_transactions(id,booking_id,payment_method,status,amount) VALUES (?,?,'VNPAY','PENDING',10000.00)",
                    payments[i], bookings[i]);
        }
    }

    @AfterEach
    void cleanup() {
        if (callbackGate != null) { callbackGate.release.countDown(); }
        if (expirationGate != null) { expirationGate.release.countDown(); }
        for (int i = 0; i < 2; i++) {
            jdbc.update("DELETE FROM payment_transactions WHERE booking_id=?", bookings[i]);
            jdbc.update("DELETE FROM booking_details WHERE booking_id=?", bookings[i]);
            jdbc.update("DELETE FROM bookings WHERE id=?", bookings[i]);
            jdbc.update("DELETE FROM courts WHERE id=?", courts[i]);
        }
        jdbc.update("DELETE FROM time_slots WHERE id=?", slot);
        jdbc.update("DELETE FROM customers WHERE id=?", customer);
        jdbc.update("DELETE FROM users WHERE id=?", user);
    }

    private record Payload(PaymentMethod method, Map<String, String> query) { }
    private record Ack(int httpStatus, String code, String message, String body) { }
    private record Snapshot(Map<String, Object> booking, Map<String, Object> payment, List<Map<String, Object>> details) { }
    private static class Gate {
        final UUID identity;
        final CountDownLatch held = new CountDownLatch(1), release = new CountDownLatch(1);
        final AtomicInteger pid = new AtomicInteger();
        Gate(UUID identity) { this.identity = identity; }
        void pause(int backendPid) throws InterruptedException {
            pid.set(backendPid); held.countDown();
            if (!release.await(15, TimeUnit.SECONDS)) { throw new AssertionError("Transaction gate timed out"); }
        }
        void awaitHeld() throws InterruptedException { assertThat(held.await(10, TimeUnit.SECONDS)).isTrue(); }
    }

    private Payload payload(int index, PaymentMethod method, String transactionId) {
        jdbc.update("UPDATE payment_transactions SET payment_method=? WHERE id=?", method.name(), payments[index]);
        var fields = PaymentCallbackFixtures.vnpay(payments[index], config);
        fields.put("vnp_Amount", "1000000"); fields.put("vnp_TransactionNo", transactionId); sign(fields, config);
        return new Payload(method, fields);
    }

    private Ack callback(Payload payload) throws Exception {
        var request = get("/api/payments/vnpay/ipn").contextPath("/api"); payload.query.forEach(request::param);
        var response = mvc.perform(request).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(200);
        var body = mapper.readTree(response.getContentAsString());
        assertThat(body.size()).isEqualTo(2);
        assertThat(body.has("RspCode") && body.has("Message")).isTrue();
        return new Ack(response.getStatus(), body.path("RspCode").asText(), body.path("Message").asText(), response.getContentAsString());
    }

    private void assertHandled(Ack ack, PaymentMethod method, String vnpayCode) {
        assertThat(ack.code).isEqualTo(vnpayCode);
    }
    private Snapshot snapshot(int index) {
        return new Snapshot(jdbc.queryForMap("SELECT * FROM bookings WHERE id=?", bookings[index]),
                jdbc.queryForMap("SELECT * FROM payment_transactions WHERE id=?", payments[index]),
                jdbc.queryForList("SELECT * FROM booking_details WHERE booking_id=? ORDER BY id", bookings[index]));
    }
    private void assertSettled(int index, String transactionId) {
        var state = snapshot(index);
        assertThat(state.booking).containsEntry("status", "PAID");
        assertThat(state.payment).containsEntry("status", "SUCCESS").containsEntry("transaction_id", transactionId);
        assertThat(state.details).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_transactions WHERE booking_id=?", Integer.class, bookings[index])).isEqualTo(1);
    }
    private void assertSlotAvailability(int index, boolean available) throws Exception {
        var response = mvc.perform(get("/api/availability").contextPath("/api").param("date", "2026-10-05")
                .param("courtId", courts[index].toString())).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(200);
        var slots = mapper.readTree(response.getContentAsString()).path("data").path("courts").get(0).path("slots");
        var fixtureSlots = java.util.stream.StreamSupport.stream(slots.spliterator(), false)
                .filter(item -> slot.toString().equals(item.path("timeSlotId").asText())).toList();
        assertThat(fixtureSlots).hasSize(1);
        assertThat(fixtureSlots.get(0).path("available").asBoolean()).isEqualTo(available);
    }

    private void lockBooking(Connection holder, int index) throws Exception {
        holder.setAutoCommit(false);
        try (var statement = holder.prepareStatement("SELECT id FROM bookings WHERE id=? FOR UPDATE")) {
            statement.setObject(1, bookings[index]); statement.executeQuery().close();
        }
    }
    private void awaitWaiters(String queryFragment, int count, int blockerPid) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            var pids = jdbc.queryForList("SELECT pid FROM pg_stat_activity WHERE datname=current_database() "
                    + "AND pid<>pg_backend_pid() AND state='active' AND wait_event_type='Lock' "
                    + "AND lower(query) LIKE ? AND (?=0 OR ?=ANY(pg_blocking_pids(pid)))",
                    Integer.class, "%" + queryFragment + "%", blockerPid, blockerPid);
            if (pids.size() == count) { assertThat(pids.stream().distinct().count()).isEqualTo(count); return; }
            Thread.yield(); // Observation of actual DB state, never time-based race scheduling.
        }
        throw new AssertionError("Expected " + count + " PostgreSQL waiters on " + queryFragment);
    }
    private void shutdown(ExecutorService workers) throws InterruptedException {
        workers.shutdownNow(); assertThat(workers.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
    }
    private List<Ack> raceSameBooking(Payload first, Payload second) throws Exception {
        var workers = Executors.newFixedThreadPool(2);
        var ready = new CountDownLatch(2); var start = new CountDownLatch(1);
        try (Connection holder = dataSource.getConnection()) {
            lockBooking(holder, 0);
            try {
                Callable<Ack> one = () -> { ready.countDown(); assertThat(start.await(10, TimeUnit.SECONDS)).isTrue(); return callback(first); };
                Callable<Ack> two = () -> { ready.countDown(); assertThat(start.await(10, TimeUnit.SECONDS)).isTrue(); return callback(second); };
                Future<Ack> a = workers.submit(one), b = workers.submit(two);
                assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue(); start.countDown();
                awaitWaiters("from bookings", 2, 0);
                assertThat(callbackPids.stream().distinct().count()).isEqualTo(2);
                assertThat(a.isDone() || b.isDone()).isFalse();
                holder.commit();
                return List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS));
            } finally { start.countDown(); holder.rollback(); shutdown(workers); }
        }
    }

    @ParameterizedTest
    @EnumSource(value = PaymentMethod.class, names = "VNPAY")
    void concurrentDuplicateHasOneSettlementThenReplayAndTenDeliveriesDoNotChangeRows(PaymentMethod method) throws Exception {
        var payload = payload(0, method, "14226112"); var before = snapshot(0);
        var acks = raceSameBooking(payload, payload);
        assertThat(acks).extracting(Ack::code).containsExactlyInAnyOrder("00", "02");
        assertThat(callbackOutcomes).containsExactlyInAnyOrder(SUCCESS, ALREADY_CONFIRMED);
        assertSettled(0, "14226112"); var settled = snapshot(0);
        assertThat(settled.details).isEqualTo(before.details);
        assertThat(settled.booking.get("court_fee")).isEqualTo(before.booking.get("court_fee"));
        assertThat(settled.payment.get("amount")).isEqualTo(before.payment.get("amount"));
        for (int i = 0; i < 8; i++) { assertHandled(callback(payload), method, "02"); assertThat(snapshot(0)).isEqualTo(settled); }
        assertSlotAvailability(0, false);
    }

    @ParameterizedTest
    @EnumSource(value = PaymentMethod.class, names = "VNPAY")
    void concurrentConflictingTransactionIdsDoNotOverwriteWinner(PaymentMethod method) throws Exception {
        var first = payload(0, method, "14226112"); var second = payload(0, method, "14226113");
        var acks = raceSameBooking(first, second);
        assertThat(acks).extracting(Ack::code).containsExactlyInAnyOrder("00", "99");
        assertThat(callbackOutcomes).containsExactlyInAnyOrder(SUCCESS, CONFLICT);
        var settled = snapshot(0); String id = (String) settled.payment.get("transaction_id");
        assertThat(id).isIn("14226112", "14226113"); assertSettled(0, id);
        var conflicting = id.equals("14226112") ? second : first;
        var ack = callback(conflicting); assertHandled(ack, method, "99");
        assertThat(ack.message).isEqualTo("Conflicting payment confirmation");
        assertThat(snapshot(0)).isEqualTo(settled);
    }

    @ParameterizedTest
    @CsvSource({"VNPAY,VNPAY"})
    void globallyUniqueTransactionIdRaceCommitsOneOwnerAndRollsBackLoser(PaymentMethod firstMethod, PaymentMethod secondMethod) throws Exception {
        var first = payload(0, firstMethod, "14226112"); var second = payload(1, secondMethod, "14226112");
        var beforeLoser = snapshot(1); var beforeWinner = snapshot(0);
        var workers = Executors.newFixedThreadPool(2); callbackGate = new Gate(payments[0]);
        try {
            var winner = workers.submit(() -> callback(first)); callbackGate.awaitHeld();
            var loser = workers.submit(() -> callback(second));
            awaitWaiters("update payment_transactions", 1, callbackGate.pid.get());
            assertThat(callbackPids.stream().distinct().count()).isEqualTo(2);
            assertThat(winner.isDone() || loser.isDone()).isFalse();
            assertThat(snapshot(0)).isEqualTo(beforeWinner); // no success acknowledgment or visible SQL before commit
            callbackGate.release.countDown();
            assertHandled(winner.get(20, TimeUnit.SECONDS), firstMethod, "00");
            var rejected = loser.get(20, TimeUnit.SECONDS); assertHandled(rejected, secondMethod, "99");
            assertThat(rejected.message).isEqualTo("Transaction ID belongs to another payment");
            assertSettled(0, "14226112"); assertThat(snapshot(1)).isEqualTo(beforeLoser);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_transactions WHERE transaction_id='14226112'", Integer.class)).isEqualTo(1);
            var winnerState = snapshot(0);
            assertHandled(callback(second), secondMethod, "99"); // collision replay remains deterministic
            assertThat(snapshot(0)).isEqualTo(winnerState); assertThat(snapshot(1)).isEqualTo(beforeLoser);
        } finally { callbackGate.release.countDown(); shutdown(workers); }
    }

    @ParameterizedTest
    @EnumSource(value = PaymentMethod.class, names = "VNPAY")
    void callbackLockWinsThenStaleExpirationCandidateCannotDeletePaidDetails(PaymentMethod method) throws Exception {
        var payload = payload(0, method, "14226112"); var before = snapshot(0);
        var workers = Executors.newFixedThreadPool(2); callbackGate = new Gate(payments[0]);
        try {
            var paid = workers.submit(() -> callback(payload)); callbackGate.awaitHeld();
            instant.set(NOW.plusMinutes(5).atZone(clock.getZone()).toInstant());
            assertThat(expiration.findCandidates()).contains(bookings[0]); // committed snapshot is still PENDING
            var expired = workers.submit(() -> expiration.expireBooking(bookings[0]));
            awaitWaiters("from bookings", 1, callbackGate.pid.get());
            assertThat(paid.isDone() || expired.isDone()).isFalse(); callbackGate.release.countDown();
            assertHandled(paid.get(20, TimeUnit.SECONDS), method, "00"); assertThat(expired.get(20, TimeUnit.SECONDS)).isFalse();
            assertSettled(0, "14226112"); assertThat(snapshot(0).details).isEqualTo(before.details);
            assertSlotAvailability(0, false);
        } finally { callbackGate.release.countDown(); shutdown(workers); }
    }

    @ParameterizedTest
    @EnumSource(value = PaymentMethod.class, names = "VNPAY")
    void expirationLockWinsAndCallbackCannotReviveOrRecreateSlots(PaymentMethod method) throws Exception {
        var payload = payload(0, method, "14226112"); var before = snapshot(0);
        instant.set(NOW.plusMinutes(5).atZone(clock.getZone()).toInstant());
        var workers = Executors.newFixedThreadPool(2); expirationGate = new Gate(bookings[0]);
        try {
            var expired = workers.submit(() -> expiration.expireBooking(bookings[0])); expirationGate.awaitHeld();
            var paid = workers.submit(() -> callback(payload));
            awaitWaiters("from bookings", 1, expirationGate.pid.get());
            assertThat(paid.isDone() || expired.isDone()).isFalse(); expirationGate.release.countDown();
            assertThat(expired.get(20, TimeUnit.SECONDS)).isTrue();
            var ack = paid.get(20, TimeUnit.SECONDS); assertHandled(ack, method, "99");
            assertThat(ack.message).isEqualTo("Booking expired or payment deadline passed");
            var after = snapshot(0); assertThat(after.booking).containsEntry("status", "EXPIRED");
            assertThat(after.payment).isEqualTo(before.payment); assertThat(after.details).isEmpty();
            assertThat(callbackOutcomes).containsExactly(LATE_EXPIRED); assertSlotAvailability(0, true);
        } finally { expirationGate.release.countDown(); shutdown(workers); }
    }

    @ParameterizedTest
    @EnumSource(value = PaymentMethod.class, names = "VNPAY")
    void callbackArrivesBeforeDeadlineButDecidesAfterWaitingAtExactDeadline(PaymentMethod method) throws Exception {
        var payload = payload(0, method, "14226112"); var before = snapshot(0);
        var workers = Executors.newSingleThreadExecutor();
        try (Connection holder = dataSource.getConnection()) {
            lockBooking(holder, 0);
            try {
                var paid = workers.submit(() -> callback(payload)); awaitWaiters("from bookings", 1, 0);
                assertThat(paid.isDone()).isFalse(); instant.set(NOW.plusMinutes(5).atZone(clock.getZone()).toInstant());
                holder.commit(); assertHandled(paid.get(20, TimeUnit.SECONDS), method, "99");
                assertThat(callbackOutcomes).containsExactly(LATE_EXPIRED); assertThat(snapshot(0)).isEqualTo(before);
                assertSlotAvailability(0, false); // expiration still owns removal; callback does not mutate details
            } finally { holder.rollback(); shutdown(workers); }
        }
    }

    @ParameterizedTest
    @EnumSource(value = PaymentMethod.class, names = "VNPAY")
    void paymentRowLockIsAcquiredAfterBookingAndExpirationWaitsWithoutDeadlock(PaymentMethod method) throws Exception {
        var payload = payload(0, method, "14226112");
        var workers = Executors.newFixedThreadPool(2);
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try (var statement = holder.prepareStatement("SELECT id FROM payment_transactions WHERE id=? FOR UPDATE")) {
                statement.setObject(1, payments[0]); statement.executeQuery().close();
            }
            try {
                var paid = workers.submit(() -> callback(payload));
                awaitWaiters("from payment_transactions", 1, 0); // callback already holds Booking, waits on Payment
                assertThat(callbackPids).hasSize(1);
                var expired = workers.submit(() -> expiration.expireBooking(bookings[0]));
                awaitWaiters("from bookings", 1, callbackPids.peek()); // actual Booking lock belongs to callback session
                assertThat(paid.isDone() || expired.isDone()).isFalse();
                holder.commit();
                assertHandled(paid.get(20, TimeUnit.SECONDS), method, "00");
                assertThat(expired.get(20, TimeUnit.SECONDS)).isFalse();
                assertSettled(0, "14226112");
            } finally { holder.rollback(); shutdown(workers); }
        }
    }

    @ParameterizedTest
    @EnumSource(value = PaymentMethod.class, names = "VNPAY")
    void invalidSignatureReturnsWhileLifecycleRowIsLocked(PaymentMethod method) throws Exception {
        var payload = payload(0, method, "14226112"); var before = snapshot(0);
        payload.query.put("vnp_Amount", "1");
        var workers = Executors.newSingleThreadExecutor();
        try (Connection holder = dataSource.getConnection()) {
            lockBooking(holder, 0);
            try {
                var ack = workers.submit(() -> callback(payload)).get(5, TimeUnit.SECONDS);
                assertThat(ack.code).isEqualTo("97");
                assertThat(callbackPids).isEmpty(); assertThat(snapshot(0)).isEqualTo(before);
            } finally { holder.rollback(); shutdown(workers); }
        }
    }

    @ParameterizedTest
    @EnumSource(value = PaymentMethod.class, names = "VNPAY")
    void failureRacingWithUncommittedSuccessAndAfterCommitNeverDowngrades(PaymentMethod method) throws Exception {
        var success = payload(0, method, "14226112"); var failure = payload(0, method, "14226112");
        failure.query.put("vnp_ResponseCode", "01"); sign(failure.query, config);
        var workers = Executors.newFixedThreadPool(2); callbackGate = new Gate(payments[0]);
        try {
            var paid = workers.submit(() -> callback(success)); callbackGate.awaitHeld();
            var ignored = workers.submit(() -> callback(failure));
            assertHandled(ignored.get(5, TimeUnit.SECONDS), method, "00"); assertThat(paid.isDone()).isFalse();
            callbackGate.release.countDown(); assertHandled(paid.get(20, TimeUnit.SECONDS), method, "00");
            assertSettled(0, "14226112"); var settled = snapshot(0);
            assertHandled(callback(failure), method, "00"); assertThat(snapshot(0)).isEqualTo(settled);
        } finally { callbackGate.release.countDown(); shutdown(workers); }
    }

    @ParameterizedTest
    @CsvSource({"VNPAY,amount", "VNPAY,merchant", "VNPAY,reference"})
    void authenticatedConflictingAmountMerchantOrCorrelationCannotChangeConfirmedRows(PaymentMethod method, String conflict) throws Exception {
        var payload = payload(0, method, "14226112"); assertHandled(callback(payload), method, "00"); var settled = snapshot(0);
        switch (conflict) {
            case "amount" -> payload.query.put("vnp_Amount", "1000001");
            case "merchant" -> payload.query.put("vnp_TmnCode", "OTHER123");
            case "reference" -> payload.query.put("vnp_TxnRef", UUID.randomUUID().toString().replace("-", ""));
        }
        sign(payload.query, config);
        assertThat(callback(payload).code).isEqualTo(conflict.equals("amount") ? "04" : conflict.equals("reference") ? "01" : "99");
        assertThat(snapshot(0)).isEqualTo(settled);
    }

    @Test
    void changedSignedPayDateIsConflictWithoutOverwritingConfirmedTimestamp() throws Exception {
        var payload = payload(0, PaymentMethod.VNPAY, "14226112"); assertHandled(callback(payload), PaymentMethod.VNPAY, "00");
        var settled = snapshot(0); payload.query.put("vnp_PayDate", "20261005120100"); sign(payload.query, config);
        assertThat(callback(payload).message).isEqualTo("Conflicting payment confirmation"); assertThat(snapshot(0)).isEqualTo(settled);
    }
}
