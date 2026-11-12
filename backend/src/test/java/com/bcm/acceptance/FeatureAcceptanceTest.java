package com.bcm.acceptance;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.*;
import com.bcm.security.UserPrincipal;
import com.bcm.service.VnPaySigner;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.time.*;
import java.math.BigDecimal;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
@SpringBootTest(properties={"booking.expiration-enabled=false","booking.payment.enabled-methods=VNPAY","booking.payment.vnpay.tmn-code=ACCEPT01","booking.payment.vnpay.hash-secret=acceptance-only-not-real","booking.payment.vnpay.return-url=https://merchant.example/return","spring.jpa.show-sql=false","logging.level.org.hibernate.SQL=OFF","logging.level.org.springframework= WARN"})
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.MethodName.class)
/** New acceptance-only HTTP/database suite. No legacy fixture/test inheritance.
 * Commits are intentional for lock/callback checks; UUID-scoped cleanup runs after each case. */
@org.junit.jupiter.api.parallel.Execution(org.junit.jupiter.api.parallel.ExecutionMode.SAME_THREAD)
class FeatureAcceptanceTest {
 @DynamicPropertySource static void database(DynamicPropertyRegistry r){
  String url=System.getenv("BCM_ACCEPTANCE_DB_URL");
  if(url==null||!url.matches("jdbc:postgresql://127\\.0\\.0\\.1:(?:5433|55438)/bcm_acceptance_test"))throw new IllegalStateException("Dedicated acceptance database required");
  r.add("spring.datasource.url",()->url);r.add("spring.datasource.username",()->"bcm_acceptance_owner");r.add("spring.datasource.password",()->"acceptance-local-only");
 }
 @Autowired com.bcm.service.BookingExpirationService expiration;
 @Autowired MockMvc mvc; @Autowired ObjectMapper mapper; @Autowired JdbcTemplate db; @Autowired PasswordEncoder encoder;
 static final AtomicLong codes=new AtomicLong(80000000),transactions=new AtomicLong(10000);
 UUID uid,cid,court,slot,category,product; List<UUID> customers;
 com.bcm.security.UserPrincipal staffActor,adminActor;
 String providerDate;
 @BeforeEach void fixtures(){
  staffActor=com.bcm.TestActors.staff(db);adminActor=com.bcm.TestActors.admin(db);
  providerDate=java.time.LocalDateTime.now(java.time.ZoneId.of("Asia/Ho_Chi_Minh")).format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
  assertThat(db.queryForObject("select current_database()",String.class)).isEqualTo("bcm_acceptance_test");
  uid=UUID.randomUUID();cid=UUID.randomUUID();court=UUID.randomUUID();category=UUID.randomUUID();product=UUID.randomUUID();customers=new ArrayList<>(List.of(cid));
  db.update("insert into users(id,email,password_hash,full_name,phone) values (?,?,'test','Acceptance','0912345678')",uid,uid+"@acceptance.invalid");
  db.update("insert into user_roles(user_id,role_id) select ?,id from roles where role_name='CUSTOMER'",uid);
  db.update("insert into customers(id,user_id,full_name,phone) values (?,?,'Acceptance','0912345678')",cid,uid);
  db.update("insert into courts(id,court_code,name,type,status,base_price) values (?,?,'Acceptance court','STANDARD_MAT','AVAILABLE',100000)",court,codes.incrementAndGet());
  slot=db.queryForObject("select id from time_slots where start_time='10:00' and end_time='11:00' and deleted_at is null",UUID.class);
  db.update("insert into categories(id,category_name) values (?,?)",category,"Acceptance-"+category);
  db.update("insert into products(id,category_id,name,type,unit,price,stock_quantity) values (?,?,'Acceptance goods','GOODS','unit',10000,10)",product,category);
 }
 @AfterEach void cleanup(){
  for(UUID c:customers){
   UUID u=db.queryForObject("select user_id from customers where id=?",UUID.class,c);
   db.update("delete from customer_voucher_usage where customer_id=?",c);
   db.update("delete from payment_transactions where booking_id in(select id from bookings where customer_id=?) or invoice_id in(select id from invoices where customer_id=?)",c,c);
   db.update("delete from invoice_details where invoice_id in(select id from invoices where customer_id=?)",c);
   db.update("delete from invoices where customer_id=?",c);db.update("delete from bookings where customer_id=?",c);
   db.update("delete from customers where id=?",c);db.update("delete from user_roles where user_id=?",u);db.update("delete from users where id=?",u);
  }
  db.update("delete from products where id=?",product);db.update("delete from categories where id=?",category);db.update("delete from courts where id=?",court);
 }
 RequestPostProcessor as(String role){if(role.equals("STAFF"))return user(staffActor);if(role.equals("ADMIN"))return user(adminActor);return user(new UserPrincipal(uid.toString(),uid+"@acceptance.invalid","unused",List.of(new SimpleGrantedAuthority("ROLE_"+role)),true));}
 JsonNode call(MockHttpServletRequestBuilder request,String role,int status)throws Exception{
  var response=mvc.perform(request.with(as(role))).andReturn().getResponse();
  assertThat(response.getStatus()).withFailMessage("Expected %s, got %s: %s",status,response.getStatus(),response.getContentAsString()).isEqualTo(status);
  return mapper.readTree(response.getContentAsString()).path("data");
 }
 MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder r,Object body)throws Exception{return r.contentType("application/json").content(mapper.writeValueAsString(body));}
 Map<String,Object> bookingBody(LocalDate date){return Map.of("customerId",cid,"details",List.of(Map.of("bookingDate",date.toString(),"courtId",court,"timeSlotId",slot)));}
 UUID book()throws Exception{return UUID.fromString(call(json(post("/bookings"),bookingBody(LocalDate.now().plusDays(7))),"CUSTOMER",201).path("bookingId").asText());}
 JsonNode pay(UUID b,String method,int expected)throws Exception{return call(json(post("/bookings/"+b+"/payments"),Map.of("paymentMethod",method)),"CUSTOMER",expected);}
 String state(UUID b){return db.queryForObject("select status from bookings where id=?",String.class,b);}
 int payments(UUID b){return db.queryForObject("select count(*) from payment_transactions where booking_id=?",Integer.class,b);}
 Map<String,String> fields(UUID attempt,String response,String amount){
  var f=new TreeMap<String,String>();f.put("vnp_PayDate",providerDate);f.put("vnp_TmnCode","ACCEPT01");f.put("vnp_TxnRef",attempt.toString().replace("-",""));f.put("vnp_Amount",amount);f.put("vnp_BankCode","NCB");f.put("vnp_OrderInfo","Acceptance payment");f.put("vnp_ResponseCode",response);f.put("vnp_TransactionStatus",response.equals("00")?"00":"02");f.put("vnp_TransactionNo",Long.toString(transactions.incrementAndGet()));sign(f);return f;
 }
 void sign(Map<String,String> f){f.put("vnp_SecureHash",VnPaySigner.sign("acceptance-only-not-real",VnPaySigner.canonicalData(f)));}
 String ipn(Map<String,String> f)throws Exception{var r=get("/payments/vnpay/ipn");f.forEach(r::param);return mapper.readTree(mvc.perform(r).andReturn().getResponse().getContentAsString()).path("RspCode").asText();}
 @Test void customerCrudHistoryAndHashedAccount()throws Exception{
  var body=new HashMap<String,Object>(Map.of("fullName","Acceptance created","email",UUID.randomUUID()+"@acceptance.invalid","phone","0912345678","address","Test address","password","Acceptance123!"));
  UUID c=UUID.fromString(call(json(post("/admin/customers"),body),"ADMIN",200).asText());customers.add(c);
  var row=db.queryForMap("select u.password_hash,u.id from users u join customers c on c.user_id=u.id where c.id=?",c);
  assertThat(encoder.matches("Acceptance123!",(String)row.get("password_hash"))).isTrue();
  call(json(post("/admin/customers"),body),"ADMIN",409);
  var login=mapper.readTree(mvc.perform(json(post("/auth/login"),Map.of("email",body.get("email"),"password",body.get("password")))).andReturn().getResponse().getContentAsString()).path("data");
  assertThat(login.path("accessToken").asText()).isNotBlank();
  assertThat(mvc.perform(get("/bookings").header("Authorization","Bearer "+login.path("accessToken").asText())).andReturn().getResponse().getStatus()).isEqualTo(200);
  body.put("fullName","Acceptance updated");call(json(put("/admin/customers/"+c),body),"ADMIN",200);
  assertThat(db.queryForObject("select full_name from users where id=?",String.class,row.get("id"))).isEqualTo("Acceptance updated");
  assertThat(call(get("/admin/customers").param("search",body.get("email").toString()),"ADMIN",200).path("totalElements").asLong()).isEqualTo(1);
  assertThat(call(get("/admin/customers").param("search",body.get("email").toString()).param("page","1"),"ADMIN",200).path("customers").size()).isZero();
  db.update("insert into bookings(customer_id,status,court_fee) values (?,'COMPLETED',100000)",c);
  db.update("insert into invoices(customer_id,booking_id,payment_method,status,court_fee,total_amount) select ?,id,'CASH','PAID',100000,100000 from bookings where customer_id=?",c,c);
  var history=call(get("/admin/customers/"+c),"ADMIN",200);assertThat(history.path("bookings").size()).isEqualTo(1);assertThat(history.path("invoices").size()).isEqualTo(1);
  call(delete("/admin/customers/"+c),"ADMIN",200);
  assertThat(db.queryForObject("select count(*) from bookings where customer_id=?",Integer.class,c)).isEqualTo(1);
  assertThat(db.queryForObject("select count(*) from invoices where customer_id=?",Integer.class,c)).isEqualTo(1);
  assertThat(db.queryForObject("select deleted_at is not null from users where id=?",Boolean.class,row.get("id"))).isTrue();
 }
 @ParameterizedTest @ValueSource(strings={"STAFF","CUSTOMER"}) void managementAuthorization(String role)throws Exception{call(get("/admin/customers"),role,403);call(get("/analytics/invoices"),role,403);}
 @Test void bookingHoldExpiryAndPastSlot()throws Exception{
  UUID b=book();assertThat(state(b)).isEqualTo("PENDING");assertThat(db.queryForObject("select count(*) from booking_details where booking_id=? and court_id=? and time_slot_id=?",Integer.class,b,court,slot)).isEqualTo(1);
  call(json(post("/bookings"),bookingBody(LocalDate.now().plusDays(7))),"CUSTOMER",409);
  call(json(post("/bookings"),bookingBody(LocalDate.now().minusDays(1))),"CUSTOMER",400);
  db.update("update bookings set expires_at=current_timestamp-interval '1 minute' where id=?",b);
  pay(b,"VNPAY",400);assertThat(payments(b)).isZero();
  assertThat(expiration.expireBooking(b)).isTrue();assertThat(state(b)).isEqualTo("EXPIRED");assertThat(db.queryForObject("select count(*) from booking_details where booking_id=?",Integer.class,b)).isZero();
 }
 @ParameterizedTest @ValueSource(strings={"CASH","BANK_TRANSFER"}) void customerOnlyVnpayNoMutations(String method)throws Exception{
  UUID b=book();var before=db.queryForMap("select status,discount_amount,promotion_code,requested_voucher from bookings where id=?",b);
  pay(b,method,400);assertThat(payments(b)).isZero();assertThat(db.queryForMap("select status,discount_amount,promotion_code,requested_voucher from bookings where id=?",b)).isEqualTo(before);
 }
 @Test void vnpayVerifiedSuccessRetryAndDuplicates()throws Exception{
  UUID b=book();UUID a=UUID.fromString(pay(b,"VNPAY",201).path("paymentAttemptId").asText());assertThat(pay(b,"VNPAY",201).path("paymentAttemptId").asText()).isEqualTo(a.toString());assertThat(payments(b)).isEqualTo(1);assertThat(state(b)).isEqualTo("PENDING");
  var f=fields(a,"00","10000000");assertThat(ipn(f)).isEqualTo("00");assertThat(ipn(f)).isEqualTo("02");assertThat(payments(b)).isEqualTo(1);assertThat(state(b)).isEqualTo("PAID");assertThat(db.queryForObject("select status from payment_transactions where id=?",String.class,a)).isEqualTo("SUCCESS");
 }
 @Test void invalidSignatureAmountFailureRetryAndLateCallback()throws Exception{
  UUID b=book();UUID a=UUID.fromString(pay(b,"VNPAY",201).path("paymentAttemptId").asText());
  var f=fields(a,"00","10000000");f.put("vnp_SecureHash","0".repeat(128));assertThat(ipn(f)).isEqualTo("97");assertThat(ipn(fields(a,"00","100"))).isEqualTo("04");assertThat(state(b)).isEqualTo("PENDING");
  assertThat(ipn(fields(a,"24","10000000"))).isEqualTo("00");assertThat(db.queryForObject("select status from payment_transactions where id=?",String.class,a)).isEqualTo("FAILED");
  UUID retry=UUID.fromString(pay(b,"VNPAY",201).path("paymentAttemptId").asText());assertThat(retry).isNotEqualTo(a);
  db.update("update bookings set expires_at=current_timestamp-interval '6 minutes' where id=?",b);assertThat(expiration.expireBooking(b)).isTrue();assertThat(ipn(fields(retry,"00","10000000"))).isEqualTo("99");assertThat(state(b)).isNotEqualTo("PAID");
 }
 @Test void foreignBookingCannotPay()throws Exception{UUID b=book();db.update("update bookings set customer_id=(select id from customers where id<>? limit 1) where id=?",cid,b);try{pay(b,"VNPAY",403);}finally{db.update("update bookings set customer_id=? where id=?",cid,b);}assertThat(payments(b)).isZero();}
 Map<String,Object> sale(UUID b,int quantity,String method){var m=new HashMap<String,Object>();m.put("customerId",cid);if(b!=null)m.put("bookingId",b);m.put("paymentMethod",method);m.put("items",List.of(Map.of("productId",product,"quantity",quantity)));m.put("idempotencyKey",UUID.randomUUID());m.put("paymentConfirmed",true);m.put("expectedTotal",10000L*quantity);return m;}
 @ParameterizedTest @ValueSource(strings={"CASH","VNPAY"}) void posCheckoutInvoiceAndRevenue(String method)throws Exception{
  UUID retail=UUID.fromString(call(json(post("/pos/sales"),sale(null,2,method)),"STAFF",201).path("id").asText());
  assertThat(db.queryForObject("select stock_quantity from products where id=?",Integer.class,product)).isEqualTo(8);
  call(json(post("/pos/sales"),sale(null,99,method)),"STAFF",400);
  UUID b=book();db.update("update bookings set status='PAID' where id=?",b);db.update("insert into payment_transactions(booking_id,payment_method,status,amount,transaction_id,transaction_date) values (?,'VNPAY','SUCCESS',100000,'ACCEPTANCE-RECEIPT',current_timestamp)",b);
  db.update("update booking_details set booking_date=? where booking_id=?",java.sql.Date.valueOf(LocalDate.now().minusDays(1)),b);
  UUID draft=UUID.fromString(call(json(post("/pos/sales"),sale(b,1,method)),"ADMIN",201).path("id").asText());
  var preview=call(get("/checkout/"+b),"STAFF",200);assertThat(preview.path("paidCourtAmount").decimalValue()).isEqualByComparingTo("100000");assertThat(preview.path("amountDue").decimalValue()).isEqualByComparingTo("10000");
  UUID invoice=UUID.fromString(call(json(post("/checkout/"+b),Map.of("paymentMethod",method,"paymentConfirmed",true,"expectedAmountDue",10000)),"STAFF",200).path("invoiceId").asText());
  assertThat(call(json(post("/checkout/"+b),Map.of("paymentMethod",method)),"STAFF",200).path("invoiceId").asText()).isEqualTo(invoice.toString());assertThat(state(b)).isEqualTo("COMPLETED");assertThat(db.queryForObject("select stock_quantity from products where id=?",Integer.class,product)).isEqualTo(7);
  assertThat(db.queryForObject("select status from invoices where id=?",String.class,draft)).isEqualTo("CANCELLED");assertThat(db.queryForObject("select count(*) from invoice_details where invoice_id=?",Integer.class,invoice)).isEqualTo(1);
  db.update("update products set price=99999 where id=?",product);
  String invoiceBefore=db.queryForObject("select row_to_json(i)::text from invoices i where id=?",String.class,invoice);
  var detail=call(get("/analytics/invoices/"+invoice),"ADMIN",200);assertThat(detail.path("invoice").path("totalAmount").decimalValue()).isEqualByComparingTo("110000");assertThat(detail.path("invoice").path("discountAmount").decimalValue()).isEqualByComparingTo("0");assertThat(detail.path("invoice").path("invoiceStatus").asText()).isEqualTo("PAID");assertThat(detail.path("invoice").path("bookingStatus").asText()).isEqualTo("COMPLETED");assertThat(detail.path("items").get(0).path("unitPrice").decimalValue()).isEqualByComparingTo("10000");
  assertThat(call(get("/analytics/invoices/"+retail),"ADMIN",200).path("invoice").path("bookingStatus").isNull()).isTrue();
  for(String search:List.of(invoice.toString(),b.toString())){var list=call(get("/analytics/invoices").param("from","2000-01-01").param("to","2099-12-31").param("search",search).param("method",method),"ADMIN",200);assertThat(list.path("totalElements").asInt()).isEqualTo(1);}
  assertThat(db.queryForObject("select row_to_json(i)::text from invoices i where id=?",String.class,invoice)).isEqualTo(invoiceBefore);
  for(String st:List.of("DRAFT","CANCELLED"))db.update("insert into invoices(customer_id,payment_method,status,total_amount) values (?,?,?,999999)",cid,null,st);
  db.update("insert into invoices(customer_id,payment_method,status,total_amount,deleted_at) values (?,?,'PAID',999999,current_timestamp)",cid,method);
  var page=call(get("/analytics/invoices").param("from","2000-01-01").param("to","2099-12-31").param("search","Acceptance").param("method",method),"ADMIN",200);assertThat(page.path("totalElements").asInt()).isEqualTo(2);
  assertThat(call(get("/analytics/invoices").param("from","2000-01-01").param("to","2099-12-31").param("search","Acceptance").param("page","1"),"ADMIN",200).path("invoices").size()).isZero();
  assertThat(call(get("/analytics/revenue").param("from","2045-01-01").param("to","2045-01-02"),"ADMIN",200).path("total_amount").decimalValue()).isEqualByComparingTo("0");
  var revenue=call(get("/analytics/revenue").param("from",LocalDate.now().toString()).param("to",LocalDate.now().toString()),"ADMIN",200);assertThat(revenue.path("total_amount").decimalValue()).isEqualByComparingTo("130000");
 }
 @Test void removedMethodCannotMutatePosOrCheckout()throws Exception{
  UUID b=book();db.update("update bookings set status='PAID' where id=?",b);
  var before=db.queryForMap("select row_to_json(b)::text as row from bookings b where id=?",b);
  for(String method:List.of("BANK_TRANSFER","QR")){
   call(json(post("/pos/sales"),sale(null,2,method)),"STAFF",400);
   call(json(post("/pos/quote"),sale(null,2,method)),"ADMIN",400);
   call(json(post("/checkout/"+b),Map.of("paymentMethod",method)),"STAFF",400);
  }
  assertThat(db.queryForObject("select stock_quantity from products where id=?",Integer.class,product)).isEqualTo(10);
  assertThat(db.queryForObject("select count(*) from invoices where customer_id=?",Integer.class,cid)).isZero();
  assertThat(payments(b)).isZero();
  assertThat(db.queryForMap("select row_to_json(b)::text as row from bookings b where id=?",b)).isEqualTo(before);
 }
 @ParameterizedTest @ValueSource(strings={"CASH","VNPAY"}) void unverifiedOrMismatchedCourtReceiptCannotBeTreatedAsPaid(String method)throws Exception{
  UUID b=book();db.update("update bookings set status='PAID' where id=?",b);
  db.update("update booking_details set booking_date=current_date-1 where booking_id=?",b);
  call(get("/checkout/"+b),"STAFF",400);
  call(json(post("/checkout/"+b),Map.of("paymentMethod",method)),"STAFF",400);
  db.update("insert into payment_transactions(booking_id,payment_method,status,amount,transaction_id,transaction_date,source,confirmed_by_user) values (?,?,'SUCCESS',1,'MISMATCH',current_timestamp,?,?)",b,method,method.equals("CASH")?"MANUAL":"GATEWAY",method.equals("CASH")?UUID.fromString(staffActor.getId()):null);
  call(json(post("/checkout/"+b),Map.of("paymentMethod",method)),"STAFF",400);
  assertThat(state(b)).isEqualTo("PAID");assertThat(payments(b)).isEqualTo(1);
  assertThat(db.queryForObject("select count(*) from invoices where booking_id=?",Integer.class,b)).isZero();
 }
 @Test void enumAndDatabaseRejectRemovedPaymentMethod()throws Exception{
  assertThat(com.bcm.entity.PaymentMethod.values()).extracting(Enum::name).containsExactly("CASH","VNPAY","BANK_TRANSFER");
  for(String table:List.of("invoices","payment_transactions")){
   String constraint=db.queryForObject("select pg_get_constraintdef(oid) from pg_constraint where conname=? and conrelid=?::regclass",String.class,table+"_payment_method_check",table);
   assertThat(constraint).contains("CASH","VNPAY").doesNotContain("BANK_TRANSFER");
   assertThat(db.queryForObject("select count(*) from "+table+" where payment_method not in ('CASH','VNPAY')",Integer.class)).isZero();
  }
  UUID b=book();
  assertThatThrownBy(()->db.update("insert into payment_transactions(booking_id,payment_method,status,amount) values (?,'BANK_TRANSFER','PENDING',100000)",b)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
  assertThatThrownBy(()->db.update("insert into invoices(customer_id,payment_method,status,total_amount) values (?,'BANK_TRANSFER','PAID',100000)",cid)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
  assertThat(payments(b)).isZero();
 }
 @Test void promotionBestOfferIsNotStackedAndLocksAfterAttempt()throws Exception{
  db.update("update courts set base_price=400000 where id=?",court);UUID b=book();
  assertThat(db.queryForObject("select discount_amount from bookings where id=?",BigDecimal.class,b)).isEqualByComparingTo("30000");
  call(json(post("/bookings/"+b+"/promotion/apply"),Map.of("code","NEWMEMBER")),"CUSTOMER",200);
  assertThat(db.queryForObject("select discount_amount from bookings where id=?",BigDecimal.class,b)).isEqualByComparingTo("30000");
  assertThat(pay(b,"VNPAY",201).path("amount").decimalValue()).isEqualByComparingTo("370000");
  call(json(post("/bookings/"+b+"/promotion/apply"),Map.of("code","NEWMEMBER")),"CUSTOMER",409);
 }
 @Test void paidBookingCannotCreateAnotherPayment()throws Exception{UUID b=book();UUID a=UUID.fromString(pay(b,"VNPAY",201).path("paymentAttemptId").asText());assertThat(ipn(fields(a,"00","10000000"))).isEqualTo("00");pay(b,"VNPAY",400);assertThat(payments(b)).isEqualTo(1);}
 @Test void independentRetailSalesUseDifferentKeysAndCreateDistinctInvoices()throws Exception{
  UUID first=UUID.fromString(call(json(post("/pos/sales"),sale(null,2,"CASH")),"STAFF",201).path("id").asText());
  UUID second=UUID.fromString(call(json(post("/pos/sales"),sale(null,2,"CASH")),"STAFF",201).path("id").asText());
  assertThat(second).isNotEqualTo(first);assertThat(db.queryForObject("select stock_quantity from products where id=?",Integer.class,product)).isEqualTo(6);
 }
 @Test void controlledConcurrentBookingHasOneWinner()throws Exception{
  var pool=Executors.newFixedThreadPool(2);var ready=new CountDownLatch(2);var start=new CountDownLatch(1);
  try{var jobs=new ArrayList<Future<Integer>>();for(int i=0;i<2;i++)jobs.add(pool.submit(()->{ready.countDown();assertThat(start.await(10,TimeUnit.SECONDS)).isTrue();return mvc.perform(json(post("/bookings"),bookingBody(LocalDate.now().plusDays(7))).with(as("CUSTOMER"))).andReturn().getResponse().getStatus();}));assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue();start.countDown();assertThat(List.of(jobs.get(0).get(20,TimeUnit.SECONDS),jobs.get(1).get(20,TimeUnit.SECONDS))).containsExactlyInAnyOrder(201,409);assertThat(db.queryForObject("select count(*) from bookings where customer_id=?",Integer.class,cid)).isEqualTo(1);assertThat(db.queryForObject("select count(*) from booking_details where court_id=?",Integer.class,court)).isEqualTo(1);
  }finally{start.countDown();pool.shutdownNow();assertThat(pool.awaitTermination(10,TimeUnit.SECONDS)).isTrue();}
 }
}
