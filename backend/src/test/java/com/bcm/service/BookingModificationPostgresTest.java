package com.bcm.service;

import com.bcm.security.CustomUserDetailsService;
import com.bcm.util.JwtUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import javax.sql.DataSource;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Committed fixtures, real JWT and PostgreSQL locks/unique index; only time is controlled. */
@SpringBootTest(properties = {"booking.expiration-enabled=false", "booking.payment.enabled-methods=VNPAY,BANK_TRANSFER",
        "booking.payment.vnpay.tmn-code=TEST1234", "booking.payment.vnpay.hash-secret=edit-tests-only",
        "booking.payment.vnpay.return-url=http://localhost/api/payments/vnpay/return"})
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "BCM_TEST_DB_URL", matches = "jdbc:postgresql:.*")
class BookingModificationPostgresTest {
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> System.getenv("BCM_TEST_DB_URL"));
        r.add("spring.datasource.username", () -> System.getenv("BCM_TEST_DB_USERNAME"));
        r.add("spring.datasource.password", () -> System.getenv("BCM_TEST_DB_PASSWORD"));
    }
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired ObjectMapper mapper;
    @Autowired JwtUtil jwt;
    @Autowired CustomUserDetailsService users;
    @Autowired BookingExpirationService expiration;
    @MockBean(name = "bookingExpirationClock") Clock clock;
    static final Instant NOW = Instant.parse("2026-10-23T05:00:00Z");
    static final LocalDate DATE = LocalDate.of(2031, 2, 20);
    UUID user, customer, court, booking, foreignUser, foreignCustomer;
    List<UUID> slots;
    String token, foreignToken;

    @BeforeEach void fixtures() {
        when(clock.getZone()).thenReturn(ZoneOffset.UTC); when(clock.instant()).thenReturn(NOW);
        user = UUID.randomUUID(); customer = UUID.randomUUID(); court = UUID.randomUUID();
        booking = UUID.randomUUID(); foreignUser = UUID.randomUUID(); foreignCustomer = UUID.randomUUID();
        token = identity(user, customer); foreignToken = identity(foreignUser, foreignCustomer);
        jdbc.update("INSERT INTO courts(id,court_code,name,type,status,base_price) VALUES (?,999821,'Edit fixture','WOODEN_FLOOR','AVAILABLE',100000)", court);
        slots = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        for (int i = 0; i < 3; i++) jdbc.update("INSERT INTO time_slots(id,start_time,end_time,price_multiplier) VALUES (?,?,?,?)",
                slots.get(i), java.sql.Time.valueOf(LocalTime.of(8 + i, 0)), java.sql.Time.valueOf(LocalTime.of(9 + i, 0)), i == 1 ? 1.25 : 1);
        hold(booking, customer, DATE, slots.get(0));
    }
    String identity(UUID userId, UUID customerId) {
        jdbc.update("INSERT INTO users(id,email,password_hash,full_name,phone) VALUES (?,?,'test','Edit owner','0900000000')", userId, userId + "@edit.test");
        jdbc.update("INSERT INTO customers(id,user_id,full_name,phone) VALUES (?,?,'Edit owner','0900000000')", customerId, userId);
        jdbc.update("INSERT INTO user_roles(user_id,role_id) SELECT ?,id FROM roles WHERE role_name='CUSTOMER'", userId);
        return jwt.generateAccessToken(users.loadUserByUsername(userId + "@edit.test"));
    }
    void hold(UUID id, UUID owner, LocalDate date, UUID slot) {
        jdbc.update("INSERT INTO bookings(id,customer_id,status,court_fee,expires_at) VALUES (?,?,'PENDING',100000,?)",
                id, owner, LocalDateTime.ofInstant(NOW.plusSeconds(600), ZoneOffset.UTC));
        jdbc.update("INSERT INTO booking_details(booking_id,court_id,time_slot_id,booking_date,price) VALUES (?,?,?,?,100000)", id, court, slot, date);
    }
    @AfterEach void cleanup() {
        for (UUID owner : List.of(customer, foreignCustomer)) {
            jdbc.update("DELETE FROM payment_transactions WHERE booking_id IN (SELECT id FROM bookings WHERE customer_id=?)", owner);
            jdbc.update("DELETE FROM booking_details WHERE booking_id IN (SELECT id FROM bookings WHERE customer_id=?)", owner);
            jdbc.update("DELETE FROM bookings WHERE customer_id=?", owner);
            jdbc.update("DELETE FROM customers WHERE id=?", owner);
        }
        jdbc.update("DELETE FROM courts WHERE id=?", court);
        slots.forEach(id -> jdbc.update("DELETE FROM time_slots WHERE id=?", id));
        for (UUID id : List.of(user, foreignUser)) {
            jdbc.update("DELETE FROM user_roles WHERE user_id=?", id); jdbc.update("DELETE FROM users WHERE id=?", id);
        }
    }
    String body(LocalDate date, UUID... selected) throws Exception {
        var node = mapper.createObjectNode().put("bookingDate", date.toString()).put("courtFee", 1).put("customerId", foreignCustomer.toString());
        var list = node.putArray("timeSlotIds"); for (UUID id : selected) list.add(id.toString());
        return node.toString();
    }
    ResultActions edit(UUID id, String auth, String body) throws Exception {
        return mvc.perform(patch("/api/bookings/" + id + "/details").contextPath("/api").header("Authorization", "Bearer " + auth)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }
    JsonNode detail(UUID id, String auth) throws Exception {
        var result = mvc.perform(get("/api/bookings/" + id).contextPath("/api").header("Authorization", "Bearer " + auth)).andExpect(status().isOk()).andReturn();
        return mapper.readTree(result.getResponse().getContentAsString()).path("data");
    }
    @Test void detailsRemainOwnedAndResponseHasNoCheckoutNote() throws Exception {
        edit(booking, foreignToken, body(DATE, slots.get(1))).andExpect(status().isForbidden());
        assertThat(detail(booking, token).has("customerNote")).isFalse();
    }

    ResultActions contact(UUID id, String auth, String json) throws Exception {
        return mvc.perform(patch("/api/bookings/" + id + "/contact").contextPath("/api")
                .header("Authorization", "Bearer " + auth).contentType(MediaType.APPLICATION_JSON).content(json));
    }
    @Test void bookingContactPersistsOnlyNameAndPhoneWithoutChangingAccountOrHold() throws Exception {
        var header = jdbc.queryForMap("SELECT * FROM bookings WHERE id=?", booking);
        var originalDetails = jdbc.queryForList("SELECT * FROM booking_details WHERE booking_id=?", booking);
        var profile = jdbc.queryForMap("SELECT * FROM customers WHERE id=?", customer);
        var account = jdbc.queryForMap("SELECT * FROM users WHERE id=?", user);
        mvc.perform(get("/api/bookings/" + booking + "/contact").contextPath("/api").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.fullName").value("Edit owner"));
        contact(booking, token, "{\"fullName\":\"  Nguyễn An  \",\"phone\":\" 0912345678 \"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.fullName").value("Nguyễn An"))
                .andExpect(jsonPath("$.data.phone").value("0912345678"))
                .andExpect(jsonPath("$.data.email").value(user + "@edit.test"));
        var changed = jdbc.queryForMap("SELECT * FROM bookings WHERE id=?", booking);
        for (String key : List.of("id", "customer_id", "status", "court_fee", "expires_at", "created_at"))
            assertThat(changed.get(key)).isEqualTo(header.get(key));
        assertThat(changed.get("contact_full_name")).isEqualTo("Nguyễn An");
        assertThat(changed.get("contact_phone")).isEqualTo("0912345678");
        assertThat(jdbc.queryForMap("SELECT * FROM customers WHERE id=?", customer)).isEqualTo(profile);
        assertThat(jdbc.queryForMap("SELECT * FROM users WHERE id=?", user)).isEqualTo(account);
        assertThat(jdbc.queryForList("SELECT * FROM booking_details WHERE booking_id=?", booking)).isEqualTo(originalDetails);
        mvc.perform(get("/api/bookings/" + booking + "/contact").contextPath("/api").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.phone").value("0912345678"));
    }

    @ParameterizedTest @ValueSource(strings = {"email", "customerId", "address", "courtFee"})
    void contactRejectsEmailIdentityAndOtherExtraFields(String key) throws Exception {
        var before = jdbc.queryForMap("SELECT * FROM bookings WHERE id=?", booking);
        var payload = mapper.createObjectNode().put("fullName", "New Name").put("phone", "0912345678").put(key, "forged");
        contact(booking, token, payload.toString()).andExpect(status().isBadRequest());
        assertThat(jdbc.queryForMap("SELECT * FROM bookings WHERE id=?", booking)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT email FROM users WHERE id=?", String.class, user)).isEqualTo(user + "@edit.test");
    }

    @Test void contactIsOwnedAndNeverExposedOrEditedAnonymously() throws Exception {
        String body = "{\"fullName\":\"New Name\",\"phone\":\"0912345678\"}";
        contact(booking, foreignToken, body).andExpect(status().isForbidden());
        contact(UUID.randomUUID(), token, body).andExpect(status().isNotFound());
        mvc.perform(get("/api/bookings/" + booking + "/contact").contextPath("/api").header("Authorization", "Bearer " + foreignToken))
                .andExpect(status().isForbidden());
        mvc.perform(patch("/api/bookings/" + booking + "/contact").contextPath("/api").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
    }

    @ParameterizedTest @ValueSource(strings = {"", "123", "012345678a", "123456789012345678901"})
    void contactValidatesPhoneAndRollsBack(String phone) throws Exception {
        var before = jdbc.queryForMap("SELECT * FROM bookings WHERE id=?", booking);
        contact(booking, token, mapper.createObjectNode().put("fullName", "Valid Name").put("phone", phone).toString())
                .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForMap("SELECT * FROM bookings WHERE id=?", booking)).isEqualTo(before);
    }

    @ParameterizedTest @ValueSource(strings = {"", " ", "A"})
    void contactRejectsInvalidName(String name) throws Exception {
        contact(booking, token, mapper.createObjectNode().put("fullName", name).put("phone", "0912345678").toString())
                .andExpect(status().isBadRequest());
    }
    @Test void replacementRepricesOnServerPreservesDeadlineAndRetainedSnapshots() throws Exception {
        jdbc.update("UPDATE courts SET base_price=200000 WHERE id=?", court);
        var before = detail(booking, token);
        edit(booking, token, body(DATE, slots.get(0), slots.get(1))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.courtFee").value(350000)) // retained 100000 + new 200000*1.25
                .andExpect(jsonPath("$.data.discountAmount").value(30000))
                .andExpect(jsonPath("$.data.finalAmount").value(320000));
        var after = detail(booking, token);
        assertThat(after.path("expiresAt")).isEqualTo(before.path("expiresAt"));
        assertThat(after.path("customerId")).isEqualTo(before.path("customerId"));
        assertThat(after.path("details").size()).isEqualTo(2);
        edit(booking, token, body(DATE.plusDays(1), slots.get(1))).andExpect(status().isOk()).andExpect(jsonPath("$.data.courtFee").value(250000))
                .andExpect(jsonPath("$.data.discountAmount").value(0)).andExpect(jsonPath("$.data.finalAmount").value(250000));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM booking_details WHERE booking_id=? AND booking_date=?", Integer.class, booking, DATE)).isZero();
    }
    @Test void conflictRetainsEveryOriginalDetailAndTotal() throws Exception {
        UUID other = UUID.randomUUID(); hold(other, foreignCustomer, DATE.plusDays(1), slots.get(1));
        var before = detail(booking, token);
        edit(booking, token, body(DATE.plusDays(1), slots.get(1))).andExpect(status().isConflict());
        assertThat(detail(booking, token)).isEqualTo(before);
    }
    @ParameterizedTest @ValueSource(strings = {"PENDING", "FAILED", "SUCCESS", "REFUNDED"})
    void anyPaymentAttemptLocksSlotEditing(String status) throws Exception {
        jdbc.update("INSERT INTO payment_transactions(booking_id,payment_method,status,amount) VALUES (?,'VNPAY',?,100000)", booking, status);
        edit(booking, token, body(DATE, slots.get(1))).andExpect(status().isConflict());
        contact(booking, token, "{\"fullName\":\"New Name\",\"phone\":\"0912345678\"}").andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT court_fee FROM bookings WHERE id=?", java.math.BigDecimal.class, booking)).isEqualByComparingTo("100000");
    }
    @ParameterizedTest @ValueSource(strings = {"PAID", "EXPIRED", "CHECKED_IN", "COMPLETED", "NO_SHOW"})
    void nonPendingBookingsCannotBeEdited(String state) throws Exception {
        jdbc.update("UPDATE bookings SET status=? WHERE id=?", state, booking);
        edit(booking, token, body(DATE, slots.get(1))).andExpect(status().isConflict());
        contact(booking, token, "{\"fullName\":\"New Name\",\"phone\":\"0912345678\"}").andExpect(status().isConflict());
    }
    @Test void deadlineExactlyTenMinutesRejectsSlotsWithoutRenewal() throws Exception {
        when(clock.instant()).thenReturn(NOW.plusSeconds(600));
        contact(booking, token, "{\"fullName\":\"New Name\",\"phone\":\"0912345678\"}").andExpect(status().isConflict());
        edit(booking, token, body(DATE, slots.get(1))).andExpect(status().isConflict());
        assertThat(expiration.expireBooking(booking)).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM booking_details WHERE booking_id=?", Integer.class, booking)).isZero();
    }
    @Test void rejectsDuplicateMissingDeletedPastSlotsAndUnavailableCourt() throws Exception {
        edit(booking, token, body(DATE, slots.get(1), slots.get(1))).andExpect(status().isBadRequest());
        edit(booking, token, body(DATE)).andExpect(status().isBadRequest());
        edit(booking, token, body(DATE, UUID.randomUUID())).andExpect(status().isBadRequest());
        edit(booking, token, body(LocalDate.of(2020, 1, 1), slots.get(1))).andExpect(status().isBadRequest());
        jdbc.update("UPDATE time_slots SET deleted_at=CURRENT_TIMESTAMP WHERE id=?", slots.get(1));
        edit(booking, token, body(DATE, slots.get(1))).andExpect(status().isBadRequest());
        jdbc.update("UPDATE courts SET status='CLOSED' WHERE id=?", court);
        edit(booking, token, body(DATE, slots.get(0))).andExpect(status().isBadRequest());
    }
    @Test void twoOwnersEditingIntoOneSlotHaveOneWinnerAndNoPartialReplacement() throws Exception {
        UUID other = UUID.randomUUID(); hold(other, foreignCustomer, DATE.plusDays(1), slots.get(0));
        var workers = Executors.newFixedThreadPool(2); var ready = new CountDownLatch(2); var start = new CountDownLatch(1);
        String target = body(DATE.plusDays(2), slots.get(1));
        try {
            var a = workers.submit(() -> { ready.countDown(); start.await(); return edit(booking, token, target).andReturn().getResponse().getStatus(); });
            var b = workers.submit(() -> { ready.countDown(); start.await(); return edit(other, foreignToken, target).andReturn().getResponse().getStatus(); });
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue(); start.countDown();
            int statusA = a.get(20, TimeUnit.SECONDS), statusB = b.get(20, TimeUnit.SECONDS);
            assertThat(List.of(statusA, statusB)).containsExactlyInAnyOrder(200, 409);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM booking_details WHERE court_id=?", Integer.class, court)).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM booking_details WHERE court_id=? AND booking_date=? AND time_slot_id=?", Integer.class, court, DATE.plusDays(2), slots.get(1))).isEqualTo(1);
            UUID loser = statusA == 409 ? booking : other;
            assertThat(jdbc.queryForObject("SELECT time_slot_id FROM booking_details WHERE booking_id=?", UUID.class, loser)).isEqualTo(slots.get(0));
        } finally { start.countDown(); workers.shutdownNow(); }
    }
    @Test void expiryIsRecheckedAfterWaitingForCourtLock() throws Exception {
        var worker = Executors.newSingleThreadExecutor();
        try (var blocker = dataSource.getConnection()) {
            blocker.setAutoCommit(false);
            try (var statement = blocker.prepareStatement("SELECT id FROM courts WHERE id=? FOR UPDATE")) { statement.setObject(1, court); statement.executeQuery(); }
            var request = worker.submit(() -> edit(booking, token, body(DATE, slots.get(1))).andReturn().getResponse().getStatus());
            // Observe the actual PostgreSQL lock wait before advancing the shared service clock.
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            boolean waiting = false;
            while (System.nanoTime() < until) {
                if (jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query LIKE '%courts%'", Integer.class) > 0) { waiting = true; break; }
                Thread.sleep(20);
            }
            assertThat(waiting).isTrue();
            when(clock.instant()).thenReturn(NOW.plusSeconds(600)); blocker.commit();
            assertThat(request.get(20, TimeUnit.SECONDS)).isEqualTo(409);
            assertThat(jdbc.queryForObject("SELECT time_slot_id FROM booking_details WHERE booking_id=?", UUID.class, booking)).isEqualTo(slots.get(0));
        } finally { worker.shutdownNow(); }
    }
    @Test void bankPaymentAfterEditUsesNewAmountAndPreservesPaymentEvidence() throws Exception {
        edit(booking, token, body(DATE, slots.get(1))).andExpect(status().isOk());
        mvc.perform(post("/api/bookings/" + booking + "/payments").contextPath("/api").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"paymentMethod\":\"BANK_TRANSFER\",\"bankTransfer\":{\"bankName\":\"Test\",\"accountHolder\":\"Test\",\"accountNumber\":\"123\",\"transferContent\":\"Test\"}}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.amount").value(125000)).andExpect(jsonPath("$.data.bookingStatus").value("PAID"));
        assertThat(jdbc.queryForObject("SELECT note FROM payment_transactions WHERE booking_id=?", String.class, booking)).startsWith("CUSTOMER_DECLARED;");
    }
    @Test void courtStateIsReadFreshAfterWaitingForItsLock() throws Exception {
        var worker = Executors.newSingleThreadExecutor();
        try (var blocker = dataSource.getConnection()) {
            blocker.setAutoCommit(false);
            try (var statement = blocker.prepareStatement("UPDATE courts SET status='CLOSED' WHERE id=?")) {
                statement.setObject(1, court); statement.executeUpdate();
            }
            var request = worker.submit(() -> edit(booking, token, body(DATE, slots.get(1))).andReturn().getResponse().getStatus());
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            boolean waiting = false;
            while (System.nanoTime() < until) {
                if (jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query LIKE '%courts%'", Integer.class) > 0) { waiting = true; break; }
                Thread.sleep(20);
            }
            assertThat(waiting).isTrue(); blocker.commit();
            assertThat(request.get(20, TimeUnit.SECONDS)).isEqualTo(400);
            assertThat(jdbc.queryForObject("SELECT time_slot_id FROM booking_details WHERE booking_id=?", UUID.class, booking)).isEqualTo(slots.get(0));
        } finally { worker.shutdownNow(); }
    }
    @Test void vnpayAfterEditSignsTheNewAmountAndLocksFurtherEdits() throws Exception {
        edit(booking, token, body(DATE, slots.get(1))).andExpect(status().isOk());
        var result = mvc.perform(post("/api/bookings/" + booking + "/payments").contextPath("/api").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"paymentMethod\":\"VNPAY\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.amount").value(125000)).andReturn();
        assertThat(mapper.readTree(result.getResponse().getContentAsString()).path("data").path("gatewayPreparation").path("checkoutUrl").asText()).contains("vnp_Amount=12500000");
        edit(booking, token, body(DATE, slots.get(2))).andExpect(status().isConflict());
    }
    @Test void paymentRacingEditAlwaysUsesTheCommittedBookingAmount() throws Exception {
        var workers = Executors.newFixedThreadPool(2); var ready = new CountDownLatch(2); var start = new CountDownLatch(1);
        try {
            var edit = workers.submit(() -> { ready.countDown(); start.await(); return edit(booking, token, body(DATE, slots.get(1))).andReturn().getResponse().getStatus(); });
            var pay = workers.submit(() -> {
                ready.countDown(); start.await();
                return mvc.perform(post("/api/bookings/" + booking + "/payments").contextPath("/api").header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON).content("{\"paymentMethod\":\"VNPAY\"}")).andReturn().getResponse().getStatus();
            });
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue(); start.countDown();
            assertThat(edit.get(20, TimeUnit.SECONDS)).isIn(200, 409);
            assertThat(pay.get(20, TimeUnit.SECONDS)).isEqualTo(201);
            assertThat(jdbc.queryForObject("SELECT p.amount=b.court_fee FROM payment_transactions p JOIN bookings b ON b.id=p.booking_id WHERE b.id=?", Boolean.class, booking)).isTrue();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_transactions WHERE booking_id=?", Integer.class, booking)).isEqualTo(1);
        } finally { start.countDown(); workers.shutdownNow(); }
    }

    @ParameterizedTest
    @CsvSource({"00:00,00:00", "00:00,07:00", "06:59,07:00", "07:00,07:00", "11:59,12:00",
            "12:00,12:00", "12:00,13:00", "12:30,12:00", "17:59,18:00", "18:00,18:00", "23:59,12:00"})
    void vietnamClockControlsAvailabilityCreationAndEditing(String localTime, String slotStart) throws Exception {
        var vietnamNow = DATE.atTime(LocalTime.parse(localTime));
        var instant = vietnamNow.atZone(ZoneId.of("Asia/Ho_Chi_Minh")).toInstant();
        when(clock.instant()).thenReturn(instant);
        jdbc.update("UPDATE bookings SET expires_at=? WHERE id=?", LocalDateTime.now(clock).plusMinutes(10), booking);
        // Distinct template IDs isolate create and edit assertions. No changes to application master data.
        UUID createSlot = UUID.randomUUID(), editSlot = UUID.randomUUID();
        slots = new ArrayList<>(slots); slots.add(createSlot); slots.add(editSlot);
        for (var id : List.of(createSlot, editSlot)) jdbc.update("INSERT INTO time_slots(id,start_time,end_time,price_multiplier) VALUES (?,?,?,1)",
                id, java.sql.Time.valueOf(LocalTime.parse(slotStart)), java.sql.Time.valueOf(LocalTime.parse(slotStart).plusHours(1)));
        mvc.perform(get("/api/public/booking-clock").contextPath("/api"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.today").value(DATE.toString()))
                .andExpect(jsonPath("$.data.timeZone").value("Asia/Ho_Chi_Minh"));
        for (int offset : new int[]{-1, 0, 1}) {
            var date = DATE.plusDays(offset);
            var occupied = new HashSet<>(jdbc.queryForList("SELECT time_slot_id FROM booking_details WHERE court_id=? AND booking_date=?", UUID.class, court, date));
            var response = mvc.perform(get("/api/availability").contextPath("/api").header("Authorization", "Bearer " + token)
                    .param("date", date.toString()).param("courtId", court.toString())).andExpect(status().isOk()).andReturn();
            var options = mapper.readTree(response.getResponse().getContentAsString()).path("data").path("courts").get(0).path("slots");
            for (var option : options) {
                boolean future = date.atTime(LocalTime.parse(option.path("startTime").asText())).isAfter(vietnamNow);
                assertThat(option.path("available").asBoolean()).isEqualTo(future && !occupied.contains(UUID.fromString(option.path("timeSlotId").asText())));
            }
            boolean allowed = date.atTime(LocalTime.parse(slotStart)).isAfter(vietnamNow);
            edit(booking, token, body(date, editSlot)).andExpect(allowed ? status().isOk() : status().isBadRequest());
            var payload = mapper.createObjectNode().put("customerId", customer.toString());
            payload.putArray("details").addObject().put("bookingDate", date.toString()).put("courtId", court.toString()).put("timeSlotId", createSlot.toString());
            mvc.perform(post("/api/bookings").contextPath("/api").header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON).content(payload.toString()))
                    .andExpect(allowed ? status().isCreated() : status().isBadRequest());
        }
    }

    @Test void publicBookingClockChangesDayAtVietnamMidnight() throws Exception {
        when(clock.instant()).thenReturn(Instant.parse("2031-02-20T16:59:59Z"));
        mvc.perform(get("/api/public/booking-clock").contextPath("/api"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.today").value("2031-02-20"));
        when(clock.instant()).thenReturn(Instant.parse("2031-02-20T17:00:00Z"));
        mvc.perform(get("/api/public/booking-clock").contextPath("/api"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.today").value("2031-02-21"));
    }
}
