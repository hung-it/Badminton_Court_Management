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
import org.springframework.security.access.prepost.PreAuthorize;
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

    @PutMapping("/me")
    @PreAuthorize("hasRole('CUSTOMER')")
    @Operation(summary="Cập nhật hồ sơ của chính mình", description="Chỉ sửa họ tên, số điện thoại và địa chỉ; đồng bộ customer profile, giữ nguyên email và thông tin xác thực.")
    public ApiResponse<UserResponse> updateCurrentUser(@AuthenticationPrincipal UserPrincipal principal,
            @jakarta.validation.Valid @RequestBody com.bcm.dto.request.UpdateProfileRequest request) {
        return ApiResponse.success(userService.updateProfile(principal.getId(),request));
    }

    @PostMapping("/me/password")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary="Tự đổi mật khẩu", description="Dùng tài khoản trong JWT, yêu cầu mật khẩu hiện tại. Mật khẩu mới 8–72 ký tự và tối đa 72 byte UTF-8. Không đổi tài khoản khác; không trả hash. JWT hiện có vẫn có hiệu lực đến hạn theo cơ chế hiện tại.")
    public ApiResponse<Void> changePassword(@AuthenticationPrincipal UserPrincipal principal,
            @jakarta.validation.Valid @RequestBody com.bcm.dto.request.ChangePasswordRequest request) {
        userService.changePassword(principal.getId(),request);
        return ApiResponse.success("Đổi mật khẩu thành công",null);
    }

    /**
     * Get current authenticated user's profile
     */
    @GetMapping("/me")
    @Operation(summary = "Get current user profile", description = "Returns the profile of the currently authenticated user")
    public ResponseEntity<ApiResponse<UserResponse>> getCurrentUser(
            @AuthenticationPrincipal UserPrincipal userPrincipal) {

        UserResponse userResponse = userService.profile(userPrincipal.getId());

        return ResponseEntity.ok(ApiResponse.success(userResponse));
    }

    /**
     * Get user by ID (admin only)
     */
    @GetMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Get user by ID", description = "Returns user details by ID (admin only)")
    public ResponseEntity<ApiResponse<UserResponse>> getUserById(@PathVariable String id) {
        User user = userService.findById(id);
        UserResponse userResponse = UserResponse.fromEntity(user);

        return ResponseEntity.ok(ApiResponse.success(userResponse));
    }
}
