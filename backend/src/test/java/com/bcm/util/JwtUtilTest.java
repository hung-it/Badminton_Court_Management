package com.bcm.util;

import com.bcm.security.UserPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JwtUtilTest {

    private JwtUtil jwtUtil;
    private static final String TEST_SECRET = "dGhpc2lzYXZlcnlzZWN1cmVzZWNyZXRrZXlmb3JiYWRtaW50b25jb3VydG1hbmFnZW1lbnRzeXN0ZW0xMjM0NTY3ODkw";

    @BeforeEach
    void setUp() {
        jwtUtil = new JwtUtil();
        ReflectionTestUtils.setField(jwtUtil, "secret", TEST_SECRET);
        ReflectionTestUtils.setField(jwtUtil, "accessTokenExpiration", 3600000L);
        ReflectionTestUtils.setField(jwtUtil, "refreshTokenExpiration", 604800000L);
    }

    @Test
    @DisplayName("Access token contains typ=ACCESS, uid, and roles claims")
    void accessTokenClaimsAreCorrectlyEmbedded() {
        String userId = UUID.randomUUID().toString();
        String email = "test@example.com";
        UserPrincipal principal = new UserPrincipal(
                userId,
                email,
                "password",
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"), new SimpleGrantedAuthority("ROLE_STAFF")),
                true
        );

        String token = jwtUtil.generateAccessToken(principal);

        assertThat(token).isNotBlank();
        assertThat(jwtUtil.extractEmail(token)).isEqualTo(email);
        assertThat(jwtUtil.extractTokenType(token)).isEqualTo(JwtUtil.TOKEN_TYPE_ACCESS);
        assertThat(jwtUtil.extractUserId(token)).isEqualTo(userId);
        assertThat(jwtUtil.extractRoles(token)).containsExactlyInAnyOrder("ROLE_ADMIN", "ROLE_STAFF");
        assertThat(jwtUtil.isAccessToken(token)).isTrue();
        assertThat(jwtUtil.isRefreshToken(token)).isFalse();
    }

    @Test
    @DisplayName("Refresh token contains typ=REFRESH claim")
    void refreshTokenClaimsAreCorrectlyEmbedded() {
        String email = "refresh@example.com";
        String token = jwtUtil.generateRefreshToken(email);

        assertThat(token).isNotBlank();
        assertThat(jwtUtil.extractEmail(token)).isEqualTo(email);
        assertThat(jwtUtil.extractTokenType(token)).isEqualTo(JwtUtil.TOKEN_TYPE_REFRESH);
        assertThat(jwtUtil.isRefreshToken(token)).isTrue();
        assertThat(jwtUtil.isAccessToken(token)).isFalse();
        assertThat(jwtUtil.extractUserId(token)).isNull();
    }

    @Test
    @DisplayName("Token type separation prevents type confusion")
    void tokenTypeConfusionIsPrevented() {
        String email = "user@example.com";
        String refreshToken = jwtUtil.generateRefreshToken(email);
        UserPrincipal principal = new UserPrincipal(
                UUID.randomUUID().toString(),
                email,
                "password",
                List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER")),
                true
        );
        String accessToken = jwtUtil.generateAccessToken(principal);

        // Refresh token cannot be accepted as access token
        assertThat(jwtUtil.isAccessToken(refreshToken)).isFalse();
        // Access token cannot be accepted as refresh token
        assertThat(jwtUtil.isRefreshToken(accessToken)).isFalse();
    }
}
