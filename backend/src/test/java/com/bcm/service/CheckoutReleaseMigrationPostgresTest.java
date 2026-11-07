package com.bcm.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named="BCM_TEST_DB_URL", matches="jdbc:postgresql:.*")
class CheckoutReleaseMigrationPostgresTest {
    @Test void addsOnlyOneNullableColumnIdempotentlyAndPreservesHistory() throws Exception {
        String schema="checkout_release_"+UUID.randomUUID().toString().replace("-", "");
        try(var connection=DriverManager.getConnection(System.getenv("BCM_TEST_DB_URL"),System.getenv("BCM_TEST_DB_USERNAME"),System.getenv("BCM_TEST_DB_PASSWORD"));var sql=connection.createStatement()) {
            sql.execute("CREATE SCHEMA "+schema);
            try {
                sql.execute("SET search_path TO "+schema+",public");
                sql.execute(Files.readString(Path.of("../db/migration.sql")));
                sql.execute("INSERT INTO users(email,password_hash,full_name,phone) VALUES ('migration@hold.test','test','History','0900000000')");
                sql.execute("INSERT INTO customers(user_id,full_name,phone) SELECT id,'History','0900000000' FROM users");
                sql.execute("INSERT INTO bookings(customer_id,status,court_fee,expires_at) SELECT id,'PENDING',100000,TIMESTAMP '2031-01-01 12:10' FROM customers");
                sql.execute("INSERT INTO booking_details(booking_id,court_id,time_slot_id,booking_date,price) SELECT b.id,c.id,t.id,DATE '2031-01-01',100000 FROM bookings b CROSS JOIN courts c CROSS JOIN time_slots t LIMIT 1");
                sql.execute("INSERT INTO payment_transactions(booking_id,payment_method,status,amount) SELECT id,'VNPAY','PENDING',100000 FROM bookings");
                String snapshot="SELECT jsonb_build_object('bookings',(SELECT jsonb_agg(to_jsonb(b)-'checkout_release_at') FROM bookings b),'details',(SELECT jsonb_agg(to_jsonb(d)) FROM booking_details d),'payments',(SELECT jsonb_agg(to_jsonb(p)) FROM payment_transactions p),'courts',(SELECT jsonb_agg(to_jsonb(c) ORDER BY id) FROM courts c))::text";
                String before; try(var r=sql.executeQuery(snapshot)){r.next();before=r.getString(1);}
                var migration=SqlScriptSections.read("booking-ux-upgrade.sql", "checkout-release");
                sql.execute(migration);sql.execute(migration);
                try(var r=sql.executeQuery(snapshot)){r.next();assertThat(r.getString(1)).isEqualTo(before);}
                try(var r=sql.executeQuery("SELECT column_name,is_nullable,data_type FROM information_schema.columns WHERE table_schema='"+schema+"' AND table_name='bookings' AND column_name LIKE 'checkout_%'")) {
                    assertThat(r.next()).isTrue();assertThat(r.getString(1)).isEqualTo("checkout_release_at");
                    assertThat(r.getString(2)).isEqualTo("YES");assertThat(r.getString(3)).isEqualTo("timestamp without time zone");assertThat(r.next()).isFalse();
                }
            } finally {sql.execute("ROLLBACK");sql.execute("SET search_path TO public");sql.execute("DROP SCHEMA "+schema+" CASCADE");}
        }
    }
}
