package com.bcm.service;

import static com.bcm.service.SqlScriptSections.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/** Real PostgreSQL, isolated schema per case, using the actual migration and seed files. */
@EnabledIfEnvironmentVariable(named = "BCM_TEST_DB_URL", matches = "jdbc:postgresql:.*")
class CourtDistrictsSeedPostgresTest {
    private static String file(String name) throws Exception {
        return Files.readString(Path.of("../db/" + name), StandardCharsets.UTF_8);
    }
    private interface Scenario { void run(Statement sql) throws Exception; }
    private void isolated(Scenario scenario) throws Exception {
        String schema = "court_seed_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = DriverManager.getConnection(System.getenv("BCM_TEST_DB_URL"),
                System.getenv("BCM_TEST_DB_USERNAME"), System.getenv("BCM_TEST_DB_PASSWORD"));
             Statement sql = connection.createStatement()) {
            sql.execute("CREATE SCHEMA " + schema);
            try {
                sql.execute("SET search_path TO " + schema + ", public");
                sql.execute(file("migration.sql"));
                sql.execute(upgrade("district"));
                scenario.run(sql);
            } finally {
                sql.execute("ROLLBACK");
                sql.execute("SET search_path TO public");
                sql.execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }
    private String scalar(Statement sql, String query) throws Exception {
        try (var rows = sql.executeQuery(query)) { rows.next(); return rows.getString(1); }
    }
    private String protectedSnapshot(Statement sql) throws Exception {
        return scalar(sql, "SELECT jsonb_build_object("
                + "'courts',(SELECT jsonb_agg(to_jsonb(c)-'name'-'district'-'updated_at'-'address' ORDER BY id) FROM courts c),"
                + "'bookings',(SELECT jsonb_agg(to_jsonb(b) ORDER BY id) FROM bookings b),"
                + "'details',(SELECT jsonb_agg(to_jsonb(d) ORDER BY id) FROM booking_details d),"
                + "'payments',(SELECT jsonb_agg(to_jsonb(p) ORDER BY id) FROM payment_transactions p),"
                + "'slots',(SELECT jsonb_agg(to_jsonb(t) ORDER BY id) FROM time_slots t))::text");
    }
    private void assertCatalog(Statement sql) throws Exception {
        assertThat(scalar(sql, "SELECT count(*) FROM courts")).isEqualTo("15");
        assertThat(scalar(sql, "SELECT count(*) FROM courts WHERE NULLIF(btrim(district),'') IS NULL")).isEqualTo("0");
        assertThat(scalar(sql, "SELECT count(DISTINCT district) FROM courts")).isEqualTo("7");
        assertThat(scalar(sql, "SELECT count(*) FROM courts WHERE name LIKE '[DEMO] %'")).isEqualTo("0");
    }
    @Test void freshMigrationSeedsFifteenCourtsAndRepeatDoesNotWriteAgain() throws Exception {
        isolated(sql -> {
            String seed = seed("districts");
            sql.execute(seed);
            assertCatalog(sql);
            String first = scalar(sql, "SELECT jsonb_agg(to_jsonb(c) ORDER BY id)::text FROM courts c");
            sql.execute(seed);
            assertThat(scalar(sql, "SELECT jsonb_agg(to_jsonb(c) ORDER BY id)::text FROM courts c")).isEqualTo(first);
        });
    }
    @Test void convertsKnownLegacyRowsInPlaceAndPreservesPricesHistoryAndSlots() throws Exception {
        isolated(sql -> {
            String seed = seed("districts");
            sql.execute(seed);
            sql.execute("UPDATE courts SET name='[DEMO] '||name WHERE court_code BETWEEN 10001 AND 10007");
            sql.execute("UPDATE courts SET district=NULL WHERE court_code BETWEEN 1 AND 8");
            sql.execute("INSERT INTO users(email,password_hash,full_name,phone) VALUES ('court-seed@test.invalid','fixture','Court seed fixture','0900000000')");
            sql.execute("INSERT INTO customers(user_id,full_name,phone) SELECT id,'Court seed fixture','0900000000' FROM users");
            sql.execute("INSERT INTO bookings(customer_id,status,court_fee) SELECT id,'PAID',250000 FROM customers");
            sql.execute("INSERT INTO booking_details(booking_id,court_id,time_slot_id,booking_date,price) SELECT b.id,c.id,t.id,DATE '2032-01-01',CASE WHEN t.start_time=TIME '06:00' THEN 100000 ELSE 150000 END FROM bookings b CROSS JOIN courts c CROSS JOIN time_slots t WHERE c.court_code=10001 AND t.start_time IN (TIME '06:00',TIME '17:00')");
            sql.execute("INSERT INTO payment_transactions(booking_id,payment_method,status,amount) SELECT id,'VNPAY','SUCCESS',250000 FROM bookings");
            String before = protectedSnapshot(sql);
            sql.execute(seed); sql.execute(seed);
            assertCatalog(sql);
            assertThat(protectedSnapshot(sql)).isEqualTo(before);
        });
    }
    @Test void retainsNonemptyDistrictAndOperatorPriceStatusEdits() throws Exception {
        isolated(sql -> {
            String seed = seed("districts");
            sql.execute(seed);
            sql.execute("UPDATE courts SET district='Quận 7',base_price=999,status='CLOSED' WHERE court_code IN (1,10001)");
            String before = scalar(sql, "SELECT jsonb_agg(to_jsonb(c) ORDER BY id)::text FROM courts c");
            sql.execute(seed);
            assertThat(scalar(sql, "SELECT jsonb_agg(to_jsonb(c) ORDER BY id)::text FROM courts c")).isEqualTo(before);
        });
    }
    @Test void conflictingReservedUuidRollsBackEveryDistrictChange() throws Exception {
        isolated(sql -> {
            sql.execute("INSERT INTO courts(id,court_code,name,base_price) VALUES ('bcdd0000-0000-4000-8000-000000000007',9999,'Other operator court',123)");
            String before = scalar(sql, "SELECT jsonb_agg(to_jsonb(c) ORDER BY id)::text FROM courts c");
            String seed = seed("districts");
            assertThatThrownBy(() -> sql.execute(seed)).isInstanceOf(java.sql.SQLException.class).hasMessageContaining("identifiers conflict");
            sql.execute("ROLLBACK");
            assertThat(scalar(sql, "SELECT jsonb_agg(to_jsonb(c) ORDER BY id)::text FROM courts c")).isEqualTo(before);
        });
    }
    @Test void unknownOriginalFixtureDoesNotGetAnInventedDistrict() throws Exception {
        isolated(sql -> {
            sql.execute("UPDATE courts SET name='Real operator court' WHERE court_code=1");
            String seed = seed("districts");
            assertThatThrownBy(() -> sql.execute(seed)).isInstanceOf(java.sql.SQLException.class).hasMessageContaining("fixtures are missing or changed");
            sql.execute("ROLLBACK");
            assertThat(scalar(sql, "SELECT count(*) FROM courts")).isEqualTo("8");
            assertThat(scalar(sql, "SELECT count(*) FROM courts WHERE district IS NOT NULL")).isEqualTo("0");
        });
    }

    @Test void metadataUpgradeAndSeedRecreateFifteenAddressesWithoutChangingHistory() throws Exception {
        isolated(sql -> {
            sql.execute(seed("districts"));
            String before=protectedSnapshot(sql);
            sql.execute(upgrade("court-metadata"));
            sql.execute(upgrade("court-metadata"));
            sql.execute(seed("court-metadata"));
            assertThat(scalar(sql,"SELECT count(*) FROM courts WHERE address LIKE '%mô phỏng%' AND position(district in address)>0")).isEqualTo("15");
            assertThat(scalar(sql,"SELECT count(DISTINCT type) FROM courts")).isEqualTo("2");
            // Compare all original columns; only address/updated_at are permitted to change.
            assertThat(protectedSnapshot(sql)).isEqualTo(before);
            String first=scalar(sql,"SELECT jsonb_agg(to_jsonb(c) ORDER BY id)::text FROM courts c");
            sql.execute(seed("court-metadata"));
            assertThat(scalar(sql,"SELECT jsonb_agg(to_jsonb(c) ORDER BY id)::text FROM courts c")).isEqualTo(first);
        });
    }

    @Test void metadataSeedPreservesOperatorEditsAndLeavesUnknownCourtsUnverified() throws Exception {
        isolated(sql -> {
            sql.execute(seed("districts"));
            sql.execute(upgrade("court-metadata"));
            sql.execute("UPDATE courts SET address='Operator verified address',type='WOODEN_FLOOR',base_price=777 WHERE court_code=10001");
            sql.execute("UPDATE courts SET district='Quận 7' WHERE court_code=10002");
            sql.execute("INSERT INTO courts(court_code,name,type,base_price,district) VALUES(9999,'Real unverified',NULL,100,'Quận 1')");
            sql.execute(seed("court-metadata"));
            assertThat(scalar(sql,"SELECT address FROM courts WHERE court_code=10001")).isEqualTo("Operator verified address");
            assertThat(scalar(sql,"SELECT type FROM courts WHERE court_code=10001")).isEqualTo("WOODEN_FLOOR");
            assertThat(scalar(sql,"SELECT count(*) FROM courts WHERE court_code IN (10002,9999) AND address IS NULL")).isEqualTo("2");
            assertThat(scalar(sql,"SELECT count(*) FROM courts")).isEqualTo("16");
        });
    }

    @Test void incompatibleLegacySurfaceStopsMigrationWithoutConvertingData() throws Exception {
        isolated(sql -> {
            sql.execute("UPDATE courts SET type='VIP' WHERE court_code=1");
            assertThatThrownBy(() -> sql.execute(upgrade("court-metadata")))
                .isInstanceOf(java.sql.SQLException.class).hasMessageContaining("courts_surface_type_check");
            sql.execute("ROLLBACK");
            assertThat(scalar(sql,"SELECT type FROM courts WHERE court_code=1")).isEqualTo("VIP");
        });
    }
}
