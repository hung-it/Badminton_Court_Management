package com.bcm.service;

import com.bcm.dto.request.*;
import com.bcm.entity.*;
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

/** Explicit opt-in; UUID-owned fixtures only. Never resets tables or seeds. */
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
@SpringBootTest(properties={"booking.auto-checkout.enabled=false","booking.expiration-enabled=false","bcm.seed.enabled=false","spring.jpa.show-sql=false","logging.level.org.hibernate.SQL=OFF","logging.level.org.springframework.security=ERROR"})
@EnabledIfEnvironmentVariable(named="BCM_INVOICE_DEV_DB_URL", matches="jdbc:postgresql:.*")
class CheckoutReceiptPostgresTest {
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",()->System.getenv("BCM_INVOICE_DEV_DB_URL"));
        r.add("spring.datasource.username",()->System.getenv("BCM_INVOICE_DEV_DB_USERNAME"));
        r.add("spring.datasource.password",()->System.getenv("BCM_INVOICE_DEV_DB_PASSWORD"));
    }
    @Autowired CheckoutService checkout;
    @Autowired com.bcm.repository.BookingRepository bookingRepository;
    @Autowired MasterDataService master;
    @Autowired PaymentCallbackTransactionService callbacks;
    @Autowired CourtPromotionService courtPromotions;
    @Autowired ImportOrderService imports;
    @Autowired StatisticsService statistics;
    @Autowired BookingService bookingService;
    @Autowired PaymentAttemptService paymentAttempts;
    @Autowired BookingExpirationService expiration;
    @Autowired PosService pos;
    @Autowired AnalyticsService analytics;
    @Autowired RevenueBreakdownService revenue;
    @Autowired BookingPromotionService promotions;
    @Autowired JdbcTemplate jdbc;
    @Autowired org.springframework.test.web.servlet.MockMvc mvc;
    UUID user,customer,booking,staff,category,product,admin,court,supplier;
    void actor(boolean asAdmin) {
        var principal=new UserPrincipal((asAdmin?admin:user).toString(),"invoice-test","test",
            List.of(new SimpleGrantedAuthority(asAdmin?"ROLE_ADMIN":"ROLE_STAFF")),true);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal,null,principal.getAuthorities()));
    }
    @BeforeEach void setup() {
        user=UUID.randomUUID();customer=UUID.randomUUID();booking=UUID.randomUUID();staff=UUID.randomUUID();
        admin=UUID.randomUUID();category=UUID.randomUUID();product=UUID.randomUUID();
        for(UUID id:List.of(user,admin)) jdbc.update("INSERT INTO users(id,email,password_hash,full_name,phone) VALUES (?,?,'test','Invoice receipt test','0000000001')",id,id+"@receipt.test");
        jdbc.update("INSERT INTO staffs(id,user_id,full_name,phone,position) VALUES (?,?,'Receipt staff','0000000001','Cashier')",staff,user);
        jdbc.update("INSERT INTO customers(id,user_id,full_name,phone) VALUES (?,?,'Receipt test','0000000001')",customer,user);
        jdbc.update("INSERT INTO categories(id,category_name) VALUES (?,?)",category,"Invoice test "+category);
        jdbc.update("INSERT INTO products(id,category_id,name,type,price,stock_quantity) VALUES (?,?,'Water snapshot','GOODS',10000,10)",product,category);
        jdbc.update("INSERT INTO bookings(id,customer_id,status,court_fee) VALUES (?,?,'PAID',200000)",booking,customer);
        jdbc.update("INSERT INTO payment_transactions(booking_id,payment_method,status,amount,transaction_id,transaction_date) VALUES (?,'VNPAY','SUCCESS',200000,?,CURRENT_TIMESTAMP)",booking,"GATEWAY-TEST-"+UUID.randomUUID());
        court=UUID.randomUUID();
        jdbc.update("INSERT INTO courts(id,court_code,name,type,status,base_price) VALUES (?,?,'Checkout test','STANDARD_MAT','AVAILABLE',200000)",court,100000+java.util.concurrent.ThreadLocalRandom.current().nextInt(10000000));
        jdbc.update("INSERT INTO booking_details(booking_id,court_id,time_slot_id,booking_date,price) SELECT ?,?,id,?,200000 FROM time_slots ORDER BY start_time LIMIT 1",booking,court,LocalDate.now(java.time.ZoneId.of("Asia/Ho_Chi_Minh")).minusDays(1));
        supplier=UUID.randomUUID();jdbc.update("INSERT INTO suppliers(id,name) VALUES (?,?)",supplier,"Import fixture "+supplier);
        actor(false);
    }
    @AfterEach void cleanup() {
        SecurityContextHolder.clearContext();
        jdbc.update("DELETE FROM import_orders WHERE supplier_id=?",supplier);
        jdbc.update("DELETE FROM suppliers WHERE id=?",supplier);
        var ids=jdbc.queryForList("SELECT id FROM invoices WHERE customer_id=? OR id IN (SELECT invoice_id FROM invoice_details WHERE product_id=?)",UUID.class,customer,product);
        for(UUID id:ids) jdbc.update("DELETE FROM payment_transactions WHERE invoice_id=?",id);
        jdbc.update("DELETE FROM payment_transactions WHERE booking_id=?",booking);
        for(UUID id:ids) jdbc.update("DELETE FROM invoice_details WHERE invoice_id=?",id);
        for(UUID id:ids) jdbc.update("UPDATE invoices SET parent_invoice_id=NULL WHERE id=?",id);
        for(UUID id:ids) jdbc.update("DELETE FROM invoices WHERE id=?",id);
        jdbc.update("DELETE FROM booking_details WHERE booking_id=?",booking);
        jdbc.update("DELETE FROM bookings WHERE id=?",booking);
        jdbc.update("DELETE FROM courts WHERE id=?",court);
        jdbc.update("DELETE FROM products WHERE id=?",product);
        jdbc.update("DELETE FROM categories WHERE id=?",category);
        jdbc.update("DELETE FROM customers WHERE id=?",customer);
        jdbc.update("DELETE FROM staffs WHERE id=?",staff);
        jdbc.update("DELETE FROM users WHERE id IN (?,?)",user,admin);
    }
    PosSaleRequest sale(boolean attached,String method) {
        var r=new PosSaleRequest(customer,attached?booking:null,UUID.randomUUID(),method,BigDecimal.ZERO,BigDecimal.ZERO,List.of(new PosSaleItemRequest(product,1)));
        r.setIdempotencyKey(UUID.randomUUID());r.setPaymentConfirmed(true);r.setExpectedTotal(new BigDecimal("10000"));r.setReference("manual-ref");return r;
    }
    CheckoutRequest receipt(String method,String amount) {
        var r=new CheckoutRequest(method);r.setPaymentConfirmed(true);r.setExpectedAmountDue(new BigDecimal(amount));r.setReference("checkout-ref");return r;
    }
    int stock() {return jdbc.queryForObject("SELECT stock_quantity FROM products WHERE id=?",Integer.class,product);}
    @Test void manualCashAndVnpayRequireConfirmationAndUseAuthenticatedStaff() {
        for(String method:List.of("CASH","VNPAY")) {
            var r=sale(false,method);r.setPaymentConfirmed(false);
            assertThatThrownBy(()->pos.sell(r)).hasMessageContaining("Confirm receipt");assertThat(stock()).isEqualTo(10-(method.equals("VNPAY")?1:0));
            r.setPaymentConfirmed(true);var inv=pos.sell(r);
            var row=jdbc.queryForMap("SELECT * FROM payment_transactions WHERE invoice_id=?",inv.getId());
            assertThat(row.get("source")).isEqualTo("MANUAL");assertThat(row.get("confirmed_by")).isEqualTo(staff);
            assertThat(row.get("confirmed_by_user")).isEqualTo(user);assertThat(row.get("transaction_date")).isNotNull();
            assertThat(row.get("reference")).isEqualTo("manual-ref");assertThat(inv.getCreatedBy()).isEqualTo(staff).isNotEqualTo(r.getStaffId());
        }
    }
    @Test void adminWithoutStaffHasRealUserAndNoInventedStaff() {
        actor(true);var inv=pos.sell(sale(false,"VNPAY"));
        assertThat(inv.getCreatedBy()).isNull();
        var row=jdbc.queryForMap("SELECT confirmed_by,confirmed_by_user FROM payment_transactions WHERE invoice_id=?",inv.getId());
        assertThat(row.get("confirmed_by")).isNull();assertThat(row.get("confirmed_by_user")).isEqualTo(admin);
    }
    @Test void mixedCheckoutCollectsOnlyProductsAndReplaysWithoutAnotherReceipt() {
        var draft=pos.sell(sale(true,null));assertThat(draft.getPaymentMethod()).isNull();assertThat(draft.getStatus()).isEqualTo("DRAFT");assertThat(stock()).isEqualTo(9);
        var preview=checkout.preview(booking);assertThat(preview.getAmountDue()).isEqualByComparingTo("10000");
        var unconfirmed=receipt("CASH","10000");unconfirmed.setPaymentConfirmed(false);
        assertThatThrownBy(()->checkout.checkout(booking,unconfirmed)).hasMessageContaining("confirm");
        assertThatThrownBy(()->checkout.checkout(booking,receipt("CASH","1"))).hasMessageContaining("Bill changed");
        var result=checkout.checkout(booking,receipt("CASH","10000")).getData();
        assertThat(result.getAmountDue()).isEqualByComparingTo("10000");assertThat(result.getCollectedAmount()).isEqualByComparingTo("10000");
        assertThat(result.getRemainingDue()).isEqualByComparingTo("0");
        assertThat(checkout.checkout(booking,receipt("CASH","10000")).getData().getInvoiceId()).isEqualTo(result.getInvoiceId());
        var report=analytics.invoice(result.getInvoiceId());
        assertThat(report.invoice().paymentMethod()).isNull();assertThat(report.invoice().paymentMethods()).containsExactly("VNPAY","CASH");assertThat(report.invoice().totalAmount()).isEqualByComparingTo("210000");
        assertThat(report.receipts()).hasSize(2);assertThat(report.receipts().stream().map(r->r.amount()).reduce(BigDecimal.ZERO,BigDecimal::add)).isEqualByComparingTo("210000");
        assertThat(stock()).isEqualTo(9);assertThatThrownBy(()->pos.cancelDraft(draft.getId())).hasMessageContaining("Merged");
        var rows=revenue.report("YEAR",LocalDate.now().getYear()).staff();
        assertThat(rows.stream().filter(r->staff.equals(r.staffId())).map(r->r.total()).reduce(BigDecimal.ZERO,BigDecimal::add)).isEqualByComparingTo("10000");
    }
    @Test void fullyPrepaidCreatesNoFakeReceipt() {
        var result=checkout.checkout(booking,receipt(null,"0")).getData();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_transactions WHERE invoice_id=?",Integer.class,result.getInvoiceId())).isZero();
        assertThat(analytics.invoice(result.getInvoiceId()).receipts()).hasSize(1);
        assertThat(result.getCollectedAmount()).isEqualByComparingTo("0");assertThat(result.getRemainingDue()).isEqualByComparingTo("0");
    }

    @Test void autoCheckoutRecognizesVerifiedRevenueWithoutStaffAndOnlyOnce() {
        var before=analytics.getRevenue("DAY",LocalDate.now()).getTotalAmount();
        SecurityContextHolder.clearContext();
        assertThat(checkout.autoCheckout(booking,java.time.Duration.ofHours(3))).isTrue();
        assertThat(checkout.autoCheckout(booking,java.time.Duration.ofHours(3))).isFalse();
        assertThat(analytics.getRevenue("DAY",LocalDate.now()).getTotalAmount().subtract(before)).isEqualByComparingTo("200000");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM invoices WHERE booking_id=? AND status='PAID'",Integer.class,booking)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT created_by_user FROM invoices WHERE booking_id=?",UUID.class,booking)).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_transactions WHERE booking_id=?",Integer.class,booking)).isEqualTo(1);
    }

    @Test void automaticCheckoutRechecksGraceAndPosAndVerifiedPayment() {
        assertThat(checkout.autoCheckout(booking,java.time.Duration.ofDays(3))).isFalse();
        var draft=pos.sell(sale(true,null));
        assertThat(checkout.autoCheckout(booking,java.time.Duration.ofHours(3))).isFalse();
        pos.cancelDraft(draft.getId());
        jdbc.update("UPDATE payment_transactions SET status='PENDING' WHERE booking_id=?",booking);
        assertThatThrownBy(()->checkout.autoCheckout(booking,java.time.Duration.ofHours(3))).hasMessageContaining("verified full court receipt");
        assertThat(jdbc.queryForObject("SELECT status FROM bookings WHERE id=?",String.class,booking)).isEqualTo("PAID");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM invoices WHERE booking_id=? AND status='PAID'",Integer.class,booking)).isZero();
    }

    @Test void automaticAndManualCheckoutRacePostsOneInvoice() throws Exception {
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        var results=concurrent(()->{
            if(calls.getAndIncrement()==0) {checkout.autoCheckout(booking,java.time.Duration.ofHours(3));return booking;}
            checkout.checkout(booking,receipt(null,"0"));return booking;
        });
        assertThat(results).hasSize(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM invoices WHERE booking_id=? AND status='PAID'",Integer.class,booking)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_transactions WHERE booking_id=?",Integer.class,booking)).isEqualTo(1);
    }

    @Test void autoCheckoutQueryUsesLatestEndAndSkipsUnverifiedOrPosBookings() {
        var cutoff=java.time.LocalDateTime.now(java.time.ZoneId.of("Asia/Ho_Chi_Minh"));
        assertThat(bookingRepository.findAutoCheckoutCandidates(cutoff)).contains(booking);
        jdbc.update("UPDATE payment_transactions SET status='PENDING' WHERE booking_id=?",booking);
        assertThat(bookingRepository.findAutoCheckoutCandidates(cutoff)).doesNotContain(booking);
        jdbc.update("UPDATE payment_transactions SET status='SUCCESS' WHERE booking_id=?",booking);
        pos.sell(sale(true,null));
        assertThat(bookingRepository.findAutoCheckoutCandidates(cutoff)).doesNotContain(booking);
    }

    @Test void autoCheckoutWaitsForExactGraceAfterLatestSlotAndJobNeedsNoUser() {
        jdbc.update("INSERT INTO booking_details(booking_id,court_id,time_slot_id,booking_date,price) SELECT booking_id,court_id,time_slot_id,booking_date+1,price FROM booking_details WHERE booking_id=?",booking);
        Object target=org.springframework.test.util.AopTestUtils.getUltimateTargetObject(checkout);
        var previous=(java.time.Clock)org.springframework.test.util.ReflectionTestUtils.getField(target,"clock");
        var end=jdbc.queryForObject("SELECT max(d.booking_date+t.end_time) FROM booking_details d JOIN time_slots t ON t.id=d.time_slot_id WHERE d.booking_id=?",java.time.LocalDateTime.class,booking);
        var zone=java.time.ZoneId.of("Asia/Ho_Chi_Minh");
        try {
            var boundary=end.plusHours(3).atZone(zone).toInstant();
            org.springframework.test.util.ReflectionTestUtils.setField(target,"clock",java.time.Clock.fixed(boundary.minusNanos(1),zone));
            assertThat(checkout.autoCheckout(booking,java.time.Duration.ofHours(3))).isFalse();
            SecurityContextHolder.clearContext();
            var fixed=java.time.Clock.fixed(boundary,zone);
            org.springframework.test.util.ReflectionTestUtils.setField(target,"clock",fixed);
            new BookingAutoCheckoutJob(bookingRepository,checkout,fixed,java.time.Duration.ofHours(3)).run();
            assertThat(jdbc.queryForObject("SELECT status FROM bookings WHERE id=?",String.class,booking)).isEqualTo("COMPLETED");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM invoices WHERE booking_id=? AND status='PAID'",Integer.class,booking)).isEqualTo(1);
        } finally {org.springframework.test.util.ReflectionTestUtils.setField(target,"clock",previous);}
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"CASH","VNPAY"})
    void pendingCounterReceiptHttpRequiresConfirmationAndRetriesSafely(String method) throws Exception {
        jdbc.update("DELETE FROM payment_transactions WHERE booking_id=?",booking);
        jdbc.update("UPDATE bookings SET status='PENDING',expires_at=? WHERE id=?",java.time.LocalDateTime.now(java.time.ZoneId.of("Asia/Ho_Chi_Minh")).plusHours(1),booking);
        var auth=org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication(SecurityContextHolder.getContext().getAuthentication());
        String route="/api/pos/bookings/"+booking+"/receive";
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(route).contextPath("/api").with(auth)
            .contentType("application/json").content(mapper.writeValueAsBytes(Map.of("paymentMethod",method,"paymentConfirmed",false))))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_transactions WHERE booking_id=?",Integer.class,booking)).isZero();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(route).contextPath("/api").with(auth)
            .contentType("application/json").content(mapper.writeValueAsBytes(Map.of("paymentMethod",method,"paymentConfirmed",true,"expectedAmount",1))))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isConflict());
        assertThat(jdbc.queryForObject("SELECT status FROM bookings WHERE id=?",String.class,booking)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_transactions WHERE booking_id=?",Integer.class,booking)).isZero();
        for(int repeat=0;repeat<2;repeat++) mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(route).contextPath("/api").with(auth)
            .contentType("application/json").content(mapper.writeValueAsBytes(Map.of("paymentMethod",method,"paymentConfirmed",true,"reference","counter-test","expectedAmount",200000))))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        assertThat(jdbc.queryForObject("SELECT status FROM bookings WHERE id=?",String.class,booking)).isEqualTo("PAID");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_transactions WHERE booking_id=?",Integer.class,booking)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT payment_method FROM payment_transactions WHERE booking_id=?",String.class,booking)).isEqualTo(method);
    }

    @Test void counterReceiptRejectsExpiredBookingAndExistingOnlineAttempt() {
        jdbc.update("DELETE FROM payment_transactions WHERE booking_id=?",booking);
        jdbc.update("UPDATE bookings SET status='PENDING',expires_at=? WHERE id=?",java.time.LocalDateTime.now(java.time.ZoneId.of("Asia/Ho_Chi_Minh")).plusHours(1),booking);
        jdbc.update("INSERT INTO payment_transactions(booking_id,payment_method,status,amount) VALUES (?,'VNPAY','PENDING',200000)",booking);
        assertThatThrownBy(()->promotions.staffReceive(booking,"CASH",true,null,new BigDecimal("200000"))).hasMessageContaining("đã có giao dịch");
        jdbc.update("DELETE FROM payment_transactions WHERE booking_id=?",booking);
        jdbc.update("UPDATE bookings SET expires_at=? WHERE id=?",java.time.LocalDateTime.now(java.time.ZoneId.of("Asia/Ho_Chi_Minh")).minusSeconds(1),booking);
        assertThatThrownBy(()->promotions.staffReceive(booking,"CASH",true,null,new BigDecimal("200000"))).hasMessageContaining("BOOKING_EXPIRED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_transactions WHERE booking_id=?",Integer.class,booking)).isZero();
    }
    @Test void cancelDraftRestoresStockExactlyOnceAndRetainsSnapshots() {
        var r=sale(true,null);var inv=pos.sell(r);assertThat(pos.sell(r).getId()).isEqualTo(inv.getId());assertThat(stock()).isEqualTo(9);
        pos.cancelDraft(inv.getId());pos.cancelDraft(inv.getId());assertThat(stock()).isEqualTo(10);
        assertThat(pos.sell(r).getStatus()).isEqualTo("CANCELLED");assertThat(stock()).isEqualTo(10);
    }
    @Test void walkInAndHistoricalSnapshotNeverFollowOtherCustomerOrCatalog() {
        var r=sale(false,"CASH");r.setCustomerId(null);var inv=pos.sell(r);
        assertThat(jdbc.queryForObject("SELECT u.email FROM customers c JOIN users u ON u.id=c.user_id WHERE c.id=?",String.class,inv.getCustomerId())).isEqualTo("guest@bcm.com");
        jdbc.update("UPDATE products SET name='Changed',price=99999 WHERE id=?",product);
        assertThat(analytics.invoice(inv.getId()).items().get(0).name()).isEqualTo("Water snapshot");
        assertThat(analytics.invoice(inv.getId()).items().get(0).unitPrice()).isEqualByComparingTo("10000");
        var invalid=sale(false,"CASH");invalid.setCustomerId(UUID.randomUUID());invalid.setExpectedTotal(new BigDecimal("99999"));assertThatThrownBy(()->pos.sell(invalid)).hasMessageContaining("Customer not found");
        assertThat(stock()).isEqualTo(9);
    }
    @Test void mismatchedCourtReceiptAndUnsupportedMethodDoNotChangeFinancialState() {
        jdbc.update("UPDATE payment_transactions SET amount=1 WHERE booking_id=?",booking);
        assertThatThrownBy(()->checkout.checkout(booking,receipt("CASH","0"))).hasMessageContaining("verified full court receipt");
        assertThatThrownBy(()->pos.sell(sale(false,"BANK_TRANSFER"))).hasMessageContaining("CASH or VNPAY");assertThat(stock()).isEqualTo(10);
    }
    @Test void concurrentPosRetryAndIndependentOversellUseDatabaseLocks() throws Exception {
        var r=sale(false,"CASH");var results=concurrent(()->pos.sell(r).getId());
        assertThat(results.get(0)).isEqualTo(results.get(1));assertThat(stock()).isEqualTo(9);
        var changed=sale(false,"CASH");changed.setIdempotencyKey(r.getIdempotencyKey());changed.setReference("different");
        assertThatThrownBy(()->pos.sell(changed)).hasMessageContaining("different sale");
        jdbc.update("UPDATE products SET stock_quantity=1 WHERE id=?",product);
        var outcomes=concurrent(()->{try {pos.sell(sale(false,"CASH"));return true;}catch(com.bcm.exception.BadRequestException e){return false;}});
        assertThat(outcomes).containsExactlyInAnyOrder(true,false);assertThat(stock()).isZero();
    }
    @Test void concurrentCheckoutRetriesReturnOneInvoiceAndOneReceipt() throws Exception {
        pos.sell(sale(true,null));
        var ids=concurrent(()->checkout.checkout(booking,receipt("VNPAY","10000")).getData().getInvoiceId());
        assertThat(ids.get(0)).isEqualTo(ids.get(1));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_transactions WHERE invoice_id=?",Integer.class,ids.get(0))).isEqualTo(1);
        assertThat(stock()).isEqualTo(9);
    }
    @Test void concurrentCancelRestoresOnceAndReceiptFailureRollsBackAllWrites() throws Exception {
        var draft=pos.sell(sale(true,null));concurrent(()->pos.cancelDraft(draft.getId()).getId());assertThat(stock()).isEqualTo(10);
        var broken=sale(false,"CASH");broken.setReference("x".repeat(300));
        assertThatThrownBy(()->pos.sell(broken)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(stock()).isEqualTo(10);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM invoices WHERE pos_request_key=?",Integer.class,broken.getIdempotencyKey())).isZero();
    }
    @Test void addInvoiceAndReceiptActorsRemainDistinctAndStaffRevenueReconciles() {
        jdbc.update("DELETE FROM payment_transactions WHERE booking_id=?",booking);
        jdbc.update("UPDATE bookings SET status='PENDING',expires_at=CURRENT_TIMESTAMP+interval '1 hour' WHERE id=?",booking);
        assertThatThrownBy(()->promotions.staffReceive(booking,"VNPAY",false,null)).hasMessageContaining("confirm");
        promotions.staffReceive(booking,"VNPAY",true,"counter-court");promotions.staffReceive(booking,"VNPAY",true,"counter-court");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_transactions WHERE booking_id=?",Integer.class,booking)).isEqualTo(1);
        var draft=pos.sell(sale(true,null));
        UUID secondStaff=UUID.randomUUID();
        jdbc.update("INSERT INTO staffs(id,user_id,full_name,phone,position) VALUES (?,?,'Second collector','0000000002','Cashier')",secondStaff,admin);
        try {
            actor(true);var id=checkout.checkout(booking,receipt("CASH","10000")).getData().getInvoiceId();
            assertThat(jdbc.queryForObject("SELECT added_by FROM invoice_details WHERE invoice_id=?",UUID.class,id)).isEqualTo(staff);
            assertThat(jdbc.queryForObject("SELECT created_by FROM invoices WHERE id=?",UUID.class,id)).isEqualTo(secondStaff);
            var report=revenue.report("YEAR",LocalDate.now().getYear());
            assertThat(report.staff().stream().filter(r->staff.equals(r.staffId())).map(r->r.total()).reduce(BigDecimal.ZERO,BigDecimal::add)).isEqualByComparingTo("200000");
            assertThat(report.staff().stream().filter(r->secondStaff.equals(r.staffId())).map(r->r.total()).reduce(BigDecimal.ZERO,BigDecimal::add)).isEqualByComparingTo("10000");
            assertThat(report.staff().stream().map(r->r.total()).reduce(BigDecimal.ZERO,BigDecimal::add))
                .isEqualByComparingTo(analytics.getRevenue("DAY",LocalDate.now()).getTotalAmount());
            jdbc.update("UPDATE invoices SET created_by=NULL,updated_by=NULL WHERE id=?",id);
            jdbc.update("UPDATE payment_transactions SET confirmed_by=NULL WHERE invoice_id=?",id);
        } finally {jdbc.update("DELETE FROM staffs WHERE id=?",secondStaff);}
    }
    @Test void httpCannotSpoofReceiptActorAndReportsRemainAdminOnly() throws Exception {
        var r=sale(false,"VNPAY");
        String json=new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(r);
        var auth=org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication(SecurityContextHolder.getContext().getAuthentication());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/pos/sales").contextPath("/api")
            .with(auth).contentType("application/json").content(json))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated());
        assertThat(jdbc.queryForObject("SELECT confirmed_by FROM payment_transactions WHERE invoice_id=(SELECT id FROM invoices WHERE pos_request_key=?)",UUID.class,r.getIdempotencyKey())).isEqualTo(staff);
        for(String route:List.of("/api/analytics/statistics","/api/analytics/revenue-breakdown")) {
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(route).contextPath("/api").with(auth))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
            actor(true);
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(route).contextPath("/api")
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication(SecurityContextHolder.getContext().getAuthentication())))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        }
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"CASH,VNPAY","VNPAY,VNPAY","CASH,CASH"})
    void methodsComeFromReceiptsInCourtThenCheckoutOrder(String courtMethod,String goodsMethod) {
        if(courtMethod.equals("CASH")) jdbc.update("UPDATE payment_transactions SET payment_method='CASH',source='MANUAL',confirmed_by=?,confirmed_by_user=? WHERE booking_id=?",staff,user,booking);
        pos.sell(sale(true,null));
        var result=checkout.checkout(booking,receipt(goodsMethod,"10000")).getData();
        var expected=java.util.stream.Stream.of(courtMethod,goodsMethod).distinct().toList();
        assertThat(result.getPaymentMethods()).containsExactlyElementsOf(expected);
        var report=analytics.invoice(result.getInvoiceId());
        assertThat(report.invoice().paymentMethods()).containsExactlyElementsOf(expected);
        assertThat(report.invoice().paymentMethod()).isEqualTo(expected.size()==1?courtMethod:null);
        assertThat(report.receipts().stream().map(r->r.amount()).reduce(BigDecimal.ZERO,BigDecimal::add)).isEqualByComparingTo(report.invoice().totalAmount());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_transactions WHERE payment_method NOT IN ('CASH','VNPAY')",Integer.class)).isZero();
    }
    @Test void checkoutReceiptFailureRollsBackMainSubBookingAndInventoryState() {
        var draft=pos.sell(sale(true,null));var request=receipt("CASH","10000");request.setReference("x".repeat(300));
        assertThatThrownBy(()->checkout.checkout(booking,request)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("SELECT status FROM bookings WHERE id=?",String.class,booking)).isEqualTo("PAID");
        assertThat(jdbc.queryForObject("SELECT status FROM invoices WHERE id=?",String.class,draft.getId())).isEqualTo("DRAFT");
        assertThat(jdbc.queryForObject("SELECT parent_invoice_id FROM invoices WHERE id=?",UUID.class,draft.getId())).isNull();
        assertThat(jdbc.queryForObject("SELECT invoice_id FROM invoice_details WHERE product_id=?",UUID.class,product)).isEqualTo(draft.getId());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM invoices WHERE booking_id=? AND status='PAID'",Integer.class,booking)).isZero();
        assertThat(stock()).isEqualTo(9);
    }
    @Test void draftWithOwnPaymentIsNotSilentlyMergedAndLost() {
        var draft=pos.sell(sale(true,null));
        jdbc.update("INSERT INTO payment_transactions(invoice_id,payment_method,status,amount) VALUES (?,'VNPAY','PENDING',10000)",draft.getId());
        assertThatThrownBy(()->checkout.checkout(booking,receipt("CASH","10000"))).hasMessageContaining("reconciliation required");
        assertThat(jdbc.queryForObject("SELECT status FROM invoices WHERE id=?",String.class,draft.getId())).isEqualTo("DRAFT");
        assertThat(jdbc.queryForObject("SELECT status FROM bookings WHERE id=?",String.class,booking)).isEqualTo("PAID");
    }
    @Test void replayRejectsUnreconciledHistoryWithoutRepairingPaidInvoice() {
        var id=checkout.checkout(booking,receipt(null,"0")).getData().getInvoiceId();
        jdbc.update("UPDATE invoices SET total_amount=199999 WHERE id=?",id);
        assertThatThrownBy(()->checkout.checkout(booking,receipt(null,"0"))).hasMessageContaining("do not reconcile");
        assertThat(jdbc.queryForObject("SELECT total_amount FROM invoices WHERE id=?",BigDecimal.class,id)).isEqualByComparingTo("199999");
    }
    @Test void mainAndChildUseOwnReceiptsAndFiltersNeverTrustStaleSummary() {
        pos.sell(sale(true,null));var id=checkout.checkout(booking,receipt("CASH","10000")).getData().getInvoiceId();
        for(String method:List.of("CASH","VNPAY")) assertThat(analytics.history("DAY",LocalDate.now(),0,null,null,"",method).invoices()).extracting(r->r.id()).contains(id);
        UUID child=UUID.randomUUID();
        jdbc.update("INSERT INTO invoices(id,booking_id,parent_invoice_id,customer_id,payment_method,status,court_fee,product_fee,total_amount) VALUES (?,?,?,?,'VNPAY','PAID',0,7,7)",child,booking,id,customer);
        jdbc.update("INSERT INTO payment_transactions(invoice_id,payment_method,source,status,amount,transaction_id,transaction_date,confirmed_by,confirmed_by_user) VALUES (?,'CASH','MANUAL','SUCCESS',7,?,CURRENT_TIMESTAMP,?,?)",child,"CHILD-"+UUID.randomUUID(),staff,user);
        assertThat(checkout.checkout(booking,receipt("CASH","10000")).getData().getInvoiceId()).isEqualTo(id);
        assertThat(analytics.invoice(child).invoice().paymentMethods()).containsExactly("CASH");
        assertThat(analytics.invoice(child).receipts()).hasSize(1);
        assertThat(analytics.invoice(id).receipts()).hasSize(2);
        assertThat(analytics.history("DAY",LocalDate.now(),0,null,null,"","VNPAY").invoices()).extracting(r->r.id()).doesNotContain(child).contains(id);
        assertThat(analytics.history("DAY",LocalDate.now(),0,null,null,"","CASH").invoices()).extracting(r->r.id()).contains(child,id);
        var report=revenue.report("YEAR",LocalDate.now().getYear());
        assertThat(report.staff().stream().filter(r->staff.equals(r.staffId())).map(r->r.total()).reduce(BigDecimal.ZERO,BigDecimal::add)).isEqualByComparingTo("10007");
        assertThat(report.staff().stream().map(r->r.total()).reduce(BigDecimal.ZERO,BigDecimal::add)).isEqualByComparingTo("210007");
    }
    @Test void checkoutRequiresTheActualStartAndRejectsMissingDetails() {
        jdbc.update("UPDATE booking_details SET booking_date=? WHERE booking_id=?",LocalDate.now(java.time.ZoneId.of("Asia/Ho_Chi_Minh")).plusDays(1),booking);
        assertThat(checkout.preview(booking).isCheckoutEligible()).isFalse();
        assertThatThrownBy(()->checkout.checkout(booking,receipt(null,"0"))).hasMessageContaining("không thể kết toán");
        jdbc.update("DELETE FROM booking_details WHERE booking_id=?",booking);
        assertThat(checkout.preview(booking).getCheckoutMessage()).contains("Không đủ chi tiết");
        assertThatThrownBy(()->checkout.checkout(booking,receipt(null,"0"))).hasMessageContaining("Không đủ chi tiết");
    }
    @Test void futurePaidCourtCannotBeClosedOrMaintained() {
        jdbc.update("UPDATE booking_details SET booking_date=? WHERE booking_id=?",LocalDate.now().plusDays(1),booking);
        int code=jdbc.queryForObject("SELECT court_code FROM courts WHERE id=?",Integer.class,court);
        for(var status:List.of(CourtStatus.CLOSED,CourtStatus.MAINTENANCE)) {
            var request=new CourtRequest(code,"Checkout test",CourtType.STANDARD_MAT,status,new BigDecimal("200000"),null);
            assertThatThrownBy(()->master.saveCourt(court,request)).hasMessageContaining("không thể bảo trì");
        }
        assertThat(jdbc.queryForObject("SELECT status FROM courts WHERE id=?",String.class,court)).isEqualTo("AVAILABLE");
    }
    @Test void successfulCallbackHasDateAndCanBeCheckedOutIdempotently() {
        UUID payment=jdbc.queryForObject("SELECT id FROM payment_transactions WHERE booking_id=?",UUID.class,booking);
        jdbc.update("UPDATE payment_transactions SET status='PENDING',transaction_id=NULL,transaction_date=NULL WHERE id=?",payment);
        jdbc.update("UPDATE bookings SET status='PENDING',expires_at=CURRENT_TIMESTAMP+interval '1 hour' WHERE id=?",booking);
        var missing=new PaymentNotification(payment,PaymentMethod.VNPAY,new BigDecimal("200000"),true,"1234567",null,false);
        assertThat(callbacks.settle(missing,booking)).isEqualTo(PaymentCallbackTransactionService.Result.INVALID_DATA);
        var notification=new PaymentNotification(payment,PaymentMethod.VNPAY,new BigDecimal("200000"),true,"1234567",java.time.LocalDateTime.now().withNano(0),false);
        assertThat(callbacks.settle(notification,booking)).isEqualTo(PaymentCallbackTransactionService.Result.SUCCESS);
        assertThat(callbacks.settle(notification,booking)).isEqualTo(PaymentCallbackTransactionService.Result.ALREADY_CONFIRMED);
        assertThat(checkout.preview(booking).isCheckoutEligible()).isTrue();
        var id=checkout.checkout(booking,receipt(null,"0")).getData().getInvoiceId();
        assertThat(analytics.invoice(id).receipts()).hasSize(1);
    }
    @Test void courtPromotionsCannotMakeFreeBookingsForEitherDiscountType() {
        for(String type:List.of("PERCENT","AMOUNT")) {
            var policy=new CourtPromotionRequest("Test zero","ZERO-"+UUID.randomUUID(),"VOUCHER",type,new BigDecimal("100"),new BigDecimal("100"),null,null,null,LocalDate.now(),null,true);
            assertThatThrownBy(()->courtPromotions.save(null,policy)).isInstanceOf(com.bcm.exception.BadRequestException.class);
            var rule=new HashMap<String,Object>();rule.put("discount_type",type);rule.put("discount_value",new BigDecimal("100"));
            assertThatThrownBy(()->courtPromotions.amount(rule,new BigDecimal("100"))).hasMessageContaining("lớn hơn 0");
        }
    }
    @Test void posRejectsCourtFeeManualDiscountAndStaleQuoteWithoutTakingStock() {
        for(boolean attached:List.of(false,true)) {
            var r=sale(attached,"CASH");r.setCourtFee(BigDecimal.ONE);
            assertThatThrownBy(()->pos.quote(r)).hasMessageContaining("POS không nhập lại");
            r.setCourtFee(BigDecimal.ZERO);r.setDiscountAmount(BigDecimal.ONE);
            assertThatThrownBy(()->pos.sell(r)).hasMessageContaining("POS không nhập lại");
        }
        var r=sale(false,"CASH");r.setExpectedTotal(pos.quote(r).totalAmount());
        jdbc.update("UPDATE products SET price=11000 WHERE id=?",product);
        assertThatThrownBy(()->pos.sell(r)).hasMessageContaining("Báo giá đã thay đổi");assertThat(stock()).isEqualTo(10);
        r.setExpectedTotal(new BigDecimal("11000"));assertThat(pos.sell(r).getTotalAmount()).isEqualByComparingTo("11000");
    }
    ImportOrderRequest importRequest() {
        return new ImportOrderRequest(supplier,LocalDate.now(),UUID.randomUUID(),List.of(new ImportOrderDetailRequest(product,2,new BigDecimal("1234.56"))));
    }
    @Test void importLocksOrderAndAddsGoodsOnceWithTrustedActor() throws Exception {
        var request=importRequest();var order=imports.create(request);
        assertThat(stock()).isEqualTo(10);assertThat(order.getCreatedBy()).isEqualTo(staff).isNotEqualTo(request.getStaffId());
        assertThat(order.getCreatedByUser()).isEqualTo(user);
        var outcomes=concurrent(()->{try {imports.approve(order.getId());return true;}catch(com.bcm.exception.BadRequestException e){return false;}});
        assertThat(outcomes).containsExactlyInAnyOrder(true,false);assertThat(stock()).isEqualTo(12);
        assertThatThrownBy(()->imports.approve(order.getId())).hasMessageContaining("nhận hàng");
        assertThat(jdbc.queryForObject("SELECT price FROM products WHERE id=?",BigDecimal.class,product)).isEqualByComparingTo("10000");
        assertThat(imports.findById(order.getId()).getUpdatedBy()).isEqualTo(staff);
    }
    @Test void importRejectsServicesAndDeletedProductsWithoutStockOrStatusMutation() {
        jdbc.update("UPDATE products SET type='SERVICE',stock_quantity=0 WHERE id=?",product);
        assertThatThrownBy(()->imports.create(importRequest())).hasMessageContaining("GOODS");
        jdbc.update("UPDATE products SET type='GOODS',stock_quantity=10 WHERE id=?",product);
        var draft=imports.create(importRequest());jdbc.update("UPDATE products SET deleted_at=CURRENT_TIMESTAMP WHERE id=?",product);
        assertThatThrownBy(()->imports.approve(draft.getId())).hasMessageContaining("GOODS");
        assertThatThrownBy(()->imports.create(importRequest())).isInstanceOf(com.bcm.exception.ResourceNotFoundException.class);
        assertThat(imports.findById(draft.getId()).getStatus()).isEqualTo("DRAFT");assertThat(stock()).isEqualTo(10);
        jdbc.update("UPDATE products SET deleted_at=NULL WHERE id=?",product);
    }
    @Test void adminImportRecordsUserWithoutInventingStaffAndCancelKeepsStock() {
        actor(true);var draft=imports.create(importRequest());assertThat(draft.getCreatedBy()).isNull();assertThat(draft.getCreatedByUser()).isEqualTo(admin);
        imports.delete(draft.getId());assertThat(stock()).isEqualTo(10);
        assertThatThrownBy(()->imports.approve(draft.getId())).isInstanceOf(com.bcm.exception.BadRequestException.class);
    }
    @Test void separateCourtAndProductDiscountsStayInTheirOwnRevenueBuckets() {
        jdbc.update("UPDATE bookings SET discount_amount=20000 WHERE id=?",booking);
        jdbc.update("UPDATE payment_transactions SET amount=180000 WHERE booking_id=?",booking);
        var draft=pos.sell(sale(true,null));
        jdbc.update("UPDATE invoice_details SET discount_amount=2000 WHERE invoice_id=?",draft.getId());
        jdbc.update("UPDATE invoices SET discount_amount=2000,total_amount=8000 WHERE id=?",draft.getId());
        var id=checkout.checkout(booking,receipt("CASH","8000")).getData().getInvoiceId();
        assertThat(analytics.invoice(id).invoice().totalAmount()).isEqualByComparingTo("188000");
        var report=revenue.report("YEAR",LocalDate.now().getYear(),null,true);
        assertThat(report.products().stream().map(r->r.total()).reduce(BigDecimal.ZERO,BigDecimal::add)).isEqualByComparingTo("8000");
        assertThat(report.courts().stream().map(r->r.total()).reduce(BigDecimal.ZERO,BigDecimal::add)).isEqualByComparingTo("180000");
        assertThat(report.details().get(0).invoiceTotal()).isEqualByComparingTo("188000");
        // An explicit additional invoice-wide cent is shared only after separate discounts.
        jdbc.update("UPDATE invoices SET discount_amount=discount_amount+0.01,total_amount=total_amount-0.01 WHERE id=?",id);
        jdbc.update("UPDATE payment_transactions SET amount=amount-0.01 WHERE invoice_id=?",id);
        var rounded=revenue.report("YEAR",LocalDate.now().getYear(),null,true).details().get(0);
        assertThat(rounded.productTotal().add(rounded.courtTotal())).isEqualByComparingTo("187999.99");
        assertThat(rounded.invoiceTotal()).isEqualByComparingTo("187999.99");
    }
    @Test void statisticsCountsOnlyReceivedGoodsAndKeepsLegacyConfirmedReadable() {
        var draft=imports.create(importRequest());
        assertThat(statistics.report("YEAR",LocalDate.now().getYear(),null,null).totalExpense()).isEqualByComparingTo("0");
        jdbc.update("UPDATE import_orders SET status='CONFIRMED' WHERE id=?",draft.getId());
        assertThat(imports.findById(draft.getId()).getStatus()).isEqualTo("CONFIRMED");
        assertThat(statistics.report("YEAR",LocalDate.now().getYear(),null,null).totalExpense()).isEqualByComparingTo("0");
        jdbc.update("UPDATE import_orders SET status='DRAFT' WHERE id=?",draft.getId());imports.approve(draft.getId());
        assertThat(statistics.report("YEAR",LocalDate.now().getYear(),null,null).totalExpense()).isEqualByComparingTo("2469.12");
    }
    void createPendingBookingAsCustomer() {
        UUID slot=jdbc.queryForObject("SELECT time_slot_id FROM booking_details WHERE booking_id=? LIMIT 1",UUID.class,booking);
        jdbc.update("DELETE FROM payment_transactions WHERE booking_id=?",booking);
        jdbc.update("DELETE FROM booking_details WHERE booking_id=?",booking);jdbc.update("DELETE FROM bookings WHERE id=?",booking);
        var principal=new UserPrincipal(user.toString(),"fixture-customer","test",List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER")),true);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal,null,principal.getAuthorities()));
        var result=bookingService.createBooking(new CreateBookingRequest(customer,List.of(new CreateBookingRequest.Detail(LocalDate.now(java.time.ZoneId.of("Asia/Ho_Chi_Minh")).plusDays(2),court,slot))));
        booking=result.getBookingId();assertThat(result.getStatus()).isEqualTo(BookingStatus.PENDING);
    }
    @Test void completeDemoAllowsEarlyCheckoutAtActualStart() throws Exception {
        createPendingBookingAsCustomer();
        assertThatThrownBy(()->paymentAttempts.createAttempt(booking,new CreatePaymentAttemptRequest(PaymentMethod.CASH))).isInstanceOf(com.bcm.exception.BadRequestException.class);
        var attempt=paymentAttempts.createAttempt(booking,new CreatePaymentAttemptRequest(PaymentMethod.VNPAY));
        var notification=new PaymentNotification(attempt.paymentAttemptId(),PaymentMethod.VNPAY,attempt.amount(),true,"3456789",java.time.LocalDateTime.now().withNano(0),false);
        assertThat(callbacks.settle(notification,booking)).isEqualTo(PaymentCallbackTransactionService.Result.SUCCESS);actor(false);
        pos.sell(sale(true,null));assertThat(checkout.preview(booking).isCheckoutEligible()).isFalse();
        assertThatThrownBy(()->checkout.checkout(booking,receipt("CASH","10000"))).hasMessageContaining("không thể kết toán");
        Object target=org.springframework.test.util.AopTestUtils.getUltimateTargetObject(checkout);
        var previous=(java.time.Clock)org.springframework.test.util.ReflectionTestUtils.getField(target,"clock");
        var start=jdbc.queryForObject("SELECT min(d.booking_date+t.start_time) FROM booking_details d JOIN time_slots t ON t.id=d.time_slot_id WHERE d.booking_id=?",java.time.LocalDateTime.class,booking);
        try {
            var zone=java.time.ZoneId.of("Asia/Ho_Chi_Minh");
            org.springframework.test.util.ReflectionTestUtils.setField(target,"clock",java.time.Clock.fixed(start.atZone(zone).toInstant().minusNanos(1),zone));
            assertThat(checkout.preview(booking).isCheckoutEligible()).isFalse();
            org.springframework.test.util.ReflectionTestUtils.setField(target,"clock",java.time.Clock.fixed(start.atZone(zone).toInstant(),zone));
            assertThat(checkout.preview(booking).isCheckoutEligible()).isTrue();
            var result=checkout.checkout(booking,receipt("CASH","10000")).getData();
            assertThat(result.getRemainingDue()).isEqualByComparingTo("0");assertThat(result.getPaymentMethods()).containsExactly("VNPAY","CASH");
            assertThat(jdbc.queryForObject("SELECT status FROM bookings WHERE id=?",String.class,booking)).isEqualTo("COMPLETED");
            assertThat(expiration.expireBooking(booking)).isFalse();
        } finally {org.springframework.test.util.ReflectionTestUtils.setField(target,"clock",previous);}
    }
    @Test void pendingExpiresAtomicallyAndZeroFinalAmountCannotStartPayment() {
        createPendingBookingAsCustomer();
        jdbc.update("UPDATE bookings SET discount_amount=court_fee WHERE id=?",booking);
        assertThatThrownBy(()->paymentAttempts.createAttempt(booking,new CreatePaymentAttemptRequest(PaymentMethod.VNPAY))).hasMessageContaining("positive");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_transactions WHERE booking_id=?",Integer.class,booking)).isZero();
        jdbc.update("UPDATE bookings SET expires_at=CURRENT_TIMESTAMP-interval '1 hour' WHERE id=?",booking);
        assertThat(expiration.expireBooking(booking)).isTrue();assertThat(expiration.expireBooking(booking)).isFalse();
        assertThat(jdbc.queryForObject("SELECT status FROM bookings WHERE id=?",String.class,booking)).isEqualTo("EXPIRED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM booking_details WHERE booking_id=?",Integer.class,booking)).isZero();
    }
    @Test void independentCustomerTransactionsCannotReserveTheSameSlotTwice() throws Exception {
        UUID slot=jdbc.queryForObject("SELECT time_slot_id FROM booking_details WHERE booking_id=? LIMIT 1",UUID.class,booking);
        var created=new java.util.concurrent.ConcurrentLinkedQueue<UUID>();
        try {
            var outcomes=concurrent(()->{
                var principal=new UserPrincipal(user.toString(),"fixture-customer","test",List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER")),true);
                SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal,null,principal.getAuthorities()));
                try {
                    var r=bookingService.createBooking(new CreateBookingRequest(customer,List.of(new CreateBookingRequest.Detail(LocalDate.now(java.time.ZoneId.of("Asia/Ho_Chi_Minh")).plusDays(3),court,slot))));
                    created.add(r.getBookingId());return true;
                } catch(com.bcm.exception.DuplicateResourceException ex) {return false;}
            });
            assertThat(outcomes).containsExactlyInAnyOrder(true,false);assertThat(created).hasSize(1);
            int code=jdbc.queryForObject("SELECT court_code FROM courts WHERE id=?",Integer.class,court);
            assertThatThrownBy(()->master.saveCourt(court,new CourtRequest(code,"Fixture",CourtType.STANDARD_MAT,CourtStatus.CLOSED,new BigDecimal("200000"),null))).hasMessageContaining("không thể bảo trì");
        } finally {
            for(var id:created) {jdbc.update("DELETE FROM booking_details WHERE booking_id=?",id);jdbc.update("DELETE FROM bookings WHERE id=?",id);}
        }
    }
    <T> List<T> concurrent(Callable<T> work) throws Exception {
        var pool=Executors.newFixedThreadPool(2);var ready=new CountDownLatch(2);var start=new CountDownLatch(1);
        Callable<T> task=()->{actor(false);ready.countDown();try{start.await();return work.call();}finally{SecurityContextHolder.clearContext();}};
        try {var a=pool.submit(task);var b=pool.submit(task);assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue();start.countDown();return List.of(a.get(30,TimeUnit.SECONDS),b.get(30,TimeUnit.SECONDS));}
        finally {start.countDown();pool.shutdownNow();}
    }
}
