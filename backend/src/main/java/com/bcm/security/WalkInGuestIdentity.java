package com.bcm.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** The configured user UUID, never a phone number, identifies the protected POS guest. */
@Component
public class WalkInGuestIdentity {
    private final JdbcTemplate jdbc;
    private final UUID userId;

    public WalkInGuestIdentity(JdbcTemplate jdbc, @Value("${bcm.pos.guest-user-id:}") String configuredId) {
        this.jdbc = jdbc;
        try {
            this.userId = UUID.fromString(configuredId);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("BCM_POS_GUEST_USER_ID must identify the dedicated POS guest user", ex);
        }
    }

    public UUID userId() { return userId; }

    public boolean isGuest(UUID candidate) { return userId.equals(candidate); }

    public UUID customerId() {
        var ids = jdbc.queryForList("SELECT c.id FROM customers c JOIN users u ON u.id=c.user_id "
                + "WHERE u.id=? AND u.deleted_at IS NULL AND c.deleted_at IS NULL "
                + "AND EXISTS (SELECT 1 FROM user_roles ur JOIN roles r ON r.id=ur.role_id WHERE ur.user_id=u.id AND r.role_name='CUSTOMER') "
                + "AND NOT EXISTS (SELECT 1 FROM user_roles ur JOIN roles r ON r.id=ur.role_id WHERE ur.user_id=u.id AND r.role_name IN ('ADMIN','STAFF'))",
                UUID.class, userId);
        if (ids.size() != 1) throw new IllegalStateException("Configured POS guest requires one active customer and CUSTOMER-only privileges");
        return ids.get(0);
    }
}
