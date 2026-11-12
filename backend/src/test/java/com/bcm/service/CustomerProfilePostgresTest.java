package com.bcm.service;

import com.bcm.dto.request.RegisterRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"booking.expiration-enabled=false","booking.auto-checkout.enabled=false"})
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named="BCM_TEST_DB_URL",matches="jdbc:postgresql:.*")
class CustomerProfilePostgresTest {
 @DynamicPropertySource static void database(DynamicPropertyRegistry r){
  r.add("spring.datasource.url",()->System.getenv("BCM_TEST_DB_URL"));
  r.add("spring.datasource.username",()->System.getenv("BCM_TEST_DB_USERNAME"));
  r.add("spring.datasource.password",()->System.getenv("BCM_TEST_DB_PASSWORD"));
 }
 @Autowired AuthService auth; @Autowired JdbcTemplate db;
 @Autowired org.springframework.test.web.servlet.MockMvc mvc; @Autowired ObjectMapper mapper;
 @Autowired com.bcm.repository.UserRepository users;
 @Autowired com.bcm.util.JwtUtil jwt;
 @Autowired com.bcm.security.WalkInGuestIdentity guest;
 @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
 UUID id; String token;
 @BeforeEach void setup(){var session=auth.register(RegisterRequest.builder().email(UUID.randomUUID()+"@profile.test").password("Profile123!").fullName("Original").phone("0912345678").address("Old address").build());id=UUID.fromString(session.getUser().getId());token=session.getAccessToken();}
 @AfterEach void cleanup(){db.update("delete from bookings where customer_id in (select id from customers where user_id=?)",id);db.update("delete from users where id=?",id);}
 org.springframework.test.web.servlet.ResultActions update(Map<String,Object> body)throws Exception{return mvc.perform(put("/users/me").header("Authorization","Bearer "+token).contentType("application/json").content(mapper.writeValueAsString(body)));}
 Map<String,Object> body(){return new HashMap<>(Map.of("fullName"," Updated customer ","phone","0987654321","address"," New address "));}
 @Test void updatesBothProfilesWithoutChangingIdentitySessionOrBookingSnapshot()throws Exception{
  UUID customer=db.queryForObject("select id from customers where user_id=?",UUID.class,id);
  db.update("insert into bookings(customer_id,status,court_fee,contact_full_name,contact_phone) values (?,'COMPLETED',100,'Booking contact','0901111111')",customer);
  var before=db.queryForMap("select email,password_hash,token_version from users where id=?",id);
  var request=body();request.put("id",UUID.randomUUID().toString());request.put("email","attacker@profile.test");request.put("roles",List.of("ADMIN"));request.put("password","Changed123!");
  update(request).andExpect(status().isOk()).andExpect(jsonPath("$.data.fullName").value("Updated customer")).andExpect(jsonPath("$.data.canEditProfile").value(true));
  assertThat(db.queryForMap("select email,password_hash,token_version from users where id=?",id)).isEqualTo(before);
  assertThat(db.queryForObject("select count(*) from users u join customers c on c.user_id=u.id where u.id=? and u.full_name=c.full_name and u.phone=c.phone and u.address=c.address and c.address='New address'",Integer.class,id)).isEqualTo(1);
  assertThat(db.queryForObject("select contact_full_name from bookings where customer_id=?",String.class,customer)).isEqualTo("Booking contact");
  mvc.perform(get("/users/me").header("Authorization","Bearer "+token)).andExpect(status().isOk()).andExpect(jsonPath("$.data.phone").value("0987654321"));
 }
 @Test void validatesInputAndAllowsClearingAddress()throws Exception{
  for(var invalid:List.of(Map.of("fullName"," ","phone","0912345678"),Map.of("fullName","Name","phone","wrong"),Map.of("fullName","Name","phone","000 000 0000"),Map.of("fullName","Name","phone","0912345678","address","x".repeat(2001))))update(new HashMap<>(invalid)).andExpect(status().isBadRequest());
  var request=body();request.put("address"," ");update(request).andExpect(status().isOk());
  assertThat(db.queryForObject("select address from customers where user_id=?",String.class,id)).isNull();
 }
 @Test void authenticationAndActiveCustomerAreRequired()throws Exception{
  mvc.perform(put("/users/me").contentType("application/json").content(mapper.writeValueAsString(body()))).andExpect(status().isUnauthorized());
  db.update("update customers set deleted_at=current_timestamp where user_id=?",id);update(body()).andExpect(status().isForbidden());
  mvc.perform(get("/users/me").header("Authorization","Bearer "+token)).andExpect(status().isOk()).andExpect(jsonPath("$.data.canEditProfile").value(false));
  db.update("update users set deleted_at=current_timestamp where id=?",id);update(body()).andExpect(status().isUnauthorized());
 }
 @Test void guestAndNonCustomerCannotEdit()throws Exception{
  String ownToken=token;
  token=jwt.generateAccessToken(com.bcm.security.UserPrincipal.create(users.findById(guest.userId()).orElseThrow()));
  update(body()).andExpect(status().isForbidden());
  token=ownToken;db.update("delete from user_roles where user_id=?",id);
  db.update("insert into user_roles(user_id,role_id) select ?,id from roles where role_name='STAFF'",id);
  update(body()).andExpect(status().isForbidden());
  assertThat(db.queryForObject("select full_name from users where id=?",String.class,id)).isEqualTo("Original");
 }
 @Test void lockCommittedWhileProfileUpdateWaitsPreventsAnyProfileWrite()throws Exception{
  var pool=java.util.concurrent.Executors.newSingleThreadExecutor();
  var pending=new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<Integer>>();
  try{
   new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(tx->{
    db.queryForObject("select id from users where id=? for update",UUID.class,id);
    pending.set(pool.submit(()->update(body()).andReturn().getResponse().getStatus()));
    long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(8);
    boolean waiting=false;
    while(System.nanoTime()<deadline){
     db.execute("select pg_stat_clear_snapshot()");
     if(db.queryForObject("select count(*) from pg_stat_activity where datname=current_database() and pid<>pg_backend_pid() and wait_event_type='Lock' and lower(query) like '%users%'",Integer.class)>0){waiting=true;break;}
     try{Thread.sleep(25);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
    }
    assertThat(waiting).as("Profile request waits for user lock").isTrue();
    db.update("update users set deleted_at=current_timestamp,token_version=token_version+1 where id=?",id);
    db.update("update customers set deleted_at=current_timestamp where user_id=?",id);
   });
   assertThat(pending.get().get(10,java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(403);
   assertThat(db.queryForObject("select full_name from customers where user_id=?",String.class,id)).isEqualTo("Original");
  }finally{pool.shutdownNow();}
 }
}
