package com.bcm.security;

import com.bcm.entity.User;
import com.bcm.repository.CustomerRepository;
import com.bcm.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Custom UserDetailsService implementation for Spring Security
 * Loads user from database and converts to UserDetails
 */
@Service
@RequiredArgsConstructor
public class CustomUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;
    private final CustomerRepository customerRepository;

    /**
     * Load user by email (username in Spring Security context)
     * @param email User email
     * @return UserDetails for authentication
     * @throws UsernameNotFoundException if user not found
     */
    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        String normalized = CredentialPolicy.email(email);
        User user = userRepository.findByEmail(normalized)
                .orElseThrow(() -> new UsernameNotFoundException("User not found"));

        boolean locked = user.getDeletedAt() != null;
        if (!locked && user.hasRole("CUSTOMER")) {
            if (customerRepository.findActiveCustomerIdByUserId(user.getId()).isEmpty()) {
                locked = true;
            }
        }

        return UserPrincipal.create(user, !locked);
    }
}
