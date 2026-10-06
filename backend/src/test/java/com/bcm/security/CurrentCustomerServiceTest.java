package com.bcm.security;

import com.bcm.repository.CustomerRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CurrentCustomerServiceTest {
    private final CustomerRepository repository = mock(CustomerRepository.class);
    private final CurrentCustomerService resolver = new CurrentCustomerService(repository);
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }
    private void authenticate(String id, String role) {
        var principal = new UserPrincipal(id, "server-side@example.test", "unused", List.of(new SimpleGrantedAuthority(role)), true);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }
    @Test void distinctUserIdsResolveDistinctCustomerIds() {
        UUID userA = UUID.randomUUID(), userB = UUID.randomUUID(), customerA = UUID.randomUUID(), customerB = UUID.randomUUID();
        when(repository.findActiveCustomerIdByUserId(userA)).thenReturn(Optional.of(customerA));
        when(repository.findActiveCustomerIdByUserId(userB)).thenReturn(Optional.of(customerB));
        authenticate(userA.toString(), "ROLE_CUSTOMER");
        assertThat(resolver.requireCustomerId()).isEqualTo(customerA).isNotEqualTo(userA);
        authenticate(userB.toString(), "ROLE_CUSTOMER");
        assertThat(resolver.requireCustomerId()).isEqualTo(customerB).isNotEqualTo(userB);
    }
    @Test void missingCustomerIsDeniedWithoutCreatingProfile() {
        UUID id = UUID.randomUUID();
        when(repository.findActiveCustomerIdByUserId(id)).thenReturn(Optional.empty());
        authenticate(id.toString(), "ROLE_CUSTOMER");
        assertThatThrownBy(resolver::requireCustomerId).isInstanceOf(AccessDeniedException.class);
        verify(repository).findActiveCustomerIdByUserId(id);
        verifyNoMoreInteractions(repository);
    }
    @Test void adminAndStaffHaveNoInventedHistoryPrivileges() {
        for (String role : List.of("ROLE_ADMIN", "ROLE_STAFF")) {
            authenticate(UUID.randomUUID().toString(), role);
            assertThatThrownBy(resolver::requireCustomerId).isInstanceOf(AccessDeniedException.class);
        }
        verifyNoInteractions(repository);
    }
    @Test void malformedPrincipalIdentityIsDenied() {
        authenticate("not-a-uuid", "ROLE_CUSTOMER");
        assertThatThrownBy(resolver::requireCustomerId).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(repository);
    }
    @Test void anonymousOrUntrustedPrincipalIsDenied() {
        assertThatThrownBy(resolver::requireCustomerId).isInstanceOf(AccessDeniedException.class);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("client-id", null,
                List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER"))));
        assertThatThrownBy(resolver::requireCustomerId).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(repository);
    }
}
