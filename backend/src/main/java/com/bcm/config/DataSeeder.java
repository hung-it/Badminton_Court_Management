package com.bcm.config;

import com.bcm.entity.Role;
import com.bcm.entity.User;
import com.bcm.repository.RoleRepository;
import com.bcm.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

/**
 * Data seeder to initialize database with default roles and admin user
 * Runs automatically on application startup
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DataSeeder implements CommandLineRunner {

    private final RoleRepository roleRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    @Transactional
    public void run(String... args) {
        seedRoles();
        seedAdminUser();
    }

    /**
     * Seed default roles: ADMIN, STAFF, CUSTOMER
     */
    private void seedRoles() {
        if (roleRepository.count() == 0) {
            log.info("Seeding roles...");

            Role adminRole = Role.builder()
                    .roleName("ADMIN")
                    .description("Administrator with full access")
                    .build();

            Role staffRole = Role.builder()
                    .roleName("STAFF")
                    .description("Staff member managing bookings and courts")
                    .build();

            Role customerRole = Role.builder()
                    .roleName("CUSTOMER")
                    .description("Regular customer making bookings")
                    .build();

            roleRepository.save(adminRole);
            roleRepository.save(staffRole);
            roleRepository.save(customerRole);

            log.info("✅ Roles seeded: ADMIN, STAFF, CUSTOMER");
        } else {
            log.info("Roles already exist, skipping seeding");
        }
    }

    /**
     * Seed default admin user
     * Email: admin@bcm.com
     * Password: admin123
     */
    private void seedAdminUser() {
        String adminEmail = "admin@bcm.com";

        if (!userRepository.existsByEmail(adminEmail)) {
            log.info("Seeding admin user...");

            Role adminRole = roleRepository.findByRoleName("ADMIN")
                    .orElseThrow(() -> new RuntimeException("ADMIN role not found"));

            User admin = User.builder()
                    .email(adminEmail)
                    .passwordHash(passwordEncoder.encode("admin123"))
                    .fullName("System Administrator")
                    .phone("0000000000")
                    .address("System")
                    .roles(Set.of(adminRole))
                    .build();

            userRepository.save(admin);

            log.info("✅ Admin user seeded:");
            log.info("   Email: admin@bcm.com");
            log.info("   Password: admin123");
            log.info("   ⚠️  IMPORTANT: Change this password in production!");
        } else {
            log.info("Admin user already exists, skipping seeding");
        }
    }
}
