package com.bcm.config;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** Validate the configured identity after DataSeeder has completed. Fail closed on misconfiguration. */
@org.springframework.core.annotation.Order(1)
@RequiredArgsConstructor
public class WalkInGuestGuard implements ApplicationRunner {
    private final com.bcm.security.WalkInGuestIdentity guest;

    @Override
    public void run(ApplicationArguments args) {
        guest.customerId();
    }
}
