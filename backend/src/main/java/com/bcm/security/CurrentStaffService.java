package com.bcm.security;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Resolves the staff profile of the authenticated user.
 *
 * <p>The JWT principal carries {@code users.id}; {@code invoices.created_by} references
 * {@code staffs.id}. This service bridges the two so invoices record the cashier that
 * actually took the payment instead of trusting a client-supplied identifier.
 */
@Service
@RequiredArgsConstructor
public class CurrentStaffService {

    private final JdbcTemplate jdbc;

    /**
     * @return the {@code staffs.id} of the authenticated user, or empty when the account has
     *         no active staff profile.
     */
    @Transactional(readOnly = true)
    public Optional<UUID> currentStaffId() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof UserPrincipal principal)) {
            return Optional.empty();
        }
        UUID userId;
        try {
            userId = UUID.fromString(principal.getId());
        } catch (IllegalArgumentException | NullPointerException ex) {
            return Optional.empty();
        }
        return findStaffIdByUserId(userId);
    }

    @Transactional(readOnly = true)
    public Optional<UUID> findStaffIdByUserId(UUID userId) {
        return jdbc.query(
                "SELECT id FROM staffs WHERE user_id = ? AND deleted_at IS NULL",
                (rs, rowNum) -> rs.getObject("id", UUID.class),
                userId).stream().findFirst();
    }
}
