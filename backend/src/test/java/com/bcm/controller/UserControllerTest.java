package com.bcm.controller;

import com.bcm.dto.response.ApiResponse;
import com.bcm.dto.response.UserResponse;
import com.bcm.entity.Role;
import com.bcm.entity.User;
import com.bcm.security.UserPrincipal;
import com.bcm.service.UserService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class UserControllerTest {

    private final UserService userService = mock(UserService.class);
    private final UserController controller = new UserController(userService);

    @Test
    @DisplayName("getUserById endpoint must be annotated with @PreAuthorize(\"hasRole('ADMIN')\") to prevent IDOR")
    void getUserByIdHasAdminPreAuthorizeAnnotation() throws NoSuchMethodException {
        Method method = UserController.class.getMethod("getUserById", String.class);
        PreAuthorize annotation = method.getAnnotation(PreAuthorize.class);

        assertThat(annotation).isNotNull();
        assertThat(annotation.value()).isEqualTo("hasRole('ADMIN')");
    }

    @Test
    @DisplayName("getCurrentUser returns complete profile with phone, address, and fullName")
    void getCurrentUserReturnsCompleteProfile() {
        UUID userId = UUID.randomUUID();
        UserPrincipal principal = new UserPrincipal(
                userId.toString(),
                "test@example.com",
                "pwd",
                List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER")),
                true
        );

        Role role = Role.builder().roleName("CUSTOMER").build();
        User user = User.builder()
                .email("test@example.com")
                .fullName("Nguyen Van A")
                .phone("0912345678")
                .address("123 Duong ABC")
                .roles(Set.of(role))
                .build();
        user.setId(userId);

        when(userService.findById(userId.toString())).thenReturn(user);

        ResponseEntity<ApiResponse<UserResponse>> response = controller.getCurrentUser(principal);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).isNotNull();
        UserResponse data = response.getBody().getData();
        assertThat(data.getId()).isEqualTo(userId.toString());
        assertThat(data.getFullName()).isEqualTo("Nguyen Van A");
        assertThat(data.getPhone()).isEqualTo("0912345678");
        assertThat(data.getAddress()).isEqualTo("123 Duong ABC");
        assertThat(data.getRoles()).containsExactly("CUSTOMER");
    }
}
