package com.bcm.service;

import com.bcm.dto.request.ChangePasswordRequest;
import com.bcm.dto.request.LoginRequest;
import com.bcm.dto.request.RegisterRequest;
import com.bcm.security.UserPrincipal;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

@SpringBootTest(properties={"booking.auto-checkout.enabled=false","booking.expiration-enabled=false","bcm.seed.enabled=false"})
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named="BCM_TEST_DB_URL",matches="jdbc:postgresql:.*")
class PasswordChangePostgresTest {
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",()->System.getenv("BCM_TEST_DB_URL"));
        r.add("spring.datasource.username",()->System.getenv("BCM_TEST_DB_USERNAME"));
        r.add("spring.datasource.password",()->System.getenv("BCM_TEST_DB_PASSWORD"));
    }
    @Autowired UserService users;
    @Autowired AuthService auth;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired com.fasterxml.jackson.databind.ObjectMapper mapper;
    @Autowired org.springframework.security.crypto.password.PasswordEncoder encoder;
    String email,id;
    @BeforeEach void setup() {
        email=UUID.randomUUID()+"@password.test";
        id=auth.register(RegisterRequest.builder().email(email).password("Original123!").fullName("Password test").phone("0900000000").build()).getUser().getId();
    }
    @AfterEach void cleanup() {
        jdbc.update("DELETE FROM customers WHERE user_id=?",UUID.fromString(id));
        jdbc.update("DELETE FROM user_roles WHERE user_id=?",UUID.fromString(id));
        jdbc.update("DELETE FROM users WHERE id=?",UUID.fromString(id));
    }
    UserPrincipal principal(String role){return new UserPrincipal(id,email,"unused",List.of(new SimpleGrantedAuthority("ROLE_"+role)),true);}
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"CUSTOMER","STAFF"})
    void selfServiceChangesHashAndLoginForBothRoles(String role) throws Exception {
        mvc.perform(post("/api/users/me/password").contextPath("/api").with(user(principal(role)))
                .contentType("application/json").content(mapper.writeValueAsBytes(new ChangePasswordRequest("Original123!","Updated123!"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true)).andExpect(jsonPath("$.data").doesNotExist());
        String hash=jdbc.queryForObject("SELECT password_hash FROM users WHERE id=?",String.class,UUID.fromString(id));
        assertThat(hash).isNotEqualTo("Updated123!");assertThat(encoder.matches("Updated123!",hash)).isTrue();
        assertThatThrownBy(()->auth.login(new LoginRequest(email,"Original123!"))).isInstanceOf(org.springframework.security.core.AuthenticationException.class);
        assertThat(auth.login(new LoginRequest(email,"Updated123!")).getAccessToken()).isNotBlank();
    }
    @Test void wrongOldSameNewShortAndAnonymousAreRejected() throws Exception {
        var oldHash=jdbc.queryForObject("SELECT password_hash FROM users WHERE id=?",String.class,UUID.fromString(id));
        assertThatThrownBy(()->users.changePassword(id,new ChangePasswordRequest("wrong","Updated123!"))).hasMessageContaining("hiện tại");
        assertThatThrownBy(()->users.changePassword(id,new ChangePasswordRequest("Original123!","Original123!"))).hasMessageContaining("khác");
        assertThatThrownBy(()->users.changePassword(id,new ChangePasswordRequest("Original123!","á".repeat(40)))).hasMessageContaining("72 byte");
        mvc.perform(post("/api/users/me/password").contextPath("/api").contentType("application/json").content("{}"))
            .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/users/me/password").contextPath("/api").with(user(principal("CUSTOMER")))
            .contentType("application/json").content(mapper.writeValueAsBytes(new ChangePasswordRequest("Original123!","short"))))
            .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT password_hash FROM users WHERE id=?",String.class,UUID.fromString(id))).isEqualTo(oldHash);
    }
    @Test void concurrentChangesWithSameOldPasswordOnlyOneSucceeds() throws Exception {
        var pool=Executors.newFixedThreadPool(2);var ready=new CountDownLatch(2);var start=new CountDownLatch(1);
        Callable<Boolean> change=()->{ready.countDown();start.await();try{users.changePassword(id,new ChangePasswordRequest("Original123!","Updated123!"));return true;}catch(com.bcm.exception.BadRequestException ex){return false;}};
        try{var a=pool.submit(change);var b=pool.submit(change);assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue();start.countDown();assertThat(List.of(a.get(30,TimeUnit.SECONDS),b.get(30,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);}
        finally{start.countDown();pool.shutdownNow();}
    }

    @Test void realJwtCanOnlyChangeItsOwnerEvenIfBodyNamesAnotherUser() throws Exception {
        var other=auth.register(RegisterRequest.builder().email(UUID.randomUUID()+"@password.test").password("Victim123!").fullName("Other account").phone("0900000001").build());
        UUID otherId=UUID.fromString(other.getUser().getId());
        try {
            var token=auth.login(new LoginRequest(email,"Original123!")).getAccessToken();
            mvc.perform(post("/api/users/me/password").contextPath("/api").header("Authorization","Bearer "+token)
                .contentType("application/json").content(mapper.writeValueAsBytes(Map.of("currentPassword","Original123!","newPassword","Updated123!","userId",otherId))))
                .andExpect(status().isOk());
            assertThat(auth.login(new LoginRequest(email,"Updated123!")).getAccessToken()).isNotBlank();
            assertThat(encoder.matches("Victim123!",jdbc.queryForObject("SELECT password_hash FROM users WHERE id=?",String.class,otherId))).isTrue();
        } finally {
            jdbc.update("DELETE FROM customers WHERE user_id=?",otherId);
            jdbc.update("DELETE FROM user_roles WHERE user_id=?",otherId);
            jdbc.update("DELETE FROM users WHERE id=?",otherId);
        }
    }
}
