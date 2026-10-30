package com.bcm.service;

import com.bcm.dto.request.*;
import com.bcm.dto.response.ProductPromotionResponse;
import com.bcm.exception.BadRequestException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties="booking.expiration-enabled=false")
@EnabledIfEnvironmentVariable(named="BCM_TEST_DB_URL",matches="jdbc:postgresql:.*")
class PosPromotionCodePostgresTest {
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",()->System.getenv("BCM_TEST_DB_URL"));
        r.add("spring.datasource.username",()->System.getenv("BCM_TEST_DB_USERNAME"));
        r.add("spring.datasource.password",()->System.getenv("BCM_TEST_DB_PASSWORD"));
    }
    @Autowired PosService pos;
    @Autowired ProductPromotionService promotions;
    @Autowired JdbcTemplate jdbc;
    UUID user,customer,category,product;
    String code;
    ProductPromotionResponse promotion;
    ProductPromotionRequest config(boolean active) {
        return new ProductPromotionRequest("POS code test",product,2,new BigDecimal("10"),LocalDate.now().minusDays(1),LocalDate.now().plusDays(1),active,code);
    }
    PosSaleRequest request(String voucher,int quantity) {
        var r=new PosSaleRequest(customer,null,null,"CASH",BigDecimal.ZERO,BigDecimal.ZERO,List.of(new PosSaleItemRequest(product,quantity)));
        r.setPromotionCode(voucher);return r;
    }
    @BeforeEach void setup() {
        user=UUID.randomUUID();customer=UUID.randomUUID();category=UUID.randomUUID();product=UUID.randomUUID();code="POS-"+UUID.randomUUID().toString().toUpperCase(Locale.ROOT);
        jdbc.update("INSERT INTO users(id,email,password_hash,full_name,phone) VALUES (?,?,'test','POS code','0900000001')",user,user+"@code.test");
        jdbc.update("INSERT INTO customers(id,user_id,full_name,phone) VALUES (?,?,'POS code','0900000001')",customer,user);
        jdbc.update("INSERT INTO categories(id,category_name) VALUES (?,'Code test')",category);
        jdbc.update("INSERT INTO products(id,category_id,name,type,price,stock_quantity) VALUES (?,?,'Test water','GOODS',10000,10)",product,category);
        promotion=promotions.save(null,config(true));
    }
    @AfterEach void cleanup() {
        jdbc.update("DELETE FROM invoices WHERE customer_id=?",customer);
        jdbc.update("DELETE FROM discount_rules WHERE target_product_id=?",product);
        jdbc.update("DELETE FROM promotions WHERE code=?",code);
        jdbc.update("DELETE FROM products WHERE category_id=?",category);
        jdbc.update("DELETE FROM categories WHERE id=?",category);
        jdbc.update("DELETE FROM customers WHERE id=?",customer);
        jdbc.update("DELETE FROM users WHERE id=?",user);
    }
    @Test void codeValidatesWithoutDoubleDiscountAndPersistsSnapshot() {
        var quote=pos.quote(request(" "+code.toLowerCase(Locale.ROOT)+" ",2));
        assertThat(quote.discountAmount()).isEqualByComparingTo("2000");
        assertThat(quote.totalAmount()).isEqualByComparingTo("18000");
        assertThat(pos.quote(request(null,2)).totalAmount()).isEqualByComparingTo(quote.totalAmount());
        var sale=pos.sell(request(code,2));
        assertThat(sale.getTotalAmount()).isEqualByComparingTo(quote.totalAmount());
        assertThat(jdbc.queryForObject("SELECT discount_amount FROM invoice_details WHERE invoice_id=?",BigDecimal.class,sale.getId())).isEqualByComparingTo("2000");
        assertThat(jdbc.queryForObject("SELECT promotion_name FROM invoice_details WHERE invoice_id=?",String.class,sale.getId())).isEqualTo("POS code test");
        promotions.save(promotion.id(),new ProductPromotionRequest("Changed",product,2,new BigDecimal("50"),LocalDate.now().minusDays(1),LocalDate.now().plusDays(1),true,code));
        assertThat(jdbc.queryForObject("SELECT total_amount FROM invoices WHERE id=?",BigDecimal.class,sale.getId())).isEqualByComparingTo("18000");
    }
    @Test void invalidExpiredAndMissingProductsDoNotChangeStock() {
        assertThatThrownBy(()->pos.quote(request("MISSING-CODE",2))).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(()->pos.sell(request(code,1))).hasMessageContaining("ít nhất");
        var wrong=request(code,2);wrong.setItems(List.of(new PosSaleItemRequest(UUID.randomUUID(),2)));
        assertThatThrownBy(()->pos.quote(wrong)).isInstanceOf(com.bcm.exception.ResourceNotFoundException.class);
        promotions.save(promotion.id(),config(false));
        assertThatThrownBy(()->pos.sell(request(code,2))).hasMessageContaining("hiệu lực");
        promotions.save(promotion.id(),new ProductPromotionRequest("Expired",product,2,BigDecimal.TEN,LocalDate.now().minusDays(3),LocalDate.now().minusDays(1),true,code));
        assertThatThrownBy(()->pos.quote(request(code,2))).hasMessageContaining("hiệu lực");
        assertThat(jdbc.queryForObject("SELECT stock_quantity FROM products WHERE id=?",Integer.class,product)).isEqualTo(10);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM invoices WHERE customer_id=?",Integer.class,customer)).isZero();
    }
    @Test void enteredCodeUsesTotalQuantityAndDoesNotRequireTargetProductInCart() {
        UUID other=UUID.randomUUID();
        jdbc.update("INSERT INTO products(id,category_id,name,type,price,stock_quantity) VALUES (?,?,'Other item','GOODS',20000,10)",other,category);
        var request=request(code,2);request.setItems(List.of(new PosSaleItemRequest(other,1),new PosSaleItemRequest(other,1)));
        var quote=pos.quote(request);
        assertThat(quote.productFee()).isEqualByComparingTo("40000");
        assertThat(quote.discountAmount()).isEqualByComparingTo("4000");
        assertThat(quote.totalAmount()).isEqualByComparingTo("36000");
        var sale=pos.sell(request);
        assertThat(sale.getTotalAmount()).isEqualByComparingTo("36000");
        assertThat(jdbc.queryForObject("SELECT promotion_name FROM invoice_details WHERE invoice_id=?",String.class,sale.getId())).isEqualTo("POS code test");
        var mixed=request(code,1);mixed.setItems(List.of(new PosSaleItemRequest(product,1),new PosSaleItemRequest(other,1)));
        assertThat(pos.quote(mixed).totalAmount()).isEqualByComparingTo("27000");
        mixed.setPromotionCode(null);
        assertThat(pos.quote(mixed).totalAmount()).isEqualByComparingTo("30000");
    }
    @Test void quoteDoesNotBypassRevalidationAtSaleAndDuplicateCodesAreRejected() {
        pos.quote(request(code,2));promotions.save(promotion.id(),config(false));
        assertThatThrownBy(()->pos.sell(request(code,2))).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(()->promotions.save(null,config(true))).isInstanceOf(com.bcm.exception.DuplicateResourceException.class);
    }
    @Test void concurrentSalesCannotConsumeTheSameStock() throws Exception {
        jdbc.update("UPDATE products SET stock_quantity=2 WHERE id=?",product);
        var ready=new CountDownLatch(2);var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        Callable<Boolean> task=()->{ready.countDown();start.await();try{pos.sell(request(code,2));return true;}catch(BadRequestException e){return false;}};
        try{
            var first=pool.submit(task);var second=pool.submit(task);assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue();start.countDown();
            assertThat(List.of(first.get(20,TimeUnit.SECONDS),second.get(20,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
            assertThat(jdbc.queryForObject("SELECT stock_quantity FROM products WHERE id=?",Integer.class,product)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM invoices WHERE customer_id=?",Integer.class,customer)).isEqualTo(1);
        }finally{start.countDown();pool.shutdownNow();}
    }
}
