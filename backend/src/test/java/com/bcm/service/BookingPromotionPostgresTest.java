package com.bcm.service;

import com.bcm.dto.request.CreatePaymentAttemptRequest;
import com.bcm.entity.PaymentMethod;
import com.bcm.security.CurrentCustomerService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;

/** Committed UUID-scoped fixtures in a dedicated PostgreSQL DB. No outer test transaction. */
@SpringBootTest(properties={"booking.expiration-enabled=false", "booking.payment.enabled-methods=VNPAY,BANK_TRANSFER"})
@EnabledIfEnvironmentVariable(named="BCM_TEST_DB_URL",matches="jdbc:postgresql:.*")
class BookingPromotionPostgresTest {
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",()->System.getenv("BCM_TEST_DB_URL"));
        r.add("spring.datasource.username",()->System.getenv("BCM_TEST_DB_USERNAME"));
        r.add("spring.datasource.password",()->System.getenv("BCM_TEST_DB_PASSWORD"));
    }
    @Autowired BookingPromotionService promotions;
    @Autowired PaymentAttemptService attempts;
    @Autowired PaymentCallbackTransactionService callbacks;
    @Autowired BookingExpirationService expiration;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource source;
    @MockBean CurrentCustomerService customer;
    UUID owner,user;
    @BeforeEach void setup() {
        owner=UUID.randomUUID(); user=UUID.randomUUID();
        jdbc.update("INSERT INTO users(id,email,password_hash,full_name,phone) VALUES (?,?,'test','Promotion','0900000000')",user,user+"@promotion.test");
        jdbc.update("INSERT INTO customers(id,user_id,full_name,phone) VALUES (?,?,'Promotion','0900000000')",owner,user);
        when(customer.requireCustomerId()).thenReturn(owner);
    }
    @AfterEach void cleanup() {
        jdbc.update("DELETE FROM discount_rules WHERE promotion_id IN (SELECT id FROM promotions WHERE code=?)",customCode());
        jdbc.update("DELETE FROM promotions WHERE code=?",customCode());
        jdbc.update("DELETE FROM payment_transactions WHERE booking_id IN (SELECT id FROM bookings WHERE customer_id=?)",owner);
        jdbc.update("DELETE FROM booking_details WHERE booking_id IN (SELECT id FROM bookings WHERE customer_id=?)",owner);
        jdbc.update("DELETE FROM bookings WHERE customer_id=?",owner);
        jdbc.update("DELETE FROM customers WHERE id=?",owner);
        jdbc.update("DELETE FROM users WHERE id=?",user);
    }
    @Autowired CourtPromotionService courtRules;
    @Autowired jakarta.persistence.EntityManager entityManager;
    String customCode() { return ("TEST-"+owner).toUpperCase(java.util.Locale.ROOT); }
    void custom(String kind,String type,String value,int limit) {
        courtRules.save(null,new com.bcm.dto.request.CourtPromotionRequest("Test court",customCode(),kind,type,new BigDecimal(value),BigDecimal.ZERO,new BigDecimal("200000"),limit,null,java.time.LocalDate.now().minusDays(1),null,true));
    }
    @Test void configurableVoucherSupportsAmountCapAndPreservesPaidSnapshot() {
        custom("VOUCHER","PERCENT","80",10);
        UUID b=booking("400000");
        var q=promotions.quote(b,customCode(),true);
        assertThat(q.discountAmount()).isEqualByComparingTo("200000");
        assertThat(q.promotionCode()).isEqualTo(customCode());
        jdbc.update("UPDATE bookings SET status='PAID' WHERE id=?",b);
        var row=courtRules.list().stream().filter(x->customCode().equals(x.get("code"))).findFirst().orElseThrow();
        courtRules.save((UUID)row.get("id"),new com.bcm.dto.request.CourtPromotionRequest("Changed",customCode(),"VOUCHER","AMOUNT",new BigDecimal("100"),BigDecimal.ZERO,null,10,null,java.time.LocalDate.now().minusDays(1),null,true));
        assertThat(jdbc.queryForObject("SELECT discount_amount FROM bookings WHERE id=?",BigDecimal.class,b)).isEqualByComparingTo("200000");
    }
    @Test void customVoucherIsNotStackedWithAutomaticAndInvalidCodeFallsBack() {
        custom("VOUCHER","AMOUNT","10000",10);
        var q=promotions.quote(booking("500000"),customCode(),true);
        assertThat(q.discountAmount()).isEqualByComparingTo("50000");
        assertThat(q.promotionCode()).isEqualTo("INVOICE_TOTAL");
    }
    @Test void configurableAutomaticTierCanBeDisabledWithoutChangingOtherTiers() {
        custom("INVOICE_TOTAL","AMOUNT","100000",10);
        UUID b=booking("400000");
        assertThat(promotions.quote(b,null,true).discountAmount()).isEqualByComparingTo("100000");
        var row=courtRules.list().stream().filter(x->customCode().equals(x.get("code"))).findFirst().orElseThrow();
        courtRules.remove((UUID)row.get("id"));
        assertThat(promotions.quote(b,null,true).discountAmount()).isEqualByComparingTo("30000");
    }
    @Test void twoBookingsCannotReserveLastGenericVoucherUse() throws Exception {
        custom("VOUCHER","AMOUNT","100000",1);
        UUID first=booking("200000"),second=booking("200000");
        var ready=new CountDownLatch(2);var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try {
            var a=pool.submit(()->{ready.countDown();start.await();return promotions.staffApply(first,customCode());});
            var b=pool.submit(()->{ready.countDown();start.await();return promotions.staffApply(second,customCode());});
            assertThat(ready.await(5,TimeUnit.SECONDS)).isTrue();start.countDown();
            var qa=a.get(15,TimeUnit.SECONDS);var qb=b.get(15,TimeUnit.SECONDS);
            assertThat(java.util.List.of(qa.voucherValid(),qb.voucherValid())).containsExactlyInAnyOrder(true,false);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM bookings WHERE customer_id=? AND promotion_code=?",Long.class,owner,customCode())).isEqualTo(1);
        } finally {start.countDown();pool.shutdownNow();}
    }

    @Autowired PosService pos;
    @Autowired CheckoutService checkout;
    @Test @org.springframework.security.test.context.support.WithMockUser(roles="STAFF")
    void counterReceiptAndDiscountedProductsProduceOneBillWithoutRechargingCourt() {
        UUID b=booking("500000"),cat=UUID.randomUUID(),prod=UUID.randomUUID(),promo=UUID.randomUUID();
        try {
            promotions.staffApply(b,null);
            assertThat(promotions.staffReceive(b,"CASH")).isEqualByComparingTo("450000");
            assertThat(promotions.staffReceive(b,"CASH")).isEqualByComparingTo("450000");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_transactions WHERE booking_id=?",Long.class,b)).isEqualTo(1);
            jdbc.update("INSERT INTO categories(id,category_name) VALUES (?,?)",cat,"Bill test "+cat);
            jdbc.update("INSERT INTO products(id,category_id,name,type,unit,price,stock_quantity,version) VALUES (?,?,?,'GOODS','quả',10000,100,0)",prod,cat,"Bill test "+prod);
            jdbc.update("INSERT INTO promotions(id,code,name,valid_from,valid_to,is_active) VALUES (?,?,?,CURRENT_DATE,CURRENT_DATE,true)",promo,"TEST-PRODUCT-"+prod,"10 giảm 10%");
            jdbc.update("INSERT INTO discount_rules(promotion_id,rule_type,target_product_id,min_quantity,discount_type,discount_value) VALUES (?,'PRODUCT',?,10,'PERCENT',10)",promo,prod);
            var request=new com.bcm.dto.request.PosSaleRequest(); request.setBookingId(b);request.setPaymentMethod("CASH");
            var item=new com.bcm.dto.request.PosSaleItemRequest();item.setProductId(prod);item.setQuantity(10);request.setItems(java.util.List.of(item));
            assertThat(pos.sell(request).getTotalAmount()).isEqualByComparingTo("90000");
            var preview=checkout.preview(b);
            assertThat(preview.getTotalAmount()).isEqualByComparingTo("540000");
            assertThat(preview.getPaidCourtAmount()).isEqualByComparingTo("450000");
            assertThat(preview.getAmountDue()).isEqualByComparingTo("90000");
            var bill=checkout.checkout(b,"CASH").getData();
            assertThat(bill.getTotalAmount()).isEqualByComparingTo("540000");
            assertThat(bill.getAmountDue()).isEqualByComparingTo("90000");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM invoices WHERE booking_id=? AND status='PAID'",Long.class,b)).isEqualTo(1);
            assertThatThrownBy(()->checkout.checkout(b,"CASH")).isInstanceOf(com.bcm.exception.BadRequestException.class);
            assertThatThrownBy(()->pos.sell(request)).isInstanceOf(com.bcm.exception.BadRequestException.class);
        } finally {
            jdbc.update("DELETE FROM invoice_details WHERE invoice_id IN (SELECT id FROM invoices WHERE booking_id=?)",b);
            jdbc.update("DELETE FROM invoices WHERE booking_id=?",b);
            jdbc.update("DELETE FROM discount_rules WHERE promotion_id=?",promo);jdbc.update("DELETE FROM promotions WHERE id=?",promo);
            jdbc.update("DELETE FROM products WHERE id=?",prod);jdbc.update("DELETE FROM categories WHERE id=?",cat);
        }
    }
    @Test void reservedVoucherSpecialRulesCannotBeChangedIntoOrdinaryCodes() {
        var row=courtRules.list().stream().filter(x->"NEWMEMBER".equals(x.get("code"))).findFirst().orElseThrow();
        assertThatThrownBy(()->courtRules.save((UUID)row.get("id"),new com.bcm.dto.request.CourtPromotionRequest("Wrong","OTHER","VOUCHER","AMOUNT",BigDecimal.TEN,BigDecimal.ZERO,null,null,null,java.time.LocalDate.now(),null,true))).isInstanceOf(com.bcm.exception.BadRequestException.class);
    }

    @ParameterizedTest @ValueSource(strings={"NEWMEMBER","KMKH10"})
    @org.springframework.transaction.annotation.Transactional
    void specialVouchersCanBeSoftDeletedWithoutChangingPaidHistory(String code) {
        if(code.equals("KMKH10")) history(15,"COMPLETED","SUCCESS",true);
        UUID b=booking("1200000");
        var quote=promotions.quote(b,code,true);
        assertThat(quote.promotionCode()).isEqualTo(code);
        entityManager.flush();
        jdbc.update("UPDATE bookings SET status='PAID' WHERE id=?",b);
        entityManager.clear();
        var row=courtRules.list().stream().filter(x->code.equals(x.get("code"))).findFirst().orElseThrow();
        courtRules.remove((UUID)row.get("id"));
        assertThat(courtRules.list()).noneMatch(x->code.equals(x.get("code")));
        assertThat(jdbc.queryForObject("SELECT deleted_at IS NOT NULL AND NOT is_active FROM promotions WHERE code=?",Boolean.class,code)).isTrue();
        assertThat(promotions.quote(booking("1200000"),code,true).voucherValid()).isFalse();
        assertThat(jdbc.queryForObject("SELECT discount_amount FROM bookings WHERE id=?",BigDecimal.class,b)).isEqualByComparingTo(quote.discountAmount());
        assertThat(jdbc.queryForObject("SELECT promotion_code FROM bookings WHERE id=?",String.class,b)).isEqualTo(code);
        assertThat(jdbc.queryForObject("SELECT status FROM bookings WHERE id=?",String.class,b)).isEqualTo("PAID");
    }

    UUID booking(String amount) {
        UUID id=UUID.randomUUID();
        jdbc.update("INSERT INTO bookings(id,customer_id,status,court_fee,expires_at) VALUES (?,?,'PENDING',?,?)",id,owner,new BigDecimal(amount),LocalDateTime.now().plusMinutes(10));
        return id;
    }
    void history(int n,String status,String payment,boolean verified) {
        for(int i=0;i<n;i++) {
            UUID b=booking("720000");
            jdbc.update("UPDATE bookings SET status=? WHERE id=?",status,b);
            jdbc.update("INSERT INTO payment_transactions(booking_id,payment_method,status,amount,transaction_id,transaction_date,note) VALUES (?,'BANK_TRANSFER',?,720000,?,?,?)",
                    b,payment,verified?UUID.randomUUID().toString():null,verified?LocalDateTime.now():null,verified?null:"CUSTOMER_DECLARED; receipt=UNVERIFIED");
        }
    }
    @ParameterizedTest @CsvSource({"299999,0,299999","300000,30000,270000","499999,30000,469999","500000,50000,450000","720000,50000,670000","0,0,0"})
    void automaticBoundariesPersistOneDiscount(String subtotal,String discount,String total) {
        UUID b=booking(subtotal);
        var q=promotions.quote(b,null,true);
        assertThat(q.discountAmount()).isEqualByComparingTo(discount);
        assertThat(q.finalAmount()).isEqualByComparingTo(total);
        assertThat(jdbc.queryForObject("SELECT final_amount FROM bookings WHERE id=?",BigDecimal.class,b)).isEqualByComparingTo(total);
        assertThat(jdbc.queryForObject("SELECT court_fee FROM bookings WHERE id=?",BigDecimal.class,b)).isEqualByComparingTo(subtotal);
    }
    @ParameterizedTest @CsvSource({"100001,5000.05,95000.95,NEWMEMBER","400000,30000,370000,INVOICE_TOTAL","1000000,50000,950000,INVOICE_TOTAL","1200000,60000,1140000,NEWMEMBER"})
    void normalizedNewmemberBestOfferAndRounding(String subtotal,String discount,String total,String selected) {
        UUID b=booking(subtotal);
        var q=promotions.quote(b,"  newmember ",true);
        assertThat(q.voucherValid()).isTrue(); assertThat(q.promotionCode()).isEqualTo(selected);
        assertThat(q.discountAmount()).isEqualByComparingTo(discount); assertThat(q.finalAmount()).isEqualByComparingTo(total);
        assertThat(promotions.quote(b,"NEWMEMBER",true).discountAmount()).isEqualByComparingTo(discount);
    }
    @ParameterizedTest @ValueSource(ints={14,15,16})
    void loyaltyDistinctCompletedVerifiedBookings(int count) {
        history(count,"COMPLETED","SUCCESS",true);
        UUID b=booking("720000");
        var q=promotions.quote(b,"KMKH10",true);
        assertThat(q.voucherValid()).isEqualTo(count>=15);
        assertThat(q.discountAmount()).isEqualByComparingTo(count>=15?"72000":"50000");
        if(count>=15) assertThat(promotions.quote(booking("720000"),"KMKH10",true).voucherValid()).isTrue();
    }
    @ParameterizedTest @CsvSource({"PAID,SUCCESS,true","EXPIRED,SUCCESS,true","COMPLETED,FAILED,true","COMPLETED,PENDING,false"})
    void loyaltyExcludesUnfinishedExpiredFailedAndDeclared(String status,String payment,boolean verified) {
        history(15,status,payment,verified);
        assertThat(promotions.quote(booking("720000"),"KMKH10",true).voucherValid()).isFalse();
    }
    @Test void newmemberRejectsSuccessfulBookingEvenIfVoucherNeverUsed() {
        history(1,"PAID","SUCCESS",true);
        assertThat(promotions.quote(booking("100000"),"NEWMEMBER",true).voucherValid()).isFalse();
    }
    @Test void retriesAndMultipleCourtsDoNotMultiplyCompletedBookingCount() {
        history(14,"COMPLETED","SUCCESS",true);
        var ids=jdbc.queryForList("SELECT id FROM bookings WHERE customer_id=?",UUID.class,owner);
        UUID b=ids.get(0);
        jdbc.update("INSERT INTO payment_transactions(booking_id,payment_method,status,amount,transaction_id,transaction_date) VALUES (?,'VNPAY','SUCCESS',720000,?,CURRENT_TIMESTAMP)",b,UUID.randomUUID().toString());
        jdbc.update("INSERT INTO payment_transactions(booking_id,payment_method,status,amount) VALUES (?,'VNPAY','FAILED',720000)",b);
        jdbc.update("INSERT INTO booking_details(booking_id,court_id,time_slot_id,booking_date,price) SELECT ?,c.id,s.id,DATE '2040-01-01',100000 FROM (SELECT id FROM courts LIMIT 2) c CROSS JOIN (SELECT id FROM time_slots LIMIT 2) s",b);
        var quote=promotions.quote(booking("720000"),"KMKH10",true);
        assertThat(quote.voucherValid()).isFalse();
        assertThat(quote.message()).isEqualTo("Bạn chưa đủ điều kiện sử dụng mã KMKH10");
        history(1,"COMPLETED","SUCCESS",true);
        assertThat(promotions.quote(booking("720000"),"KMKH10",true).voucherValid()).isTrue();
    }
    @Test void declaredAuditAndPartialReceiptsCannotEarnLoyaltyOrConsumeFirstVerifiedUse() {
        history(15,"COMPLETED","SUCCESS",true);
        jdbc.update("UPDATE payment_transactions SET note='CUSTOMER_DECLARED; receipt=UNVERIFIED' WHERE booking_id IN (SELECT id FROM bookings WHERE customer_id=?)",owner);
        assertThat(promotions.quote(booking("720000"),"KMKH10",false).message()).isEqualTo("Bạn chưa đủ điều kiện sử dụng mã KMKH10");
        assertThat(promotions.quote(booking("100000"),"NEWMEMBER",false).voucherValid()).isTrue();
        jdbc.update("UPDATE payment_transactions SET note=NULL,amount=1 WHERE booking_id IN (SELECT id FROM bookings WHERE customer_id=?)",owner);
        assertThat(promotions.quote(booking("720000"),"KMKH10",false).message()).isEqualTo("Bạn chưa đủ điều kiện sử dụng mã KMKH10");
    }
    @Test void historyBelongsToCustomerDomainIdentity() {
        history(15,"COMPLETED","SUCCESS",true);
        UUID otherUser=UUID.randomUUID(),otherOwner=UUID.randomUUID();
        jdbc.update("INSERT INTO users(id,email,password_hash,full_name,phone) VALUES (?,?,'test','Other','0900000000')",otherUser,otherUser+"@promotion.test");
        jdbc.update("INSERT INTO customers(id,user_id,full_name,phone) VALUES (?,?,'Other','0900000000')",otherOwner,otherUser);
        UUID candidate=booking("720000");
        try {
            jdbc.update("UPDATE bookings SET customer_id=? WHERE id=?",otherOwner,candidate);
            when(customer.requireCustomerId()).thenReturn(otherOwner);
            assertThat(promotions.quote(candidate,"KMKH10",false).message()).isEqualTo("Bạn chưa đủ điều kiện sử dụng mã KMKH10");
            assertThat(promotions.quote(candidate,"NEWMEMBER",false).voucherValid()).isTrue();
        } finally {
            jdbc.update("DELETE FROM bookings WHERE id=?",candidate);
            jdbc.update("DELETE FROM customers WHERE id=?",otherOwner);
            jdbc.update("DELETE FROM users WHERE id=?",otherUser);
        }
    }
    @Test void previewDoesNotReserveAndRemoveReleases() {
        UUID a=booking("100000"),b=booking("100000");
        assertThat(promotions.quote(a,"NEWMEMBER",false).voucherValid()).isTrue();
        assertThat(promotions.quote(b,"NEWMEMBER",true).voucherValid()).isTrue();
        assertThat(promotions.quote(a,"NEWMEMBER",true).voucherValid()).isFalse();
        promotions.quote(b,null,true);
        assertThat(promotions.quote(a,"NEWMEMBER",true).voucherValid()).isTrue();
    }
    @Test void expiryReleasesAndRejectsFurtherMutation() {
        UUID a=booking("100000"); promotions.quote(a,"NEWMEMBER",true);
        jdbc.update("UPDATE bookings SET expires_at=? WHERE id=?",LocalDateTime.now().minusSeconds(1),a);
        assertThatThrownBy(()->promotions.quote(a,"NEWMEMBER",true)).hasMessageContaining("BOOKING_EXPIRED");
        assertThat(expiration.expireBooking(a)).isTrue();
        assertThat(promotions.quote(booking("100000"),"NEWMEMBER",true).voucherValid()).isTrue();
    }
    @Test void terminalFailureReleasesButRetryCannotStealAnotherReservation() {
        UUID a=booking("100000"); promotions.quote(a,"NEWMEMBER",true);
        var p=attempts.createAttempt(a,new CreatePaymentAttemptRequest(PaymentMethod.VNPAY));
        callbacks.settle(new PaymentNotification(p.paymentAttemptId(),PaymentMethod.VNPAY,p.amount(),false,null,null,true),a);
        assertThat(promotions.quote(booking("100000"),"NEWMEMBER",true).voucherValid()).isTrue();
        assertThatThrownBy(()->attempts.createAttempt(a,new CreatePaymentAttemptRequest(PaymentMethod.VNPAY))).hasMessageContaining("đủ điều kiện");
    }
    @Test void callbackChecksDiscountedAmountAndDuplicateDoesNotConsumeTwice() {
        UUID b=booking("100000"); promotions.quote(b,"NEWMEMBER",true);
        var p=attempts.createAttempt(b,new CreatePaymentAttemptRequest(PaymentMethod.VNPAY));
        assertThat(p.amount()).isEqualByComparingTo("95000");
        var bad=new PaymentNotification(p.paymentAttemptId(),PaymentMethod.VNPAY,new BigDecimal("100000"),true,"promo-"+UUID.randomUUID(),LocalDateTime.now());
        assertThat(callbacks.settle(bad,b)).isEqualTo(PaymentCallbackTransactionService.Result.INVALID_AMOUNT);
        var good=new PaymentNotification(p.paymentAttemptId(),PaymentMethod.VNPAY,p.amount(),true,"promo-"+UUID.randomUUID(),LocalDateTime.now().withNano(0));
        assertThat(callbacks.settle(good,b)).isEqualTo(PaymentCallbackTransactionService.Result.SUCCESS);
        assertThat(callbacks.settle(good,b)).isEqualTo(PaymentCallbackTransactionService.Result.ALREADY_CONFIRMED);
        assertThat(promotions.quote(booking("100000"),"NEWMEMBER",true).voucherValid()).isFalse();
    }
    @Test void declaredTransferUsesFinalAmountButDoesNotEarnLoyalty() {
        UUID b=booking("720000");promotions.quote(b,null,true);
        var request=new CreatePaymentAttemptRequest(PaymentMethod.BANK_TRANSFER,new CreatePaymentAttemptRequest.BankTransfer("Bank","Payer","00123","Booking"));
        var p=attempts.createOrReuseAttempt(b,request,()->{});
        assertThat(p.amount()).isEqualByComparingTo("670000");
        assertThat(p.confirmationMethod()).isEqualTo("CUSTOMER_DECLARED");
        assertThat(attempts.createOrReuseAttempt(b,request,()->{}).paymentAttemptId()).isEqualTo(p.paymentAttemptId());
        assertThatThrownBy(()->promotions.quote(b,null,true)).hasMessageContaining("BOOKING_EXPIRED");
    }
    @Test void invalidCodeFallsBackAndOwnershipIsChecked() {
        UUID b=booking("400000"); var q=promotions.quote(b,"FAKE",true);
        assertThat(q.voucherValid()).isFalse(); assertThat(q.finalAmount()).isEqualByComparingTo("370000");
        when(customer.requireCustomerId()).thenReturn(UUID.randomUUID());
        assertThatThrownBy(()->promotions.quote(b,"NEWMEMBER",true)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }
    @Test void declaredNewmemberIsReservedWithoutClaimingVerifiedSuccessfulUse() {
        UUID a=booking("100000");
        assertThat(promotions.quote(a,"NEWMEMBER",true).voucherValid()).isTrue();
        var request=new CreatePaymentAttemptRequest(PaymentMethod.BANK_TRANSFER,new CreatePaymentAttemptRequest.BankTransfer("Bank","Payer","00123","Booking"));
        attempts.createOrReuseAttempt(a,request,()->{});
        var quote=promotions.quote(booking("100000"),"NEWMEMBER",false);
        assertThat(quote.voucherValid()).isFalse();
        assertThat(quote.message()).contains("đang được giữ").doesNotContain("đã đặt sân thành công");
        assertThat(jdbc.queryForObject("SELECT status FROM payment_transactions WHERE booking_id=?",String.class,a)).isEqualTo("PENDING");
    }
    @Test void twoIndependentTransactionsCannotReserveNewmemberTwice() throws Exception {
        UUID a=booking("100000"),b=booking("100000");
        var ready=new CountDownLatch(2);var go=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try(var blocker=source.getConnection()) {
            blocker.setAutoCommit(false);
            try(var statement=blocker.prepareStatement("SELECT id FROM customers WHERE id=? FOR UPDATE")) { statement.setObject(1,owner);statement.executeQuery().close(); }
            var first=pool.submit(()->{ready.countDown();go.await();return promotions.quote(a,"NEWMEMBER",true);});
            var second=pool.submit(()->{ready.countDown();go.await();return promotions.quote(b,"NEWMEMBER",true);});
            assertThat(ready.await(5,TimeUnit.SECONDS)).isTrue();go.countDown();
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);int waiting=0;
            while(System.nanoTime()<deadline && waiting<1) {
                waiting=jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query LIKE 'SELECT id FROM customers WHERE id=%'",Integer.class);
                if(waiting<1) Thread.sleep(25);
            }
            assertThat(waiting).isGreaterThanOrEqualTo(1);blocker.commit();
            assertThat(java.util.List.of(first.get(10,TimeUnit.SECONDS).voucherValid(),second.get(10,TimeUnit.SECONDS).voucherValid())).containsExactlyInAnyOrder(true,false);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM bookings WHERE customer_id=? AND promotion_code='NEWMEMBER'",Integer.class,owner)).isEqualTo(1);
        } finally { pool.shutdownNow(); }
    }
}
