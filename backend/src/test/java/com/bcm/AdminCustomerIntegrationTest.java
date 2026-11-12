package com.bcm;
import com.bcm.dto.request.AdminCustomerRequest;
import com.bcm.service.AdminCustomerService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.crypto.password.PasswordEncoder;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
@SpringBootTest(properties="booking.expiration-enabled=false") @AutoConfigureMockMvc @ActiveProfiles("h2") @Transactional
class AdminCustomerIntegrationTest {
 @jakarta.persistence.PersistenceContext jakarta.persistence.EntityManager entityManager;
 @Autowired com.bcm.repository.UserRepository users; @Autowired com.fasterxml.jackson.databind.ObjectMapper mapper; @Autowired AdminCustomerService service; @Autowired JdbcTemplate jdbc; @Autowired PasswordEncoder encoder; @Autowired MockMvc mvc;
 @org.junit.jupiter.api.BeforeEach void completeH2StaffReferenceForAccountQueries()throws Exception{
  // Staff is deliberately an id-only reference entity. Only H2's generated test
  // table needs the profile columns that already exist in PostgreSQL.
  try(var connection=jdbc.getDataSource().getConnection()){
   if(!"H2".equals(connection.getMetaData().getDatabaseProductName()))return;
  }
  for(String column:java.util.List.of("user_id uuid","position varchar(100)","full_name varchar(255)","phone varchar(20)","updated_at timestamp"))
   jdbc.execute("alter table staffs add column if not exists "+column);
 }
 AdminCustomerRequest request(String email){return new AdminCustomerRequest("Test customer",email,"0912345678","Address","Password123!");}
 @Test void createsSynchronizedAccountAndDisablesWithoutDeletingHistory(){
  String email=UUID.randomUUID()+"@example.com";UUID id=service.save(null,request(email));
  assertThat(service.list(email,0,"created").get("totalElements")).isEqualTo(1L);
  assertThat(service.detail(id).get("id")).isEqualTo(id);
  var row=jdbc.queryForMap("select u.password_hash,u.full_name,c.full_name as customer_name,u.id from customers c join users u on u.id=c.user_id where c.id=?",id);
  assertThat(encoder.matches("Password123!",(String)row.get("password_hash"))).isTrue();
  assertThat(row.get("full_name")).isEqualTo(row.get("customer_name"));
  assertThat(jdbc.queryForObject("select count(*) from user_roles ur join roles r on r.id=ur.role_id where ur.user_id=? and r.role_name='CUSTOMER'",Long.class,row.get("id"))).isEqualTo(1);
  service.save(id,new AdminCustomerRequest("Updated",email,"0987654321","New",null));
  assertThat(jdbc.queryForObject("select full_name from users where id=?",String.class,row.get("id"))).isEqualTo("Updated");
  adminContext(); service.disable(id); service.disable(id);
  assertThat(jdbc.queryForObject("select count(*) from customers where id=? and deleted_at is not null",Long.class,id)).isEqualTo(1);
 }
 @Test void rejectsDuplicateEmailAndMissingPassword(){String email=UUID.randomUUID()+"@example.com";service.save(null,request(email));assertThatThrownBy(()->service.save(null,request(email))).isInstanceOf(com.bcm.exception.DuplicateResourceException.class);assertThatThrownBy(()->service.save(null,new AdminCustomerRequest("Test","other@example.com","0912345678",null,null))).isInstanceOf(com.bcm.exception.BadRequestException.class);}
 @Test @WithMockUser(roles="STAFF") void staffReadsInvoicesButCannotManageCustomersOrStatistics() throws Exception {mvc.perform(get("/admin/customers")).andExpect(status().isForbidden());mvc.perform(get("/analytics/invoices")).andExpect(status().isOk());mvc.perform(get("/analytics/statistics")).andExpect(status().isForbidden());mvc.perform(post("/analytics/invoices")).andExpect(status().isForbidden());mvc.perform(put("/analytics/invoices/"+UUID.randomUUID())).andExpect(status().isForbidden());mvc.perform(delete("/analytics/invoices/"+UUID.randomUUID())).andExpect(status().isForbidden());mvc.perform(post("/analytics/invoices/"+UUID.randomUUID()+"/refund")).andExpect(status().isForbidden());}
 @Test @WithMockUser(roles="CUSTOMER") void customerCannotManageCustomers() throws Exception {mvc.perform(get("/admin/customers")).andExpect(status().isForbidden());}
 @Test @WithMockUser(roles="ADMIN") void validatesCustomerInput() throws Exception {mvc.perform(post("/admin/customers").contentType("application/json").content("{}" )).andExpect(status().isBadRequest());}

