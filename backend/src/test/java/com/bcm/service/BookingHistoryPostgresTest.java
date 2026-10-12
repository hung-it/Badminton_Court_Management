package com.bcm.service;

import com.bcm.dto.request.CreateBookingRequest;
import com.bcm.entity.BookingStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Committed fixtures and real read-only service transactions, no outer test transaction. */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@AutoConfigureMockMvc
@Import({BookingWebTestConfiguration.class, BookingHistoryPostgresTest.SnapshotProbe.class})
@EnabledIfEnvironmentVariable(named = "BCM_TEST_DB_URL", matches = "jdbc:postgresql:.*")
class BookingHistoryPostgresTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 5, 12, 0);
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("BCM_TEST_DB_URL"));
        registry.add("spring.datasource.username", () -> Objects.requireNonNull(System.getenv("BCM_TEST_DB_USERNAME")));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("BCM_TEST_DB_PASSWORD", ""));
    }
    @Autowired private JdbcTemplate jdbc;
    @Autowired private org.springframework.web.context.WebApplicationContext webContext;
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private BookingHistoryService history;
    @Autowired private BookingService creation;
    @Autowired private BookingExpirationService expiration;
    @Autowired private EntityManagerFactory emf;
    @Autowired private com.bcm.util.JwtUtil jwt;
    @Autowired private com.bcm.security.CustomUserDetailsService userDetails;
    @MockBean(name = "bookingExpirationClock") private Clock clock;
    private UUID userA, userB, customerA, customerB, court, slot, invoice;

    @BeforeEach
    void fixtures() {
        when(clock.getZone()).thenReturn(ZoneId.systemDefault());
        when(clock.instant()).thenReturn(NOW.atZone(ZoneId.systemDefault()).toInstant());
        userA = UUID.randomUUID(); userB = UUID.randomUUID();
        customerA = UUID.randomUUID(); customerB = UUID.randomUUID();
        court = UUID.randomUUID(); slot = UUID.randomUUID(); invoice = UUID.randomUUID();
        for (var ids : List.of(List.of(userA, customerA), List.of(userB, customerB))) {
            jdbc.update("INSERT INTO users(id,email,password_hash,full_name,phone) VALUES (?,?,?,'Booking fixture','0900000000')", ids.get(0), ids.get(0) + "@history.test", "test-only");
            jdbc.update("INSERT INTO customers(id,user_id,full_name,phone) VALUES (?,?,?,?)",
                    ids.get(1), ids.get(0), "History", "0900000000");
        }
        jdbc.update("INSERT INTO user_roles(user_id,role_id) SELECT ?,id FROM roles WHERE role_name='CUSTOMER'", userA);
        jdbc.update("INSERT INTO user_roles(user_id,role_id) SELECT ?,id FROM roles WHERE role_name='CUSTOMER'", userB);
        authenticate(userA);
        jdbc.update("INSERT INTO courts(id,court_number,name,base_price) VALUES (?,999601,'History court',10.05)", court);
        jdbc.update("INSERT INTO time_slots(id,start_time,end_time,price_multiplier) VALUES (?,'00:00','01:00',1.10)", slot);
    }

    @AfterEach
    void cleanup() {
        SnapshotProbe.beforeDetailRead.remove();
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
        jdbc.update("DELETE FROM payment_transactions WHERE booking_id IN (SELECT id FROM bookings WHERE customer_id IN (?,?)) OR invoice_id=?",
                customerA, customerB, invoice);
        jdbc.update("DELETE FROM invoices WHERE id=?", invoice);
        jdbc.update("DELETE FROM bookings WHERE customer_id IN (?,?)", customerA, customerB);
        jdbc.update("DELETE FROM courts WHERE id=?", court);
        jdbc.update("DELETE FROM time_slots WHERE id=?", slot);
        jdbc.update("DELETE FROM customers WHERE id IN (?,?)", customerA, customerB);
        jdbc.update("DELETE FROM users WHERE id IN (?,?)", userA, userB);
    }

    @Test
    void customerFilterOrderingAndPaginationAreDatabaseBoundedAndStableForTies() throws Exception {
        UUID older = booking(customerA, BookingStatus.PENDING, NOW.minusDays(1));
        UUID low = UUID.fromString("00000000-0000-0000-0000-000000006001");
        UUID high = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffff6002");
        insertBooking(low, customerA, BookingStatus.PAID, NOW);
        insertBooking(high, customerA, BookingStatus.EXPIRED, NOW);
        booking(customerB, BookingStatus.PAID, NOW.plusDays(1));
        mvc.perform(get("/api/bookings").contextPath("/api").param("customerId", customerA.toString()).param("size", "2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.content.length()").value(2))
                .andExpect(jsonPath("$.data.content[0].bookingId").value(high.toString()))
                .andExpect(jsonPath("$.data.content[1].bookingId").value(low.toString()))
                .andExpect(jsonPath("$.data.content[0].customerId").value(customerA.toString()))
                .andExpect(jsonPath("$.data.content[0].details").doesNotExist())
                .andExpect(jsonPath("$.data.totalElements").value(3))
                .andExpect(jsonPath("$.data.totalPages").value(2))
                .andExpect(jsonPath("$.data.page").value(0)).andExpect(jsonPath("$.data.size").value(2));
        authenticate(userA);
        var next = history.history(customerA, null, 1, 2);
        assertThat(next.content()).extracting("bookingId").containsExactly(older);
        assertThat(history.history(customerA, null, 2, 2).content()).isEmpty();
        authenticate(userB);
        assertThat(history.history(customerB, null, 0, 20).totalElements()).isEqualTo(1);
    }

    @ParameterizedTest
    @EnumSource(BookingStatus.class)
    void statusFilterSupportsEverySchemaStatusWithoutHidingExpired(BookingStatus status) throws Exception {
        UUID selected = booking(customerA, status, NOW);
        booking(customerA, status == BookingStatus.PAID ? BookingStatus.PENDING : BookingStatus.PAID, NOW);
        mvc.perform(get("/api/bookings").contextPath("/api").param("customerId", customerA.toString()).param("status", status.name()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].bookingId").value(selected.toString()));
    }

    @Test
    void defaultPaginationForOwnCustomerReturnsEmptyPage() throws Exception {
        mvc.perform(get("/api/bookings").contextPath("/api").param("customerId", customerA.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.content").isEmpty())
                .andExpect(jsonPath("$.data.page").value(0)).andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.totalElements").value(0)).andExpect(jsonPath("$.data.totalPages").value(0));
    }

    @Test
    void normalDetailUsesPersistedSnapshotAndCurrentDisplayDataWithNoPayment() throws Exception {
        var detail = new CreateBookingRequest.Detail(LocalDate.of(2026, 10, 5), court, slot);
        var created = creation.createBooking(new CreateBookingRequest(customerA, List.of(detail)));
        jdbc.update("UPDATE courts SET base_price=99,name='Updated name' WHERE id=?", court);
        jdbc.update("UPDATE time_slots SET price_multiplier=2 WHERE id=?", slot);
        mvc.perform(get("/api/bookings/" + created.getBookingId()).contextPath("/api"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.customerId").value(customerA.toString()))
                .andExpect(jsonPath("$.data.courtFee").value(11.06))
                .andExpect(jsonPath("$.data.createdAt").exists()).andExpect(jsonPath("$.data.updatedAt").exists())
                .andExpect(jsonPath("$.data.details.length()").value(1))
                .andExpect(jsonPath("$.data.details[0].detailId").isNotEmpty())
                .andExpect(jsonPath("$.data.details[0].bookingDate").value("2026-10-05"))
                .andExpect(jsonPath("$.data.details[0].courtId").value(court.toString()))
                .andExpect(jsonPath("$.data.details[0].timeSlotId").value(slot.toString()))
                .andExpect(jsonPath("$.data.details[0].price").value(11.06))
                .andExpect(jsonPath("$.data.details[0].courtName").value("Updated name"))
                .andExpect(jsonPath("$.data.details[0].startTime").value("00:00:00"))
                .andExpect(jsonPath("$.data.payments").isEmpty());
    }

    @Test
    void realExpirationHeaderRemainsVisibleWithEmptyDetailsAndPreservedPayment() throws Exception {
        UUID id = booking(customerA, BookingStatus.PENDING, NOW);
        addDetail(id, LocalDate.of(2026, 10, 5));
        UUID payment = payment(id, "VNPAY", "PENDING", NOW, null);
        jdbc.update("UPDATE bookings SET expires_at=? WHERE id=?", NOW, id);
        assertThat(expiration.expireBooking(id)).isTrue();
        mvc.perform(get("/api/bookings/" + id).contextPath("/api"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("EXPIRED"))
                .andExpect(jsonPath("$.data.courtFee").value(11.06)).andExpect(jsonPath("$.data.details").isEmpty())
                .andExpect(jsonPath("$.data.payments[0].paymentAttemptId").value(payment.toString()))
                .andExpect(jsonPath("$.data.payments[0].paymentStatus").value("PENDING"));
        authenticate(userA);
        assertThat(history.history(customerA, null, 0, 20).content()).extracting("bookingId").contains(id);
    }

    @Test
    void paymentsUseActualStatusesAndStableOrderingAndExcludeInvoiceTargetEvenWhenInvoiceLinksBooking() throws Exception {
        UUID id = booking(customerA, BookingStatus.PAID, NOW);
        UUID oldest = payment(id, "VNPAY", "FAILED", NOW.minusDays(1), null);
        UUID low = UUID.fromString("00000000-0000-0000-0000-000000006003");
        UUID high = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffff6004");
        insertPayment(low, id, "VNPAY", "REFUNDED", NOW, "history-refunded-" + id);
        insertPayment(high, id, "VNPAY", "SUCCESS", NOW, "history-success-" + id);
        UUID latest = payment(id, "VNPAY", "PENDING", NOW.plusDays(1), null);
        jdbc.update("INSERT INTO invoices(id,booking_id,customer_id,payment_method,total_amount) VALUES (?,?,?,'VNPAY',11.06)",
                invoice, id, customerA);
        jdbc.update("INSERT INTO payment_transactions(invoice_id,payment_method,status,amount) VALUES (?,'VNPAY','SUCCESS',11.06)", invoice);
        var result = history.detail(id);
        assertThat(result.payments()).extracting("paymentAttemptId").containsExactly(latest, high, low, oldest);
        assertThat(result.payments()).extracting("paymentStatus").containsExactly(
                com.bcm.entity.PaymentStatus.PENDING, com.bcm.entity.PaymentStatus.SUCCESS,
                com.bcm.entity.PaymentStatus.REFUNDED, com.bcm.entity.PaymentStatus.FAILED);
        assertThat(result.payments().get(0).transactionId()).isNull();
        assertThat(result.payments().get(0).transactionDate()).isNull();
        assertThat(result.payments().get(1).transactionId()).isEqualTo("history-success-" + id);
        assertThat(result.payments().get(1).transactionDate()).isEqualTo(NOW);
        assertThat(result.payments()).allSatisfy(p -> assertThat(p.amount()).isEqualByComparingTo("11.06"));
        mvc.perform(get("/api/bookings/" + id).contextPath("/api")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PAID"))
                .andExpect(jsonPath("$.data.payments.length()").value(4))
                .andExpect(jsonPath("$.data.payments[0].paymentMethod").value("VNPAY"))
                .andExpect(jsonPath("$.data.payments[0].paymentStatus").value("PENDING"))
                .andExpect(jsonPath("$.data.payments[0].transactionId").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.payments[0].transactionDate").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.payments[1].transactionId").value("history-success-" + id))
                .andExpect(jsonPath("$.data.payments[1].transactionDate").value("2026-10-05T12:00:00"))
                .andExpect(jsonPath("$.data.payments[0].invoiceId").doesNotExist())
                .andExpect(jsonPath("$.data.payments[0].note").doesNotExist());
    }

    @Test
    void getNeverExpiresOverduePendingOrMutatesAnyRows() throws Exception {
        UUID id = booking(customerA, BookingStatus.PENDING, NOW);
        addDetail(id, LocalDate.of(2026, 10, 5));
        payment(id, "VNPAY", "PENDING", NOW, null);
        jdbc.update("UPDATE bookings SET expires_at=? WHERE id=?", NOW.minusSeconds(1), id);
        var header = jdbc.queryForMap("SELECT * FROM bookings WHERE id=?", id);
        var slots = jdbc.queryForList("SELECT * FROM booking_details WHERE booking_id=?", id);
        var payments = jdbc.queryForList("SELECT * FROM payment_transactions WHERE booking_id=?", id);
        mvc.perform(get("/api/bookings/" + id).contextPath("/api")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING")).andExpect(jsonPath("$.data.details.length()").value(1));
        authenticate(userA);
        history.history(customerA, null, 0, 20);
        assertThat(jdbc.queryForMap("SELECT * FROM bookings WHERE id=?", id)).isEqualTo(header);
        assertThat(jdbc.queryForList("SELECT * FROM booking_details WHERE booking_id=?", id)).isEqualTo(slots);
        assertThat(jdbc.queryForList("SELECT * FROM payment_transactions WHERE booking_id=?", id)).isEqualTo(payments);
    }

    @Test
    void queryCountStaysConstantAsCollectionsAndPageGrow() {
        UUID id = booking(customerA, BookingStatus.PENDING, NOW);
        for (int i = 0; i < 10; i++) {
            addDetail(id, LocalDate.of(2026, 10, 5).plusDays(i));
            payment(id, "VNPAY", "FAILED", NOW.plusSeconds(i), null);
            booking(customerA, BookingStatus.PAID, NOW.plusSeconds(i));
        }
        var stats = emf.unwrap(SessionFactory.class).getStatistics();
        stats.clear();
        assertThat(history.detail(id).details()).hasSize(10);
        assertThat(stats.getPrepareStatementCount()).isEqualTo(4);
        stats.clear();
        assertThat(history.history(customerA, null, 0, 5).content()).hasSize(5);
        assertThat(stats.getPrepareStatementCount()).isEqualTo(3);
    }

    @Test
    void concurrentExpirationCannotMixOldHeaderWithDeletedDetailsInReadSnapshot() throws Exception {
        UUID id = booking(customerA, BookingStatus.PENDING, NOW);
        addDetail(id, LocalDate.of(2026, 10, 5));
        jdbc.update("UPDATE bookings SET expires_at=? WHERE id=?", NOW, id);
        var worker = Executors.newSingleThreadExecutor();
        try {
            SnapshotProbe.beforeDetailRead.set(() -> {
                try {
                    assertThat(worker.submit(() -> expiration.expireBooking(id)).get(10, TimeUnit.SECONDS)).isTrue();
                } catch (Exception ex) { throw new AssertionError("Expiration must commit before detail SELECT", ex); }
            });
            var oldSnapshot = history.detail(id);
            assertThat(oldSnapshot.status()).isEqualTo(BookingStatus.PENDING);
            assertThat(oldSnapshot.details()).hasSize(1);
            assertThat(history.detail(id).status()).isEqualTo(BookingStatus.EXPIRED);
            assertThat(history.detail(id).details()).isEmpty();
        } finally { SnapshotProbe.beforeDetailRead.remove(); worker.shutdownNow(); }
    }

    @Test
    void absentBookingReturns404AndMalformedBookingIdReturns400() throws Exception {
        mvc.perform(get("/api/bookings/" + UUID.randomUUID()).contextPath("/api"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.success").value(false));
        mvc.perform(get("/api/bookings/not-a-uuid").contextPath("/api"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.success").value(false));
    }

    @ParameterizedTest
    @CsvSource({"page,-1", "page,abc", "size,0", "size,-1", "size,101", "size,abc", "status,UNKNOWN", "customerId,invalid"})
    void invalidFiltersReturn400Envelope(String key, String value) throws Exception {
        var request = get("/api/bookings").contextPath("/api");
        if (!key.equals("customerId")) { request.param("customerId", customerA.toString()); }
        mvc.perform(request.param(key, value)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void absentCustomerDefaultsToTrustedIdentityAndInvalidServiceArgumentsAreRejected() throws Exception {
        mvc.perform(get("/api/bookings").contextPath("/api"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.content").isEmpty());
        assertThatThrownBy(() -> history.history(null, null, -1, 20)).isInstanceOf(com.bcm.exception.BadRequestException.class);
        assertThatThrownBy(() -> history.detail(null)).isInstanceOf(com.bcm.exception.BadRequestException.class);
    }

    @Test
    void openApiContainsEveryBookingEnginePathAndActualResponseContracts() throws Exception {
        String body = mvc.perform(get("/api/api-docs").contextPath("/api")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode document = mapper.readTree(body);
        JsonNode paths = document.path("paths");
        assertThat(paths.has("/availability")).isTrue();
        assertThat(paths.has("/bookings")).isTrue();
        assertThat(paths.has("/bookings/{bookingId}")).isTrue();
        assertThat(paths.has("/bookings/{bookingId}/payments")).isTrue();
        assertThat(paths.has("/payments/vnpay/return")).isTrue();
        assertThat(paths.has("/payments/vnpay/ipn")).isTrue();
        assertThat(paths.fieldNames()).toIterable().filteredOn(path -> path.startsWith("/payments/"))
                .containsExactlyInAnyOrder("/payments/vnpay/ipn", "/payments/vnpay/return");
        paths.fieldNames().forEachRemaining(path -> assertThat(path).doesNotStartWith("/api/"));
        var operations = List.of(paths.path("/availability").path("get"), paths.path("/bookings").path("post"),
                paths.path("/bookings").path("get"), paths.path("/bookings/{bookingId}").path("get"),
                paths.path("/bookings/{bookingId}/payments").path("post"));
        for (var operation : List.of(paths.path("/payments/vnpay/return").path("get"),
                paths.path("/payments/vnpay/ipn").path("get"))) {
            assertThat(operation.path("security").isEmpty()).isTrue();
        }
        assertThat(operations).allSatisfy(op -> assertThat(op.path("security").get(0).has("Bearer Authentication")).isTrue());
        assertThat(operations).allSatisfy(op -> {
            assertThat(op.path("summary").asText()).isNotBlank();
            assertThat(op.path("responses").has("403")).isTrue();
        });
        assertThat(paths.path("/availability").path("get").path("responses").fieldNames())
                .toIterable().contains("200", "400", "404");
        assertThat(paths.path("/bookings").path("post").path("responses").fieldNames())
                .toIterable().contains("201", "400", "404", "409");
        assertThat(paths.path("/bookings/{bookingId}/payments").path("post").path("responses").fieldNames())
                .toIterable().contains("201", "400", "404", "409", "502", "503");
        assertThat(paths.path("/payments/vnpay/return").path("get").path("responses").fieldNames())
                .toIterable().contains("200", "400", "503");
        String ackRef = paths.path("/payments/vnpay/ipn").path("get").path("responses").path("200")
                .path("content").path("application/json").path("schema").path("$ref").asText();
        assertThat(ackRef).isEqualTo("#/components/schemas/VnPayIpnAcknowledgment");
        JsonNode schemas = document.path("components").path("schemas");
        assertThat(schemas.path("CreatePaymentAttemptRequest").path("properties").path("paymentMethod").path("enum"))
                .containsExactly(mapper.getNodeFactory().textNode("VNPAY"));
        assertThat(schemas.path("VnPayIpnAcknowledgment").path("properties").fieldNames()).toIterable()
                .containsExactlyInAnyOrder("RspCode", "Message");
        assertThat(schemas.has("BookingHistoryResponse")).isTrue();
        assertThat(schemas.has("BookingHistoryDetailResponse")).isTrue();
        assertThat(schemas.path("BookingHistoryDetailResponse").path("properties").path("payments")
                .path("items").path("$ref").asText()).isEqualTo("#/components/schemas/BookingPaymentHistory");
        assertThat(schemas.path("BookingPaymentHistory").path("properties").path("paymentStatus").path("enum").toString())
                .isEqualTo("[\"PENDING\",\"SUCCESS\",\"FAILED\",\"REFUNDED\"]");
        assertThat(paths.path("/bookings").path("get").path("description").asText()).contains("ownership is enforced");
        JsonNode parameters = paths.path("/bookings").path("get").path("parameters");
        assertThat(parameters).anySatisfy(parameter -> {
            assertThat(parameter.path("name").asText()).isEqualTo("customerId");
            assertThat(parameter.path("required").asBoolean()).isFalse();
        });
        assertThat(parameters).anySatisfy(parameter -> {
            assertThat(parameter.path("name").asText()).isEqualTo("size");
            assertThat(parameter.path("schema").path("maximum").asInt()).isEqualTo(100);
        });
        String errorRef = paths.path("/bookings/{bookingId}").path("get").path("responses").path("404")
                .path("content").path("*/*").path("schema").path("$ref").asText();
        assertThat(errorRef).isEqualTo("#/components/schemas/ApiResponse");
        assertThat(paths.path("/payments/vnpay/return").path("get").path("description").asText())
                .contains("never reads or writes the database");
        assertThat(paths.path("/bookings/{bookingId}/payments").path("post").path("description").asText())
                .contains("VNPAY only", "checkoutReady=true", "Unsupported methods return 400 before persistence")
                .doesNotContain("checkout blocker");
    }

    private UUID booking(UUID customer, BookingStatus status, LocalDateTime created) {
        UUID id = UUID.randomUUID(); insertBooking(id, customer, status, created); return id;
    }

    @Test
    void ownerFilterChainRejectsAnonymousHistoryAndDetailRequests() throws Exception {
        UUID id = booking(customerA, BookingStatus.PAID, NOW);
        mvc.perform(get("/api/bookings").contextPath("/api").param("customerId", customerA.toString())
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.data").doesNotExist());
        mvc.perform(get("/api/bookings/" + id).contextPath("/api")
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void everyApplicationBookingOperationRejectsAnonymousRequestsBeforeBusinessValidation() throws Exception {
        var anonymous = org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous();
        for (var request : List.of(
                get("/api/availability"),
                get("/api/bookings"),
                get("/api/bookings/" + UUID.randomUUID()),
                post("/api/bookings").contentType(MediaType.APPLICATION_JSON).content("{}"),
                post("/api/bookings/" + UUID.randomUUID() + "/payments")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))) {
            mvc.perform(request.contextPath("/api").with(anonymous))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.data").doesNotExist());
        }
    }

    @Test
    void ownerJwtFilterAuthenticatesDatabaseUserAndResolvesDistinctCustomerId() throws Exception {
        UUID id = booking(customerA, BookingStatus.PAID, NOW);
        String token = jwt.generateAccessToken(userDetails.loadUserByUsername(userA + "@history.test"));
        mvc.perform(get("/api/bookings/" + id).contextPath("/api")
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.bookingId").value(id.toString()));
    }
    @Test
    void realJwtCannotSpoofCustomerOrReadForeignPaymentData() throws Exception {
        UUID own = booking(customerA, BookingStatus.PAID, NOW);
        UUID foreign = booking(customerB, BookingStatus.PAID, NOW);
        payment(foreign, "VNPAY", "SUCCESS", NOW, "jwt-private-" + foreign);
        String token = jwt.generateAccessToken(userDetails.loadUserByUsername(userA + "@history.test"));
        var anonymous = org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous();
        mvc.perform(get("/api/bookings").contextPath("/api").with(anonymous).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].bookingId").value(own.toString()));
        mvc.perform(get("/api/bookings").contextPath("/api").with(anonymous).header("Authorization", "Bearer " + token)
                        .param("customerId", customerB.toString()))
                .andExpect(status().isForbidden());
        String body = mvc.perform(get("/api/bookings/" + foreign).contextPath("/api").with(anonymous)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.data").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("jwt-private-", "11.06", "VNPAY", "transactionDate");
    }

    private void authenticate(UUID userId) {
        var principal = userDetails.loadUserByUsername(userId + "@history.test");
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
        mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(webContext)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity())
                .defaultRequest(get("/").with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user(principal)))
                .build();
    }

    @Test
    void spoofedCustomerFilterIsForbiddenAndForeignBookingNeverLeaksPayments() throws Exception {
        UUID foreign = booking(customerB, BookingStatus.PAID, NOW);
        addDetail(foreign, LocalDate.of(2026, 10, 5));
        payment(foreign, "VNPAY", "SUCCESS", NOW, "private-transaction-" + foreign);
        mvc.perform(get("/api/bookings").contextPath("/api").param("customerId", customerB.toString()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.data").doesNotExist());
        String body = mvc.perform(get("/api/bookings/" + foreign).contextPath("/api"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.data").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("private-transaction-", "VNPAY", "11.06", "paymentStatus", "detailId");
    }

    @Test
    void trustedCustomerCannotReadAnotherCustomersExpiredHeaderOrPayment() throws Exception {
        UUID expired = booking(customerA, BookingStatus.EXPIRED, NOW);
        payment(expired, "VNPAY", "PENDING", NOW, null);
        assertThat(history.detail(expired).details()).isEmpty();
        assertThat(history.detail(expired).payments()).hasSize(1);
        authenticate(userB);
        mvc.perform(get("/api/bookings/" + expired).contextPath("/api"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void absentCustomerFilterUsesOnlyAuthenticatedCustomer() throws Exception {
        UUID own = booking(customerA, BookingStatus.PAID, NOW);
        booking(customerA, BookingStatus.PENDING, NOW.minusDays(1));
        booking(customerB, BookingStatus.PAID, NOW.plusDays(1));
        mvc.perform(get("/api/bookings").contextPath("/api").param("status", "PAID").param("size", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].bookingId").value(own.toString()));
    }

    private void insertBooking(UUID id, UUID customer, BookingStatus status, LocalDateTime created) {
        jdbc.update("INSERT INTO bookings(id,customer_id,status,court_fee,expires_at,created_at,updated_at) VALUES (?,?,?,11.06,?,?,?)",
                id, customer, status.name(), NOW.plusMinutes(5), created, created);
    }
    private void addDetail(UUID id, LocalDate date) {
        jdbc.update("INSERT INTO booking_details(booking_id,court_id,time_slot_id,booking_date,price) VALUES (?,?,?,?,11.06)", id, court, slot, date);
    }
    private UUID payment(UUID booking, String method, String status, LocalDateTime created, String transaction) {
        UUID id = UUID.randomUUID(); insertPayment(id, booking, method, status, created, transaction); return id;
    }
    private void insertPayment(UUID id, UUID booking, String method, String status, LocalDateTime created, String transaction) {
        jdbc.update("INSERT INTO payment_transactions(id,booking_id,payment_method,status,amount,created_at,transaction_id,transaction_date) "
                + "VALUES (?,?,?,?,11.06,?,?,?)", id, booking, method, status, created, transaction, transaction == null ? null : NOW);
    }

    @TestConfiguration
    static class SnapshotProbe {
        private static final ThreadLocal<Runnable> beforeDetailRead = new ThreadLocal<>();

        @Bean
        HibernatePropertiesCustomizer snapshotInspector() {
            return properties -> properties.put("hibernate.session_factory.statement_inspector", (StatementInspector) sql -> {
                if (sql.startsWith("select") && sql.contains("from booking_details") && beforeDetailRead.get() != null) {
                    Runnable action = beforeDetailRead.get();
                    beforeDetailRead.remove();
                    action.run();
                }
                return sql;
            });
        }
    }
}
