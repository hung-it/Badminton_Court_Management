package com.bcm.service;

import com.bcm.dto.request.*;
import com.bcm.entity.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

/** Regression for the user-management business review. */
@SpringBootTest(properties={"booking.expiration-enabled=false","booking.auto-checkout.enabled=false"})
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named="BCM_TEST_DB_URL",matches="jdbc:postgresql:.*")
class UserManagementBusinessPostgresTest {
 @DynamicPropertySource static void db(DynamicPropertyRegistry r) {
  r.add("spring.datasource.url",()->System.getenv("BCM_TEST_DB_URL"));
  r.add("spring.datasource.username",()->System.getenv("BCM_TEST_DB_USERNAME"));
  r.add("spring.datasource.password",()->System.getenv("BCM_TEST_DB_PASSWORD"));
 }
 @Autowired JdbcTemplate jdbc;
 @Autowired AuthService auth;
 @Autowired AdminCustomerService customers;
 @Autowired AdminAccountService accounts;
 @Autowired PlatformTransactionManager transactions;
 @Autowired EntityManager em;
 @Autowired PosService pos;
 @Autowired org.springframework.test.web.servlet.MockMvc mvc;

 @Test void registrationAfterWalkInPurchaseAllowsExplicitCustomerSelection() throws Exception {
  String phone="09"+String.format("%08d",new java.security.SecureRandom().nextInt(100000000));
  UUID category=UUID.randomUUID(),product=UUID.randomUUID();
  var actor=com.bcm.TestActors.admin(jdbc);com.bcm.TestActors.authenticate(actor);
  jdbc.update("insert into categories(id,category_name) values (?,?)",category,"Review "+category);
  jdbc.update("insert into products(id,category_id,name,type,unit,price,stock_quantity) values (?,?,'Review service','SERVICE','item',10000,0)",product,category);
  UUID user=null;
  try {
   pos.sell(sale(phone,product));
   user=UUID.fromString(auth.register(RegisterRequest.builder().email(UUID.randomUUID()+"@review.invalid").password("Password123").fullName("New member").phone(phone).build()).getUser().getId());
   assertThat(jdbc.queryForObject("select count(*) from customers where phone=? and deleted_at is null",Integer.class,phone)).isEqualTo(2);
   mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/pos/customers").param("phone",phone)
    .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user(actor)))
    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.length()").value(2));
   com.bcm.TestActors.authenticate(actor);
   assertThatThrownBy(()->pos.sell(sale(phone,product))).isInstanceOf(com.bcm.exception.DuplicateResourceException.class).hasMessageContaining("nhiều hồ sơ");

