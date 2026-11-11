package com.bcm.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Startup guard for the POS walk-in flow. Walk-in sales without an explicit customer resolve to
 * the dedicated guest account (guest@bcm.com / phone 0000000000); if it is missing or ambiguous
 * every walk-in sale fails with a 400. This runner does not fix that — it surfaces it at boot
 * with an actionable message instead of the first cashier request discovering it.
 *
 * Deliberately an ApplicationRunner: Boot invokes all CommandLineRunners first, so this check
 * runs AFTER DataSeeder (a CommandLineRunner) and cannot report the guest the seeder just created.
 * Non-fatal: only the no-customerId POS path degrades.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WalkInGuestGuard implements ApplicationRunner {
    private final JdbcTemplate jdbc;

    @Override
    public void run(ApplicationArguments args) {
        int count = jdbc.queryForObject(
                "SELECT count(*) FROM customers c JOIN users u ON u.id = c.user_id "
                        + "WHERE u.email = 'guest@bcm.com' AND c.phone = '0000000000' "
                        + "AND c.deleted_at IS NULL AND u.deleted_at IS NULL",
                Integer.class);
        if (count != 1) {
            log.warn("Walk-in POS (no customerId) is UNUSABLE: dedicated guest account guest@bcm.com "
                    + "(phone 0000000000) is missing, deleted or duplicated (found {}). "
                    + "Seed it via bcm.seed.enabled=true or create/restore it manually.", count);
        }
    }
}
