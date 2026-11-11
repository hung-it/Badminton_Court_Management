package com.bcm.service;

import com.bcm.dto.request.*;
import com.bcm.entity.*;
import com.bcm.repository.*;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class P2RegressionTest {
    @Test void newPasswordsRespectUtf8ByteBoundariesAndLegacyPasswordsRemainValid() {
        assertThatCode(()->com.bcm.security.CredentialPolicy.requireNewPassword("a".repeat(72))).doesNotThrowAnyException();
        assertThatCode(()->com.bcm.security.CredentialPolicy.requireNewPassword("ắ".repeat(24))).doesNotThrowAnyException();
        for(String password:List.of("a".repeat(73),"ắ".repeat(25),"short12")) {
            assertThatThrownBy(()->com.bcm.security.CredentialPolicy.requireNewPassword(password))
                    .isInstanceOf(com.bcm.exception.BadRequestException.class);
        }
        assertThatCode(()->com.bcm.security.CredentialPolicy.requireCurrentPassword("a".repeat(100))).doesNotThrowAnyException();
        assertThatThrownBy(()->com.bcm.security.CredentialPolicy.requireCurrentPassword("a".repeat(101)))
                .isInstanceOf(com.bcm.exception.BadRequestException.class);
    }

    @Test void counterUsesBookingContactAndFallsBackPerField() {
        var bookings=mock(BookingRepository.class);var details=mock(BookingDetailRepository.class);
        var profile=new Customer();profile.setFullName("Profile name");profile.setPhone("0912345678");
        var b=new Booking();b.setId(UUID.randomUUID());b.setCustomer(profile);b.setStatus(BookingStatus.PENDING);
        b.setCourtFee(BigDecimal.TEN);b.setDiscountAmount(BigDecimal.ZERO);
        b.setContactFullName("Booking contact");b.setContactPhone("0987654321");
        when(bookings.findByStatusInOrderByCreatedAtDesc(any())).thenReturn(List.of(b));
        when(details.findHistoryDetails(b.getId())).thenReturn(List.of());
        var service=new CheckoutService(bookings,details,null,null,null,null,null,Clock.systemUTC());
        var result=service.getActiveBookings().get(0);
        assertThat(result.customerName()).isEqualTo("Booking contact");
        assertThat(result.customerPhone()).isEqualTo("0987654321");
        b.setContactPhone(" ");
        assertThat(service.getActiveBookings().get(0).customerPhone()).isEqualTo("0912345678");
    }

    @Test void existingEightyBytePasswordCanBeChangedWithoutTruncatingTheNewPassword() {
        var users=mock(UserRepository.class);var encoder=new BCryptPasswordEncoder();
        UUID id=UUID.randomUUID();var user=new User();user.setId(id);
        String legacy="a".repeat(80);user.setPasswordHash(encoder.encode(legacy));
        when(users.findActiveForUpdate(id)).thenReturn(Optional.of(user));
        assertThatCode(()->new UserService(users,encoder).changePassword(id.toString(),new ChangePasswordRequest(legacy,"NewPassword123")))
                .doesNotThrowAnyException();
        assertThat(encoder.matches("NewPassword123",user.getPasswordHash())).isTrue();
    }

    @Test void adminCannotAdvertiseAZeroPriceCourt() {
        try(var factory=Validation.buildDefaultValidatorFactory()) {
            var r=new CourtRequest(1,"Court",CourtType.STANDARD_MAT,CourtStatus.AVAILABLE,BigDecimal.ZERO,null);
            assertThat(factory.getValidator().validate(r)).isNotEmpty();
        }
    }
}