   var selected=sale(phone,product); selected.setCustomerId(jdbc.queryForObject("select id from customers where user_id=?",UUID.class,user));
   var invoice=pos.sell(selected);
   assertThat(invoice.getCustomerId()).isEqualTo(selected.getCustomerId());
   assertThat(pos.sell(selected).getId()).isEqualTo(invoice.getId());
   assertThat(jdbc.queryForObject("select count(*) from invoices i join customers c on c.id=i.customer_id where c.phone=?",Integer.class,phone)).isEqualTo(2);
   var invalid=sale(phone,product);invalid.setCustomerId(UUID.randomUUID());
   assertThatThrownBy(()->pos.sell(invalid)).isInstanceOf(com.bcm.exception.DuplicateResourceException.class);
   accounts.disable(user);
   assertThat(pos.buyers(phone)).hasSize(1);
   assertThatThrownBy(()->pos.sell(saleWithCustomer(phone,product,selected.getCustomerId()))).isInstanceOf(com.bcm.exception.DuplicateResourceException.class);
  } finally {
   jdbc.update("delete from payment_transactions where invoice_id in (select i.id from invoices i join customers c on c.id=i.customer_id where c.phone=?)",phone);
   jdbc.update("delete from invoices where customer_id in (select id from customers where phone=?)",phone);
   jdbc.update("delete from customers where phone=?",phone);
   if(user!=null)jdbc.update("delete from users where id=?",user);
   jdbc.update("delete from products where id=?",product);jdbc.update("delete from categories where id=?",category);
   org.springframework.security.core.context.SecurityContextHolder.clearContext();
  }
 }
 private PosSaleRequest sale(String phone,UUID product) {
  var r=new PosSaleRequest(null,null,null,"CASH",java.math.BigDecimal.ZERO,java.math.BigDecimal.ZERO,List.of(new PosSaleItemRequest(product,1)));
  r.setCustomerPhone(phone);r.setIdempotencyKey(UUID.randomUUID());r.setPaymentConfirmed(true);r.setExpectedTotal(new java.math.BigDecimal("10000"));return r;
 }
 private PosSaleRequest saleWithCustomer(String phone,UUID product,UUID customer) {
  var r=sale(phone,product);r.setCustomerId(customer);return r;
 }

 @Test void staleCustomerEditPreservesCommittedAccountLock() throws Exception {
  String email=UUID.randomUUID()+"@review.invalid";
  var session=auth.register(RegisterRequest.builder().email(email).password("Password123").fullName("Review").phone("0987123456").build());
  UUID user=UUID.fromString(session.getUser().getId());
  UUID customer=jdbc.queryForObject("select id from customers where user_id=?",UUID.class,user);
  var actor=com.bcm.TestActors.admin(jdbc);
  ExecutorService pool=Executors.newSingleThreadExecutor();
  try {
   new TransactionTemplate(transactions).executeWithoutResult(tx->{
    // Interleaving point: these are the same managed objects loaded at the start of save().
    em.find(Customer.class,customer);em.find(User.class,user).getRoles().size();
    try {
     pool.submit(()->{
      com.bcm.TestActors.authenticate(actor);
      try {accounts.disable(user);} finally {org.springframework.security.core.context.SecurityContextHolder.clearContext();}
     }).get(10,TimeUnit.SECONDS);
    } catch(Exception ex){throw new RuntimeException(ex);}
    assertThat(jdbc.queryForObject("select deleted_at is not null from users where id=?",Boolean.class,user)).isTrue();
    customers.save(customer,new AdminCustomerRequest("Changed",email,"0987123456",null,null));
   });
   assertThat(jdbc.queryForObject("select deleted_at is not null from users where id=?",Boolean.class,user)).isTrue();
   assertThat(jdbc.queryForObject("select deleted_at is not null from customers where id=?",Boolean.class,customer)).isTrue();
   assertThat(jdbc.queryForObject("select token_version from users where id=?",Long.class,user)).isEqualTo(1L);
   assertThatThrownBy(()->auth.login(new LoginRequest(email,"Password123"))).isInstanceOf(org.springframework.security.core.AuthenticationException.class);
   com.bcm.TestActors.authenticate(actor);accounts.unlock(user);
   assertThat(auth.login(new LoginRequest(email,"Password123")).getUser().getId()).isEqualTo(user.toString());
   assertThatThrownBy(()->auth.refreshToken(new RefreshTokenRequest(session.getRefreshToken()))).isInstanceOf(org.springframework.security.authentication.BadCredentialsException.class);
  } finally {pool.shutdownNow();jdbc.update("delete from users where id=?",user);org.springframework.security.core.context.SecurityContextHolder.clearContext();}
 }

 @Test void profileSaveFinishesBeforeWaitingLockAndCannotPreventLock() throws Exception {
  String email=UUID.randomUUID()+"@review.invalid";
  UUID user=UUID.fromString(auth.register(RegisterRequest.builder().email(email).password("Password123").fullName("Review").phone("0987123456").build()).getUser().getId());
  UUID customer=jdbc.queryForObject("select id from customers where user_id=?",UUID.class,user);
  var actor=com.bcm.TestActors.admin(jdbc);var pool=Executors.newSingleThreadExecutor();var started=new CountDownLatch(1);
  var future=new java.util.concurrent.atomic.AtomicReference<Future<?>>();
  try {
   new TransactionTemplate(transactions).executeWithoutResult(tx->{
    customers.save(customer,new AdminCustomerRequest("Changed",email,"0987123456",null,null));
    future.set(pool.submit(()->{com.bcm.TestActors.authenticate(actor);started.countDown();try{accounts.disable(user);}finally{org.springframework.security.core.context.SecurityContextHolder.clearContext();}}));
    try {assertThat(started.await(3,TimeUnit.SECONDS)).isTrue();assertThatThrownBy(()->future.get().get(200,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);}
    catch(InterruptedException ex){Thread.currentThread().interrupt();throw new RuntimeException(ex);}
   });
   future.get().get(5,TimeUnit.SECONDS);
   assertThat(jdbc.queryForObject("select deleted_at is not null from users where id=?",Boolean.class,user)).isTrue();
   assertThat(jdbc.queryForObject("select full_name from customers where id=?",String.class,customer)).isEqualTo("Changed");
  } finally {pool.shutdownNow();jdbc.update("delete from users where id=?",user);}
 }
}
