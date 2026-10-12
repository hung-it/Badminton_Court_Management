package com.bcm.service;

import com.bcm.dto.request.CreateBookingRequest;
import com.bcm.dto.request.CreateBookingRequest.Detail;
import com.bcm.entity.BookingStatus;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Real PostgreSQL, original migration, committed fixtures; no outer test transaction or repository mocks.
 * Two MockMvc worker threads enter separate service transactions. A JDBC holder keeps both waiting on
 * Court locks until pg_stat_activity proves two independent database sessions reached the lock query.
 */
@SpringBootTest(properties = {
        "spring.datasource.hikari.connection-init-sql=SET statement_timeout = '15s'"
})
@AutoConfigureMockMvc
@org.springframework.context.annotation.Import(com.bcm.service.BookingWebTestConfiguration.class)
@EnabledIfEnvironmentVariable(named = "BCM_TEST_DB_URL", matches = "jdbc:postgresql:.*")
class BookingConcurrencyPostgresTest {
    private static final LocalDate DATE = LocalDate.of(2020, 2, 29);

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("BCM_TEST_DB_URL"));
        registry.add("spring.datasource.username", () -> Objects.requireNonNull(
                System.getenv("BCM_TEST_DB_USERNAME"), "BCM_TEST_DB_USERNAME is required"));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("BCM_TEST_DB_PASSWORD", ""));
    }

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;
    @Autowired private BookingService service;

    private UUID userId;
    private UUID customerId;
    // Lifecycle/concurrency regression fixture; real JWT ownership is covered separately.
    @org.springframework.boot.test.mock.mockito.MockBean
    private com.bcm.security.CurrentCustomerService currentCustomer;
    private UUID courtA;
    private UUID courtB;
    private UUID slotId;

    @BeforeEach
    void fixtures() {
        userId = UUID.randomUUID();
        customerId = UUID.randomUUID();
        org.mockito.Mockito.when(currentCustomer.requireCustomerId()).thenReturn(customerId);
        var ids = List.of(UUID.randomUUID(), UUID.randomUUID()).stream()
                .sorted(Comparator.comparing(UUID::toString)).toList();
        courtA = ids.get(0);
        courtB = ids.get(1);
        slotId = UUID.randomUUID();
        jdbc.update("INSERT INTO users(id,email,password_hash,full_name,phone) VALUES (?,?,?,'Booking fixture','0900000000')", userId, userId + "@concurrency.test", "test-only");
        jdbc.update("INSERT INTO customers(id,user_id,full_name,phone) VALUES (?,?,?,?)",
                customerId, userId, "Concurrency test", "0900000000");
        jdbc.update("INSERT INTO courts(id,court_number,name,base_price) VALUES (?, -401, 'Concurrency A', 10.05)", courtA);
        jdbc.update("INSERT INTO courts(id,court_number,name,base_price) VALUES (?, -402, 'Concurrency B', 10.05)", courtB);
        jdbc.update("INSERT INTO time_slots(id,start_time,end_time,price_multiplier) VALUES (?, '00:00', '01:00', 1.10)", slotId);
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM bookings WHERE customer_id = ?", customerId);
        jdbc.update("DELETE FROM courts WHERE id IN (?, ?)", courtA, courtB);
        jdbc.update("DELETE FROM time_slots WHERE id = ?", slotId);
        jdbc.update("DELETE FROM customers WHERE id = ?", customerId);
        jdbc.update("DELETE FROM users WHERE id = ?", userId);
    }

    @Test
    void sameSlotHasExactlyOneCreatedOneConflictAndNoOrphan() throws Exception {
        var input = request(detail(courtA, DATE));
        assertThat(race(input, input)).containsExactlyInAnyOrder(201, 409);
        assertCounts(1, 1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM booking_details
                WHERE booking_date = ? AND court_id = ? AND time_slot_id = ?
                """, Long.class, DATE, courtA, slotId)).isEqualTo(1L);
    }

    @Test
    void reverseMultiCourtInputUsesSameLockOrderAndRollsBackLoser() throws Exception {
        var first = request(detail(courtA, DATE), detail(courtB, DATE));
        var reversed = request(detail(courtB, DATE), detail(courtA, DATE));
        assertThat(race(first, reversed)).containsExactlyInAnyOrder(201, 409);
        assertCounts(1, 2);
        assertThat(jdbc.queryForObject("SELECT count(DISTINCT booking_id) FROM booking_details WHERE court_id IN (?, ?)",
                Long.class, courtA, courtB)).isEqualTo(1L);
    }

    @Test
    void nonOverlappingTuplesOnSameCourtsBothSucceed() throws Exception {
        // Both queries have the same date/court sets; cross-product candidates must match exact tuples.
        var first = request(detail(courtA, DATE), detail(courtB, DATE.plusDays(1)));
        var second = request(detail(courtB, DATE), detail(courtA, DATE.plusDays(1)));
        assertThat(race(first, second)).containsExactly(201, 201);
        assertCounts(2, 4);
    }

    @ParameterizedTest
    @EnumSource(BookingStatus.class)
    void existingDetailBlocksEveryHeaderStatusAndRollsBackWholeRequest(BookingStatus state) throws Exception {
        var existing = service.createBooking(request(detail(courtA, DATE)));
        jdbc.update("UPDATE bookings SET status = ?, expires_at = TIMESTAMP '2000-01-01' WHERE id = ?",
                state.name(), existing.getBookingId());
        mvc.perform(post("/api/bookings").contextPath("/api").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request(detail(courtA, DATE.plusDays(1)),
                                detail(courtA, DATE), detail(courtB, DATE)))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("This time slot is already booked"));
        assertCounts(1, 1);
        assertThat(jdbc.queryForObject("SELECT status FROM bookings WHERE id = ?", String.class, existing.getBookingId()))
                .isEqualTo(state.name());
    }

    @ParameterizedTest
    @ValueSource(strings = {"CLOSED", "DELETED"})
    void validatesCourtStateCommittedWhileRequestWaitedForLock(String state) throws Exception {
        var worker = Executors.newSingleThreadExecutor();
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try {
                String sql = state.equals("CLOSED")
                        ? "UPDATE courts SET status = 'CLOSED' WHERE id = ?"
                        : "UPDATE courts SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?";
                try (var statement = holder.prepareStatement(sql)) {
                    statement.setObject(1, courtA);
                    statement.executeUpdate();
                }
                var result = worker.submit(() -> perform(request(detail(courtA, DATE))));
                awaitCourtWaiters(1);
                holder.commit();
                assertThat(result.get(20, TimeUnit.SECONDS)).isEqualTo(400);
                assertCounts(0, 0);
            } finally {
                holder.rollback();
                worker.shutdownNow();
                assertThat(worker.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
            }
        }
    }

    private List<Integer> race(CreateBookingRequest first, CreateBookingRequest second) throws Exception {
        var workers = Executors.newFixedThreadPool(2);
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try {
                try (var statement = holder.prepareStatement("SELECT id FROM courts WHERE id = ? FOR UPDATE")) {
                    statement.setObject(1, courtA);
                    statement.executeQuery().close();
                }
                var results = List.of(first, second).stream().map(input -> workers.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new AssertionError("Start latch timed out");
                    }
                    return perform(input);
                })).toList();
                assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
                start.countDown();
                awaitCourtWaiters(2);
                holder.commit();
                return List.of(results.get(0).get(20, TimeUnit.SECONDS), results.get(1).get(20, TimeUnit.SECONDS));
            } finally {
                start.countDown();
                holder.rollback();
                workers.shutdownNow();
                assertThat(workers.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
            }
        }
    }

    private void awaitCourtWaiters(int count) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            var queries = jdbc.queryForList("""
                    SELECT query FROM pg_stat_activity
                    WHERE datname = current_database() AND pid <> pg_backend_pid()
                      AND wait_event_type = 'Lock' AND state = 'active'
                      AND lower(query) LIKE '%from%courts%'
                      AND lower(query) LIKE '%for%update%'
                    """, String.class);
            if (queries.size() == count) {
                // Actual blocked SQL proves DB locking (a unique-index-only implementation fails this assertion).
                assertThat(queries).allSatisfy(sql -> assertThat(sql.toLowerCase()).contains("order by"));
                return;
            }
            Thread.sleep(20); // Test-only bounded observation; no sleeps or JVM locks in application code.
        }
        throw new AssertionError("Expected " + count + " independent PostgreSQL Court lock waiters");
    }

    private int perform(CreateBookingRequest input) throws Exception {
        var result = mvc.perform(post("/api/bookings").contextPath("/api")
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(input))).andReturn();
        int code = result.getResponse().getStatus();
        var body = mapper.readTree(result.getResponse().getContentAsString());
        if (code == 409) {
            assertThat(body.path("success").asBoolean()).isFalse();
            assertThat(body.path("message").asText()).isEqualTo("This time slot is already booked");
        } else if (code == 201) {
            assertThat(body.path("success").asBoolean()).isTrue();
        }
        return code;
    }

    private Detail detail(UUID court, LocalDate date) {
        return new Detail(date, court, slotId);
    }

    private CreateBookingRequest request(Detail... details) {
        return new CreateBookingRequest(customerId, List.of(details));
    }

    private void assertCounts(long headers, long details) {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bookings WHERE customer_id = ?", Long.class, customerId))
                .isEqualTo(headers);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM booking_details WHERE court_id IN (?, ?)",
                Long.class, courtA, courtB)).isEqualTo(details);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM bookings b WHERE b.customer_id = ?
                AND NOT EXISTS (SELECT 1 FROM booking_details d WHERE d.booking_id = b.id)
                """, Long.class, customerId)).isZero();
    }
}
