package com.bcm.service;

import com.bcm.dto.response.RevenueBreakdownResponse;
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

@SpringBootTest(properties = "booking.expiration-enabled=false")
@EnabledIfEnvironmentVariable(named = "BCM_TEST_DB_URL", matches = "jdbc:postgresql:.*")
@Transactional(isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
class RevenueBreakdownPostgresTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("BCM_TEST_DB_URL"));
        registry.add("spring.datasource.username", () -> System.getenv("BCM_TEST_DB_USERNAME"));
        registry.add("spring.datasource.password", () -> System.getenv("BCM_TEST_DB_PASSWORD"));
    }

    @Autowired RevenueBreakdownService breakdown;
    @Autowired AnalyticsService analytics;
    @Autowired JdbcTemplate jdbc;

    @Test
    void aggregatesWithPostgresNumericAndMatchesInvoiceRevenue() {
        UUID user = UUID.randomUUID();
        jdbc.update("INSERT INTO users(id,email,password_hash,full_name,phone) VALUES (?,?,'test','Report cashier','0000000000')",
                user, user + "@breakdown.test");
        UUID staff = UUID.randomUUID();
        jdbc.update("INSERT INTO staffs(id,user_id,full_name,phone,position) VALUES (?,?,'Report cashier','0000000000','Cashier')",
                staff, user);
        UUID customerUser = UUID.randomUUID();
        jdbc.update("INSERT INTO users(id,email,password_hash,full_name,phone) VALUES (?,?,'test','Report customer','0000000001')",
                customerUser, customerUser + "@breakdown.test");
        UUID customer = UUID.randomUUID();
        jdbc.update("INSERT INTO customers(id,user_id,full_name,phone) VALUES (?,?,'Report customer','0000000001')",
                customer, customerUser);
        UUID category = UUID.randomUUID();
        jdbc.update("INSERT INTO categories(id,category_name) VALUES (?,'Report category')", category);
        UUID product = UUID.randomUUID();
        jdbc.update("INSERT INTO products(id,category_id,name,type,price,unit,stock_quantity,version) VALUES (?,?,'Report product','GOODS',0,'piece',0,0)",
                product, category);
        UUID invoice = UUID.randomUUID();
        LocalDateTime at = LocalDateTime.parse("2087-03-31T23:59:59");
        jdbc.update("INSERT INTO invoices(id,customer_id,created_by,payment_method,status,court_fee,product_fee,discount_amount,total_amount,created_at,updated_at) VALUES (?,?,?,'CASH','PAID',0,1.01,0,1.01,?,?)",
                invoice, customer, staff, at, at);
        jdbc.update("INSERT INTO invoice_details(invoice_id,product_id,quantity,unit_price,discount_amount,discount_percent) VALUES (?,?,3,0.37,0.11,9.91)",
                invoice, product);

        var report = breakdown.report("QUARTER", 2087);
        assertThat(report.staff()).filteredOn(row -> staff.equals(row.staffId())).singleElement()
                .satisfies(row -> assertThat(row.values().get("Q1/2087")).isEqualByComparingTo("1.01"));
        assertThat(report.customers()).filteredOn(row -> customer.equals(row.customerId())).singleElement()
                .satisfies(row -> assertThat(row.total()).isEqualByComparingTo("1.01"));
        assertThat(report.products()).filteredOn(row -> product.equals(row.productId())).singleElement()
                .satisfies(row -> {
                    assertThat(row.totalQuantity()).isEqualByComparingTo("3");
                    assertThat(row.total()).isEqualByComparingTo("1.00");
                });
        assertThat(report.staff().stream().map(RevenueBreakdownResponse.StaffRow::total)
                .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add))
                .isEqualByComparingTo(analytics.getRevenue("MONTH", null,
                        LocalDate.of(2087, 1, 1), LocalDate.of(2087, 12, 31)).getTotalAmount());
    }
}
