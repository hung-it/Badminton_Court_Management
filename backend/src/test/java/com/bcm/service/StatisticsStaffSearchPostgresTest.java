package com.bcm.service;

import com.bcm.exception.BadRequestException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import java.sql.*;
import static org.assertj.core.api.Assertions.*;

/** All fixtures are connection-local TEMP tables; never writes to runtime public tables. */
@EnabledIfEnvironmentVariable(named="BCM_TEST_DB_URL",matches="jdbc:postgresql:.*")
class StatisticsStaffSearchPostgresTest {
 private static final String STAFF="f4e736e8-3069-5f8c-bf70-2b5fd9b96ace";
 Connection connection;JdbcTemplate jdbc;StatisticsService statistics;
 @BeforeEach void fixture()throws Exception {
  connection=DriverManager.getConnection(System.getenv("BCM_TEST_DB_URL"),System.getenv("BCM_TEST_DB_USERNAME"),System.getenv("BCM_TEST_DB_PASSWORD"));
  jdbc=new JdbcTemplate(new SingleConnectionDataSource(connection,true));
  jdbc.execute("CREATE TEMP TABLE staffs(id uuid,full_name text)");
  jdbc.execute("CREATE TEMP TABLE customers(id uuid,full_name text)");
  jdbc.execute("CREATE TEMP TABLE invoices(id uuid,created_by uuid,customer_id uuid,total_amount numeric,status text,deleted_at timestamp,created_at timestamp)");
  jdbc.update("INSERT INTO pg_temp.staffs VALUES (?::uuid,'Nhân viên demo 1'),('aaaaaaaa-1111-4111-8111-111111111111','Other staff')",STAFF);
  jdbc.execute("INSERT INTO pg_temp.invoices(created_by,total_amount,status,created_at) VALUES ('"+STAFF+"',100000,'PAID','2026-11-01'),('"+STAFF+"',200000,'PAID','2026-11-02'),('"+STAFF+"',999999,'DRAFT','2026-11-02'),('"+STAFF+"',999999,'PAID','2025-11-02'),('aaaaaaaa-1111-4111-8111-111111111111',50000,'PAID','2026-11-03')");
  statistics=new StatisticsService(null,null,jdbc,null);
 }
 @AfterEach void close()throws Exception {if(connection!=null)connection.close();}
 private StatisticsService.Matrix report(String search){return statistics.matrix("STAFF","MONTH",2026,2026,2026,null,null,search);}
 @Test void visibleEightCharacterCodeFindsTheSameInvoicesAsFullUuid(){
  var full=report(STAFF);assertThat(full.total()).isEqualByComparingTo("300000");
  assertThat(report("f4e736e8")).isEqualTo(full);
 }
 @Test void searchTrimsWhitespaceAndIgnoresUuidLetterCase(){
  assertThat(report("  F4E736E8  ")).isEqualTo(report(STAFF));
  assertThat(report(STAFF.toUpperCase(java.util.Locale.ROOT))).isEqualTo(report(STAFF));
 }
 @Test void unknownCodeReturnsNoRowsAndBlankKeepsAllStaff(){
  assertThat(report("deadbeef").rows()).isEmpty();
  assertThat(report("").rows()).hasSize(2);assertThat(report("").total()).isEqualByComparingTo("350000");
 }
 @Test void ambiguousShortCodeRequiresFullUuidInsteadOfCombiningPeople(){
  jdbc.execute("INSERT INTO pg_temp.staffs VALUES ('f4e736e8-1111-4111-8111-111111111111','Duplicate prefix')");
  assertThatThrownBy(()->report("f4e736e8")).isInstanceOf(BadRequestException.class).hasMessageContaining("UUID đầy đủ");
  assertThat(report(STAFF).total()).isEqualByComparingTo("300000");
 }
}
