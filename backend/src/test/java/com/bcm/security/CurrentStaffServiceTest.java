package com.bcm.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CurrentStaffServiceTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void resolvesStaffFromAuthenticatedUsersId() {
        UUID userId = UUID.randomUUID();
        UUID staffId = UUID.randomUUID();
        UserPrincipal principal = mock(UserPrincipal.class);
        when(principal.getId()).thenReturn(userId.toString());
        var authentication = new UsernamePasswordAuthenticationToken(principal, null, List.of());
        SecurityContextHolder.getContext().setAuthentication(authentication);

        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(org.mockito.ArgumentMatchers.eq(
                "SELECT id FROM staffs WHERE user_id = ? AND deleted_at IS NULL"),
                org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<UUID>>any(),
                org.mockito.ArgumentMatchers.eq(userId))).thenAnswer(invocation ->
                List.of(staffId));

        assertThat(new CurrentStaffService(jdbc).currentStaffId()).isEqualTo(Optional.of(staffId));
    }

    @Test
    void returnsEmptyWhenAuthenticatedUserHasNoStaffProfile() {
        UserPrincipal principal = mock(UserPrincipal.class);
        when(principal.getId()).thenReturn(UUID.randomUUID().toString());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));

        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<UUID>>any(),
                org.mockito.ArgumentMatchers.any())).thenReturn(List.of());

        assertThat(new CurrentStaffService(jdbc).currentStaffId()).isEmpty();
    }
}
