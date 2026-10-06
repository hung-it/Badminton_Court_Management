package com.bcm.service;

import com.bcm.dto.response.AvailabilityResponse;
import com.bcm.dto.response.AvailabilityResponse.CourtAvailability;
import com.bcm.dto.response.AvailabilityResponse.SlotAvailability;
import com.bcm.entity.BookingStatus;
import com.bcm.exception.BadRequestException;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Time;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Uses the Phase 1 PostgreSQL test database contract: BCM_TEST_DB_URL,
 * BCM_TEST_DB_USERNAME and BCM_TEST_DB_PASSWORD, with db/migration.sql already applied.
 * Fixtures roll back; availability never deletes details, including expired ones.
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@AutoConfigureMockMvc
@org.springframework.context.annotation.Import(com.bcm.service.BookingWebTestConfiguration.class)
@Transactional
@EnabledIfEnvironmentVariable(named = "BCM_TEST_DB_URL", matches = "jdbc:postgresql:.*")
class AvailabilityPostgresTest {

    private static final LocalDate DATE = LocalDate.of(2020, 2, 29);

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("BCM_TEST_DB_URL"));
        registry.add("spring.datasource.username", () -> Objects.requireNonNull(
                System.getenv("BCM_TEST_DB_USERNAME"), "BCM_TEST_DB_USERNAME is required"));
        registry.add("spring.datasource.password", () ->
                System.getenv().getOrDefault("BCM_TEST_DB_PASSWORD", ""));
    }

    @Autowired
    private AvailabilityService service;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private UUID customerId;
    private UUID courtId;
    private UUID otherCourtId;
    private UUID maintenanceCourtId;
    private UUID closedCourtId;
    private UUID deletedCourtId;
    private UUID slotId;
    private UUID otherSlotId;

    @BeforeEach
    void fixtures() {
        UUID userId = UUID.randomUUID();
        customerId = UUID.randomUUID();
        jdbc.update("INSERT INTO users(id,email,password_hash,full_name,phone) VALUES (?,?,?,'Booking fixture','0900000000')",
                userId, userId + "@availability.test", "test-only");
        jdbc.update("INSERT INTO customers (id, user_id, full_name, phone) VALUES (?, ?, ?, ?)",
                customerId, userId, "Availability customer", "0900000000");
        courtId = insertCourt(-20, "AVAILABLE", null);
        otherCourtId = insertCourt(-19, "AVAILABLE", null);
        maintenanceCourtId = insertCourt(-18, "MAINTENANCE", null);
        closedCourtId = insertCourt(-17, "CLOSED", null);
        deletedCourtId = insertCourt(-16, "AVAILABLE", LocalDateTime.of(2019, 1, 1, 0, 0));
        slotId = insertSlot("00:00:00", "01:00:00");
        otherSlotId = insertSlot("01:00:00", "02:00:00");
    }

    @Test
    void returnsAllTemplatesAsAvailableWhenNoDetailsExist() {
        AvailabilityResponse response = service.getAvailability(DATE, courtId);

        assertThat(response.getDate()).isEqualTo(DATE);
        assertThat(response.getCourts()).extracting(CourtAvailability::getCourtId).containsExactly(courtId);
        List<SlotAvailability> slots = response.getCourts().get(0).getSlots();
        List<UUID> expectedIds = jdbc.query("SELECT id FROM time_slots ORDER BY start_time, end_time, id",
                (rs, row) -> rs.getObject("id", UUID.class));
        assertThat(slots).extracting(SlotAvailability::getTimeSlotId).containsExactlyElementsOf(expectedIds);
        assertThat(slots).allMatch(SlotAvailability::isAvailable);
        assertThat(slots.get(0).getPriceMultiplier()).isEqualByComparingTo("1.25");
    }

    @Test
    void matchingDetailOccupiesOnlyItsTimeSlot() {
        insertDetail(courtId, slotId, DATE, BookingStatus.PAID);

        AvailabilityResponse response = service.getAvailability(DATE, courtId);

        assertThat(slot(response, courtId, slotId).isAvailable()).isFalse();
        assertThat(slot(response, courtId, otherSlotId).isAvailable()).isTrue();
    }

    @Test
    void detailOnAnotherDateDoesNotOccupyRequestedDate() {
        insertDetail(courtId, slotId, DATE.plusDays(1), BookingStatus.PAID);

        assertThat(slot(service.getAvailability(DATE, courtId), courtId, slotId).isAvailable()).isTrue();
    }

    @Test
    void detailOnAnotherCourtDoesNotOccupyRequestedCourt() {
        insertDetail(otherCourtId, slotId, DATE, BookingStatus.PAID);

        AvailabilityResponse response = service.getAvailability(DATE, null);

        assertThat(slot(response, courtId, slotId).isAvailable()).isTrue();
        assertThat(slot(response, otherCourtId, slotId).isAvailable()).isFalse();
        assertThat(slot(service.getAvailability(DATE, courtId), courtId, slotId).isAvailable()).isTrue();
    }

    @Test
    void excludesMaintenanceClosedAndSoftDeletedCourts() {
        assertThat(service.getAvailability(DATE, null).getCourts())
                .extracting(CourtAvailability::getCourtId)
                .containsExactly(courtId, otherCourtId)
                .doesNotContain(maintenanceCourtId, closedCourtId, deletedCourtId);
    }

    @Test
    void optionalFilterReturnsOnlyRequestedEligibleCourt() {
        assertThat(service.getAvailability(DATE, otherCourtId).getCourts())
                .extracting(CourtAvailability::getCourtId).containsExactly(otherCourtId);
    }

    @ParameterizedTest
    @EnumSource(BookingStatus.class)
    void existingDetailBlocksRegardlessOfHeaderStatusOrExpiredDeadline(BookingStatus status) {
        insertDetail(courtId, slotId, DATE, status);

        assertThat(slot(service.getAvailability(DATE, courtId), courtId, slotId).isAvailable()).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM booking_details WHERE court_id = ?",
                Long.class, courtId)).isEqualTo(1L);
    }

    @Test
    void returnsEmptyCourtsWhenNoneAreEligible() {
        jdbc.update("UPDATE courts SET status = 'CLOSED' WHERE id IN (?, ?)", courtId, otherCourtId);

        assertThat(service.getAvailability(DATE, null).getCourts()).isEmpty();
    }

    @Test
    void rejectsNullDateAtServiceBoundary() {
        assertThatThrownBy(() -> service.getAvailability(null, null)).isInstanceOf(BadRequestException.class);
    }

    @Test
    void publicApiReturnsWrappedGridIncludingOccupiedBoolean() throws Exception {
        insertDetail(courtId, slotId, DATE, BookingStatus.EXPIRED);

        mvc.perform(get("/api/availability").contextPath("/api")
                        .param("date", DATE.toString()).param("courtId", courtId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Success"))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.data.date").value(DATE.toString()))
                .andExpect(jsonPath("$.data.courts", hasSize(1)))
                .andExpect(jsonPath("$.data.courts[0].courtId").value(courtId.toString()))
                .andExpect(jsonPath("$.data.courts[0].courtNumber").value(-20))
                .andExpect(jsonPath("$.data.courts[0].name").value("Court -20"))
                .andExpect(jsonPath("$.data.courts[0].slots[0].timeSlotId").value(slotId.toString()))
                .andExpect(jsonPath("$.data.courts[0].slots[0].startTime").value("00:00:00"))
                .andExpect(jsonPath("$.data.courts[0].slots[0].endTime").value("01:00:00"))
                .andExpect(jsonPath("$.data.courts[0].slots[0].priceMultiplier").value(1.25))
                .andExpect(jsonPath("$.data.courts[0].slots[0].available").value(false))
                .andExpect(jsonPath("$.data.courts[0].slots[1].available").value(true))
                .andExpect(jsonPath("$.data.courts[0].deletedAt").doesNotExist())
                .andExpect(jsonPath("$.data.courts[0].slots[0].booking").doesNotExist());
    }

    @Test
    void apiAllowsOmittingCourtFilterAndQueryingPastDate() throws Exception {
        mvc.perform(get("/api/availability").contextPath("/api").param("date", DATE.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.courts", hasSize(2)));
    }

    @Test
    void missingDateReturns400Envelope() throws Exception {
        mvc.perform(get("/api/availability").contextPath("/api"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Required query parameter: date"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "not-a-date", "2026-02-30", "2026/10/05"})
    void invalidDateReturns400Envelope(String date) throws Exception {
        mvc.perform(get("/api/availability").contextPath("/api").param("date", date))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").isString());
    }

    @Test
    void malformedCourtIdReturns400Envelope() throws Exception {
        mvc.perform(get("/api/availability").contextPath("/api")
                        .param("date", DATE.toString()).param("courtId", "invalid-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Invalid query parameter: courtId"));
    }

    @Test
    void unknownOrIneligibleCourtFilterReturns404Envelope() throws Exception {
        for (UUID id : List.of(UUID.randomUUID(), maintenanceCourtId, closedCourtId, deletedCourtId)) {
            mvc.perform(get("/api/availability").contextPath("/api")
                            .param("date", DATE.toString()).param("courtId", id.toString()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.message").isString());
        }
    }

    @Test
    void usesThreeSelectsAsGridGrowsAndDoesNotMutateDatabase() {
        insertDetail(courtId, slotId, DATE, BookingStatus.EXPIRED);
        for (int i = 0; i < 20; i++) {
            insertCourt(1000 + i, "AVAILABLE", null);
            insertSlot("02:00:00", "03:00:00");
        }
        Map<String, String> before = databaseSnapshot();
        var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();

        AvailabilityResponse response = service.getAvailability(DATE, null);

        assertThat(response.getCourts()).hasSize(22);
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(3L);
        assertThat(statistics.getEntityInsertCount()).isZero();
        assertThat(statistics.getEntityUpdateCount()).isZero();
        assertThat(statistics.getEntityDeleteCount()).isZero();
        assertThat(databaseSnapshot()).isEqualTo(before);
    }

    @Test
    void documentsAvailabilityUnderCurrentOwnerAuthenticationPolicy() throws Exception {
        mvc.perform(get("/api/api-docs").contextPath("/api"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/availability'].get.parameters[0].name").value("date"))
                .andExpect(jsonPath("$.paths['/availability'].get.parameters[0].required").value(true))
                .andExpect(jsonPath("$.paths['/availability'].get.parameters[1].name").value("courtId"))
                .andExpect(jsonPath("$.paths['/availability'].get.parameters[1].required").value(false))
                .andExpect(jsonPath("$.paths['/availability'].get.security[0]['Bearer Authentication']").isArray())
                .andExpect(jsonPath("$.paths['/availability'].post").doesNotExist());
    }

    private SlotAvailability slot(AvailabilityResponse response, UUID court, UUID timeSlot) {
        return response.getCourts().stream().filter(c -> c.getCourtId().equals(court)).findFirst().orElseThrow()
                .getSlots().stream().filter(s -> s.getTimeSlotId().equals(timeSlot)).findFirst().orElseThrow();
    }

    private UUID insertCourt(int number, String status, LocalDateTime deletedAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO courts (id, court_number, name, status, base_price, deleted_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, id, number, "Court " + number, status, new BigDecimal("100000.00"), deletedAt);
        return id;
    }

    private UUID insertSlot(String start, String end) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO time_slots (id, start_time, end_time, price_multiplier) VALUES (?, ?, ?, ?)",
                id, Time.valueOf(start), Time.valueOf(end), new BigDecimal("1.25"));
        return id;
    }

    private void insertDetail(UUID court, UUID timeSlot, LocalDate date, BookingStatus status) {
        UUID booking = UUID.randomUUID();
        jdbc.update("INSERT INTO bookings (id, customer_id, status, court_fee, expires_at) VALUES (?, ?, ?, ?, ?)",
                booking, customerId, status.name(), new BigDecimal("125000.00"),
                LocalDateTime.of(2000, 1, 1, 0, 0));
        jdbc.update("""
                INSERT INTO booking_details (booking_id, court_id, time_slot_id, booking_date, price)
                VALUES (?, ?, ?, ?, ?)
                """, booking, court, timeSlot, date, new BigDecimal("125000.00"));
    }

    private Map<String, String> databaseSnapshot() {
        Map<String, String> snapshot = new LinkedHashMap<>();
        for (String table : List.of("courts", "time_slots", "bookings", "booking_details", "payment_transactions")) {
            snapshot.put(table, jdbc.queryForObject(
                    "SELECT COALESCE(jsonb_agg(to_jsonb(t) ORDER BY id)::text, '[]') FROM " + table + " t", String.class));
        }
        return snapshot;
    }
}
