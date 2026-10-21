package com.bcm.service;

import com.bcm.dto.request.RegisterRequest;
import com.bcm.entity.Customer;
import com.bcm.repository.CustomerRepository;
import com.bcm.repository.UserRepository;
import com.bcm.security.CustomUserDetailsService;
import com.bcm.security.CurrentCustomerService;
import com.bcm.util.JwtUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import java.util.Objects;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"booking.payment.vnpay.tmn-code=FAKE_TEST_MERCHANT",
        "booking.payment.vnpay.hash-secret=fake-test-secret"})
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "BCM_TEST_DB_URL", matches = "jdbc:postgresql:.*")
class CustomerAuthPostgresTest {
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("BCM_TEST_DB_URL"));
        registry.add("spring.datasource.username", () -> Objects.requireNonNull(System.getenv("BCM_TEST_DB_USERNAME")));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("BCM_TEST_DB_PASSWORD", ""));
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired AuthService auth;
    @Autowired CustomerRepository customers;
    @Autowired UserRepository users;
    @Autowired CurrentCustomerService resolver;
    @Autowired CustomUserDetailsService principals;
    @Autowired JwtUtil jwt;
    private String email;
    @BeforeEach void prepare() { email = UUID.randomUUID() + "@phase6-auth.test"; }
    @AfterEach void cleanup() {
        SecurityContextHolder.clearContext();
        jdbc.execute("DROP TRIGGER IF EXISTS phase6_customer_failure ON customers");
        jdbc.execute("DROP FUNCTION IF EXISTS phase6_customer_failure()");
        jdbc.update("DELETE FROM users WHERE email=?", email);
    }
    private RegisterRequest request() {
        return RegisterRequest.builder().email(email).password("test-only-password").fullName("Customer Test")
                .phone("0901234567").address("Test address").build();
    }
    private String register() {
        return auth.register(request()).getUser().getId();
    }
    @Test void customerRegistrationPersistsUserRoleAndDistinctCustomerAtomically() throws Exception {
        String response = mvc.perform(post("/api/auth/register").contextPath("/api").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(request())))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID userId = UUID.fromString(mapper.readTree(response).path("data").path("user").path("id").asText());
        var customer = jdbc.queryForMap("SELECT * FROM customers WHERE user_id=?", userId);
        assertThat(customer.get("id")).isNotEqualTo(userId);
        assertThat(customer).containsEntry("full_name", "Customer Test").containsEntry("phone", "0901234567")
                .containsEntry("address", "Test address");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM customers WHERE user_id=?", Integer.class, userId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_roles ur JOIN roles r ON r.id=ur.role_id WHERE ur.user_id=? AND r.role_name='CUSTOMER'", Integer.class, userId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT created_at FROM user_roles WHERE user_id=?", java.time.LocalDateTime.class, userId)).isNotNull();
        var principal = principals.loadUserByUsername(email);
        assertThat(principal).isInstanceOf(com.bcm.security.UserPrincipal.class);
        assertThat(principal.getAuthorities()).extracting(authority -> authority.getAuthority())
                .containsExactly("ROLE_CUSTOMER");
        String token = mapper.readTree(response).path("data").path("accessToken").asText();
        mvc.perform(get("/api/bookings").contextPath("/api").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.content").isEmpty());
    }
    @Test void duplicateEmailIs409WithoutExtraCustomer() throws Exception {
        UUID userId = UUID.fromString(register());
        mvc.perform(post("/api/auth/register").contextPath("/api").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(request())))
                .andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM customers WHERE user_id=?", Integer.class, userId)).isEqualTo(1);
    }
    @Test void customerUserIdUniqueConstraintRejectsDuplicateProfile() {
        UUID userId = UUID.fromString(register());
        Customer duplicate = new Customer();
        duplicate.setUser(users.findById(userId).orElseThrow());
        duplicate.setFullName("Second Profile"); duplicate.setPhone("0900000000");
        assertThatThrownBy(() -> customers.saveAndFlush(duplicate)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM customers WHERE user_id=?", Integer.class, userId)).isEqualTo(1);
    }
    @Test void realCustomerInsertFailureRollsBackUserAndRoleAssociation() {
        jdbc.execute("CREATE FUNCTION phase6_customer_failure() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.full_name='Reject Profile' THEN RAISE EXCEPTION 'test profile rejection' USING ERRCODE='23514'; END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER phase6_customer_failure BEFORE INSERT ON customers FOR EACH ROW EXECUTE FUNCTION phase6_customer_failure()");
        var registration = request(); registration.setFullName("Reject Profile");
        int beforeRoles = jdbc.queryForObject("SELECT count(*) FROM user_roles", Integer.class);
        assertThatThrownBy(() -> auth.register(registration)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE email=?", Integer.class, email)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_roles", Integer.class)).isEqualTo(beforeRoles);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM customers WHERE full_name='Reject Profile'", Integer.class)).isZero();
    }
    @Test void requiredProfileValidationRejectsRegistrationBeforePersistence() throws Exception {
        for (String field : new String[]{"fullName", "phone"}) {
            var json = mapper.valueToTree(request()); ((com.fasterxml.jackson.databind.node.ObjectNode) json).put(field, "");
            mvc.perform(post("/api/auth/register").contextPath("/api").contentType(MediaType.APPLICATION_JSON).content(json.toString()))
                    .andExpect(status().isBadRequest());
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE email=?", Integer.class, email)).isZero();
    }
    @Test void legacyMissingCustomerIs403AndDoesNotCreateProfileOnRead() throws Exception {
        UUID userId = UUID.fromString(register());
        jdbc.update("DELETE FROM customers WHERE user_id=?", userId);
        String token = jwt.generateAccessToken(principals.loadUserByUsername(email));
        mvc.perform(get("/api/bookings").contextPath("/api").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.data").doesNotExist());
        mvc.perform(get("/api/bookings/" + UUID.randomUUID()).contextPath("/api").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM customers WHERE user_id=?", Integer.class, userId)).isZero();
    }
    @Test void softDeletedCustomerIsNotResolved() {
        UUID userId = UUID.fromString(register());
        jdbc.update("UPDATE customers SET deleted_at=now() WHERE user_id=?", userId);
        var principal = principals.loadUserByUsername(email);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
        assertThatThrownBy(resolver::requireCustomerId).isInstanceOf(AccessDeniedException.class);
    }
    @Test void unauthenticatedProviderRequestsReachCryptographicVerifier() throws Exception {
        mvc.perform(get("/api/payments/vnpay/ipn").contextPath("/api").param("vnp_SecureHash", "invalid"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.RspCode").value("97"));
        mvc.perform(get("/api/payments/vnpay/return").contextPath("/api").param("vnp_SecureHash", "invalid"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.success").value(false));
    }
    @Test void publicDocumentationAndAuthRoutesRemainReachable() throws Exception {
        mvc.perform(get("/api/api-docs").contextPath("/api")).andExpect(status().isOk());
        mvc.perform(get("/api/swagger-ui.html").contextPath("/api")).andExpect(status().is3xxRedirection());
        mvc.perform(get("/api/swagger-ui/index.html").contextPath("/api")).andExpect(status().isOk());
        mvc.perform(post("/api/auth/login").contextPath("/api").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }
    @Test void adminAndStaffCannotUseCustomerHistoryPolicy() throws Exception {
        UUID userId = UUID.fromString(register());
        for (String role : new String[]{"ADMIN", "STAFF"}) {
            jdbc.update("DELETE FROM user_roles WHERE user_id=?", userId);
            jdbc.update("INSERT INTO user_roles(user_id,role_id) SELECT ?,id FROM roles WHERE role_name=?", userId, role);
            String token = jwt.generateAccessToken(principals.loadUserByUsername(email));
            mvc.perform(get("/api/bookings").contextPath("/api").header("Authorization", "Bearer " + token))
                    .andExpect(status().isForbidden());
            mvc.perform(get("/api/bookings/" + UUID.randomUUID()).contextPath("/api").header("Authorization", "Bearer " + token))
                    .andExpect(status().isForbidden());
        }
    }
    @Test void registrationOpenApiDocumentsRequiredProfileAndActualStatuses() throws Exception {
        String body = mvc.perform(get("/api/api-docs").contextPath("/api")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        var document = mapper.readTree(body);
        assertThat(document.path("components").path("schemas").path("RegisterRequest").path("required").toString())
                .contains("fullName", "phone", "email", "password");
        var operation = document.path("paths").path("/auth/register").path("post");
        assertThat(operation.path("security").isEmpty()).isTrue();
        assertThat(operation.path("responses").fieldNames()).toIterable().contains("201", "400", "409");
    }
    @Test void publicProvidersDoNotOpenBookingOrPaymentInitiationRoutes() throws Exception {
        mvc.perform(get("/api/bookings").contextPath("/api")).andExpect(status().isForbidden());
        mvc.perform(get("/api/bookings/" + UUID.randomUUID()).contextPath("/api")).andExpect(status().isForbidden());
        mvc.perform(post("/api/bookings").contextPath("/api").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/bookings/" + UUID.randomUUID() + "/payments").contextPath("/api").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/payments/vnpay/ipn").contextPath("/api"))
                .andExpect(status().isForbidden());
    }
}
