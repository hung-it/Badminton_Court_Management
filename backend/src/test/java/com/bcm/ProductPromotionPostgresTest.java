package com.bcm;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Runs the same behavior suite against a separately migrated PostgreSQL database. */
@EnabledIfEnvironmentVariable(named="BCM_PROMOTION_TEST_DB_URL", matches="jdbc:postgresql:.*")
@ActiveProfiles(value="promotion-postgres", inheritProfiles=false)
class ProductPromotionPostgresTest extends ProductPromotionIntegrationTest {
    @org.junit.jupiter.api.Test
    @org.springframework.transaction.annotation.Transactional(propagation=org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void concurrentSalesCannotBothConsumeLastTenDiscountedProducts() throws Exception {
        jdbc.update("update products set stock_quantity=10 where id=?",product);
        create(campaign("Concurrent discount",10,10,today,today,true));
        byte[] body=mapper.writeValueAsBytes(cart(10));
        var ready=new java.util.concurrent.CountDownLatch(2);
        var start=new java.util.concurrent.CountDownLatch(1);
        var executor=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Callable<Integer> sale=()->{
                ready.countDown();
                if(!start.await(10,java.util.concurrent.TimeUnit.SECONDS))throw new IllegalStateException("Start timeout");
                return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/pos/sales")
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("cashier").roles("STAFF"))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(body))
                        .andReturn().getResponse().getStatus();
            };
            var a=executor.submit(sale);var b=executor.submit(sale);
            org.assertj.core.api.Assertions.assertThat(ready.await(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            start.countDown();
            org.assertj.core.api.Assertions.assertThat(java.util.List.of(a.get(20,java.util.concurrent.TimeUnit.SECONDS),b.get(20,java.util.concurrent.TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(201,400);
            org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("select stock_quantity from products where id=?",Integer.class,product)).isZero();
            org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("select count(*) from invoice_details where product_id=?",Integer.class,product)).isEqualTo(1);
            org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("select discount_amount from invoice_details where product_id=?",java.math.BigDecimal.class,product))
                    .isEqualByComparingTo("10000");
        } finally {start.countDown();executor.shutdownNow();}
    }

    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("BCM_PROMOTION_TEST_DB_URL"));
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "");
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.show-sql", () -> "false");
    }
}
