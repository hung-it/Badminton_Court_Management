package com.bcm.service;

import static com.bcm.service.SqlScriptSections.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.nio.file.*;
import java.sql.*;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="BCM_TEST_DB_URL",matches="jdbc:postgresql:.*")
class BranchSeedPostgresTest {
    String file(String name) throws Exception {return Files.readString(Path.of("../db/"+name));}
    interface Scenario {void run(Statement sql) throws Exception;}
    void isolated(Scenario scenario) throws Exception {
        String schema="branch_seed_"+UUID.randomUUID().toString().replace("-","");
        try(var connection=DriverManager.getConnection(System.getenv("BCM_TEST_DB_URL"),System.getenv("BCM_TEST_DB_USERNAME"),System.getenv("BCM_TEST_DB_PASSWORD"));var sql=connection.createStatement()) {
            sql.execute("CREATE SCHEMA "+schema);
            try {
                sql.execute("SET search_path TO "+schema+",public");
                for(String script:new String[]{file("migration.sql"),upgrade("district"),seed("districts"),upgrade("contact"),upgrade("court-metadata"),seed("court-metadata")}) sql.execute(script);
                scenario.run(sql);
            } finally {sql.execute("ROLLBACK");sql.execute("SET search_path TO public");sql.execute("DROP SCHEMA "+schema+" CASCADE");}
        }
    }
    String scalar(Statement sql,String query) throws Exception {try(var rows=sql.executeQuery(query)){rows.next();return rows.getString(1);}}
    String history(Statement sql) throws Exception {return scalar(sql,"SELECT jsonb_build_object('b',(SELECT jsonb_agg(to_jsonb(b) ORDER BY id) FROM bookings b),'d',(SELECT jsonb_agg(to_jsonb(d) ORDER BY id) FROM booking_details d),'p',(SELECT jsonb_agg(to_jsonb(p) ORDER BY id) FROM payment_transactions p),'s',(SELECT jsonb_agg(to_jsonb(s) ORDER BY id) FROM time_slots s))::text");}
    void legacyCatalog(Statement sql) throws Exception {
        String seed=seed("branches");
        sql.execute(seed.substring(0,seed.indexOf("-- Independent branch migration:"))+"COMMIT;");
    }

    @Test void additiveMigrationAndSeedTwicePreserveIdsPricesAndBookedFacilityAndHistory() throws Exception {
        isolated(sql->{
            sql.execute("INSERT INTO users(email,password_hash,full_name,phone) VALUES('branch@fixture.test','test','Test','0900000000')");
            sql.execute("INSERT INTO customers(user_id,full_name,phone) SELECT id,'Test','0900000000' FROM users");
            sql.execute("INSERT INTO bookings(customer_id,status,court_fee) SELECT id,'PAID',100000 FROM customers");
            sql.execute("INSERT INTO booking_details(booking_id,court_id,time_slot_id,booking_date,price) SELECT b.id,c.id,s.id,DATE '2035-01-01',100000 FROM bookings b CROSS JOIN courts c CROSS JOIN time_slots s WHERE c.court_code=10001 AND s.start_time=TIME '12:00'");
            sql.execute("INSERT INTO payment_transactions(booking_id,payment_method,status,amount) SELECT id,'VNPAY','SUCCESS',100000 FROM bookings");
            String ids=scalar(sql,"SELECT jsonb_agg(jsonb_build_array(id,court_code,base_price,type,status) ORDER BY id)::text FROM courts");
            String protectedData=history(sql);
            sql.execute(upgrade("branches"));sql.execute(upgrade("branches"));
            sql.execute(seed("branches"));
            assertThat(scalar(sql,"SELECT count(*) FROM branches")).isEqualTo("35");
            assertThat(scalar(sql,"SELECT count(*) FROM courts")).isEqualTo("210");
            assertThat(scalar(sql,"SELECT count(*) FROM (SELECT branch_id FROM courts WHERE branch_id IS NOT NULL AND status='AVAILABLE' AND deleted_at IS NULL GROUP BY branch_id HAVING count(*)=6) x")).isEqualTo("35");
            assertThat(scalar(sql,"SELECT count(*) FROM courts WHERE branch_id IS NULL")).isEqualTo("0");
            assertThat(scalar(sql,"SELECT jsonb_agg(jsonb_build_array(id,court_code,base_price,type,status) ORDER BY id)::text FROM courts WHERE court_code<20000")).isEqualTo(ids);
            assertThat(history(sql)).isEqualTo(protectedData);
            assertThat(scalar(sql,"SELECT count(*) FROM courts c JOIN branches b ON b.id=c.branch_id WHERE c.district<>b.district OR c.address<>b.address")).isEqualTo("0");
            assertThat(scalar(sql,"SELECT count(*) FROM courts WHERE name LIKE '%[DEMO]%' OR address LIKE '%mô phỏng%' OR address LIKE '%giả lập%'")).isEqualTo("0");
            String first=scalar(sql,"SELECT jsonb_agg(to_jsonb(c) ORDER BY id)::text FROM courts c");
            sql.execute(seed("branches"));
            assertThat(scalar(sql,"SELECT jsonb_agg(to_jsonb(c) ORDER BY id)::text FROM courts c")).isEqualTo(first);
        });
    }
    @Test void operatorFacilityAddressIsNeverSilentlyMappedOrOverwritten() throws Exception {
        isolated(sql->{
            sql.execute(upgrade("branches"));
            sql.execute("UPDATE courts SET address='Operator verified distinct facility' WHERE court_code=10001");
            assertThatThrownBy(()->sql.execute(seed("branches"))).isInstanceOf(SQLException.class).hasMessageContaining("manual review required");
            sql.execute("ROLLBACK");
            assertThat(scalar(sql,"SELECT count(*) FROM branches")).isEqualTo("0");
            assertThat(scalar(sql,"SELECT count(*) FROM courts")).isEqualTo("15");
            assertThat(scalar(sql,"SELECT address FROM courts WHERE court_code=10001")).isEqualTo("Operator verified distinct facility");
        });
    }

