package com.bcm.security;

import com.bcm.repository.CustomerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

/** Consumes the owner principal; never parses JWT or creates missing profiles. */
@Service
@RequiredArgsConstructor
public class CurrentCustomerService {
    private final CustomerRepository customers;

    @Transactional(readOnly = true)
    public UUID requireCustomerId() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof UserPrincipal principal)
                || !principal.isEnabled()
                || authentication.getAuthorities().stream().noneMatch(a -> a.getAuthority().equals("ROLE_CUSTOMER"))) {
            throw new AccessDeniedException("Customer identity required");
        }
        UUID userId;
        try { userId = UUID.fromString(principal.getId()); }
        catch (IllegalArgumentException | NullPointerException ex) {
            throw new AccessDeniedException("Customer identity required");
        }
        return customers.findActiveCustomerIdByUserId(userId)
                .orElseThrow(() -> new AccessDeniedException("Customer profile unavailable"));
    }
}
