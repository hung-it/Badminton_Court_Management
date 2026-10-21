package com.bcm.service;

import com.bcm.security.UserPrincipal;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcBuilderCustomizer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import java.util.List;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** Authenticated request fixture only; intentionally does not resolve a customer or bypass the owner filter chain. */
@TestConfiguration
public class BookingWebTestConfiguration {
    @Bean
    MockMvcBuilderCustomizer authenticatedBookingRequests() {
        var principal = new UserPrincipal("00000000-0000-0000-0000-000000007001",
                "booking-regression@example.test", "unused-test-password",
                List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER")), true);
        return builder -> builder.defaultRequest(get("/").with(user(principal)));
    }
}
