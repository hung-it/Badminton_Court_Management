package com.bcm.service;

import com.bcm.config.BookingPaymentConfig;
import com.bcm.repository.CourtRepository;
import com.bcm.security.CustomUserDetailsService;
import com.bcm.util.JwtUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real JWT filter, DB-backed principal/customer resolution and committed PostgreSQL transactions. */
@SpringBootTest(properties = {
        "booking.expiration-enabled=false",
        "booking.payment.vnpay.tmn-code=TEST1234",
        "booking.payment.vnpay.hash-secret=ownership-test-only",
        "booking.payment.vnpay.return-url=https://merchant.example/return"
})
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "BCM_TEST_DB_URL", matches = "jdbc:postgresql:.*")
class BookingWriteAuthorizationPostgresTest {
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("BCM_TEST_DB_URL"));
        registry.add("spring.datasource.username", () -> Objects.requireNonNull(System.getenv("BCM_TEST_DB_USERNAME")));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("BCM_TEST_DB_PASSWORD", ""));
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JwtUtil jwt;
    @Autowired CustomUserDetailsService users;
    @Autowired AvailabilityService availability;
    @Autowired BookingPaymentConfig config;
    @SpyBean CourtRepository courts;
    private UUID userA, userB, customerA, customerB, court, slot;
    private String tokenA;

    @BeforeEach
    void fixtures() {
        userA = UUID.randomUUID(); userB = UUID.randomUUID();
        customerA = UUID.randomUUID(); customerB = UUID.randomUUID();
        court = UUID.randomUUID(); slot = UUID.randomUUID();
        for (var pair : List.of(List.of(userA, customerA), List.of(userB, customerB))) {
            jdbc.update("INSERT INTO users(id,email,password_hash,full_name,phone) VALUES (?,?,?,'Write auth','0900000000')",
                    pair.get(0), pair.get(0) + "@write-auth.test", "test-only");
            jdbc.update("INSERT INTO customers(id,user_id,full_name,phone) VALUES (?,?,'Write auth','0900000000')",
                    pair.get(1), pair.get(0));
            jdbc.update("INSERT INTO user_roles(user_id,role_id) SELECT ?,id FROM roles WHERE role_name='CUSTOMER'", pair.get(0));
        }
        jdbc.update("INSERT INTO courts(id,court_number,name,base_price) VALUES (?,999701,'Write auth',10.05)", court);
        jdbc.update("INSERT INTO time_slots(id,start_time,end_time,price_multiplier) VALUES (?,'00:00','01:00',1.10)", slot);
        tokenA = token(userA);
        assertThat(customerA).isNotEqualTo(userA);
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM payment_transactions WHERE booking_id IN (SELECT id FROM bookings WHERE customer_id IN (?,?))", customerA, customerB);
        jdbc.update("DELETE FROM bookings WHERE customer_id IN (?,?)", customerA, customerB);
        jdbc.update("DELETE FROM courts WHERE id=?", court);
        jdbc.update("DELETE FROM time_slots WHERE id=?", slot);
        jdbc.update("DELETE FROM customers WHERE id IN (?,?)", customerA, customerB);
        jdbc.update("DELETE FROM users WHERE id IN (?,?)", userA, userB);
    }

    @Test
    void customerCreatesOwnPendingBookingWithNormalPricingAndDetails() throws Exception {
        var result = create(customerA).andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.courtFee").value(11.06))
                .andExpect(jsonPath("$.data.expiresAt").exists())
                .andExpect(jsonPath("$.data.details.length()").value(1)).andReturn();
        UUID id = UUID.fromString(mapper.readTree(result.getResponse().getContentAsString()).path("data").path("bookingId").asText());
        assertThat(jdbc.queryForObject("SELECT customer_id FROM bookings WHERE id=?", UUID.class, id)).isEqualTo(customerA);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM booking_details WHERE booking_id=?", Integer.class, id)).isEqualTo(1);
    }

    @Test
    void foreignCustomerCreateIsForbiddenBeforeCourtLocksAndCommitsNothing() throws Exception {
        create(customerB).andExpect(status().isForbidden()).andExpect(jsonPath("$.data").doesNotExist());
        verify(courts, never()).findAllForBookingWithLock(any());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bookings WHERE customer_id IN (?,?)", Integer.class, customerA, customerB)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM booking_details WHERE court_id=?", Integer.class, court)).isZero();
        assertThat(availability.getAvailability(java.time.LocalDate.of(2027, 4, 15), court).getCourts().get(0).getSlots())
                .allMatch(s -> s.isAvailable());
    }

    @Test
    void ownerInitiatesAndReusesSamePendingPayment() throws Exception {
        UUID booking = booking(customerA);
        var first = payment(booking).andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.amount").value(11.06))
                .andExpect(jsonPath("$.data.gatewayPreparation.checkoutReady").value(true)).andReturn();
        String attempt = mapper.readTree(first.getResponse().getContentAsString()).path("data").path("paymentAttemptId").asText();
        payment(booking).andExpect(status().isCreated()).andExpect(jsonPath("$.data.paymentAttemptId").value(attempt));
        assertThat(jdbc.queryForObject("SELECT booking_id FROM payment_transactions WHERE id=?", UUID.class, UUID.fromString(attempt))).isEqualTo(booking);
        assertThat(paymentCount(booking)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM bookings WHERE id=?", String.class, booking)).isEqualTo("PENDING");
    }

    @Test
    void foreignPaymentCreateIsForbiddenAndBookingUnchanged() throws Exception {
        UUID booking = booking(customerB);
        var before = jdbc.queryForMap("SELECT * FROM bookings WHERE id=?", booking);
        payment(booking).andExpect(status().isForbidden()).andExpect(jsonPath("$.data").doesNotExist());
        assertThat(paymentCount(booking)).isZero();
        assertThat(jdbc.queryForMap("SELECT * FROM bookings WHERE id=?", booking)).isEqualTo(before);
    }

    @Test
    void foreignPendingPaymentCannotBeReusedOrExposed() throws Exception {
        UUID booking = booking(customerB), attempt = UUID.randomUUID();
        jdbc.update("INSERT INTO payment_transactions(id,booking_id,payment_method,status,amount) VALUES (?,?,'VNPAY','PENDING',11.06)", attempt, booking);
        var before = jdbc.queryForMap("SELECT * FROM payment_transactions WHERE id=?", attempt);
        var header = jdbc.queryForMap("SELECT * FROM bookings WHERE id=?", booking);
        String body = payment(booking).andExpect(status().isForbidden()).andExpect(jsonPath("$.data").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain(attempt.toString(), "11.06", "paymentAttemptId", "amount", "checkoutUrl", "checkoutReady", "gatewayPreparation", "transactionId");
        assertThat(paymentCount(booking)).isEqualTo(1);
        assertThat(jdbc.queryForMap("SELECT * FROM payment_transactions WHERE id=?", attempt)).isEqualTo(before);
        assertThat(jdbc.queryForMap("SELECT * FROM bookings WHERE id=?", booking)).isEqualTo(header);
    }

    @Test
    void missingBookingStillReturns404() throws Exception {
        payment(UUID.randomUUID()).andExpect(status().isNotFound());
    }

    @Test
    void foreignBookingReturns403EvenWithoutGatewayConfiguration() throws Exception {
        String merchant = config.getVnpay().getTmnCode();
        try {
            config.getVnpay().setTmnCode("");
            payment(booking(customerB)).andExpect(status().isForbidden());
            payment(booking(customerA)).andExpect(status().isServiceUnavailable());
            assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_transactions WHERE booking_id IN (SELECT id FROM bookings WHERE customer_id IN (?,?))", Integer.class, customerA, customerB)).isZero();
        } finally { config.getVnpay().setTmnCode(merchant); }
    }

    @Test
    void anonymousWritesRetain403() throws Exception {
        mvc.perform(post("/api/bookings").contextPath("/api").contentType(MediaType.APPLICATION_JSON).content(createBody(customerA)))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/bookings/" + booking(customerA) + "/payments").contextPath("/api")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"paymentMethod\":\"VNPAY\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminAndStaffAreNotGrantedCustomerWritePrivileges() throws Exception {
        UUID booking = booking(customerA);
        for (String role : List.of("ADMIN", "STAFF")) {
            jdbc.update("DELETE FROM user_roles WHERE user_id=?", userA);
            jdbc.update("INSERT INTO user_roles(user_id,role_id) SELECT ?,id FROM roles WHERE role_name=?", userA, role);
            tokenA = token(userA);
            create(customerA).andExpect(status().isForbidden());
            payment(booking).andExpect(status().isForbidden());
        }
        assertThat(paymentCount(booking)).isZero();
    }

    @Test
    void swaggerDescribesBothOwnershipChecksWithoutChangingRoutes() throws Exception {
        var response = mvc.perform(get("/api/api-docs").contextPath("/api")).andExpect(status().isOk()).andReturn();
        var paths = mapper.readTree(response.getResponse().getContentAsString()).path("paths");
        assertThat(paths.path("/bookings").path("post").path("responses").path("403").path("description").asText()).contains("customerId mismatch");
        assertThat(paths.path("/bookings/{bookingId}/payments").path("post").path("responses").path("403").path("description").asText()).contains("foreign booking");
    }

    private String token(UUID user) {
        return jwt.generateAccessToken(users.loadUserByUsername(user + "@write-auth.test"));
    }
    private String createBody(UUID customer) {
        return "{\"customerId\":\"" + customer + "\",\"details\":[{\"bookingDate\":\"2027-04-15\",\"courtId\":\"" + court
                + "\",\"timeSlotId\":\"" + slot + "\"}]}";
    }
    private ResultActions create(UUID customer) throws Exception {
        return mvc.perform(post("/api/bookings").contextPath("/api").header("Authorization", "Bearer " + tokenA)
                .contentType(MediaType.APPLICATION_JSON).content(createBody(customer)));
    }
    private ResultActions payment(UUID booking) throws Exception {
        return mvc.perform(post("/api/bookings/" + booking + "/payments").contextPath("/api").header("Authorization", "Bearer " + tokenA)
                .contentType(MediaType.APPLICATION_JSON).content("{\"paymentMethod\":\"VNPAY\"}"));
    }
    private UUID booking(UUID customer) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO bookings(id,customer_id,status,court_fee,expires_at) VALUES (?,?,'PENDING',11.06,?)", id, customer, LocalDateTime.now().plusMinutes(15));
        return id;
    }
    private int paymentCount(UUID booking) {
        return jdbc.queryForObject("SELECT count(*) FROM payment_transactions WHERE booking_id=?", Integer.class, booking);
    }
}
