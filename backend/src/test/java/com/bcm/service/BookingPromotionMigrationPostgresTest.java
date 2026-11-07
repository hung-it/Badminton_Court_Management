package com.bcm.service;

import static com.bcm.service.SqlScriptSections.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named="BCM_TEST_DB_URL",matches="jdbc:postgresql:.*")
class BookingPromotionMigrationPostgresTest {
    @Test void upgradeTwicePreservesPaidHistoryAndSeedsNonExpiringVouchers() throws Exception {
        String schema="promotion_upgrade_"+UUID.randomUUID().toString().replace("-","");
        try(var c=DriverManager.getConnection(System.getenv("BCM_TEST_DB_URL"),System.getenv("BCM_TEST_DB_USERNAME"),System.getenv("BCM_TEST_DB_PASSWORD"));var s=c.createStatement()) {
            s.execute("CREATE SCHEMA "+schema);
            try {
                s.execute("SET search_path TO "+schema+", public");
                s.execute(Files.readString(Path.of("../db/migration.sql")));
                s.execute("INSERT INTO users(email,password_hash,full_name,phone) VALUES ('legacy@promo.test','test','Legacy','0900000000')");
                s.execute("INSERT INTO customers(user_id,full_name,phone) SELECT id,'Legacy','0900000000' FROM users");
                s.execute("INSERT INTO bookings(customer_id,status,court_fee) SELECT id,'PAID',720000 FROM customers");
                s.execute("INSERT INTO payment_transactions(booking_id,payment_method,status,amount) SELECT id,'VNPAY','SUCCESS',720000 FROM bookings");
                String sql=upgrade("promotions");s.execute(sql);s.execute(sql);
                try(var r=s.executeQuery("SELECT court_fee,discount_amount,final_amount,(SELECT amount FROM payment_transactions) FROM bookings")) {
                    assertThat(r.next()).isTrue();assertThat(r.getBigDecimal(1)).isEqualByComparingTo("720000");
                    assertThat(r.getBigDecimal(2)).isEqualByComparingTo("0");assertThat(r.getBigDecimal(3)).isEqualByComparingTo("720000");
                    assertThat(r.getBigDecimal(4)).isEqualByComparingTo("720000");
                }
                try(var r=s.executeQuery("SELECT count(*),count(valid_to),(SELECT count(*) FROM discount_rules) FROM promotions WHERE code IN ('NEWMEMBER','KMKH10')")) {
                    r.next();assertThat(r.getInt(1)).isEqualTo(2);assertThat(r.getInt(2)).isZero();assertThat(r.getInt(3)).isEqualTo(2);
                }
            } finally { s.execute("SET search_path TO public");s.execute("DROP SCHEMA "+schema+" CASCADE"); }
        }
    }
}
