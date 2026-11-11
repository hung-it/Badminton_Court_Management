package com.bcm.service;

import com.bcm.entity.User;
import com.bcm.exception.ResourceNotFoundException;
import com.bcm.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Service for user-related business logic
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserService {

    private final UserRepository userRepository;
    private final org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;

    @Transactional
    public void changePassword(String userId, com.bcm.dto.request.ChangePasswordRequest request) {
        com.bcm.security.CredentialPolicy.requireCurrentPassword(request.currentPassword());
        com.bcm.security.CredentialPolicy.requireNewPassword(request.newPassword());
        User user=userRepository.findActiveForUpdate(UUID.fromString(userId))
                .orElseThrow(()->new ResourceNotFoundException("Không tìm thấy tài khoản đang hoạt động"));
        if(!passwordEncoder.matches(request.currentPassword(),user.getPasswordHash()))
            throw new com.bcm.exception.BadRequestException("Mật khẩu hiện tại không đúng.");
        if(passwordEncoder.matches(request.newPassword(),user.getPasswordHash()))
            throw new com.bcm.exception.BadRequestException("Mật khẩu mới phải khác mật khẩu hiện tại.");
        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
    }

    /**
     * Find user by ID
     * @param id User ID
     * @return User entity
     * @throws ResourceNotFoundException if user not found
     */
    public User findById(String id) {
        return userRepository.findById(UUID.fromString(id))
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", id));
    }

    /**
     * Find user by email
     * @param email User email
     * @return User entity
     * @throws ResourceNotFoundException if user not found
     */
    public User findByEmail(String email) {
        return userRepository.findByEmail(com.bcm.security.CredentialPolicy.email(email))
                .orElseThrow(() -> new ResourceNotFoundException("User", "email", email));
    }

    /**
     * Check if email exists
     * @param email Email to check
     * @return true if exists
     */
    public boolean existsByEmail(String email) {
        return userRepository.existsByEmail(com.bcm.security.CredentialPolicy.email(email));
    }
}
