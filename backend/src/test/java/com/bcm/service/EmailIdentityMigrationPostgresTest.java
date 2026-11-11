package com.bcm.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.nio.file.*;
import java.sql.*;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="BCM_TEST_DB_URL",matches="jdbc:postgresql:.*")
class EmailIdentityMigrationPostgresTest {
    @Test void normalizesOncePreservesIdentityAndRejectsCaseDuplicates() throws Exception {
        scenario(false);
    }
    @Test void collisionsRollbackWithoutMergingOrChangingAnyAccount() throws Exception {
        scenario(true);
    }
    void scenario(boolean collision) throws Exception {
        String schema="p2_email_"+UUID.randomUUID().toString().replace("-","");
        try(var c=DriverManager.getConnection(System.getenv("BCM_TEST_DB_URL"),System.getenv("BCM_TEST_DB_USERNAME"),System.getenv("BCM_TEST_DB_PASSWORD"));var s=c.createStatement()) {
            s.execute("CREATE SCHEMA "+schema);
            try {
                s.execute("SET search_path TO "+schema+",public");
                s.execute("CREATE TABLE users(id uuid PRIMARY KEY,email varchar(255) UNIQUE,password_hash text,deleted_at timestamp)");
                s.execute("INSERT INTO users VALUES ('00000000-0000-4000-8000-000000000001',' Mixed@Example.com ','unchanged',NULL)");
                if(collision)s.execute("INSERT INTO users VALUES ('00000000-0000-4000-8000-000000000002','mixed@example.com','other',CURRENT_TIMESTAMP)");
                String before=snapshot(s),upgrade=Files.readString(Path.of("../db/upgrades/email-identity-upgrade.sql"));
                if(collision) {
                    assertThatThrownBy(()->s.execute(upgrade)).isInstanceOf(SQLException.class).hasMessageContaining("collisions require review");
                    s.execute("ROLLBACK");
                    assertThat(snapshot(s)).isEqualTo(before);
                } else {
                    s.execute(upgrade);
                    assertThat(value(s,"SELECT email FROM users")).isEqualTo("mixed@example.com");
                    assertThat(value(s,"SELECT id||'/'||password_hash FROM users")).isEqualTo("00000000-0000-4000-8000-000000000001/unchanged");
                    String normalized=snapshot(s);s.execute(upgrade);assertThat(snapshot(s)).isEqualTo(normalized);
                    assertThatThrownBy(()->s.execute("INSERT INTO users(id,email) VALUES (gen_random_uuid(),'MIXED@example.com')"))
                            .isInstanceOf(SQLException.class).hasMessageContaining("uq_users_email_lower");
                }
            } finally {s.execute("ROLLBACK");s.execute("SET search_path TO public");s.execute("DROP SCHEMA "+schema+" CASCADE");}
        }
    }
    String snapshot(Statement s) throws SQLException {return value(s,"SELECT jsonb_agg(to_jsonb(u) ORDER BY id)::text FROM users u");}
    String value(Statement s,String query) throws SQLException {try(var r=s.executeQuery(query)){r.next();return r.getString(1);}}
}
