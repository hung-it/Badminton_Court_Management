package com.bcm.service;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties="booking.expiration-enabled=false")
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named="BCM_TEST_DB_URL", matches="jdbc:postgresql:.*")
class CheckoutReceiptPostgresTest {
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",()->System.getenv("BCM_TEST_DB_URL"));
        r.add("spring.datasource.username",()->System.getenv("BCM_TEST_DB_USERNAME"));
        r.add("spring.datasource.password",()->System.getenv("BCM_TEST_DB_PASSWORD"));
    }
    @Autowired CheckoutService checkout;
    @Autowired AnalyticsService analytics;
    @Autowired JdbcTemplate jdbc;
    @Autowired org.springframework.test.web.servlet.MockMvc mvc;
    UUID user,customer,booking;
    @BeforeEach void setup() {
        user=UUID.randomUUID();customer=UUID.randomUUID();booking=UUID.randomUUID();
        jdbc.update("INSERT INTO users(id,email,password_hash,full_name,phone) VALUES (?,?,'test','Receipt test','0000000001')",user,user+"@receipt.test");
        jdbc.update("INSERT INTO customers(id,user_id,full_name,phone) VALUES (?,?,'Receipt test','0000000001')",customer,user);
        jdbc.update("INSERT INTO bookings(id,customer_id,status,court_fee) VALUES (?,?,'PAID',200000)",booking,customer);
        jdbc.update("INSERT INTO payment_transactions(booking_id,payment_method,status,amount,note) VALUES (?,'BANK_TRANSFER','PENDING',200000,'CUSTOMER_DECLARED; receipt=UNVERIFIED')",booking);
        jdbc.update("INSERT INTO invoices(customer_id,booking_id,payment_method,status,court_fee,product_fee,discount_amount,total_amount) VALUES (?,?,'CASH','DRAFT',0,30000,0,30000)",customer,booking);
    }
    @AfterEach void cleanup() {
        jdbc.update("DELETE FROM payment_transactions WHERE booking_id=?",booking);
        jdbc.update("DELETE FROM invoices WHERE booking_id=?",booking);
        jdbc.update("DELETE FROM bookings WHERE id=?",booking);
        jdbc.update("DELETE FROM customers WHERE id=?",customer);
        jdbc.update("DELETE FROM users WHERE id=?",user);
    }
    @Test void requiresReceiptThenPostsCombinedRevenueExactlyOnce() {
        var preview=checkout.preview(booking);
        assertThat(preview.isCourtReceiptRequired()).isTrue();
        assertThat(preview.getPaidCourtAmount()).isEqualByComparingTo("0");
        assertThat(preview.getAmountDue()).isEqualByComparingTo("230000");
        assertThatThrownBy(()->checkout.checkout(booking,"CASH")).hasMessageContaining("xác nhận");
        assertThat(jdbc.queryForObject("SELECT status FROM payment_transactions WHERE booking_id=?",String.class,booking)).isEqualTo("PENDING");
        var before=analytics.getRevenue("DAY",LocalDate.now()).getTotalAmount();
        var response=checkout.checkout(booking,"CASH",true).getData();
        assertThat(response.getTotalAmount()).isEqualByComparingTo("230000");
        assertThat(response.getAmountDue()).isEqualByComparingTo("30000");
        assertThat(jdbc.queryForObject("SELECT status FROM payment_transactions WHERE booking_id=?",String.class,booking)).isEqualTo("SUCCESS");
        assertThat(jdbc.queryForObject("SELECT note FROM payment_transactions WHERE booking_id=?",String.class,booking)).startsWith("STAFF_RECEIVED;");
        assertThat(analytics.getRevenue("DAY",LocalDate.now()).getTotalAmount().subtract(before)).isEqualByComparingTo("230000");
        assertThatThrownBy(()->checkout.checkout(booking,"CASH",true)).isInstanceOf(com.bcm.exception.BadRequestException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM invoices WHERE booking_id=? AND status='PAID'",Integer.class,booking)).isEqualTo(1);
    }
    @Test void mismatchedReceiptRollsBack() {
        jdbc.update("UPDATE payment_transactions SET amount=1 WHERE booking_id=?",booking);
        assertThatThrownBy(()->checkout.checkout(booking,"CASH",true)).hasMessageContaining("không khớp");
        assertThat(jdbc.queryForObject("SELECT status FROM bookings WHERE id=?",String.class,booking)).isEqualTo("PAID");
        assertThat(jdbc.queryForObject("SELECT status FROM payment_transactions WHERE booking_id=?",String.class,booking)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM invoices WHERE booking_id=? AND status='PAID'",Integer.class,booking)).isZero();
    }
    @Test void invoiceHistoryAndDetailsUseStoredPricesAndExcludeDrafts() {
        UUID category=UUID.randomUUID(),product=UUID.randomUUID();
        jdbc.update("INSERT INTO categories(id,category_name) VALUES (?,'History test')",category);
        jdbc.update("INSERT INTO products(id,category_id,name,type,price) VALUES (?,?,'Water','GOODS',10000)",product,category);
        try {
            UUID draft=jdbc.queryForObject("SELECT id FROM invoices WHERE booking_id=?",UUID.class,booking);
            jdbc.update("INSERT INTO invoice_details(invoice_id,product_id,quantity,unit_price) VALUES (?,?,3,10000)",draft,product);
            assertThatThrownBy(()->analytics.invoice(draft)).isInstanceOf(com.bcm.exception.ResourceNotFoundException.class);
            var paid=checkout.checkout(booking,"CASH",true).getData().getInvoiceId();
            jdbc.update("UPDATE products SET price=99999,deleted_at=CURRENT_TIMESTAMP WHERE id=?",product);
            var detail=analytics.invoice(paid);
            assertThat(detail.invoice().totalAmount()).isEqualByComparingTo("230000");
            assertThat(detail.items()).hasSize(1);
            assertThat(detail.items().get(0).name()).isEqualTo("Water");
            assertThat(detail.items().get(0).unitPrice()).isEqualByComparingTo("10000");
            assertThat(detail.items().get(0).totalAmount()).isEqualByComparingTo("30000");
            assertThat(analytics.history("DAY",LocalDate.now(),0).invoices()).extracting(com.bcm.dto.response.InvoiceReportResponse.Summary::id).contains(paid).doesNotContain(draft);
            jdbc.update("UPDATE invoices SET created_at='2040-06-15 10:00:00' WHERE id=?",paid);
            assertThat(analytics.history("DAY",LocalDate.now(),0).invoices()).extracting(com.bcm.dto.response.InvoiceReportResponse.Summary::id).doesNotContain(paid);
            assertThat(analytics.history("DAY",LocalDate.of(2040,6,15),0).invoices()).hasSize(1);
            jdbc.update("UPDATE invoices SET deleted_at=CURRENT_TIMESTAMP WHERE id=?",paid);
            assertThat(analytics.history("DAY",LocalDate.of(2040,6,15),0).invoices()).isEmpty();
            assertThatThrownBy(()->analytics.invoice(paid)).isInstanceOf(com.bcm.exception.ResourceNotFoundException.class);
            assertThatThrownBy(()->analytics.history("DAY",LocalDate.now(),-1)).isInstanceOf(com.bcm.exception.BadRequestException.class);
        } finally {
            jdbc.update("DELETE FROM invoice_details WHERE product_id=?",product);
            jdbc.update("DELETE FROM products WHERE id=?",product);
            jdbc.update("DELETE FROM categories WHERE id=?",category);
        }
    }
    @Test void concurrentCheckoutsCreateOnePaidInvoice() throws Exception {
        var pool=Executors.newFixedThreadPool(2);
        var ready=new CountDownLatch(2);var start=new CountDownLatch(1);
        Callable<Boolean> task=()->{ready.countDown();start.await();try{checkout.checkout(booking,"CASH",true);return true;}catch(com.bcm.exception.BadRequestException e){return false;}};
        try {
            var first=pool.submit(task);var second=pool.submit(task);
            assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue();start.countDown();
            assertThat(java.util.List.of(first.get(20,TimeUnit.SECONDS),second.get(20,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM invoices WHERE booking_id=? AND status='PAID'",Integer.class,booking)).isEqualTo(1);
        } finally {start.countDown();pool.shutdownNow();}
    }
    @Test void historyPaginationAndAdminAccess() throws Exception {
        jdbc.update("INSERT INTO invoices(customer_id,booking_id,payment_method,status,court_fee,product_fee,discount_amount,total_amount,created_at) SELECT ?,?,'CASH','PAID',0,1,0,1,'2041-06-15 10:00:00'::timestamp FROM generate_series(1,21)",customer,booking);
        var first=analytics.history("DAY",LocalDate.of(2041,6,15),0);
        var second=analytics.history("DAY",LocalDate.of(2041,6,15),1);
        assertThat(first.totalElements()).isEqualTo(21);
        assertThat(first.totalPages()).isEqualTo(2);
        assertThat(first.invoices()).hasSize(20);
        assertThat(second.invoices()).hasSize(1).doesNotContainAnyElementsOf(first.invoices());
        var get=org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/analytics/invoices").contextPath("/api");
        mvc.perform(get.with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("staff").roles("STAFF")))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/analytics/invoices/"+first.invoices().get(0).id()).contextPath("/api")
            .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("admin").roles("ADMIN")))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.invoice.productFee").value(1));
    }
}
