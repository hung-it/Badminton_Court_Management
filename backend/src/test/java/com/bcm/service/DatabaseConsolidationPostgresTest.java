package com.bcm.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static com.bcm.service.SqlScriptSections.*;

/** Compares the previous bootstrap order with the consolidated entry points on PostgreSQL. */
@EnabledIfEnvironmentVariable(named="BCM_TEST_DB_URL",matches="jdbc:postgresql:.*")
class DatabaseConsolidationPostgresTest {
    private String scalar(Statement sql,String query) throws Exception {
        try(var rows=sql.executeQuery(query)) {rows.next();return rows.getString(1);}
    }

    @Test void consolidatedScriptsMatchPreviousOrderAndPreservePopulatedBookingHistory() throws Exception {
        String oldSchema="db_before_"+UUID.randomUUID().toString().replace("-","");
        String newSchema="db_after_"+UUID.randomUUID().toString().replace("-","");
        try(var connection=DriverManager.getConnection(System.getenv("BCM_TEST_DB_URL"),System.getenv("BCM_TEST_DB_USERNAME"),System.getenv("BCM_TEST_DB_PASSWORD"));var sql=connection.createStatement()) {
            sql.execute("CREATE SCHEMA "+oldSchema);sql.execute("CREATE SCHEMA "+newSchema);
            try {
                String expected=null;
                for(String schema:new String[]{oldSchema,newSchema}) {
                    sql.execute("SET search_path TO "+schema+",public");
                    sql.execute(Files.readString(Path.of("../db/migration.sql")));
                    sql.execute(upgrade("court-numbers"));
                    // The historical migration generates UUIDs. Use the same synthetic identities
                    // in both isolated schemas so every fixture and foreign key can be compared.
                    sql.execute("UPDATE courts SET id=md5('consolidation:court:'||court_code)::uuid");
                    sql.execute("UPDATE time_slots SET id=md5('consolidation:slot:'||start_time)::uuid");
                    sql.execute("INSERT INTO users(id,email,password_hash,full_name,phone) VALUES ('aaaaaaaa-0000-4000-8000-000000000001','preserve@test.invalid','test','History','0900000000')");
                    sql.execute("INSERT INTO customers(id,user_id,full_name,phone) VALUES ('aaaaaaaa-0000-4000-8000-000000000002','aaaaaaaa-0000-4000-8000-000000000001','History','0900000000')");
                    sql.execute("INSERT INTO bookings(id,customer_id,status,court_fee) VALUES ('aaaaaaaa-0000-4000-8000-000000000003','aaaaaaaa-0000-4000-8000-000000000002','PAID',720000)");
                    sql.execute("INSERT INTO booking_details(booking_id,court_id,time_slot_id,booking_date,price) SELECT 'aaaaaaaa-0000-4000-8000-000000000003',c.id,t.id,DATE '2035-01-01',720000 FROM courts c CROSS JOIN time_slots t LIMIT 1");
                    sql.execute("INSERT INTO payment_transactions(booking_id,payment_method,status,amount) VALUES ('aaaaaaaa-0000-4000-8000-000000000003','VNPAY','SUCCESS',720000)");
                    String protectedBefore=protectedData(sql);
                    if(schema.equals(oldSchema)) {
                        for(String stage:new String[]{upgrade("district"),upgrade("contact"),upgrade("court-metadata"),upgrade("branches"),seed("branches"),upgrade("promotions"),upgrade("checkout-release")})sql.execute(stage);
                    } else {
                        sql.execute(Files.readString(Path.of("../db/booking-ux-upgrade.sql")));
                        sql.execute(Files.readString(Path.of("../db/branches-seed.sql")));
                    }
                    assertThat(protectedData(sql)).isEqualTo(protectedBefore);
                    assertThat(scalar(sql,"SELECT count(*) FROM courts")).isEqualTo("210");
                    assertThat(scalar(sql,"SELECT count(*) FROM branches")).isEqualTo("35");
                    assertThat(scalar(sql,"SELECT court_fee||'/'||discount_amount||'/'||final_amount FROM bookings")).isEqualTo("720000.00/0.00/720000.00");
                    String actual=scalar(sql,"SELECT jsonb_build_object("
                        +"'courts',(SELECT jsonb_agg(to_jsonb(c)-'created_at'-'updated_at' ORDER BY id) FROM courts c),"
                        +"'branches',(SELECT jsonb_agg(to_jsonb(b)-'created_at'-'updated_at' ORDER BY id) FROM branches b),"
                        +"'promotions',(SELECT jsonb_agg(to_jsonb(p)-'id'-'created_at'-'updated_at' ORDER BY code) FROM promotions p),"
                        +"'rules',(SELECT jsonb_agg((to_jsonb(d)-'id'-'promotion_id'-'created_at')||jsonb_build_object('promotion_code',p.code) ORDER BY p.code) FROM discount_rules d JOIN promotions p ON p.id=d.promotion_id))::text");
                    if(expected==null)expected=actual;else assertThat(actual).isEqualTo(expected);
                }
            } finally {
                sql.execute("ROLLBACK");sql.execute("SET search_path TO public");
                sql.execute("DROP SCHEMA "+oldSchema+" CASCADE");sql.execute("DROP SCHEMA "+newSchema+" CASCADE");
            }
        }
    }

    private String protectedData(Statement sql) throws Exception {
        return scalar(sql,"SELECT jsonb_build_object("
            +"'bookings',(SELECT jsonb_agg(to_jsonb(b)-'contact_full_name'-'contact_phone'-'discount_amount'-'promotion_code'-'requested_voucher'-'final_amount'-'checkout_release_at' ORDER BY id) FROM bookings b),"
            +"'details',(SELECT jsonb_agg(to_jsonb(d) ORDER BY id) FROM booking_details d),"
            +"'payments',(SELECT jsonb_agg(to_jsonb(p) ORDER BY id) FROM payment_transactions p),"
            +"'customers',(SELECT jsonb_agg(to_jsonb(c) ORDER BY id) FROM customers c),"
            +"'slots',(SELECT jsonb_agg(to_jsonb(s) ORDER BY id) FROM time_slots s))::text");
    }
}
