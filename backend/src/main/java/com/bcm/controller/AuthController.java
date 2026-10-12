package com.bcm.controller;

import com.bcm.dto.request.LoginRequest;
import com.bcm.dto.request.RefreshTokenRequest;
import com.bcm.dto.request.RegisterRequest;
import com.bcm.dto.response.ApiResponse;
import com.bcm.dto.response.LoginResponse;
import com.bcm.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * REST Controller for authentication endpoints
 * - Register new user
 * - Login user
 * - Refresh access token
 */
@RestController
@RequestMapping("/auth")
@SecurityRequirements
@RequiredArgsConstructor
@Tag(name = "Authentication", description = "User authentication and registration endpoints")
public class AuthController {

    private final AuthService authService;

    /**
     * Register a new user
     * @param request Registration details
     * @return Login response with JWT tokens
     */
    @PostMapping("/register")
    @Operation(summary = "Register new user", description = "Atomically register User, CUSTOMER role and Customer profile using required fullName and phone and optional address")
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "User, CUSTOMER role and Customer committed atomically"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Registration validation failure", content = @io.swagger.v3.oas.annotations.media.Content(schema = @io.swagger.v3.oas.annotations.media.Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Duplicate email or persistence constraint", content = @io.swagger.v3.oas.annotations.media.Content(schema = @io.swagger.v3.oas.annotations.media.Schema(implementation = ApiResponse.class)))
    })
    public ResponseEntity<ApiResponse<LoginResponse>> register(@Valid @RequestBody RegisterRequest request) {
        LoginResponse response = authService.register(request);
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.success("User registered successfully", response));
    }

    /**
     * Login user
     * @param request Login credentials
     * @return Login response with JWT tokens
     */
    @PostMapping("/login")
    @Operation(summary = "Login user", description = "Authenticate user and return JWT tokens")
    public ResponseEntity<ApiResponse<LoginResponse>> login(@Valid @RequestBody LoginRequest request) {
        LoginResponse response = authService.login(request);
        return ResponseEntity.ok(ApiResponse.success("Login successful", response));
    }

    /**
     * Refresh access token
     * @param request Refresh token
     * @return New access token and refresh token
     */
    @PostMapping("/refresh")
    @Operation(summary = "Refresh token", description = "Generate new access token using refresh token")
    public ResponseEntity<ApiResponse<LoginResponse>> refreshToken(@Valid @RequestBody RefreshTokenRequest request) {
        LoginResponse response = authService.refreshToken(request);
        return ResponseEntity.ok(ApiResponse.success("Token refreshed successfully", response));
    }
}