    void recreatePreviousFifty(Statement sql) throws Exception {
        sql.execute("""
            INSERT INTO courts(id,court_code,name,type,status,base_price,district,address,branch_id,court_number)
            SELECT md5('bcm:branch:'||b.code||':court:'||n)::uuid,20000+right(b.id::text,1)::int*100+n,
              'Cơ sở '||b.district||' - Sân '||lpad(n::text,2,'0'),a.type,'AVAILABLE',a.base_price,b.district,b.address,b.id,1000000+right(b.id::text,1)::int*100+n
            FROM branches b CROSS JOIN generate_series(2,6) n JOIN courts a ON a.id=('bcdd0000-0000-4000-8000-'||lpad(right(b.id::text,1),12,'0'))::uuid
            ON CONFLICT(id) DO NOTHING
            """);
        assertThat(scalar(sql,"SELECT count(*) FROM courts")).isEqualTo("50");
    }

    @Test void restoresThirtyFiveFromFiftyAndRetainsBookedAndOtherFkReferencedGeneratedCourts() throws Exception {
        isolated(sql->{
            sql.execute(upgrade("branches"));legacyCatalog(sql);recreatePreviousFifty(sql);
            // A separate dependency with CASCADE must be discovered and preserved as well.
            sql.execute("CREATE TABLE fixture_court_dependency(court_id uuid REFERENCES courts(id) ON DELETE CASCADE)");
            sql.execute("INSERT INTO fixture_court_dependency SELECT id FROM courts WHERE court_code=20106");
            sql.execute("INSERT INTO users(email,password_hash,full_name,phone) VALUES('generated@fixture.test','test','Test','0900000000')");
            sql.execute("INSERT INTO customers(user_id,full_name,phone) SELECT id,'Test','0900000000' FROM users");
            sql.execute("INSERT INTO bookings(customer_id,status,court_fee) SELECT id,'PAID',120000 FROM customers");
            sql.execute("INSERT INTO booking_details(booking_id,court_id,time_slot_id,booking_date,price) SELECT b.id,c.id,s.id,DATE '2035-01-01',120000 FROM bookings b CROSS JOIN courts c CROSS JOIN time_slots s WHERE c.court_code=20206 AND s.start_time=TIME '12:00'");
            sql.execute("INSERT INTO payment_transactions(booking_id,payment_method,status,amount) SELECT id,'VNPAY','SUCCESS',120000 FROM bookings");
            String ids=scalar(sql,"SELECT jsonb_agg(jsonb_build_array(id,court_code,name,type,base_price) ORDER BY id)::text FROM courts WHERE court_code BETWEEN 1 AND 8");
            String protectedData=history(sql);
            sql.execute(seed("branches"));
            assertThat(scalar(sql,"SELECT count(*) FROM courts")).isEqualTo("210");
            assertThat(scalar(sql,"SELECT count(*) FROM courts WHERE court_code=20106")).isEqualTo("1");
            assertThat(scalar(sql,"SELECT count(*) FROM courts WHERE court_code=20206")).isEqualTo("1");
            assertThat(scalar(sql,"SELECT count(*) FROM fixture_court_dependency")).isEqualTo("1");
            assertThat(scalar(sql,"SELECT jsonb_agg(jsonb_build_array(id,court_code,name,type,base_price) ORDER BY id)::text FROM courts WHERE court_code BETWEEN 1 AND 8")).isEqualTo(ids);
            assertThat(scalar(sql,"SELECT count(*) FROM courts WHERE court_code BETWEEN 1 AND 8 AND branch_id IS NOT NULL")).isEqualTo("8");
            assertThat(history(sql)).isEqualTo(protectedData);
            String first=scalar(sql,"SELECT jsonb_agg(to_jsonb(c) ORDER BY id)::text FROM courts c");
            sql.execute(seed("districts"));sql.execute(seed("court-metadata"));sql.execute(seed("branches"));
            assertThat(scalar(sql,"SELECT jsonb_agg(to_jsonb(c) ORDER BY id)::text FROM courts c")).isEqualTo(first);
        });
    }

