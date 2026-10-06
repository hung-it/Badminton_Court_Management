package com.bcm.service;

import com.bcm.dto.request.CreateBookingRequest;
import com.bcm.dto.request.CreateBookingRequest.Detail;
import com.bcm.entity.BookingStatus;
import com.bcm.entity.Booking;
import com.bcm.entity.BookingDetail;
import com.bcm.entity.Customer;
import com.bcm.entity.Court;
import com.bcm.entity.TimeSlot;
import com.bcm.repository.BookingRepository;
import com.bcm.repository.BookingDetailRepository;
import com.bcm.exception.GlobalExceptionHandler;
import jakarta.persistence.EntityManager;
import com.bcm.exception.BadRequestException;
import com.bcm.exception.ResourceNotFoundException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Dedicated PostgreSQL database initialized with the original db/migration.sql.
 * Deliberately no test transaction: assertions observe service commits/rollbacks from JDBC.
 * Cleanup only removes this test's UUID-scoped fixtures and committed bookings.
 */
@SpringBootTest(properties = "booking.hold-duration=PT7M")
@AutoConfigureMockMvc
@org.springframework.context.annotation.Import(com.bcm.service.BookingWebTestConfiguration.class)
@EnabledIfEnvironmentVariable(named = "BCM_TEST_DB_URL", matches = "jdbc:postgresql:.*")
class BookingPostgresTest {
    private static final LocalDate DATE = LocalDate.of(2020, 2, 29);

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("BCM_TEST_DB_URL"));
        registry.add("spring.datasource.username", () -> Objects.requireNonNull(
                System.getenv("BCM_TEST_DB_USERNAME"), "BCM_TEST_DB_USERNAME is required"));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("BCM_TEST_DB_PASSWORD", ""));
    }

    @Autowired private BookingService service;
    @Autowired private AvailabilityService availability;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private BookingRepository bookings;
    @Autowired private BookingDetailRepository bookingDetails;
    @Autowired private EntityManager entityManager;
    @Autowired private GlobalExceptionHandler exceptionHandler;

    private UUID userId;
    private UUID customerId;
    private UUID courtId;
    private UUID slotId;

    @BeforeEach
    void fixtures() {
        userId = UUID.randomUUID();
        customerId = UUID.randomUUID();
        courtId = UUID.randomUUID();
        slotId = UUID.randomUUID();
        jdbc.update("INSERT INTO users(id,email,password_hash,full_name,phone) VALUES (?,?,?,'Booking fixture','0900000000')", userId, userId + "@booking.test", "test-only");
        jdbc.update("INSERT INTO customers(id,user_id,full_name,phone) VALUES (?,?,?,?)",
                customerId, userId, "Booking test", "0900000000");
        jdbc.update("INSERT INTO courts(id,court_number,name,base_price) VALUES (?, -301, 'Booking test', 10.05)", courtId);
        jdbc.update("""
                INSERT INTO time_slots(id,start_time,end_time,price_multiplier)
                VALUES (?, '00:00', '01:00', 1.10)
                """, slotId);
    }

    @AfterEach
    void cleanup() {
        // Details are removed by the original ON DELETE CASCADE on bookings.
        jdbc.update("DELETE FROM bookings WHERE customer_id = ?", customerId);
        jdbc.update("DELETE FROM courts WHERE id = ?", courtId);
        jdbc.update("DELETE FROM time_slots WHERE id = ?", slotId);
        jdbc.update("DELETE FROM customers WHERE id = ?", customerId);
        jdbc.update("DELETE FROM users WHERE id = ?", userId);
    }

    @Test
    void commitsPendingHeaderAndDetailWithConfiguredExpiryAndDomainCustomer() {
        LocalDateTime before = LocalDateTime.now();
        var response = service.createBooking(request(detail(DATE)));
        LocalDateTime after = LocalDateTime.now();

        assertThat(response.getStatus()).isEqualTo(BookingStatus.PENDING);
        assertThat(response.getExpiresAt()).isBetween(before.plusMinutes(7), after.plusMinutes(7));
        var row = jdbc.queryForMap("SELECT * FROM bookings WHERE id = ?", response.getBookingId());
        assertThat(row.get("customer_id")).isEqualTo(customerId).isNotEqualTo(userId);
        assertThat(row.get("status")).isEqualTo("PENDING");
        assertThat(row.get("expires_at")).isNotNull();
        assertThat(row.get("created_by")).isNull();
        assertThat(row.get("updated_by")).isNull();
        assertThat(row.get("created_at")).isNotNull();
        assertThat(row.get("updated_at")).isNotNull();
        assertCounts(1, 1);
        assertThat(jdbc.queryForObject("SELECT booking_id FROM booking_details WHERE court_id = ?",
                UUID.class, courtId)).isEqualTo(response.getBookingId());
        assertThat(availability.getAvailability(DATE, courtId).getCourts().get(0).getSlots().stream()
                .filter(s -> s.getTimeSlotId().equals(slotId)).findFirst().orElseThrow().isAvailable()).isFalse();
    }

    @Test
    void roundsEachSnapshotHalfUpBeforeSummingAndCommitsAllDetails() {
        // 10.05 * 1.10 = 11.055 -> 11.06; sum rounded details = 22.12, not 22.11.
        var response = service.createBooking(request(detail(DATE), detail(DATE.plusDays(1))));
        assertCounts(1, 2);
        assertThat(response.getCourtFee()).isEqualTo(new BigDecimal("22.12"));
        assertThat(response.getDetails()).hasSize(2).allSatisfy(d ->
                assertThat(d.getPrice()).isEqualTo(new BigDecimal("11.06")));
        assertThat(jdbc.queryForObject("SELECT court_fee FROM bookings WHERE id = ?",
                BigDecimal.class, response.getBookingId())).isEqualTo(new BigDecimal("22.12"));
        assertThat(jdbc.queryForObject("SELECT SUM(price) FROM booking_details WHERE booking_id = ?",
                BigDecimal.class, response.getBookingId())).isEqualTo(response.getCourtFee());
        assertThat(jdbc.queryForObject("SELECT count(DISTINCT booking_id) FROM booking_details WHERE court_id = ?",
                Long.class, courtId)).isEqualTo(1L);
    }

    @Test
    void snapshotRemainsUnchangedAfterMasterPricesChange() {
        var original = service.createBooking(request(detail(DATE)));
        jdbc.update("UPDATE courts SET base_price = 20.00 WHERE id = ?", courtId);
        jdbc.update("UPDATE time_slots SET price_multiplier = 1.50 WHERE id = ?", slotId);
        var subsequent = service.createBooking(request(detail(DATE.plusDays(1))));
        assertThat(subsequent.getCourtFee()).isEqualTo(new BigDecimal("30.00"));
        assertThat(jdbc.queryForObject("SELECT court_fee FROM bookings WHERE id = ?",
                BigDecimal.class, original.getBookingId())).isEqualTo(new BigDecimal("11.06"));
        assertThat(jdbc.queryForObject("SELECT price FROM booking_details WHERE booking_id = ?",
                BigDecimal.class, original.getBookingId())).isEqualTo(new BigDecimal("11.06"));
    }

    @Test
    void rejectsUnknownCustomerAndDoesNotTreatUserIdAsCustomerId() {
        for (UUID id : List.of(UUID.randomUUID(), userId)) {
            assertThatThrownBy(() -> service.createBooking(new CreateBookingRequest(id, List.of(detail(DATE)))))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
        assertCounts(0, 0);
    }

    @Test
    void rejectsUnknownCourt() {
        assertThatThrownBy(() -> service.createBooking(request(new Detail(DATE, UUID.randomUUID(), slotId))))
                .isInstanceOf(ResourceNotFoundException.class);
        assertCounts(0, 0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"MAINTENANCE", "CLOSED", "DELETED"})
    void rejectsIneligibleCourt(String state) {
        if (state.equals("DELETED")) {
            jdbc.update("UPDATE courts SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?", courtId);
        } else {
            jdbc.update("UPDATE courts SET status = ? WHERE id = ?", state, courtId);
        }
        assertThatThrownBy(() -> service.createBooking(request(detail(DATE))))
                .isInstanceOf(BadRequestException.class);
        assertCounts(0, 0);
    }

    @Test
    void rejectsUnknownTimeSlot() {
        assertThatThrownBy(() -> service.createBooking(request(new Detail(DATE, courtId, UUID.randomUUID()))))
                .isInstanceOf(ResourceNotFoundException.class);
        assertCounts(0, 0);
    }

    @Test
    void rejectsDuplicateTupleBeforePersistence() {
        assertThatThrownBy(() -> service.createBooking(request(detail(DATE), detail(DATE))))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("Duplicate");
        assertCounts(0, 0);
    }

    @Test
    void validThenInvalidDetailLeavesNoHeaderOrDetail() {
        assertThatThrownBy(() -> service.createBooking(request(detail(DATE),
                new Detail(DATE.plusDays(1), courtId, UUID.randomUUID()))))
                .isInstanceOf(ResourceNotFoundException.class);
        assertCounts(0, 0);
    }

    @Test
    void databaseFailureRollsBackHeaderAndOtherDetail() {
        var existing = service.createBooking(request(detail(DATE)));
        // Bypass the service pre-check to exercise the real DB fallback, with no mocked repository.
        // The header and first detail are flushed before the second detail violates uq_booking_slot.
        var failure = org.junit.jupiter.api.Assertions.assertThrows(DataIntegrityViolationException.class,
                () -> new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
                    Booking header = new Booking();
                    header.setCustomer(entityManager.getReference(Customer.class, customerId));
                    header.setCourtFee(new BigDecimal("22.12"));
                    header.setExpiresAt(LocalDateTime.now().plusMinutes(7));
                    bookings.saveAndFlush(header);
                    for (var date : List.of(DATE.plusDays(1), DATE)) {
                        BookingDetail item = new BookingDetail();
                        item.setBooking(header);
                        item.setCourt(entityManager.getReference(Court.class, courtId));
                        item.setTimeSlot(entityManager.getReference(TimeSlot.class, slotId));
                        item.setBookingDate(date);
                        item.setPrice(new BigDecimal("11.06"));
                        bookingDetails.saveAndFlush(item);
                    }
                }));
        assertThat(failure.getMessage()).contains("uq_booking_slot");
        var error = exceptionHandler.handleDataIntegrityViolationException(failure, null);
        assertThat(error.getStatusCode().value()).isEqualTo(409);
        assertThat(error.getBody().isSuccess()).isFalse();
        assertThat(error.getBody().getMessage()).isEqualTo("This time slot is already booked");
        assertCounts(1, 1);
        assertThat(jdbc.queryForObject("SELECT id FROM bookings WHERE customer_id = ?", UUID.class, customerId))
                .isEqualTo(existing.getBookingId());
        assertThat(jdbc.queryForObject("SELECT booking_date FROM booking_details WHERE court_id = ?",
                LocalDate.class, courtId)).isEqualTo(DATE);
    }

    @Test
    void serviceRejectsIncompleteInputWithoutPersistence() {
        var invalidRequests = Arrays.asList(null, new CreateBookingRequest(null, List.of(detail(DATE))),
                new CreateBookingRequest(customerId, null), request(), request((Detail) null),
                request(new Detail(null, courtId, slotId)), request(new Detail(DATE, null, slotId)),
                request(new Detail(DATE, courtId, null)));
        for (var invalid : invalidRequests) {
            assertThatThrownBy(() -> service.createBooking(invalid)).isInstanceOf(BadRequestException.class);
        }
        assertCounts(0, 0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"DETAIL", "TOTAL"})
    void rejectsNumericOverflowWithoutPersistence(String scope) {
        jdbc.update("UPDATE courts SET base_price = ? WHERE id = ?",
                new BigDecimal(scope.equals("DETAIL") ? "99999999.99" : "50000000.00"), courtId);
        assertThatThrownBy(() -> service.createBooking(request(detail(DATE), detail(DATE.plusDays(1)))))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("NUMERIC(10,2)");
        assertCounts(0, 0);
    }

    @Test
    void publicApiIgnoresClientPricesStatusExpiryAndReturnsDto() throws Exception {
        ObjectNode input = mapper.valueToTree(request(detail(DATE)));
        input.put("courtFee", 0).put("status", "PAID").put("expiresAt", "2099-01-01T00:00:00");
        ((ObjectNode) input.withArray("details").get(0)).put("price", 0);
        var before = LocalDateTime.now();
        var result = mvc.perform(post("/api/bookings").contextPath("/api")
                        .contentType(MediaType.APPLICATION_JSON).content(input.toString()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.bookingId").isString())
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.courtFee").value(11.06))
                .andExpect(jsonPath("$.data.details[0].bookingDate").value(DATE.toString()))
                .andExpect(jsonPath("$.data.details[0].courtId").value(courtId.toString()))
                .andExpect(jsonPath("$.data.details[0].timeSlotId").value(slotId.toString()))
                .andExpect(jsonPath("$.data.details[0].price").value(11.06))
                .andExpect(jsonPath("$.data.customer").doesNotExist())
                .andExpect(jsonPath("$.data.details[0].booking").doesNotExist()).andReturn();
        var expiry = LocalDateTime.parse(mapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("expiresAt").asText());
        assertThat(expiry).isBetween(before.plusMinutes(7), LocalDateTime.now().plusMinutes(7));
        assertCounts(1, 1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "null", "{}", "{", "{\"details\":[]}"})
    void missingOrMalformedBodyReturns400Envelope(String body) throws Exception {
        mvc.perform(post("/api/bookings").contextPath("/api").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.success").value(false));
        assertCounts(0, 0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"INVALID_DATE", "INVALID_UUID", "NULL_DETAIL", "MISSING_DATE"})
    void invalidDetailInputReturns400Envelope(String kind) throws Exception {
        ObjectNode input = mapper.valueToTree(request(detail(DATE)));
        ObjectNode item = (ObjectNode) input.withArray("details").get(0);
        switch (kind) {
            case "INVALID_DATE" -> item.put("bookingDate", "2026-02-30");
            case "INVALID_UUID" -> item.put("courtId", "bad-uuid");
            case "NULL_DETAIL" -> input.withArray("details").set(0, mapper.nullNode());
            case "MISSING_DATE" -> item.remove("bookingDate");
            default -> throw new AssertionError(kind);
        }
        mvc.perform(post("/api/bookings").contextPath("/api")
                        .contentType(MediaType.APPLICATION_JSON).content(input.toString()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.success").value(false));
        assertCounts(0, 0);
    }

    @Test
    void apiReportsReferenceValidationAndPersistenceErrors() throws Exception {
        mvc.perform(post("/api/bookings").contextPath("/api").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new CreateBookingRequest(UUID.randomUUID(), List.of(detail(DATE))))))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.success").value(false));
        mvc.perform(post("/api/bookings").contextPath("/api").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request(detail(DATE), detail(DATE)))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.success").value(false));
        service.createBooking(request(detail(DATE)));
        mvc.perform(post("/api/bookings").contextPath("/api").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request(detail(DATE.plusDays(1)), detail(DATE)))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("This time slot is already booked"));
        assertCounts(1, 1);
    }

    @Test
    void openApiDocumentsCreateAndIdentityLimitation() throws Exception {
        mvc.perform(get("/api/api-docs").contextPath("/api"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/bookings'].post.responses['201']").exists())
                .andExpect(jsonPath("$.paths['/bookings'].post.responses['409']").exists())
                .andExpect(jsonPath("$.paths['/bookings'].post.description").value(
                        org.hamcrest.Matchers.containsString("not proof of identity/ownership")))
                .andExpect(jsonPath("$.components.schemas.CreateBookingRequest.properties.courtFee").doesNotExist())
                .andExpect(jsonPath("$.components.schemas.CreateBookingRequest.properties.expiresAt").doesNotExist());
    }

    private Detail detail(LocalDate date) {
        return new Detail(date, courtId, slotId);
    }

    private CreateBookingRequest request(Detail... items) {
        return new CreateBookingRequest(customerId, Arrays.asList(items));
    }

    private void assertCounts(long headers, long items) {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bookings WHERE customer_id = ?", Long.class, customerId))
                .isEqualTo(headers);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM booking_details WHERE court_id = ?", Long.class, courtId))
                .isEqualTo(items);
    }
}
