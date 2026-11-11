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
 * Data seeder to initialize database with default roles, admin user, and guest customer
 * Runs automatically on application startup
 */
@Component
@org.springframework.core.annotation.Order(0)
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="bcm.seed.enabled", havingValue="true", matchIfMissing=true)
@RequiredArgsConstructor
@Slf4j
public class DataSeeder implements CommandLineRunner {

    private final RoleRepository roleRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final com.bcm.security.WalkInGuestIdentity guest;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    @Override
    @Transactional
    public void run(String... args) {
        seedRoles();
        seedAdminUser();
        seedGuestCustomer();
    }

    /**
     * Seed default roles: ADMIN, STAFF, CUSTOMER
     */
    private void seedRoles() {
        if (roleRepository.count() == 0) {
            log.info("Seeding roles...");

            Role adminRole = Role.builder()
                    .roleName("ADMIN")
                    .build();

            Role staffRole = Role.builder()
                    .roleName("STAFF")
                    .build();

            Role customerRole = Role.builder()
                    .roleName("CUSTOMER")
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

    /**
     * Seed default guest customer "Khách vãng lai" for POS sales
     */
    private void seedGuestCustomer() {
        if (userRepository.findById(guest.userId()).isPresent()) return;
        if (userRepository.existsByEmail("guest@bcm.com"))
            throw new IllegalStateException("Configure BCM_POS_GUEST_USER_ID with the existing guest users.id");
        jdbc.update("INSERT INTO users(id,email,password_hash,full_name,phone,created_at,updated_at) VALUES (?,'guest@bcm.com',?,'Khách vãng lai','0000000000',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                guest.userId(), passwordEncoder.encode("guest123"));
        jdbc.update("INSERT INTO user_roles(user_id,role_id) SELECT ?,id FROM roles WHERE role_name='CUSTOMER'", guest.userId());
        jdbc.update("INSERT INTO customers(id,user_id,full_name,phone,created_at,updated_at) VALUES (?,?,'Khách vãng lai','0000000000',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                java.util.UUID.randomUUID(),guest.userId());
    }
}