    @Test void tooManyDependenciesBlockWholeReconciliationWithoutDeletingHistory() throws Exception {
        isolated(sql->{
            sql.execute(upgrade("branches"));legacyCatalog(sql);recreatePreviousFifty(sql);
            sql.execute("CREATE TABLE fixture_court_dependency(court_id uuid REFERENCES courts(id))");
            sql.execute("INSERT INTO fixture_court_dependency SELECT id FROM courts WHERE court_code BETWEEN 20102 AND 20106");
            String before=scalar(sql,"SELECT jsonb_agg(to_jsonb(c) ORDER BY id)::text FROM courts c");
            assertThatThrownBy(()->sql.execute(seed("branches"))).isInstanceOf(SQLException.class).hasMessageContaining("too many protected courts");
            sql.execute("ROLLBACK");
            assertThat(scalar(sql,"SELECT jsonb_agg(to_jsonb(c) ORDER BY id)::text FROM courts c")).isEqualTo(before);
            assertThat(scalar(sql,"SELECT count(*) FROM fixture_court_dependency")).isEqualTo("5");
        });
    }

    @Test void consolidatedComposeSequenceCreatesCatalogAndRepeatsWithoutChanges() throws Exception {
        // A second isolated schema follows exactly the remaining Compose init mounts.
        String schema="branch_clean_"+UUID.randomUUID().toString().replace("-","");
        try(var connection=DriverManager.getConnection(System.getenv("BCM_TEST_DB_URL"),System.getenv("BCM_TEST_DB_USERNAME"),System.getenv("BCM_TEST_DB_PASSWORD"));var sql=connection.createStatement()) {
            sql.execute("CREATE SCHEMA "+schema);
            try {
                sql.execute("SET search_path TO "+schema+",public");
                for(String script:new String[]{"migration.sql","booking-ux-upgrade.sql","branches-seed.sql"})sql.execute(file(script));
                assertThat(scalar(sql,"SELECT count(*) FROM courts")).isEqualTo("210");
                assertThat(scalar(sql,"SELECT count(*) FROM courts WHERE branch_id IS NULL OR district IS NULL OR address IS NULL")).isEqualTo("0");
                assertThat(scalar(sql,"SELECT count(DISTINCT branch_id) FROM courts")).isEqualTo("35");
                assertThat(scalar(sql,"SELECT count(DISTINCT name) FROM courts")).isEqualTo("210");
                assertThat(scalar(sql,"SELECT count(*) FROM courts WHERE name ~ ' - Sân [0-9]+$'")).isEqualTo("0");
                assertThat(scalar(sql,"SELECT count(*) FROM promotions WHERE code IN ('NEWMEMBER','KMKH10') AND valid_to IS NULL")).isEqualTo("2");
                assertThat(scalar(sql,"SELECT count(*) FROM discount_rules WHERE voucher_code IN ('NEWMEMBER','KMKH10')")).isEqualTo("2");
                String first=scalar(sql,"SELECT jsonb_agg(to_jsonb(c) ORDER BY id)::text FROM courts c");
                sql.execute(file("booking-ux-upgrade.sql"));sql.execute(file("branches-seed.sql"));
                assertThat(scalar(sql,"SELECT jsonb_agg(to_jsonb(c) ORDER BY id)::text FROM courts c")).isEqualTo(first);
            } finally {sql.execute("ROLLBACK");sql.execute("SET search_path TO public");sql.execute("DROP SCHEMA "+schema+" CASCADE");}
        }
    }
}