 @Test @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="BCM_TEST_DB_URL",matches="jdbc:postgresql:.*")
 void lockedCustomerRemainsVisibleAndKeepsNonemptyTransactionHistory()throws Exception{
  UUID admin=actor("ADMIN"),staff=actor("STAFF");String a=token(admin),st=token(staff);
  String email=UUID.randomUUID()+"@locked-view.test";
  UUID profile=service.save(null,request(email));
  UUID user=jdbc.queryForObject("select user_id from customers where id=?",UUID.class,profile);
  UUID booking=UUID.randomUUID(),invoice=UUID.randomUUID(),payment=UUID.randomUUID();
  jdbc.update("insert into bookings(id,customer_id,status,court_fee,discount_amount,created_at,updated_at) values (?,?,'PAID',100,0,current_timestamp,current_timestamp)",booking,profile);
  jdbc.update("insert into invoices(id,booking_id,customer_id,payment_method,status,court_fee,product_fee,discount_amount,total_amount,created_at,updated_at) values (?,?,?,'CASH','PAID',100,0,0,100,current_timestamp,current_timestamp)",invoice,booking,profile);
  jdbc.update("insert into payment_transactions(id,invoice_id,payment_method,status,source,confirmed_by_user,transaction_date,amount,created_at) values (?,?,'CASH','SUCCESS','MANUAL',?,current_timestamp,100,current_timestamp)",payment,invoice,staff);
  var history=java.util.List.of(jdbc.queryForList("select * from bookings where id=?",booking),jdbc.queryForList("select * from invoices where id=?",invoice),jdbc.queryForList("select * from payment_transactions where id=?",payment));
  mvc.perform(delete("/admin/customers/"+profile).header("Authorization","Bearer "+a)).andExpect(status().isOk());
  mvc.perform(delete("/admin/customers/"+profile).header("Authorization","Bearer "+a)).andExpect(status().isOk());
  mvc.perform(get("/admin/customers").param("search",email).header("Authorization","Bearer "+a))
   .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(1))
   .andExpect(jsonPath("$.data.customers[0].deletedAt").isNotEmpty());
  mvc.perform(get("/admin/customers/"+profile).header("Authorization","Bearer "+a))
   .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(profile.toString()))
   .andExpect(jsonPath("$.data.userId").value(user.toString())).andExpect(jsonPath("$.data.email").value(email))
   .andExpect(jsonPath("$.data.deletedAt").isNotEmpty()).andExpect(jsonPath("$.data.bookings[0].id").value(booking.toString()))
   .andExpect(jsonPath("$.data.invoices[0].id").value(invoice.toString()))
   .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("password"))));
  mvc.perform(get("/analytics/invoices/"+invoice).header("Authorization","Bearer "+st))
   .andExpect(status().isOk()).andExpect(jsonPath("$.data.invoice.id").value(invoice.toString()))
   .andExpect(jsonPath("$.data.receipts[0].amount").value(100));
  assertThat(history).isEqualTo(java.util.List.of(jdbc.queryForList("select * from bookings where id=?",booking),jdbc.queryForList("select * from invoices where id=?",invoice),jdbc.queryForList("select * from payment_transactions where id=?",payment)));
  assertThat(jdbc.queryForObject("select count(*) from users u join customers c on c.user_id=u.id where u.id=?",Long.class,user)).isEqualTo(1L);
 }
 @Test @WithMockUser(roles="ADMIN") void invalidCreateNeverPersistsAnAccount()throws Exception{
  long before=jdbc.queryForObject("select count(*) from users",Long.class);
  for(var invalid:java.util.List.of(
   new AdminCustomerRequest("Test","invalid-email","0912345678",null,"Password123!"),
   new AdminCustomerRequest("Test","invalid@test.test","bad-phone",null,"Password123!"),
   new AdminCustomerRequest("Test","invalid@test.test","0912345678",null,null),
   new AdminCustomerRequest("Test","invalid@test.test","0912345678",null,"short"),
   new AdminCustomerRequest("Test","invalid@test.test","000 000 0000",null,"Password123!"),
   new AdminCustomerRequest("Test","invalid@test.test","0912345678",null,"\u00e9".repeat(37)))){
   mvc.perform(post("/admin/customers").contentType("application/json").content(mapper.writeValueAsString(invalid))).andExpect(status().isBadRequest());
  }
  assertThat(jdbc.queryForObject("select count(*) from users",Long.class)).isEqualTo(before);
 }
 @Test void customerSearchSortAndPaginationIncludeLockedAccounts(){
  String batch="Page-"+UUID.randomUUID();
  for(int i=0;i<21;i++)service.save(null,new AdminCustomerRequest(batch+String.format("-%02d",i),batch+"-"+i+"@page.test","0912345678",null,"Password123!"));
  var first=service.list(batch,0,"name");var second=service.list(batch,1,"name");
  assertThat(first.get("totalElements")).isEqualTo(21L);assertThat(first.get("totalPages")).isEqualTo(2L);
  var rows=(java.util.List<java.util.Map<String,Object>>)first.get("customers");
  assertThat(rows).hasSize(20);assertThat(rows.get(0).get("fullName")).isEqualTo(batch+"-00");
  assertThat((java.util.List<?>)second.get("customers")).hasSize(1);
  adminContext();service.disable((UUID)rows.get(0).get("id"));
  assertThat(service.list(batch,0,"name").get("totalElements")).isEqualTo(21L);
  assertThat(service.list(batch+"-0@page.test",0,"created").get("totalElements")).isEqualTo(1L);
 }

 UUID actor(String role){UUID id=UUID.randomUUID();jdbc.update("insert into users(id,email,password_hash,full_name,phone,created_at,updated_at,token_version) values (?,?,?,?,?,current_timestamp,current_timestamp,0)",id,id+"@user-management.test",encoder.encode("Password123!"),"Transaction fixture","0912345678");jdbc.update("insert into user_roles(user_id,role_id) select ?,id from roles where role_name=?",id,role);return id;}
 void adminContext(){UUID id=actor("ADMIN");var principal=com.bcm.security.UserPrincipal.create(users.findById(id).orElseThrow());org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(principal,null,principal.getAuthorities()));}
 String token(UUID id)throws Exception{return mapper.readTree(mvc.perform(post("/auth/login").contentType("application/json").content(mapper.writeValueAsString(java.util.Map.of("email",id+"@user-management.test","password","Password123!")))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data").path("accessToken").asText();}
 @Test void createViewLockUnlockUsesRealJwtAndPreservesTransactions()throws Exception{
  UUID admin=actor("ADMIN"),staff=actor("STAFF");String a=token(admin),st=token(staff);
  var history=java.util.List.of(jdbc.queryForList("select * from bookings order by id"),jdbc.queryForList("select * from invoices order by id"),jdbc.queryForList("select * from payment_transactions order by id"));
  String email=UUID.randomUUID()+"@new-customer.test";
  String body=mapper.writeValueAsString(java.util.Map.of("fullName","Customer fixture","email",email,"phone","0987654321","address","Test address","password","Password123!","roles",java.util.List.of("ADMIN")));
  mvc.perform(post("/admin/customers").header("Authorization","Bearer "+st).contentType("application/json").content(body)).andExpect(status().isForbidden());
  UUID customerId=UUID.fromString(mapper.readTree(mvc.perform(post("/admin/customers").header("Authorization","Bearer "+a).contentType("application/json").content(body)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data").asText());
  UUID uid=jdbc.queryForObject("select user_id from customers where id=?",UUID.class,customerId);
  assertThat(users.findById(uid).orElseThrow().getRoles()).extracting(com.bcm.entity.Role::getRoleName).containsExactly("CUSTOMER");
  mvc.perform(post("/admin/customers").header("Authorization","Bearer "+a).contentType("application/json").content(body)).andExpect(status().isConflict());
  assertThat(service.list("Customer fixture",0,"name").get("totalElements")).isEqualTo(1L);
  assertThat(service.list("0987654321",1,"created").get("customers")).isEqualTo(java.util.List.of());
  assertThatThrownBy(()->service.save(null,new AdminCustomerRequest("Guest",UUID.randomUUID()+"@guest.test","000 000 0000",null,"Password123!"))).isInstanceOf(com.bcm.exception.BadRequestException.class);
  entityManager.clear(); // Validate freshly persisted PostgreSQL timestamp precision, not cached entities.
  String passwordHash=jdbc.queryForObject("select password_hash from users where id=?",String.class,uid);
  var login=mapper.readTree(mvc.perform(post("/auth/login").contentType("application/json").content(mapper.writeValueAsString(java.util.Map.of("email",email,"password","Password123!")))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
  mvc.perform(get("/users/me").header("Authorization","Bearer "+login.path("accessToken").asText())).andExpect(status().isOk());
  mvc.perform(get("/admin/accounts").param("search",email).header("Authorization","Bearer "+a)).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("password_hash"))));
  mvc.perform(get("/admin/accounts/"+uid).header("Authorization","Bearer "+a)).andExpect(status().isOk());
  mvc.perform(get("/admin/accounts").header("Authorization","Bearer "+st)).andExpect(status().isForbidden());
  mvc.perform(delete("/admin/accounts/"+uid).header("Authorization","Bearer "+st)).andExpect(status().isForbidden());
  mvc.perform(delete("/admin/accounts/"+staff).header("Authorization","Bearer "+a)).andExpect(status().isBadRequest());
  mvc.perform(delete("/admin/accounts/"+admin).header("Authorization","Bearer "+a)).andExpect(status().isBadRequest());
  UUID guest=jdbc.queryForObject("select user_id from customers where phone='0000000000'",UUID.class);
  mvc.perform(delete("/admin/accounts/"+guest).header("Authorization","Bearer "+a)).andExpect(status().isBadRequest());
  mvc.perform(post("/admin/accounts/"+uid+"/unlock").header("Authorization","Bearer "+st)).andExpect(status().isForbidden());
  mvc.perform(post("/admin/accounts/"+uid+"/unlock").header("Authorization","Bearer "+login.path("accessToken").asText())).andExpect(status().isForbidden());
  for(UUID protectedId:java.util.List.of(admin,staff,guest))mvc.perform(post("/admin/accounts/"+protectedId+"/unlock").header("Authorization","Bearer "+a)).andExpect(status().isBadRequest());
  mvc.perform(get("/pos/products").header("Authorization","Bearer "+st)).andExpect(status().isOk());
  mvc.perform(get("/checkout/active-bookings").header("Authorization","Bearer "+st)).andExpect(status().isOk());
  mvc.perform(get("/analytics/invoices").header("Authorization","Bearer "+st)).andExpect(status().isOk());
  mvc.perform(get("/analytics/invoices/"+UUID.randomUUID()).header("Authorization","Bearer "+st)).andExpect(status().isNotFound());
  mvc.perform(get("/analytics/statistics").header("Authorization","Bearer "+st)).andExpect(status().isForbidden());
  mvc.perform(get("/analytics/revenue-breakdown").header("Authorization","Bearer "+st)).andExpect(status().isForbidden());
  mvc.perform(delete("/admin/accounts/"+uid).header("Authorization","Bearer "+a)).andExpect(status().isOk());
  mvc.perform(delete("/admin/accounts/"+uid).header("Authorization","Bearer "+a)).andExpect(status().isOk());
  mvc.perform(get("/admin/accounts/"+uid).header("Authorization","Bearer "+a)).andExpect(status().isOk());
  mvc.perform(get("/admin/accounts").header("Authorization","Bearer "+login.path("accessToken").asText())).andExpect(status().isUnauthorized());
  mvc.perform(post("/auth/refresh").contentType("application/json").content(mapper.writeValueAsString(java.util.Map.of("refreshToken",login.path("refreshToken").asText())))).andExpect(status().is4xxClientError());
  mvc.perform(post("/auth/login").contentType("application/json").content(mapper.writeValueAsString(java.util.Map.of("email",email,"password","Password123!")))).andExpect(status().isUnauthorized());
  assertThat(jdbc.queryForObject("select deleted_at is not null from customers where id=?",Boolean.class,customerId)).isTrue();
  var lockedVersion=jdbc.queryForObject("select token_version from users where id=?",Long.class,uid);
  mvc.perform(delete("/admin/accounts/"+uid).header("Authorization","Bearer "+a)).andExpect(status().isOk());
  assertThat(jdbc.queryForObject("select token_version from users where id=?",Long.class,uid)).isEqualTo(lockedVersion);
  mvc.perform(post("/admin/accounts/"+uid+"/unlock").header("Authorization","Bearer "+a)).andExpect(status().isOk());
  var unlockedVersion=jdbc.queryForObject("select token_version from users where id=?",Long.class,uid);
  assertThat(lockedVersion).isEqualTo(1L);
  assertThat(unlockedVersion).isEqualTo(lockedVersion+1);
  mvc.perform(post("/admin/accounts/"+uid+"/unlock").header("Authorization","Bearer "+a)).andExpect(status().isOk());
  assertThat(jdbc.queryForObject("select token_version from users where id=?",Long.class,uid)).isEqualTo(unlockedVersion);
  assertThat(jdbc.queryForObject("select count(*) from users u join customers c on c.user_id=u.id where u.id=? and u.deleted_at is null and c.deleted_at is null",Long.class,uid)).isEqualTo(1L);
  entityManager.clear();
  mvc.perform(get("/users/me").header("Authorization","Bearer "+login.path("accessToken").asText())).andExpect(status().isUnauthorized());
  mvc.perform(post("/auth/refresh").contentType("application/json").content(mapper.writeValueAsString(java.util.Map.of("refreshToken",login.path("refreshToken").asText())))).andExpect(status().isUnauthorized());
  var fresh=mapper.readTree(mvc.perform(post("/auth/login").contentType("application/json").content(mapper.writeValueAsString(java.util.Map.of("email",email,"password","Password123!")))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
  mvc.perform(get("/users/me").header("Authorization","Bearer "+fresh.path("accessToken").asText())).andExpect(status().isOk());
  mvc.perform(post("/auth/refresh").contentType("application/json").content(mapper.writeValueAsString(java.util.Map.of("refreshToken",fresh.path("refreshToken").asText())))).andExpect(status().isOk());
  assertThat(jdbc.queryForObject("select password_hash from users where id=?",String.class,uid)).isEqualTo(passwordHash);
  assertThat(users.findById(uid).orElseThrow().getRoles()).extracting(com.bcm.entity.Role::getRoleName).containsExactly("CUSTOMER");
  assertThat(history).isEqualTo(java.util.List.of(jdbc.queryForList("select * from bookings order by id"),jdbc.queryForList("select * from invoices order by id"),jdbc.queryForList("select * from payment_transactions order by id")));
 }
 @Test void lockUnlockRejectsElevatedCustomerAndSynchronizesBothSoftDeleteStates()throws Exception{
  UUID admin=actor("ADMIN");String token=token(admin);
  UUID profile=service.save(null,request(UUID.randomUUID()+"@guard.test"));
  UUID id=jdbc.queryForObject("select user_id from customers where id=?",UUID.class,profile);
  for(String extraRole:java.util.List.of("ADMIN","STAFF")){
   jdbc.update("insert into user_roles(user_id,role_id) select ?,id from roles where role_name=?",id,extraRole);
   entityManager.clear();
   mvc.perform(delete("/admin/accounts/"+id).header("Authorization","Bearer "+token)).andExpect(status().isBadRequest());
   mvc.perform(post("/admin/accounts/"+id+"/unlock").header("Authorization","Bearer "+token)).andExpect(status().isBadRequest());
   mvc.perform(get("/admin/accounts/"+id).header("Authorization","Bearer "+token)).andExpect(status().isOk())
    .andExpect(jsonPath("$.data.canLock").value(false)).andExpect(jsonPath("$.data.canUnlock").value(false));
   assertThat(jdbc.queryForObject("select token_version from users where id=?",Long.class,id)).isZero();
   jdbc.update("delete from user_roles where user_id=? and role_id=(select id from roles where role_name=?)",id,extraRole);
  }
  entityManager.clear();
  mvc.perform(post("/admin/accounts/"+actor("ADMIN")+"/unlock").header("Authorization","Bearer "+token)).andExpect(status().isBadRequest());
  jdbc.update("update customers set deleted_at=current_timestamp where id=?",profile);
  entityManager.clear();
  mvc.perform(post("/admin/accounts/"+id+"/unlock").header("Authorization","Bearer "+token)).andExpect(status().isOk());
  assertThat(jdbc.queryForObject("select count(*) from users u join customers c on c.user_id=u.id where u.id=? and u.deleted_at is null and c.deleted_at is null",Long.class,id)).isEqualTo(1L);
  jdbc.update("update users set deleted_at=current_timestamp where id=?",id);
  entityManager.clear();
  mvc.perform(delete("/admin/accounts/"+id).header("Authorization","Bearer "+token)).andExpect(status().isOk());
  assertThat(jdbc.queryForObject("select count(*) from users u join customers c on c.user_id=u.id where u.id=? and u.deleted_at is not null and c.deleted_at is not null",Long.class,id)).isEqualTo(1L);
 }
 @Test void ordinaryReadsAndRefreshDoNotRevokeCurrentTokens()throws Exception{
  UUID staff=actor("STAFF");String access=token(staff);
  var before=jdbc.queryForObject("select updated_at from users where id=?",java.time.LocalDateTime.class,staff);
  for(String path:java.util.List.of("/users/me","/pos/products","/checkout/active-bookings","/analytics/invoices","/users/me"))mvc.perform(get(path).header("Authorization","Bearer "+access)).andExpect(status().isOk());
  assertThat(jdbc.queryForObject("select updated_at from users where id=?",java.time.LocalDateTime.class,staff)).isEqualTo(before);
  UUID profile=service.save(null,request(UUID.randomUUID()+"@session-stability.test"));
  var customer=users.findById(jdbc.queryForObject("select user_id from customers where id=?",UUID.class,profile)).orElseThrow();
  var session=mapper.readTree(mvc.perform(post("/auth/login").contentType("application/json").content(mapper.writeValueAsString(java.util.Map.of("email",customer.getEmail(),"password","Password123!")))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
  for(int attempt=0;attempt<2;attempt++){
   mvc.perform(get("/bookings/customer-profile").header("Authorization","Bearer "+session.path("accessToken").asText())).andExpect(status().isOk());
   mvc.perform(post("/auth/refresh").contentType("application/json").content(mapper.writeValueAsString(java.util.Map.of("refreshToken",session.path("refreshToken").asText())))).andExpect(status().isOk());
  }
  mvc.perform(get("/users/me").header("Authorization","Bearer "+session.path("accessToken").asText())).andExpect(status().isOk());
 }
 @Test void ordinaryProfileUpdateMustNotRevokeExistingSession()throws Exception{
  UUID admin=actor("ADMIN");String adminToken=token(admin);
  String email=UUID.randomUUID()+"@profile-session.test";
  UUID profile=service.save(null,request(email));
  UUID id=jdbc.queryForObject("select user_id from customers where id=?",UUID.class,profile);
  entityManager.clear();
  long versionBefore=jdbc.queryForObject("select token_version from users where id=?",Long.class,id);
  var session=mapper.readTree(mvc.perform(post("/auth/login").contentType("application/json").content(mapper.writeValueAsString(java.util.Map.of("email",email,"password","Password123!")))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
  mvc.perform(get("/users/me").header("Authorization","Bearer "+session.path("accessToken").asText())).andExpect(status().isOk());
  mvc.perform(put("/admin/accounts/"+id).header("Authorization","Bearer "+adminToken).contentType("application/json").content(mapper.writeValueAsString(java.util.Map.of("fullName","Updated display name","email",email,"phone","0912345678","address","Updated address")))).andExpect(status().isOk());
  entityManager.clear();
  int accessStatus=mvc.perform(get("/users/me").header("Authorization","Bearer "+session.path("accessToken").asText())).andReturn().getResponse().getStatus();
  int refreshStatus=mvc.perform(post("/auth/refresh").contentType("application/json").content(mapper.writeValueAsString(java.util.Map.of("refreshToken",session.path("refreshToken").asText())))).andReturn().getResponse().getStatus();
  mvc.perform(post("/auth/login").contentType("application/json").content(mapper.writeValueAsString(java.util.Map.of("email",email,"password","Password123!")))).andExpect(status().isOk());
  assertThat(jdbc.queryForObject("select token_version from users where id=?",Long.class,id)).isEqualTo(versionBefore);
  // The existing customer-profile update route must preserve the same session too.
  mvc.perform(put("/admin/customers/"+profile).header("Authorization","Bearer "+adminToken).contentType("application/json").content(mapper.writeValueAsString(java.util.Map.of("fullName","Another display name","email",email,"phone","0912345678","address","Another address")))).andExpect(status().isOk());
  entityManager.clear();
  assertThat(jdbc.queryForObject("select token_version from users where id=?",Long.class,id)).isEqualTo(versionBefore);
  mvc.perform(get("/users/me").header("Authorization","Bearer "+session.path("accessToken").asText())).andExpect(status().isOk());
  mvc.perform(post("/auth/refresh").contentType("application/json").content(mapper.writeValueAsString(java.util.Map.of("refreshToken",session.path("refreshToken").asText())))).andExpect(status().isOk());
  org.assertj.core.api.SoftAssertions.assertSoftly(check->{
   check.assertThat(accessStatus).as("Existing access token after changing only name/address").isEqualTo(200);
   check.assertThat(refreshStatus).as("Existing refresh token after changing only name/address").isEqualTo(200);
  });
 }
}
