package com.bcm.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties="booking.expiration-enabled=false")
@EnabledIfEnvironmentVariable(named="BCM_TEST_DB_URL",matches="jdbc:postgresql:.*")
@Transactional(isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
class StatisticsPostgresTest {
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",()->System.getenv("BCM_TEST_DB_URL"));
        r.add("spring.datasource.username",()->System.getenv("BCM_TEST_DB_USERNAME"));
        r.add("spring.datasource.password",()->System.getenv("BCM_TEST_DB_PASSWORD"));
    }
    @Autowired StatisticsService statistics;
    @Autowired AnalyticsService analytics;
    @Autowired JdbcTemplate jdbc;

    @Test void statisticsMatchesRevenueAndInventoryAcrossQuarterAndYearBoundaries() {
        UUID user=UUID.randomUUID(),customer=UUID.randomUUID(),supplier=UUID.randomUUID();
        jdbc.update("INSERT INTO users(id,email,password_hash,full_name,phone) VALUES (?,?,'test','Statistics test','0000000001')",user,user+"@statistics.test");
        jdbc.update("INSERT INTO customers(id,user_id,full_name,phone) VALUES (?,?,'Statistics test','0000000001')",customer,user);
        jdbc.update("INSERT INTO suppliers(id,name) VALUES (?,'Statistics test')",supplier);
        var before=statistics.report("QUARTER",2087,null,null);
        for(String date:new String[]{"2087-03-31T23:59:59","2087-04-01T00:00:00","2088-01-01T00:00:00"})
            jdbc.update("INSERT INTO invoices(customer_id,payment_method,status,court_fee,product_fee,discount_amount,total_amount,created_at) VALUES (?,'CASH','PAID',100000,20000,5000,115000,?)",customer,LocalDateTime.parse(date));
        for(String state:new String[]{"DRAFT","RECEIVED","CANCELLED"})
            jdbc.update("INSERT INTO import_orders(supplier_id,status,total_amount,import_date) VALUES (?,?,60000,?)",supplier,state,LocalDate.parse("2087-03-31"));
        var after=statistics.report("QUARTER",2087,null,null);
        assertThat(after.rows()).hasSize(4);
        assertThat(after.rows().get(0).revenue().subtract(before.rows().get(0).revenue())).isEqualByComparingTo("115000");
        assertThat(after.rows().get(0).expense().subtract(before.rows().get(0).expense())).isEqualByComparingTo("120000");
        assertThat(after.rows().get(0).profit().subtract(before.rows().get(0).profit())).isEqualByComparingTo("-5000");
        assertThat(after.totalRevenue().subtract(before.totalRevenue())).isEqualByComparingTo("230000");
        assertThat(after.totalRevenue()).isEqualByComparingTo(analytics.getRevenue("MONTH",null,LocalDate.of(2087,1,1),LocalDate.of(2087,12,31)).getTotalAmount());
        assertThat(statistics.report("MONTH",2087,null,null).rows()).hasSize(12);
        assertThat(statistics.report("YEARS",2087,2087,2088).rows()).hasSize(2);
    }
}
