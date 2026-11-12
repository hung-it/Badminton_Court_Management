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
    private final com.bcm.repository.CustomerRepository customers;
    private final com.bcm.security.WalkInGuestIdentity guest;
    private final org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;

    public com.bcm.dto.response.UserResponse profile(String userId) {
        User user = findById(userId);
        if(user.getDeletedAt()!=null) throw new org.springframework.security.access.AccessDeniedException("Account unavailable");
        var response = com.bcm.dto.response.UserResponse.fromEntity(user);
        response.setCanEditProfile(user.hasRole("CUSTOMER") && !guest.isGuest(user.getId())
                && customers.findActiveCustomerIdByUserId(user.getId()).isPresent());
        return response;
    }

    @Transactional
    public com.bcm.dto.response.UserResponse updateProfile(String userId, com.bcm.dto.request.UpdateProfileRequest request) {
        User user = userRepository.findActiveForUpdate(UUID.fromString(userId))
                .orElseThrow(()->new org.springframework.security.access.AccessDeniedException("Account unavailable"));
        if(!user.hasRole("CUSTOMER") || guest.isGuest(user.getId()))
            throw new org.springframework.security.access.AccessDeniedException("Customer profile required");
        var customer = customers.findActiveForUpdateByUserId(user.getId())
                .orElseThrow(()->new org.springframework.security.access.AccessDeniedException("Customer profile unavailable"));
        String phone = request.phone().strip();
        if("0000000000".equals(phone.replaceAll("[^0-9]","")))
            throw new com.bcm.exception.BadRequestException("Số điện thoại dành riêng cho khách vãng lai.");
        String address = request.address()==null ? null : request.address().strip();
        if(address!=null && address.isEmpty()) address=null;
        user.setFullName(request.fullName().strip());user.setPhone(phone);user.setAddress(address);
        customer.setFullName(user.getFullName());customer.setPhone(phone);customer.setAddress(address);
        userRepository.saveAndFlush(user);customers.saveAndFlush(customer);
        var response = com.bcm.dto.response.UserResponse.fromEntity(user);
        response.setCanEditProfile(true);
        return response;
    }

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
