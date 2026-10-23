package com.bcm.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Applies the historical schema then the additive upgrade twice over populated legacy tables. */
@EnabledIfEnvironmentVariable(named = "BCM_TEST_DB_URL", matches = "jdbc:postgresql:.*")
class BookingUxMigrationPostgresTest {
    @Test void upgradeIsRepeatableAndPreservesLegacyBookingsPaymentsAndCourts() throws Exception {
        String schema = "booking_ux_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(System.getenv("BCM_TEST_DB_URL"),
                System.getenv("BCM_TEST_DB_USERNAME"), System.getenv("BCM_TEST_DB_PASSWORD"));
             var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
            try {
                statement.execute("SET search_path TO " + schema + ", public");
                statement.execute(Files.readString(Path.of("../db/migration.sql"), StandardCharsets.UTF_8));
                statement.execute("INSERT INTO users(email,password_hash,full_name,phone) VALUES ('legacy@migration.test','test','Legacy','0900000000')");
                statement.execute("INSERT INTO customers(user_id,full_name,phone) SELECT id,'Legacy','0900000000' FROM users");
                statement.execute("INSERT INTO bookings(customer_id,status,court_fee,expires_at) SELECT id,'PENDING',100000,TIMESTAMP '2031-01-01 12:10:00' FROM customers");
                statement.execute("INSERT INTO booking_details(booking_id,court_id,time_slot_id,booking_date,price) SELECT b.id,c.id,t.id,DATE '2031-01-01',100000 FROM bookings b CROSS JOIN courts c CROSS JOIN time_slots t LIMIT 1");
                statement.execute("INSERT INTO payment_transactions(booking_id,payment_method,status,amount,note) SELECT id,'BANK_TRANSFER','PENDING',100000,'CUSTOMER_DECLARED; receipt=UNVERIFIED' FROM bookings");
                String upgrade = Files.readString(Path.of("../db/booking-ux-upgrade.sql"), StandardCharsets.UTF_8);
                statement.execute(upgrade); statement.execute(upgrade);
                try (var rows = statement.executeQuery("SELECT b.customer_note,b.court_fee,b.expires_at,p.note,(SELECT count(*) FROM courts) AS courts,(SELECT count(*) FROM booking_details) AS details FROM bookings b JOIN payment_transactions p ON p.booking_id=b.id")) {
                    assertThat(rows.next()).isTrue(); assertThat(rows.getString("customer_note")).isNull();
                    assertThat(rows.getBigDecimal("court_fee")).isEqualByComparingTo("100000");
                    assertThat(rows.getTimestamp("expires_at").toLocalDateTime().toString()).isEqualTo("2031-01-01T12:10");
                    assertThat(rows.getString("note")).isEqualTo("CUSTOMER_DECLARED; receipt=UNVERIFIED");
                    assertThat(rows.getInt("courts")).isEqualTo(8); assertThat(rows.getInt("details")).isEqualTo(1);
                }
                try (var rows = statement.executeQuery("SELECT count(*) FROM courts WHERE district IS NOT NULL")) {
                    rows.next(); assertThat(rows.getInt(1)).isZero();
                }
                String demo = Files.readString(Path.of("../db/demo-districts-seed.sql"), StandardCharsets.UTF_8);
                assertThatThrownBy(() -> statement.execute(demo)).isInstanceOf(java.sql.SQLException.class).hasMessageContaining("explicit SET");
                statement.execute("ROLLBACK");
                statement.execute("SET bcm.allow_demo_seed='on'");
                statement.execute(demo); statement.execute(demo);
                try (var rows = statement.executeQuery("SELECT count(*),count(DISTINCT district) FROM courts WHERE name LIKE '[DEMO] %'")) {
                    rows.next(); assertThat(rows.getInt(1)).isEqualTo(7); assertThat(rows.getInt(2)).isEqualTo(7);
                }
                statement.execute("UPDATE courts SET district='Operator verified label' WHERE court_number=10001");
                statement.execute(demo);
                try (var rows = statement.executeQuery("SELECT district FROM courts WHERE court_number=10001")) {
                    rows.next(); assertThat(rows.getString(1)).isEqualTo("Operator verified label");
                }
                try (var rows = statement.executeQuery("SELECT (SELECT count(*) FROM courts),(SELECT count(*) FROM bookings),(SELECT count(*) FROM payment_transactions),(SELECT count(*) FROM booking_details)")) {
                    rows.next(); assertThat(rows.getInt(1)).isEqualTo(15); assertThat(rows.getInt(2)).isEqualTo(1);
                    assertThat(rows.getInt(3)).isEqualTo(1); assertThat(rows.getInt(4)).isEqualTo(1);
                }
            } finally {
                // Only this test's isolated schema and synthetic fixtures are removed.
                statement.execute("SET search_path TO public"); statement.execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }
}
