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
        assertThat(after.rows().get(0).expense().subtract(before.rows().get(0).expense())).isEqualByComparingTo("60000");
        assertThat(after.rows().get(0).profit().subtract(before.rows().get(0).profit())).isEqualByComparingTo("55000");
        assertThat(after.totalRevenue().subtract(before.totalRevenue())).isEqualByComparingTo("230000");
        assertThat(after.totalRevenue()).isEqualByComparingTo(analytics.getRevenue("MONTH",null,LocalDate.of(2087,1,1),LocalDate.of(2087,12,31)).getTotalAmount());
        assertThat(statistics.report("MONTH",2087,null,null).rows()).hasSize(12);
        assertThat(statistics.report("YEARS",2087,2087,2088).rows()).hasSize(2);
    }

    @Test void matrixIncludesBoundariesRejectsInvalidRangeAndExportsAllRows()throws Exception {
        UUID customer=jdbc.queryForObject("select id from customers order by id limit 1",UUID.class);
        for(String date:java.util.List.of("2090-01-01T00:00:00","2090-01-31T23:59:59"))
            jdbc.update("insert into invoices(id,customer_id,status,payment_method,court_fee,product_fee,discount_amount,total_amount,created_at,updated_at) values (?,?,'PAID','VNPAY',100,0,0,100,?,?)",UUID.randomUUID(),customer,LocalDateTime.parse(date),LocalDateTime.parse(date));
        jdbc.update("insert into invoices(id,customer_id,status,payment_method,court_fee,product_fee,discount_amount,total_amount,created_at,updated_at) values (?,?,'DRAFT',NULL,900,0,0,900,'2090-01-15','2090-01-15')",UUID.randomUUID(),customer);
        var date=statistics.matrix("DATE","MONTH",2090,2090,2090,LocalDate.of(2090,1,1),LocalDate.of(2090,1,31),"");
        assertThat(date.total()).isEqualByComparingTo("200");assertThat(date.rows()).hasSize(2);
        for(String type:java.util.List.of("TIME","STAFF","CUSTOMER")){
            var m=statistics.matrix(type,"YEAR",2090,2090,2090,null,null,"");assertThat(m.total()).isEqualByComparingTo("200");
        }
        assertThat(statistics.matrix("TIME","MONTH",2090,2090,2090,null,null,"").columns()).hasSize(12);
        assertThat(statistics.matrix("TIME","QUARTER",2090,2090,2090,null,null,"").columns()).hasSize(4);
        assertThat(statistics.matrix("TIME","YEARS",2090,2089,2091,null,null,"").columns()).hasSize(3);
        assertThat(statistics.matrix("IMPORT","YEAR",2090,2090,2090,null,null,"").total()).isEqualByComparingTo("0");
        assertThat(statistics.matrix("PRODUCT","QUARTER",2090,2090,2090,null,null,"").total()).isEqualByComparingTo("0");
        org.assertj.core.api.Assertions.assertThatThrownBy(()->statistics.matrix("DATE","MONTH",2090,2090,2090,LocalDate.of(2090,2,1),LocalDate.of(2090,1,1),"")).isInstanceOf(com.bcm.exception.BadRequestException.class);
        byte[] excel=statistics.excel(date,"2090-01");
        try(var zip=new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(excel))){
            String sheet="";java.util.zip.ZipEntry entry;
            while((entry=zip.getNextEntry())!=null)if(entry.getName().equals("xl/worksheets/sheet1.xml"))sheet=new String(zip.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
            assertThat(sheet).contains("2090-01","<v>200","<v>100");
            javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new java.io.ByteArrayInputStream(sheet.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        }
    }
}
