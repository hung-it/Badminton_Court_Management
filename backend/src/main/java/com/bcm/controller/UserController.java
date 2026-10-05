package com.bcm.controller;

import com.bcm.dto.response.ApiResponse;
import com.bcm.dto.response.UserResponse;
import com.bcm.entity.User;
import com.bcm.security.UserPrincipal;
import com.bcm.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * REST controller for user-related endpoints
 */
@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
@Tag(name = "User Management", description = "APIs for user operations")
@SecurityRequirement(name = "bearerAuth")
public class UserController {

    private final UserService userService;

    /**
     * Get current authenticated user's profile
     */
    @GetMapping("/me")
    @Operation(summary = "Get current user profile", description = "Returns the profile of the currently authenticated user")
    public ResponseEntity<ApiResponse<UserResponse>> getCurrentUser(
            @AuthenticationPrincipal UserPrincipal userPrincipal) {

        User user = userService.findById(userPrincipal.getId());
        UserResponse userResponse = UserResponse.fromEntity(user);

        return ResponseEntity.ok(ApiResponse.success(userResponse));
    }

    /**
     * Get user by ID (admin only)
     */
    @GetMapping("/{id}")
    @Operation(summary = "Get user by ID", description = "Returns user details by ID (admin only)")
    public ResponseEntity<ApiResponse<UserResponse>> getUserById(@PathVariable String id) {
        User user = userService.findById(id);
        UserResponse userResponse = UserResponse.fromEntity(user);

        return ResponseEntity.ok(ApiResponse.success(userResponse));
    }
}
