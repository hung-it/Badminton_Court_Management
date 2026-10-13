package com.bcm.security;

import com.bcm.util.JwtUtil;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class JwtAuthenticationFilterTest {

    private JwtUtil jwtUtil;
    private UserDetailsService userDetailsService;
    private JwtAuthenticationFilter filter;

    private static final String TEST_SECRET = "dGhpc2lzYXZlcnlzZWN1cmVzZWNyZXRrZXlmb3JiYWRtaW50b25jb3VydG1hbmFnZW1lbnRzeXN0ZW0xMjM0NTY3ODkw";

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();

        jwtUtil = new JwtUtil();
        ReflectionTestUtils.setField(jwtUtil, "secret", TEST_SECRET);
        ReflectionTestUtils.setField(jwtUtil, "accessTokenExpiration", 3600000L);
        ReflectionTestUtils.setField(jwtUtil, "refreshTokenExpiration", 604800000L);

        userDetailsService = mock(UserDetailsService.class);
        filter = new JwtAuthenticationFilter(jwtUtil, userDetailsService);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("Filter rejects Refresh Token presented in Authorization header")
    void filterRejectsRefreshTokenInAuthorizationHeader() throws Exception {
        String refreshToken = jwtUtil.generateRefreshToken("user@example.com");

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + refreshToken);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain filterChain = mock(FilterChain.class);

        filter.doFilter(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(filterChain).doFilter(request, response);
        verifyNoInteractions(userDetailsService);
    }

    @Test
    @DisplayName("Filter performs stateless authentication from token claims without hitting DB")
    void filterPerformsStatelessAuthenticationWithoutDatabaseLookup() throws Exception {
        String userId = UUID.randomUUID().toString();
        String email = "stateless@example.com";
        UserPrincipal principal = new UserPrincipal(
                userId,
                email,
                "password",
                List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER")),
                true
        );

        String accessToken = jwtUtil.generateAccessToken(principal);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + accessToken);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain filterChain = mock(FilterChain.class);

        filter.doFilter(request, response, filterChain);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(auth.getPrincipal()).isInstanceOf(UserPrincipal.class);
        UserPrincipal authenticatedPrincipal = (UserPrincipal) auth.getPrincipal();
        assertThat(authenticatedPrincipal.getId()).isEqualTo(userId);
        assertThat(authenticatedPrincipal.getUsername()).isEqualTo(email);
        assertThat(authenticatedPrincipal.getAuthorities())
                .extracting("authority")
                .containsExactly("ROLE_CUSTOMER");

        verify(filterChain).doFilter(request, response);
        // CRITICAL: userDetailsService MUST NOT be touched when claims are present (stateless)
        verifyNoInteractions(userDetailsService);
    }
}
