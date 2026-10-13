package com.bcm.service;

import com.bcm.dto.request.LoginRequest;
import com.bcm.dto.request.RefreshTokenRequest;
import com.bcm.dto.request.RegisterRequest;
import com.bcm.dto.response.LoginResponse;
import com.bcm.dto.response.UserResponse;
import com.bcm.entity.Role;
import com.bcm.entity.User;
import com.bcm.entity.Customer;
import com.bcm.repository.CustomerRepository;
import com.bcm.exception.BadRequestException;
import com.bcm.exception.DuplicateResourceException;
import com.bcm.exception.ResourceNotFoundException;
import com.bcm.security.UserPrincipal;
import com.bcm.repository.RoleRepository;
import com.bcm.repository.UserRepository;
import com.bcm.util.JwtUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * Service for authentication operations
 * - User registration
 * - User login
 * - Token refresh
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {

    private final UserRepository userRepository;
    private final CustomerRepository customerRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final AuthenticationManager authenticationManager;

    /**
     * Register a new user with CUSTOMER role by default
     * @param request Registration details
     * @return Login response with tokens
     */
    @Transactional
    public LoginResponse register(RegisterRequest request) {
        // Check if email already exists
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new DuplicateResourceException("Email already registered");
        }

        // Get CUSTOMER role
        Role customerRole = roleRepository.findByRoleName("CUSTOMER")
                .orElseThrow(() -> new RuntimeException("CUSTOMER role not found. Please run data seeder."));

        // Create new user
        User user = User.builder()
                .email(request.getEmail())
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .fullName(request.getFullName())
                .phone(request.getPhone())
                .address(request.getAddress())
                .roles(Set.of(customerRole))
                .build();

        User savedUser = userRepository.saveAndFlush(user);
        Customer customer = new Customer();
        customer.setUser(savedUser);
        customer.setFullName(request.getFullName());
        customer.setPhone(request.getPhone());
        customer.setAddress(request.getAddress());
        customerRepository.saveAndFlush(customer);
        log.info("New user registered: {}", savedUser.getEmail());

        // Generate tokens
        UserPrincipal principal = UserPrincipal.create(savedUser);
        String accessToken = jwtUtil.generateAccessToken(principal);
        String refreshToken = jwtUtil.generateRefreshToken(savedUser.getEmail());

        UserResponse userResponse = UserResponse.fromEntity(savedUser);

        return new LoginResponse(accessToken, refreshToken, userResponse);
    }

    /**
     * Authenticate user and generate tokens
     * @param request Login credentials
     * @return Login response with tokens
     */
    @Transactional(readOnly = true)
    public LoginResponse login(LoginRequest request) {
        // Authenticate user
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.getEmail(), request.getPassword())
        );

        UserDetails userDetails = (UserDetails) authentication.getPrincipal();

        // Get user from database
        User user = userRepository.findByEmailAndDeletedAtIsNull(request.getEmail())
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        // Generate tokens
        UserPrincipal principal = authentication.getPrincipal() instanceof UserPrincipal p
                ? p : UserPrincipal.create(user);
        String accessToken = jwtUtil.generateAccessToken(principal);
        String refreshToken = jwtUtil.generateRefreshToken(user.getEmail());

        UserResponse userResponse = UserResponse.fromEntity(user);

        log.info("User logged in: {}", user.getEmail());

        return new LoginResponse(accessToken, refreshToken, userResponse);
    }

    /**
     * Refresh access token using refresh token
     * @param request Refresh token request
     * @return Login response with new tokens
     */
    @Transactional(readOnly = true)
    public LoginResponse refreshToken(RefreshTokenRequest request) {
        String refreshToken = request.getRefreshToken();

        // Validate refresh token
        if (!jwtUtil.validateToken(refreshToken) || !jwtUtil.isRefreshToken(refreshToken)) {
            throw new BadRequestException("Invalid or expired refresh token");
        }

        // Extract email from refresh token
        String email = jwtUtil.extractEmail(refreshToken);

        // Get user
        User user = userRepository.findByEmailAndDeletedAtIsNull(email)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        // Generate new tokens
        UserPrincipal principal = UserPrincipal.create(user);
        String newAccessToken = jwtUtil.generateAccessToken(principal);
        String newRefreshToken = jwtUtil.generateRefreshToken(user.getEmail());

        UserResponse userResponse = UserResponse.fromEntity(user);

        log.info("Token refreshed for user: {}", user.getEmail());

        return new LoginResponse(newAccessToken, newRefreshToken, userResponse);
    }
}
