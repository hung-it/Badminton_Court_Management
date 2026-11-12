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
    @DisplayName("Filter rejects Refresh Token presented in Authorization header with 401")
    void filterRejectsRefreshTokenInAuthorizationHeader() throws Exception {
        String refreshToken = jwtUtil.generateRefreshToken("user@example.com");

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + refreshToken);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain filterChain = mock(FilterChain.class);

        filter.doFilter(request, response, filterChain);

        // Refresh token must NOT be accepted in Authorization header
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(response.getStatus()).isEqualTo(401);
        verifyNoInteractions(filterChain);
        verifyNoInteractions(userDetailsService);
    }

    @Test
    @DisplayName("Filter reloads current account state before accepting access claims")
    void filterReloadsCurrentAccountState() throws Exception {
        String userId = UUID.randomUUID().toString();
        String email = "stateless@example.com";
        UserPrincipal principal = new UserPrincipal(
                userId,
                email,
                "password",
                List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER")),
                true
        );

        principal.setTokenVersion(0L);
        when(userDetailsService.loadUserByUsername(email)).thenReturn(principal);
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
        verify(userDetailsService).loadUserByUsername(email);
    }

    @Test void unlockedAccountRejectsAccessTokenFromBeforeLock() throws Exception {
        var principal=new UserPrincipal(UUID.randomUUID().toString(),"restored@test.invalid","hash",List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER")),true,0L);
        String token=jwtUtil.generateAccessToken(principal);
        principal.setTokenVersion(1L);
        when(userDetailsService.loadUserByUsername(principal.getUsername())).thenReturn(principal);
        var request=new MockHttpServletRequest();request.addHeader("Authorization","Bearer "+token);
        filter.doFilter(request,new MockHttpServletResponse(),mock(FilterChain.class));
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test void lockedAccountCannotUsePreviouslyIssuedAccessToken() throws Exception {
        var principal=new UserPrincipal(UUID.randomUUID().toString(),"locked@test.invalid","hash",List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER")),true);
        String token=jwtUtil.generateAccessToken(principal);principal.setEnabled(false);
        when(userDetailsService.loadUserByUsername(principal.getUsername())).thenReturn(principal);
        var request=new MockHttpServletRequest();request.addHeader("Authorization","Bearer "+token);
        filter.doFilter(request,new MockHttpServletResponse(),mock(FilterChain.class));
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
}
