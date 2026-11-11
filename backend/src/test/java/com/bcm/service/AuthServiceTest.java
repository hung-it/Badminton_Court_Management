package com.bcm.service;

import com.bcm.dto.request.LoginRequest;
import com.bcm.dto.request.RefreshTokenRequest;
import com.bcm.dto.request.RegisterRequest;
import com.bcm.exception.BadRequestException;
import com.bcm.exception.DuplicateResourceException;
import com.bcm.exception.ResourceNotFoundException;
import com.bcm.repository.CustomerRepository;
import com.bcm.repository.RoleRepository;
import com.bcm.repository.UserRepository;
import com.bcm.util.JwtUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AuthServiceTest {

    @Test
    void legacyRefreshWithoutIdentityMustNotResolveReusedEmail() {
        when(jwtUtil.validateToken("legacy")).thenReturn(true);
        when(jwtUtil.isRefreshToken("legacy")).thenReturn(true);
        when(jwtUtil.extractEmail("legacy")).thenReturn("reused@example.com");
        assertThatThrownBy(() -> authService.refreshToken(new RefreshTokenRequest("legacy")))
                .isInstanceOf(org.springframework.security.authentication.BadCredentialsException.class);
        verify(userRepository, never()).findByEmailAndDeletedAtIsNull(any());
    }

    @Test
    void registrationRejectsReservedGuestPhoneAndEmailBeforePersistence() {
        for (var request : java.util.List.of(
                RegisterRequest.builder().email("normal@example.com").phone("0000000000").build(),
                RegisterRequest.builder().email(" GUEST@BCM.COM ").phone("0912345678").build())) {
            assertThatThrownBy(() -> authService.register(request)).isInstanceOf(BadRequestException.class);
        }
        verifyNoInteractions(userRepository, customerRepository);
    }

    private final UserRepository userRepository = mock(UserRepository.class);
    private final CustomerRepository customerRepository = mock(CustomerRepository.class);
    private final RoleRepository roleRepository = mock(RoleRepository.class);
    private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
    private final JwtUtil jwtUtil = mock(JwtUtil.class);
    private final AuthenticationManager authenticationManager = mock(AuthenticationManager.class);

    private final AuthService authService = new AuthService(
            userRepository,
            customerRepository,
            roleRepository,
            passwordEncoder,
            jwtUtil,
            authenticationManager
    );

    @Test
    @DisplayName("register throws DuplicateResourceException (409) instead of RuntimeException when email exists")
    void registerThrowsDuplicateResourceExceptionWhenEmailExists() {
        RegisterRequest request = RegisterRequest.builder()
                .email("duplicate@example.com")
                .password("Password123")
                .fullName("Test")
                .phone("0912345678")
                .build();

        when(userRepository.existsByEmail("duplicate@example.com")).thenReturn(true);

        assertThatThrownBy(() -> authService.register(request))
                .isInstanceOf(DuplicateResourceException.class)
                .hasMessageContaining("Email already registered");
    }

    @Test
    @DisplayName("refreshToken rejects a non-refresh token with authentication failure")
    void refreshTokenThrowsBadRequestExceptionWhenTokenIsNotRefreshToken() {
        RefreshTokenRequest request = new RefreshTokenRequest("access-token-passed-as-refresh");

        when(jwtUtil.validateToken(request.getRefreshToken())).thenReturn(true);
        when(jwtUtil.isRefreshToken(request.getRefreshToken())).thenReturn(false);

        assertThatThrownBy(() -> authService.refreshToken(request))
                .isInstanceOf(org.springframework.security.authentication.BadCredentialsException.class);
    }

    @Test
    @DisplayName("refreshToken rejects an unavailable identity with authentication failure")
    void refreshTokenThrowsResourceNotFoundExceptionWhenUserNotFound() {
        String token = "valid-refresh-token";
        RefreshTokenRequest request = new RefreshTokenRequest(token);

        when(jwtUtil.validateToken(token)).thenReturn(true);
        when(jwtUtil.isRefreshToken(token)).thenReturn(true);
        var missingId = UUID.randomUUID();
        when(jwtUtil.extractUserId(token)).thenReturn(missingId.toString());
        when(userRepository.findById(missingId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.refreshToken(request))
                .isInstanceOf(org.springframework.security.authentication.BadCredentialsException.class);
    }

    @Test
    @DisplayName("login throws ResourceNotFoundException (404) when user is not found in database")
    void loginThrowsResourceNotFoundExceptionWhenUserNotFound() {
        LoginRequest request = new LoginRequest("missing@example.com", "Password123");
        Authentication auth = mock(Authentication.class);
        org.springframework.security.core.userdetails.UserDetails userDetails =
                mock(org.springframework.security.core.userdetails.UserDetails.class);

        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class))).thenReturn(auth);
        when(auth.getPrincipal()).thenReturn(userDetails);
        when(userRepository.findByEmailAndDeletedAtIsNull("missing@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(request))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("User not found");
    }
}
