package com.bcm.config;
import com.bcm.security.WalkInGuestIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class WalkInGuestGuardTest {
 @Test void missingOrMalformedConfigurationFailsClosed() {
  for(String id:List.of("","not-a-uuid")) assertThatThrownBy(()->new WalkInGuestIdentity(mock(JdbcTemplate.class),id)).isInstanceOf(IllegalArgumentException.class);
 }
 @Test void startupPropagatesMissingInactiveOrPrivilegedGuest() {
  var guest=mock(WalkInGuestIdentity.class);when(guest.customerId()).thenThrow(new IllegalStateException("Invalid guest"));
  assertThatThrownBy(()->new WalkInGuestGuard(guest).run(null)).isInstanceOf(IllegalStateException.class);
 }
 @Test void configuredIdentityDoesNotProtectOtherUsers() {
  UUID id=UUID.randomUUID();var guest=new WalkInGuestIdentity(mock(JdbcTemplate.class),id.toString());
  assertThat(guest.isGuest(id)).isTrue();assertThat(guest.isGuest(UUID.randomUUID())).isFalse();
 }
}
