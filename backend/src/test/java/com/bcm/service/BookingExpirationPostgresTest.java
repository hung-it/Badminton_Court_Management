package com.bcm.service;

import com.bcm.dto.request.CreateBookingRequest;
import com.bcm.entity.BookingStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.support.ScheduledMethodRunnable;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Real PostgreSQL and committed fixtures; only the Clock is mocked, never repositories or DB locks. */
@SpringBootTest(properties = {
        "booking.expiration-enabled=true",
        "booking.expiration-check-interval=PT24H",
        "spring.datasource.hikari.connection-init-sql=SET statement_timeout = '15s'"
})
@AutoConfigureMockMvc
@org.springframework.context.annotation.Import(com.bcm.service.BookingWebTestConfiguration.class)
@EnabledIfEnvironmentVariable(named = "BCM_TEST_DB_URL", matches = "jdbc:postgresql:.*")
class BookingExpirationPostgresTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 5, 12, 0);
    private static final LocalDate DATE = LocalDate.of(2020, 2, 29);

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("BCM_TEST_DB_URL"));
        registry.add("spring.datasource.username", () -> Objects.requireNonNull(
                System.getenv("BCM_TEST_DB_USERNAME"), "BCM_TEST_DB_USERNAME is required"));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("BCM_TEST_DB_PASSWORD", ""));
    }

    @MockBean(name = "bookingExpirationClock") private Clock clock;
    @Autowired private BookingExpirationService expiration;
    @Autowired private BookingExpirationJob job;
    @Autowired private AvailabilityService availability;
    @Autowired private ScheduledAnnotationBeanPostProcessor scheduling;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;

    private UUID userId;
    private UUID customerId;
    private UUID courtId;
    private UUID slotId;
    private int dateOffset;

    @BeforeEach
    void fixtures() {
        var zone = ZoneId.systemDefault();
        when(clock.getZone()).thenReturn(zone);
        when(clock.instant()).thenReturn(NOW.atZone(zone).toInstant());
        userId = UUID.randomUUID();
        customerId = UUID.randomUUID();
        courtId = UUID.randomUUID();
        slotId = UUID.randomUUID();
        dateOffset = 0;
        jdbc.update("INSERT INTO users(id,email,password_hash,full_name,phone) VALUES (?,?,?,'Booking fixture','0900000000')", userId, userId + "@expiration.test", "test-only");
        jdbc.update("INSERT INTO customers(id,user_id,full_name,phone) VALUES (?,?,?,?)",
                customerId, userId, "Expiration test", "0900000000");
        jdbc.update("INSERT INTO courts(id,court_number,name,base_price) VALUES (?, -501, 'Expiration test', 10.00)", courtId);
        jdbc.update("INSERT INTO time_slots(id,start_time,end_time,price_multiplier) VALUES (?, '00:00', '01:00', 1.00)", slotId);
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM payment_transactions WHERE booking_id IN (SELECT id FROM bookings WHERE customer_id = ?)", customerId);
        jdbc.update("DELETE FROM bookings WHERE customer_id = ?", customerId);
        jdbc.update("DELETE FROM courts WHERE id = ?", courtId);
        jdbc.update("DELETE FROM time_slots WHERE id = ?", slotId);
        jdbc.update("DELETE FROM customers WHERE id = ?", customerId);
        jdbc.update("DELETE FROM users WHERE id = ?", userId);
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0})
    void expiresBeforeAndExactlyAtDeadline(int seconds) {
        UUID id = seed(BookingStatus.PENDING, NOW.plusSeconds(seconds), 1);
        assertThat(expiration.findCandidates()).contains(id);
        assertThat(expiration.expireBooking(id)).isTrue();
        assertState(id, BookingStatus.EXPIRED, 0);
    }

    @Test
    void futureDeadlineIsNotEligible() {
        UUID id = seed(BookingStatus.PENDING, NOW.plusNanos(1000), 1);
        assertThat(expiration.findCandidates()).doesNotContain(id);
        assertThat(expiration.expireBooking(id)).isFalse();
        assertState(id, BookingStatus.PENDING, 1);
    }

    @ParameterizedTest
    @EnumSource(value = BookingStatus.class, names = "PENDING", mode = EnumSource.Mode.EXCLUDE)
    void neverChangesNonPendingBookingsOrTheirDetails(BookingStatus state) {
        UUID id = seed(state, NOW.minusDays(1), 1);
        Map<String, Object> before = header(id);
        assertThat(expiration.findCandidates()).doesNotContain(id);
        assertThat(expiration.expireBooking(id)).isFalse();
        job.expireDueBookings();
        assertState(id, state, 1);
        assertThat(header(id)).isEqualTo(before);
    }

    @Test
    void nullDeadlineAndMissingBookingAreSafeNoOps() {
        // PENDING+NULL cannot be inserted under chk_pending_expires; keep the original constraint intact.
        UUID id = seed(BookingStatus.PAID, null, 1);
        assertThat(expiration.expireBooking(id)).isFalse();
        assertThat(expiration.expireBooking(UUID.randomUUID())).isFalse();
        assertThat(expiration.findCandidates()).doesNotContain(id);
        assertState(id, BookingStatus.PAID, 1);
    }

    @Test
    void deletesEveryDetailButPreservesHeaderAmountsAuditAndPaymentHistoryAndRerunIsSafe() {
        UUID id = seed(BookingStatus.PENDING, NOW.minusSeconds(1), 3);
        UUID paymentId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO payment_transactions(id,booking_id,payment_method,status,amount,note)
                VALUES (?, ?, 'BANK_TRANSFER', 'FAILED', 30.00, 'Existing history fixture')
                """, paymentId, id);
        Map<String, Object> before = header(id);
        var paymentBefore = jdbc.queryForMap("SELECT * FROM payment_transactions WHERE id = ?", paymentId);
        assertThat(expiration.expireBooking(id)).isTrue();
        assertState(id, BookingStatus.EXPIRED, 0);
        var expected = new java.util.HashMap<>(before);
        expected.put("status", "EXPIRED");
        assertThat(header(id)).isEqualTo(expected);
        assertThat(jdbc.queryForMap("SELECT * FROM payment_transactions WHERE id = ?", paymentId)).isEqualTo(paymentBefore);
        assertThat(expiration.expireBooking(id)).isFalse();
        job.expireDueBookings();
        assertThat(header(id)).isEqualTo(expected);
        assertState(id, BookingStatus.EXPIRED, 0);
    }

    @Test
    void releaseChangesAvailabilityAndAllowsPostBookingOnSameTuple() throws Exception {
        UUID oldId = seed(BookingStatus.PENDING, NOW, 1);
        assertThat(available()).isFalse();
        assertThat(expiration.expireBooking(oldId)).isTrue();
        assertThat(available()).isTrue();
        var request = new CreateBookingRequest(customerId,
                List.of(new CreateBookingRequest.Detail(DATE, courtId, slotId)));
        var result = mvc.perform(post("/api/bookings").contextPath("/api").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isCreated()).andReturn();
        UUID newId = UUID.fromString(mapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("bookingId").asText());
        assertThat(newId).isNotEqualTo(oldId);
        assertState(oldId, BookingStatus.EXPIRED, 0);
        assertState(newId, BookingStatus.PENDING, 1);
        assertThat(available()).isFalse();
    }

    @Test
    void candidatesAreFilteredAndOrderedByDeadlineThenId() {
        UUID sameA = seed(BookingStatus.PENDING, NOW, 1);
        UUID sameB = seed(BookingStatus.PENDING, NOW, 1);
        UUID earlier = seed(BookingStatus.PENDING, NOW.minusDays(1), 1);
        seed(BookingStatus.PENDING, NOW.plusSeconds(1), 1);
        seed(BookingStatus.PAID, NOW.minusDays(2), 1);
        var ties = List.of(sameA, sameB).stream().sorted(Comparator.comparing(UUID::toString)).toList();
        assertThat(expiration.findCandidates()).containsExactly(earlier, ties.get(0), ties.get(1));
    }

    @ParameterizedTest
    @ValueSource(strings = {"PAID", "EXTENDED"})
    void rechecksStatusAndDeadlineAfterWaitingOnBookingLock(String change) throws Exception {
        UUID id = seed(BookingStatus.PENDING, NOW, 1);
        assertThat(expiration.findCandidates()).contains(id);
        var worker = Executors.newSingleThreadExecutor();
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try {
                String sql = change.equals("PAID")
                        ? "UPDATE bookings SET status = 'PAID' WHERE id = ?"
                        : "UPDATE bookings SET expires_at = TIMESTAMP '2099-01-01' WHERE id = ?";
                try (var statement = holder.prepareStatement(sql)) {
                    statement.setObject(1, id);
                    statement.executeUpdate();
                }
                var result = worker.submit(() -> expiration.expireBooking(id));
                awaitBookingLockWaiter();
                holder.commit();
                assertThat(result.get(20, TimeUnit.SECONDS)).isFalse();
                assertState(id, change.equals("PAID") ? BookingStatus.PAID : BookingStatus.PENDING, 1);
            } finally {
                holder.rollback();
                worker.shutdownNow();
                assertThat(worker.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
            }
        }
    }

    @Test
    void deleteFailureRollsBackAlreadyExecutedStatusUpdate() throws Exception {
        UUID id = seed(BookingStatus.PENDING, NOW, 2);
        var before = header(id);
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try {
                try (var statement = holder.prepareStatement("SELECT id FROM booking_details WHERE booking_id = ? FOR UPDATE")) {
                    statement.setObject(1, id);
                    statement.executeQuery().close();
                }
                // Real database lock timeout at DELETE, after UPDATE has executed. No schema/test hooks in production.
                assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
                    jdbc.execute("SET LOCAL lock_timeout = '300ms'");
                    expiration.expireBooking(id);
                })).isInstanceOf(PessimisticLockingFailureException.class)
                        .hasMessageContaining("booking_details");
                assertThat(header(id)).isEqualTo(before);
                assertState(id, BookingStatus.PENDING, 2);
            } finally {
                holder.rollback();
            }
        }
        assertThat(expiration.expireBooking(id)).isTrue();
        assertState(id, BookingStatus.EXPIRED, 0);
    }

    @Test
    void scheduledJobIsRegisteredAndCallsTransactionalServiceProxy() {
        UUID first = seed(BookingStatus.PENDING, NOW.minusSeconds(1), 1);
        UUID second = seed(BookingStatus.PENDING, NOW, 2);
        assertThat(AopUtils.isAopProxy(expiration)).isTrue();
        assertThat(scheduling.getScheduledTasks()).anySatisfy(task -> {
            assertThat(task.getTask().getRunnable()).isInstanceOf(ScheduledMethodRunnable.class);
            var runnable = (ScheduledMethodRunnable) task.getTask().getRunnable();
            assertThat(runnable.getTarget()).isSameAs(job);
            assertThat(runnable.getMethod().getName()).isEqualTo("expireDueBookings");
        });
        // Invoke the registered job directly; PT24H initial delay avoids timing-dependent assertions.
        job.expireDueBookings();
        assertState(first, BookingStatus.EXPIRED, 0);
        assertState(second, BookingStatus.EXPIRED, 0);
        job.expireDueBookings();
        assertThat(expiration.findCandidates()).isEmpty();
    }

    private UUID seed(BookingStatus state, LocalDateTime deadline, int count) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO bookings(id,customer_id,status,court_fee,expires_at,created_at,updated_at)
                VALUES (?,?,?,?,?,TIMESTAMP '2019-01-01',TIMESTAMP '2019-01-02')
                """, id, customerId, state.name(), new BigDecimal("10.00").multiply(BigDecimal.valueOf(count)), deadline);
        for (int i = 0; i < count; i++) {
            jdbc.update("""
                    INSERT INTO booking_details(booking_id,court_id,time_slot_id,booking_date,price)
                    VALUES (?,?,?,?,10.00)
                    """, id, courtId, slotId, DATE.plusDays(dateOffset++));
        }
        return id;
    }

    private Map<String, Object> header(UUID id) {
        return jdbc.queryForMap("SELECT * FROM bookings WHERE id = ?", id);
    }

    private void assertState(UUID id, BookingStatus state, long details) {
        assertThat(header(id).get("status")).isEqualTo(state.name());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM booking_details WHERE booking_id = ?", Long.class, id))
                .isEqualTo(details);
    }

    private boolean available() {
        return availability.getAvailability(DATE, courtId).getCourts().get(0).getSlots().stream()
                .filter(s -> s.getTimeSlotId().equals(slotId)).findFirst().orElseThrow().isAvailable();
    }

    private void awaitBookingLockWaiter() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            Long count = jdbc.queryForObject("""
                    SELECT count(*) FROM pg_stat_activity WHERE datname = current_database()
                    AND pid <> pg_backend_pid() AND wait_event_type = 'Lock' AND state = 'active'
                    AND lower(query) LIKE '%from%bookings%' AND lower(query) LIKE '%for%update%'
                    """, Long.class);
            if (count != null && count == 1) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("Expiration did not wait for the Booking row lock");
    }
}
