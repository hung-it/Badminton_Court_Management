package com.bcm.service;

import com.bcm.dto.request.*;
import com.bcm.entity.Invoice;
import com.bcm.exception.BadRequestException;
import com.bcm.security.UserPrincipal;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties={"booking.expiration-enabled=false","booking.auto-checkout.enabled=false","bcm.seed.enabled=false","spring.jpa.show-sql=false","logging.level.org.hibernate.SQL=OFF"})
@EnabledIfEnvironmentVariable(named="BCM_INVOICE_DEV_DB_URL",matches="jdbc:postgresql:.*")
class PosVoucherUsagePostgresTest {
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",()->System.getenv("BCM_INVOICE_DEV_DB_URL"));
        r.add("spring.datasource.username",()->System.getenv("BCM_INVOICE_DEV_DB_USERNAME"));
        r.add("spring.datasource.password",()->System.getenv("BCM_INVOICE_DEV_DB_PASSWORD"));
    }
    @Autowired BookingService bookingService;
    @Autowired BookingPromotionService bookingPromotions;
    @Autowired PosService pos;
    @Autowired CheckoutService checkout;
    @Autowired CourtPromotionService rules;
    @Autowired JdbcTemplate jdbc;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    UUID user, customer, booking, category, product, court, promotion, rule;
    String code;
    void actor() {
        var p=new UserPrincipal(user.toString(),"voucher-test","test",List.of(new SimpleGrantedAuthority("ROLE_ADMIN")),true);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(p,null,p.getAuthorities()));
    }
    @BeforeEach void setup() {
        user=UUID.randomUUID();customer=UUID.randomUUID();booking=UUID.randomUUID();category=UUID.randomUUID();product=UUID.randomUUID();court=UUID.randomUUID();promotion=UUID.randomUUID();rule=UUID.randomUUID();code=("V-"+promotion).toUpperCase(Locale.ROOT);
        jdbc.update("INSERT INTO users(id,email,password_hash,full_name,phone) VALUES (?,?,'test','Voucher test','0901234567')",user,user+"@voucher.test");
        jdbc.update("INSERT INTO customers(id,user_id,full_name,phone) VALUES (?,?,'Voucher test','0901234567')",customer,user);
        jdbc.update("INSERT INTO categories(id,category_name) VALUES (?,'Voucher test')",category);
        jdbc.update("INSERT INTO products(id,category_id,name,type,price,stock_quantity) VALUES (?,?,'Voucher product','GOODS',10000,20)",product,category);
        jdbc.update("INSERT INTO bookings(id,customer_id,status,court_fee) VALUES (?,?,'PAID',200000)",booking,customer);
        jdbc.update("INSERT INTO payment_transactions(booking_id,payment_method,status,amount,transaction_id,transaction_date) VALUES (?,'VNPAY','SUCCESS',200000,?,CURRENT_TIMESTAMP)",booking,"TEST-"+booking);
        jdbc.update("INSERT INTO courts(id,court_code,name,type,status,base_price) VALUES (?,?,'Voucher court','STANDARD_MAT','AVAILABLE',200000)",court,ThreadLocalRandom.current().nextInt(10000000,20000000));
        jdbc.update("INSERT INTO booking_details(booking_id,court_id,time_slot_id,booking_date,price) SELECT ?,?,id,?,200000 FROM time_slots ORDER BY start_time LIMIT 1",booking,court,LocalDate.now().minusDays(1));
        jdbc.update("INSERT INTO promotions(id,code,name,valid_from,valid_to) VALUES (?,?,'Voucher test',CURRENT_DATE-1,CURRENT_DATE+1)",promotion,code);
        jdbc.update("INSERT INTO discount_rules(id,promotion_id,rule_type,voucher_code,discount_type,discount_value,min_invoice_amount,max_total_usage,max_usage_per_customer) VALUES (?,?,'VOUCHER',?,'AMOUNT',1000,0,1,1)",rule,promotion,code);
        actor();
    }
    @AfterEach void cleanup() {
        SecurityContextHolder.clearContext();
        jdbc.update("DELETE FROM customer_voucher_usage WHERE customer_id=?",customer);
        jdbc.update("DELETE FROM payment_transactions WHERE booking_id=? OR invoice_id IN (SELECT id FROM invoices WHERE customer_id=?)",booking,customer);
        jdbc.update("DELETE FROM invoice_details WHERE invoice_id IN (SELECT id FROM invoices WHERE customer_id=?)",customer);
        jdbc.update("UPDATE invoices SET parent_invoice_id=NULL WHERE customer_id=?",customer);
        jdbc.update("DELETE FROM invoices WHERE customer_id=?",customer);
        jdbc.update("DELETE FROM booking_details WHERE booking_id=?",booking);
        jdbc.update("DELETE FROM bookings WHERE customer_id=?",customer);
        jdbc.update("DELETE FROM courts WHERE id=?",court);
        jdbc.update("DELETE FROM discount_rules WHERE id=?",rule);
        jdbc.update("DELETE FROM promotions WHERE id=?",promotion);
        jdbc.update("DELETE FROM products WHERE category_id=?",category);
        jdbc.update("DELETE FROM categories WHERE id=?",category);
        jdbc.update("DELETE FROM customers WHERE id=?",customer);
        jdbc.update("DELETE FROM user_roles WHERE user_id=?",user);
        jdbc.update("DELETE FROM users WHERE id=?",user);
    }
    PosSaleRequest request(boolean attached) {
        var r=new PosSaleRequest(customer,attached?booking:null,null,"CASH",BigDecimal.ZERO,BigDecimal.ZERO,List.of(new PosSaleItemRequest(product,1)));
        r.setCustomerPhone("0901234567");r.setPromotionCode(code);r.setIdempotencyKey(UUID.randomUUID());r.setPaymentConfirmed(true);r.setExpectedTotal(new BigDecimal("9000"));return r;
    }
    int stock(){return jdbc.queryForObject("SELECT stock_quantity FROM products WHERE id=?",Integer.class,product);}
    @Test void standalonePosCountsBookingUsageAndRollsBack() {
        jdbc.update("UPDATE bookings SET promotion_code=? WHERE id=?",code,booking);
        assertThatThrownBy(()->pos.quote(request(false))).hasMessageContaining("hết lượt");
        assertThatThrownBy(()->pos.sell(request(false))).hasMessageContaining("hết lượt");
        assertThat(stock()).isEqualTo(20);
    }
    @Test void attachedPosAlsoCountsItsOwnBookingVoucher() {
        jdbc.update("UPDATE bookings SET promotion_code=? WHERE id=?",code,booking);
        assertThatThrownBy(()->pos.sell(request(true))).hasMessageContaining("hết lượt");
    }
    @Test void draftReservesLastUseCancellationReleasesAndReplayDoesNotConsumeAgain() {
        var r=request(true);var first=pos.sell(r);
        assertThat(pos.sell(r).getId()).isEqualTo(first.getId());
        assertThatThrownBy(()->pos.sell(request(true))).hasMessageContaining("hết lượt");
        assertThatThrownBy(()->pos.sell(request(false))).hasMessageContaining("hết lượt");
        pos.cancelDraft(first.getId());
        assertThat(pos.sell(request(true)).getStatus()).isEqualTo("DRAFT");
        assertThat(stock()).isEqualTo(19);
    }
    @Test void multipleAllowedUsesRemainDistinctAfterCheckoutAndReplay() {
        jdbc.update("UPDATE discount_rules SET max_total_usage=2,max_usage_per_customer=2 WHERE id=?",rule);
        pos.sell(request(true));pos.sell(request(true));
        var r=new CheckoutRequest("CASH");r.setPaymentConfirmed(true);r.setExpectedAmountDue(new BigDecimal("18000"));
        var paid=checkout.checkout(booking,r).getData();
        assertThat(paid.getProductDiscount()).isEqualByComparingTo("2000");
        assertThat(checkout.checkout(booking,r).getData().getInvoiceId()).isEqualTo(paid.getInvoiceId());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM customer_voucher_usage WHERE discount_rule_id=?",Integer.class,rule)).isEqualTo(2);
        assertThatThrownBy(()->pos.sell(request(false))).hasMessageContaining("hết lượt");
    }
    @Test void customerLimitCountsDraftsIndependentlyOfGlobalLimit() {
        jdbc.update("UPDATE discount_rules SET max_total_usage=10 WHERE id=?",rule);
        pos.sell(request(true));
        assertThatThrownBy(()->pos.sell(request(true))).hasMessageContaining("Khách hàng");
    }
    @Test void bookingLimitsSeePosDraftReservation() {
        pos.sell(request(true));
        new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(status -> {
            var r=rules.voucher(code,LocalDate.now());
            assertThat(rules.limits(r,customer,UUID.randomUUID(),code,java.time.LocalDateTime.now())).contains("hết lượt");
        });
    }
    @Test void staleQuoteRollsBackAndDoesNotReserveVoucher() {
        var r=request(true);r.setExpectedTotal(BigDecimal.ONE);
        assertThatThrownBy(()->pos.sell(r)).hasMessageContaining("Báo giá");
        assertThat(stock()).isEqualTo(20);
        assertThat(pos.sell(request(true)).getStatus()).isEqualTo("DRAFT");
    }
    @Test void legacyUnreservedDraftCannotSilentlyKeepDiscountAtCheckout() {
        var draft=pos.sell(request(true));
        jdbc.update("DELETE FROM customer_voucher_usage WHERE invoice_id=?",draft.getId());
        var r=new CheckoutRequest("CASH");r.setPaymentConfirmed(true);r.setExpectedAmountDue(new BigDecimal("9000"));
        assertThatThrownBy(()->checkout.checkout(booking,r)).hasMessageContaining("Legacy draft voucher");
        assertThat(jdbc.queryForObject("SELECT status FROM bookings WHERE id=?",String.class,booking)).isEqualTo("PAID");
        assertThat(jdbc.queryForObject("SELECT status FROM invoices WHERE id=?",String.class,draft.getId())).isEqualTo("DRAFT");
    }
    @Test void quoteByPhoneChecksExistingCustomerHistory() {
        var r=request(false);r.setCustomerId(null);r.setPromotionCode("NEWMEMBER");
        assertThatThrownBy(()->pos.quote(r)).hasMessageContaining("không còn đủ điều kiện");
        r.setCustomerPhone("0988888888");r.setPromotionCode("KMKH10");
        assertThatThrownBy(()->pos.quote(r)).hasMessageContaining("chưa đủ điều kiện");
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"CASH,CASH,true","CASH,VNPAY,true","VNPAY,CASH,true","VNPAY,VNPAY,true","CASH,VNPAY,false"})
    void actualBookingPromotionCounterPaymentAndCheckout(String courtMethod,String itemMethod,boolean courtVoucher) {
        jdbc.update("DELETE FROM payment_transactions WHERE booking_id=?",booking);
        jdbc.update("DELETE FROM booking_details WHERE booking_id=?",booking);
        jdbc.update("DELETE FROM bookings WHERE id=?",booking);
        jdbc.update("INSERT INTO user_roles(user_id,role_id) SELECT ?,id FROM roles WHERE role_name='CUSTOMER'",user);
        var p=new UserPrincipal(user.toString(),"voucher-customer","test",List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER")),true);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(p,null,p.getAuthorities()));
        UUID slot=jdbc.queryForObject("SELECT id FROM time_slots WHERE deleted_at IS NULL ORDER BY start_time LIMIT 1",UUID.class);
        booking=bookingService.createBooking(new CreateBookingRequest(customer,List.of(new CreateBookingRequest.Detail(LocalDate.now().plusDays(2),court,slot)))).getBookingId();
        assertThat(jdbc.queryForObject("SELECT status FROM bookings WHERE id=?",String.class,booking)).isEqualTo("PENDING");
        jdbc.update("UPDATE discount_rules SET max_total_usage=10,max_usage_per_customer=10 WHERE id=?",rule);
        actor();
        var quote=bookingPromotions.staffApply(booking,courtVoucher?code:null);
        var courtDue=quote.finalAmount();
        assertThat(courtDue).isEqualByComparingTo(courtVoucher?"199000":"200000");
        assertThatThrownBy(()->bookingPromotions.staffReceive(booking,courtMethod,true,BigDecimal.ONE)).hasMessageContaining("thay đổi");
        bookingPromotions.staffReceive(booking,courtMethod,true,courtDue);
        bookingPromotions.staffReceive(booking,courtMethod,true,courtDue);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_transactions WHERE booking_id=?",Integer.class,booking)).isEqualTo(1);
        var sale=request(true);sale.setExpectedTotal(pos.quote(sale).totalAmount());pos.sell(sale);
        var stale=new CheckoutRequest(itemMethod);stale.setPaymentConfirmed(true);stale.setExpectedAmountDue(BigDecimal.ZERO);
        assertThatThrownBy(()->checkout.checkout(booking,stale)).hasMessageContaining("Bill changed");
        var preview=checkout.preview(booking);
        assertThat(preview.getAmountDue()).isEqualByComparingTo("9000");
        var receipt=new CheckoutRequest(itemMethod);receipt.setPaymentConfirmed(true);receipt.setExpectedAmountDue(preview.getAmountDue());
        var paid=checkout.checkout(booking,receipt).getData();
        assertThat(paid.getTotalAmount()).isEqualByComparingTo(courtVoucher?"208000":"209000");
        assertThat(paid.getRemainingDue()).isEqualByComparingTo("0");
        assertThat(checkout.checkout(booking,receipt).getData().getInvoiceId()).isEqualTo(paid.getInvoiceId());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_transactions WHERE invoice_id=?",Integer.class,paid.getInvoiceId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM bookings WHERE id=?",String.class,booking)).isEqualTo("COMPLETED");
        assertThat(stock()).isEqualTo(19);
    }
    @RepeatedTest(3) void simultaneousSalesReserveOnlyOneLastUse() throws Exception {
        UUID other=UUID.randomUUID();
        jdbc.update("INSERT INTO products(id,category_id,name,type,price,stock_quantity) VALUES (?,?,'Other voucher product','GOODS',10000,20)",other,category);
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        var ready=new CountDownLatch(2);var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        Callable<Boolean> sale=()->{actor();ready.countDown();start.await();try{var r=request(calls.getAndIncrement()==0);if(r.getBookingId()==null)r.setItems(List.of(new PosSaleItemRequest(other,1)));pos.sell(r);return true;}catch(BadRequestException ex){assertThat(ex).hasMessageContaining("hết lượt");return false;}finally{SecurityContextHolder.clearContext();}};
        try {
            var a=pool.submit(sale);var b=pool.submit(sale);assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue();start.countDown();
            assertThat(List.of(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
            assertThat(jdbc.queryForObject("SELECT sum(stock_quantity) FROM products WHERE category_id=?",Integer.class,category)).isEqualTo(39);
        } finally {start.countDown();pool.shutdownNow();}
    }
}
