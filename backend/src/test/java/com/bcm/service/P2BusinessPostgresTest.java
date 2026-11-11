package com.bcm.service;

import com.bcm.dto.request.*;
import com.bcm.entity.*;
import com.bcm.exception.*;
import com.bcm.security.CurrentCustomerService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;

@SpringBootTest(properties={"booking.expiration-enabled=false","booking.auto-checkout.enabled=false"})
@EnabledIfEnvironmentVariable(named="BCM_TEST_DB_URL",matches="jdbc:postgresql:.*")
class P2BusinessPostgresTest {
    @DynamicPropertySource static void db(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",()->System.getenv("BCM_TEST_DB_URL"));
        r.add("spring.datasource.username",()->System.getenv("BCM_TEST_DB_USERNAME"));
        r.add("spring.datasource.password",()->System.getenv("BCM_TEST_DB_PASSWORD"));
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired AuthService auth;
    @Autowired MasterDataService master;
    @Autowired BookingService bookings;
    @Autowired BookingPromotionService promotions;
    @Autowired BookingHistoryService history;
    @Autowired AvailabilityService availability;
    @Autowired PosService pos;
    @Autowired PlatformTransactionManager transactions;
    @MockBean CurrentCustomerService customer;
    UUID user,owner,court,slot,category,product;
    com.bcm.security.UserPrincipal actor;
    final LocalDate date=LocalDate.of(2090,1,1);
    @BeforeEach void setup() {
        var session=auth.register(registration(UUID.randomUUID()+"@p2.invalid"));
        user=UUID.fromString(session.getUser().getId());
        owner=jdbc.queryForObject("SELECT id FROM customers WHERE user_id=?",UUID.class,user);
        when(customer.requireCustomerId()).thenReturn(owner);
        actor=com.bcm.TestActors.admin(jdbc);com.bcm.TestActors.authenticate(actor);
        court=UUID.randomUUID();slot=UUID.randomUUID();category=UUID.randomUUID();product=UUID.randomUUID();
        jdbc.update("INSERT INTO courts(id,court_code,name,base_price) VALUES (?,100090101,'P2 court',100000)",court);
        jdbc.update("INSERT INTO time_slots(id,start_time,end_time,price_multiplier) VALUES (?,'00:00','01:00',1)",slot);
        jdbc.update("INSERT INTO categories(id,category_name) VALUES (?,?)",category,"P2 "+category);
        jdbc.update("INSERT INTO products(id,category_id,name,type,unit,price,stock_quantity) VALUES (?,?,'P2 goods','GOODS','item',10000,1)",product,category);
    }
    @AfterEach void cleanup() {
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
        jdbc.update("DELETE FROM payment_transactions WHERE booking_id IN (SELECT id FROM bookings WHERE customer_id=?) OR invoice_id IN (SELECT id FROM invoices WHERE customer_id=?)",owner,owner);
        jdbc.update("DELETE FROM invoices WHERE customer_id=?",owner);
        jdbc.update("DELETE FROM bookings WHERE customer_id=?",owner);
        jdbc.update("DELETE FROM products WHERE id=?",product);jdbc.update("DELETE FROM categories WHERE id=?",category);
        jdbc.update("DELETE FROM courts WHERE id=?",court);jdbc.update("DELETE FROM time_slots WHERE id=?",slot);
        jdbc.update("DELETE FROM users WHERE id=?",user);
    }
    RegisterRequest registration(String email) {return RegisterRequest.builder().email(email).password("Password123").fullName("P2 customer").phone("0912345678").build();}
    UUID book() {return bookings.createBooking(new CreateBookingRequest(owner,List.of(new CreateBookingRequest.Detail(date,court,slot)))).getBookingId();}
    UUID paid() {UUID id=book();promotions.staffReceive(id,"CASH",true,null,new BigDecimal("100000"));return id;}
    PosSaleRequest sale(UUID booking) {
        var r=new PosSaleRequest(owner,booking,null,"CASH",BigDecimal.ZERO,BigDecimal.ZERO,List.of(new PosSaleItemRequest(product,1)));
        r.setIdempotencyKey(UUID.randomUUID());r.setPaymentConfirmed(true);r.setExpectedTotal(new BigDecimal("10000"));return r;
    }
    ProductRequest serviceRequest() {var p=master.product(product);return new ProductRequest(category,p.getName(),ProductType.SERVICE,p.getUnit(),p.getPrice(),0,p.getVersion(),null);}

    @Test void summaryUsesPersistedDiscountEvenAfterCourtPriceChanges() {
        jdbc.update("UPDATE courts SET base_price=300000 WHERE id=?",court);UUID id=book();
        jdbc.update("UPDATE courts SET base_price=999999 WHERE id=?",court);
        var row=history.history(owner,null,0,10).content().stream().filter(r->r.bookingId().equals(id)).findFirst().orElseThrow();
        assertThat(row.courtFee()).isEqualByComparingTo("300000");assertThat(row.discountAmount()).isEqualByComparingTo("30000");
        assertThat(row.finalAmount()).isEqualByComparingTo("270000");
    }
    @Test void legacyZeroPriceIsNotAvailableOrBookable() {
        jdbc.update("UPDATE courts SET base_price=0 WHERE id=?",court);
        assertThatThrownBy(()->availability.getAvailability(date,court)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(()->master.quote(court,slot)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(this::book).isInstanceOf(BadRequestException.class);
        assertThat(master.courts()).extracting(Court::getId).contains(court);
    }
    @Test void draftPreventsTypeChangeAndCancelledGoodsCanStillBeSold() {
        var draft=pos.sell(sale(paid()));
        assertThatThrownBy(()->master.saveProduct(product,serviceRequest())).isInstanceOf(DuplicateResourceException.class);
        pos.cancelDraft(draft.getId());pos.sell(sale(null));
        assertThat(master.saveProduct(product,serviceRequest()).getType()).isEqualTo(ProductType.SERVICE);
        assertThat(master.product(product).getStockQuantity()).isZero();
    }
    @Test void legacyWrongTypeDraftCannotSilentlyRestoreServiceStock() {
        var draft=pos.sell(sale(paid()));jdbc.update("UPDATE products SET type='SERVICE' WHERE id=?",product);
        assertThatThrownBy(()->pos.cancelDraft(draft.getId())).isInstanceOf(DuplicateResourceException.class);
        assertThat(master.product(product).getStockQuantity()).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM invoices WHERE id=?",String.class,draft.getId())).isEqualTo("DRAFT");
        assertThat(jdbc.queryForObject("SELECT stock_deducted FROM invoice_details WHERE invoice_id=?",Boolean.class,draft.getId())).isTrue();
    }
    @RepeatedTest(10) void saleWinsThenConcurrentTypeChangeSeesDraft() throws Exception {
        UUID id=paid();var r=serviceRequest();
        assertThat(race(()->pos.sell(sale(id)),()->master.saveProduct(product,r))).isInstanceOf(DuplicateResourceException.class);
        assertThat(master.product(product).getType()).isEqualTo(ProductType.GOODS);
        assertThat(master.product(product).getStockQuantity()).isZero();
    }
    @RepeatedTest(10) void cancellationWinsThenTypeChangeCannotDiscardRestoredStock() throws Exception {
        var draft=pos.sell(sale(paid()));var r=serviceRequest();
        assertThat(race(()->pos.cancelDraft(draft.getId()),()->master.saveProduct(product,r))).isInstanceOf(DuplicateResourceException.class);
        assertThat(master.product(product).getStockQuantity()).isEqualTo(1);
        assertThat(master.product(product).getType()).isEqualTo(ProductType.GOODS);
    }
    @Test void normalizedEmailLoginAndConcurrentRegistrationPreserveOneIdentity() throws Exception {
        String email=UUID.randomUUID()+"@p2-email.invalid";
        var pool=Executors.newFixedThreadPool(2);var gate=new CountDownLatch(1);
        try {
            Callable<Boolean> a=()->{gate.await();try{auth.register(registration(email.toUpperCase(Locale.ROOT)));return true;}catch(DuplicateResourceException|org.springframework.dao.DataIntegrityViolationException e){return false;}};
            var first=pool.submit(a);var second=pool.submit(()->{gate.await();try{auth.register(registration(email));return true;}catch(DuplicateResourceException|org.springframework.dao.DataIntegrityViolationException e){return false;}});
            gate.countDown();assertThat(List.of(first.get(15,TimeUnit.SECONDS),second.get(15,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
            assertThat(auth.login(new LoginRequest(email.toUpperCase(Locale.ROOT),"Password123")).getUser().getEmail()).isEqualTo(email);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE lower(email)=?",Integer.class,email)).isEqualTo(1);
        } finally {gate.countDown();pool.shutdownNow();jdbc.update("DELETE FROM users WHERE email=?",email);}
    }
    Throwable race(Runnable first,Runnable second) throws Exception {
        var pool=Executors.newFixedThreadPool(2);var held=new CountDownLatch(1);var release=new CountDownLatch(1);
        try {
            var a=pool.submit(()->{com.bcm.TestActors.authenticate(actor);return new TransactionTemplate(transactions).execute(s->{first.run();held.countDown();await(release);return true;});});
            assertThat(held.await(10,TimeUnit.SECONDS)).isTrue();
            var b=pool.submit(()->{com.bcm.TestActors.authenticate(actor);try{second.run();return null;}catch(RuntimeException e){return e;}});
            try {
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);boolean blocked=false;
                while(System.nanoTime()<deadline&&!b.isDone()) {blocked=Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE datname=current_database() AND cardinality(pg_blocking_pids(pid))>0)",Boolean.class));if(blocked)break;Thread.sleep(20);}
                assertThat(blocked).isTrue();
            } finally {release.countDown();}
            assertThat(a.get(10,TimeUnit.SECONDS)).isTrue();return b.get(10,TimeUnit.SECONDS);
        } finally {release.countDown();pool.shutdown();assertThat(pool.awaitTermination(15,TimeUnit.SECONDS)).isTrue();}
    }
    static void await(CountDownLatch l) {try{if(!l.await(15,TimeUnit.SECONDS))throw new IllegalStateException("Test timeout");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}}
}
