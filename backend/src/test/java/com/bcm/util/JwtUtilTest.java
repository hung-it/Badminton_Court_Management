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
    @Test void accountVersionAndIdentityBindBothTokenTypes() {
        var principal=new UserPrincipal(UUID.randomUUID().toString(),"customer@test.invalid","hash",List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER")),true,0L);
        var tokens=List.of(jwtUtil.generateAccessToken(principal),jwtUtil.generateRefreshToken(principal));
        for(String token:tokens) assertThat(jwtUtil.validateToken(token,principal)).isTrue();
        principal.setTokenVersion(1L);
        for(String token:tokens) assertThat(jwtUtil.validateToken(token,principal)).isFalse();
        principal.setTokenVersion(0L);principal.setId(UUID.randomUUID().toString());
        for(String token:tokens) assertThat(jwtUtil.validateToken(token,principal)).isFalse();
        assertThat(jwtUtil.validateToken(jwtUtil.generateRefreshToken(principal.getUsername()),principal)).isFalse();
    }

    @Test void profileTimestampChangesDoNotInvalidateEitherTokenType() {
        var user=new com.bcm.entity.User();user.setId(UUID.randomUUID());user.setEmail("precision@test.invalid");user.setPasswordHash("hash");user.setRoles(java.util.Set.of());
        user.setUpdatedAt(java.time.LocalDateTime.parse("2026-11-11T12:00:00.123999700"));
        var tokens=List.of(jwtUtil.generateAccessToken(UserPrincipal.create(user)),jwtUtil.generateRefreshToken(UserPrincipal.create(user)));
        user.setFullName("Changed name");user.setAddress("Changed address");
        user.setUpdatedAt(java.time.LocalDateTime.parse("2026-12-12T14:10:09.124000"));
        for(String token:tokens)assertThat(jwtUtil.validateToken(token,UserPrincipal.create(user))).isTrue();
    }
    @Test void legacyTimestampVersionIsRejectedWithoutThrowing() {
        var principal=new UserPrincipal(UUID.randomUUID().toString(),"legacy@test.invalid","hash",List.of(),true,0L);
        String legacy=io.jsonwebtoken.Jwts.builder().subject(principal.getUsername())
                .claim("uid",principal.getId()).claim("ver","2026-11-11T12:00:00.123456")
                .expiration(new java.util.Date(System.currentTimeMillis()+60000))
                .signWith(io.jsonwebtoken.security.Keys.hmacShaKeyFor(io.jsonwebtoken.io.Decoders.BASE64.decode(TEST_SECRET))).compact();
        assertThat(jwtUtil.validateToken(legacy,principal)).isFalse();
    }
    @Test void unversionedJwtIsRejectedForExistingAccount() {
        var principal=new UserPrincipal(UUID.randomUUID().toString(),"unversioned@test.invalid","hash",List.of(),true,0L);
        for(String type:List.of(JwtUtil.TOKEN_TYPE_ACCESS,JwtUtil.TOKEN_TYPE_REFRESH)) {
            String legacy=io.jsonwebtoken.Jwts.builder().subject(principal.getUsername())
                    .claim("uid",principal.getId()).claim("typ",type)
                    .expiration(new java.util.Date(System.currentTimeMillis()+60000))
                    .signWith(io.jsonwebtoken.security.Keys.hmacShaKeyFor(io.jsonwebtoken.io.Decoders.BASE64.decode(TEST_SECRET))).compact();
            assertThat(jwtUtil.validateToken(legacy,principal)).isFalse();
        }
    }

    @Test void bigintEpochRetainsExactPrecision() {
        var principal=new UserPrincipal(UUID.randomUUID().toString(),"bigint@test.invalid","hash",List.of(),true,9007199254740993L);
        var tokens=List.of(jwtUtil.generateAccessToken(principal),jwtUtil.generateRefreshToken(principal));
        for(String token:tokens)assertThat(jwtUtil.validateToken(token,principal)).isTrue();
        principal.setTokenVersion(9007199254740994L);
        for(String token:tokens)assertThat(jwtUtil.validateToken(token,principal)).isFalse();
    }
}
