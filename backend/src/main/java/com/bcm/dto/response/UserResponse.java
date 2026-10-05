package com.bcm.dto.response;

import com.bcm.entity.User;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * DTO for user information in responses
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserResponse {

    private String id;
    private String email;
    private Set<String> roles;

    /**
     * Convert User entity to UserResponse DTO
     * @param user User entity
     * @return UserResponse DTO
     */
    public static UserResponse fromEntity(User user) {
        return UserResponse.builder()
                .id(user.getId().toString())
                .email(user.getEmail())
                .roles(user.getRoles().stream()
                        .map(role -> role.getRoleName())
                        .collect(Collectors.toSet()))
                .build();
    }
}
