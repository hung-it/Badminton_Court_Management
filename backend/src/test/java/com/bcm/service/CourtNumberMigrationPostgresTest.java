package com.bcm.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.UUID;
import static com.bcm.service.SqlScriptSections.upgrade;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="BCM_TEST_DB_URL", matches="jdbc:postgresql:.*")
class CourtNumberMigrationPostgresTest {
    interface Scenario { void run(Statement sql) throws Exception; }
    void isolated(Scenario scenario) throws Exception {
        String schema="court_numbers_"+UUID.randomUUID().toString().replace("-", "");
        try(var connection=DriverManager.getConnection(System.getenv("BCM_TEST_DB_URL"),System.getenv("BCM_TEST_DB_USERNAME"),System.getenv("BCM_TEST_DB_PASSWORD")); var sql=connection.createStatement()) {
            sql.execute("CREATE SCHEMA "+schema);
            try {
                sql.execute("SET search_path TO "+schema+",public");
                sql.execute(Files.readString(Path.of("../db/migration.sql")));
                // Reconstruct the previous branch columns over the unchanged historical migration.
                String legacy=upgrade("branches").substring(upgrade("court-numbers").length())
                        .replaceAll("\\bcourt_number\\b", "branch_court_number");
                sql.execute(legacy);
                sql.execute("INSERT INTO branches(id,code,name,district,address) SELECT md5('number-branch-'||n)::uuid,'N-'||n,'Branch '||n,'Area '||(n%7),'Address' FROM generate_series(1,35)n");
                sql.execute("UPDATE courts SET branch_id=md5('number-branch-'||court_number)::uuid,branch_court_number=1");
                sql.execute("INSERT INTO courts(id,court_number,name,type,base_price,branch_id,branch_court_number) SELECT md5('number-court-'||b||'-'||n)::uuid,1000000+b*10+n,'Court '||b||'-'||n,'STANDARD_MAT',100000,md5('number-branch-'||b)::uuid,n FROM generate_series(1,35)b CROSS JOIN generate_series(1,6)n WHERE NOT(n=1 AND b<=8)");
                sql.execute("INSERT INTO users(email,password_hash,full_name,phone) VALUES ('number@fixture.test','test','Number','0900000000')");
                sql.execute("INSERT INTO customers(user_id,full_name,phone) SELECT id,'Number','0900000000' FROM users");
                sql.execute("INSERT INTO bookings(customer_id,status,court_fee) SELECT id,'PAID',100000 FROM customers");
                sql.execute("INSERT INTO booking_details(booking_id,court_id,time_slot_id,booking_date,price) SELECT b.id,c.id,s.id,DATE '2035-01-01',100000 FROM bookings b CROSS JOIN courts c CROSS JOIN time_slots s LIMIT 1");
                sql.execute("INSERT INTO payment_transactions(booking_id,payment_method,status,amount,note) SELECT id,'BANK_TRANSFER','PENDING',100000,'CUSTOMER_DECLARED; receipt=UNVERIFIED' FROM bookings");
                scenario.run(sql);
            } finally { sql.execute("ROLLBACK");sql.execute("SET search_path TO public");sql.execute("DROP SCHEMA "+schema+" CASCADE"); }
        }
    }
    String scalar(Statement sql,String query) throws Exception {try(var rows=sql.executeQuery(query)){rows.next();return rows.getString(1);}}
    String history(Statement sql) throws Exception {
        return scalar(sql,"SELECT jsonb_build_array((SELECT jsonb_agg(to_jsonb(b) ORDER BY id) FROM bookings b),(SELECT jsonb_agg(to_jsonb(d) ORDER BY id) FROM booking_details d),(SELECT jsonb_agg(to_jsonb(p) ORDER BY id) FROM payment_transactions p),(SELECT jsonb_agg(to_jsonb(c) ORDER BY id) FROM customers c))::text");
    }
    @Test void renamesAll210UsingExistingMappingWithoutUpdatingIdsPricesOrHistoryAndIsRepeatable() throws Exception {
        isolated(sql->{
            String expected=scalar(sql,"SELECT jsonb_agg((to_jsonb(c)-'court_number'-'branch_court_number')||jsonb_build_object('court_code',court_number,'court_number',branch_court_number) ORDER BY id)::text FROM courts c");
            String protectedHistory=history(sql);
            sql.execute(upgrade("court-numbers"));sql.execute(upgrade("court-numbers"));
            assertThat(scalar(sql,"SELECT jsonb_agg(to_jsonb(c) ORDER BY id)::text FROM courts c")).isEqualTo(expected);
            assertThat(history(sql)).isEqualTo(protectedHistory);
            assertThat(scalar(sql,"SELECT count(*) FROM (SELECT branch_id FROM courts GROUP BY branch_id HAVING array_agg(court_number ORDER BY court_number)=ARRAY[1,2,3,4,5,6]) x")).isEqualTo("35");
            assertThatThrownBy(()->sql.execute("UPDATE courts SET court_number=7 WHERE court_code=1")).isInstanceOf(SQLException.class);
            assertThatThrownBy(()->sql.execute("UPDATE courts SET court_number=2 WHERE court_code=1")).isInstanceOf(SQLException.class);
            assertThat(scalar(sql,"SELECT count(*) FROM courts")).isEqualTo("210");
        });
    }
    @Test void invalidLocalMappingRollsBackEveryColumnRename() throws Exception {
        isolated(sql->{
            sql.execute("UPDATE courts SET branch_court_number=7 WHERE court_number=1");
            String before=scalar(sql,"SELECT jsonb_agg(to_jsonb(c) ORDER BY id)::text FROM courts c");
            assertThatThrownBy(()->sql.execute(upgrade("court-numbers"))).isInstanceOf(SQLException.class).hasMessageContaining("BLOCKED");
            sql.execute("ROLLBACK");
            assertThat(scalar(sql,"SELECT jsonb_agg(to_jsonb(c) ORDER BY id)::text FROM courts c")).isEqualTo(before);
            assertThat(scalar(sql,"SELECT count(*) FROM pg_attribute WHERE attrelid='courts'::regclass AND attname='court_code' AND NOT attisdropped")).isEqualTo("0");
        });
    }
}
