package com.bcm.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.nio.file.*;
import java.sql.*;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="BCM_TEST_DB_URL",matches="jdbc:postgresql:.*")
class MigrationIdentityPostgresTest {
 @Test void repeatedFullUpgradePreservesRealIdentityVoucherAndPaymentReferences() throws Exception {
  String name="bcm_identity_"+UUID.randomUUID().toString().replace("-","");
  String original=System.getenv("BCM_TEST_DB_URL");
  String prefix=original.substring(0,original.lastIndexOf('/')+1);
  String user=System.getenv("BCM_TEST_DB_USERNAME"),pass=System.getenv("BCM_TEST_DB_PASSWORD");
  try(var admin=DriverManager.getConnection(prefix+"postgres",user,pass);var management=admin.createStatement()) {
   management.execute("CREATE DATABASE "+name);
   try(var connection=DriverManager.getConnection(prefix+name,user,pass);var sql=connection.createStatement()) {
    sql.execute(Files.readString(Path.of("../db/migration.sql")));
    upgrade(sql);
    sql.execute("INSERT INTO users(email,password_hash,full_name,phone) VALUES ('member.demo@example.test','!','Real demo member','0987111122'),('member@example.test','!','Other member','0987111123')");
    sql.execute("INSERT INTO customers(user_id,full_name,phone) SELECT id,full_name,phone FROM users WHERE email='member.demo@example.test'");
    sql.execute("INSERT INTO promotions(code,name,valid_from,is_active) VALUES ('DEMO-REVIEW','Real voucher','2020-01-01',true)");
    sql.execute("INSERT INTO discount_rules(promotion_id,rule_type,voucher_code,discount_type,discount_value,min_quantity) SELECT id,'VOUCHER','DEMO-REVIEW','AMOUNT',10000,1 FROM promotions WHERE code='DEMO-REVIEW'");
    sql.execute("INSERT INTO bookings(customer_id,status,court_fee,expires_at,promotion_code,requested_voucher,contact_full_name,contact_phone) SELECT id,'PENDING',100000,'2090-01-01','DEMO-REVIEW','DEMO-REVIEW','Real demo contact','0987111122' FROM customers WHERE phone='0987111122'");
    sql.execute("INSERT INTO payment_transactions(booking_id,payment_method,status,amount,transaction_id,reference) SELECT id,'VNPAY','PENDING',100000,'order-demo-456','demo-123' FROM bookings WHERE requested_voucher='DEMO-REVIEW'");
    String before=snapshot(sql);upgrade(sql);upgrade(sql);
    assertThat(snapshot(sql)).isEqualTo(before);
   } finally {management.execute("DROP DATABASE "+name+" WITH (FORCE)");}
  }
 }
 private void upgrade(Statement sql)throws Exception {
  for(String file:new String[]{"booking-ux-upgrade.sql","product-promotions-upgrade.sql","court-promotions-upgrade.sql","email-identity-upgrade.sql"})
   sql.execute(Files.readString(Path.of("../db/upgrades/"+file)));
 }
 private String snapshot(Statement sql)throws Exception {
  try(var r=sql.executeQuery("SELECT json_build_array((SELECT json_agg(row_to_json(t) ORDER BY email) FROM (SELECT id,email,full_name FROM users WHERE email IN ('member.demo@example.test','member@example.test')) t),(SELECT json_agg(row_to_json(t)) FROM (SELECT p.id,p.code,d.voucher_code FROM promotions p JOIN discount_rules d ON d.promotion_id=p.id WHERE p.code='DEMO-REVIEW') t),(SELECT json_agg(row_to_json(t)) FROM (SELECT id,promotion_code,requested_voucher,contact_full_name,court_fee,status FROM bookings WHERE requested_voucher='DEMO-REVIEW') t),(SELECT json_agg(row_to_json(t)) FROM (SELECT id,transaction_id,reference,amount,status FROM payment_transactions WHERE reference='demo-123') t))::text")) {r.next();return r.getString(1);}
 }
}
