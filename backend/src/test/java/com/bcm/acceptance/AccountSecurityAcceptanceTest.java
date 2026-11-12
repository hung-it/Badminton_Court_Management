package com.bcm.acceptance;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
@SpringBootTest(properties={"booking.expiration-enabled=false","spring.jpa.show-sql=false"}) @AutoConfigureMockMvc
@org.junit.jupiter.api.parallel.Execution(org.junit.jupiter.api.parallel.ExecutionMode.SAME_THREAD)
class AccountSecurityAcceptanceTest {
 @DynamicPropertySource static void database(DynamicPropertyRegistry r){String url=System.getenv("BCM_ACCEPTANCE_DB_URL");if(!java.util.List.of("jdbc:postgresql://127.0.0.1:5433/bcm_acceptance_test","jdbc:postgresql://127.0.0.1:55438/bcm_acceptance_test").contains(url))throw new IllegalStateException("Acceptance DB only");r.add("spring.datasource.url",()->url);r.add("spring.datasource.username",()->"bcm_acceptance_owner");r.add("spring.datasource.password",()->"acceptance-local-only");}
 @Autowired MockMvc mvc; @Autowired JdbcTemplate db; @Autowired ObjectMapper mapper; @Autowired PasswordEncoder encoder;
 List<UUID> ids;UUID admin,staff,customer,multi,customerProfile,staffProfile;String batch;
 UUID account(String...roles){UUID id=UUID.randomUUID();ids.add(id);db.update("insert into users(id,email,password_hash,full_name,phone,address) values (?,?,?,?,?,?)",id,id+"@accounts-acceptance.invalid",encoder.encode("Acceptance123!"),batch,"0912345678","Acceptance address");for(String role:roles)db.update("insert into user_roles(user_id,role_id) select ?,id from roles where role_name=?",id,role);return id;}
 @BeforeEach void setup(){ids=new ArrayList<>();batch="AccountBatch-"+UUID.randomUUID();admin=account("ADMIN");staff=account("STAFF");customer=account("CUSTOMER");multi=account("ADMIN","STAFF");customerProfile=UUID.randomUUID();staffProfile=UUID.randomUUID();db.update("insert into customers(id,user_id,full_name,phone) values (?,?,?,'0912345678')",customerProfile,customer,batch);db.update("insert into staffs(id,user_id,full_name,phone,position) values (?,?,?,'0912345678','Reception')",staffProfile,staff,batch);}
 @AfterEach void cleanup(){for(UUID id:ids){db.update("delete from customers where user_id=?",id);db.update("delete from staffs where user_id=?",id);db.update("delete from user_roles where user_id=?",id);db.update("delete from users where id=?",id);}}
 JsonNode request(MockHttpServletRequestBuilder req,String token,int expected)throws Exception{if(token!=null)req.header("Authorization","Bearer "+token);var response=mvc.perform(req).andReturn().getResponse();assertThat(response.getStatus()).withFailMessage("%s instead of %s: %s",response.getStatus(),expected,response.getContentAsString()).isEqualTo(expected);return mapper.readTree(response.getContentAsString()).path("data");}
 MockHttpServletRequestBuilder body(MockHttpServletRequestBuilder r,Object b)throws Exception{return r.contentType("application/json").content(mapper.writeValueAsString(b));}
 String login(UUID id)throws Exception{return request(body(post("/auth/login"),Map.of("email",id+"@accounts-acceptance.invalid","password","Acceptance123!")),null,200).path("accessToken").asText();}
 @Test void uniqueUsersRoleOrderProfilesAndPagination()throws Exception{String token=login(admin);var all=request(get("/admin/accounts").param("search",batch),token,200);assertThat(all.path("totalElements").asInt()).isEqualTo(4);assertThat(all.path("accounts").size()).isEqualTo(4);assertThat(all.toString()).doesNotContain("passwordHash","password_hash","accessToken");var row=request(get("/admin/accounts/"+multi),token,200);assertThat(row.path("roles").toString()).isEqualTo("[\"ADMIN\",\"STAFF\"]");assertThat(row.path("staffId").isNull()).isTrue();assertThat(request(get("/admin/accounts/"+staff),token,200).path("position").asText()).isEqualTo("Reception");assertThat(request(get("/admin/accounts").param("search",batch).param("role","STAFF"),token,200).path("totalElements").asInt()).isEqualTo(2);assertThat(request(get("/admin/accounts").param("search",batch).param("page","1"),token,200).path("accounts").size()).isZero();}
 @Test void realJwtRolesGuardAllReportsAndOperations()throws Exception{for(UUID id:List.of(staff,customer)){String token=login(id);for(String path:List.of("/admin/accounts","/analytics/statistics","/analytics/revenue-breakdown"))request(get(path),token,403);request(delete("/admin/accounts/"+admin),token,403);}request(get("/admin/accounts"),null,401);String token=login(admin);request(get("/analytics/statistics"),token,200);request(get("/analytics/revenue-breakdown"),token,200);token=login(staff);request(get("/pos/products"),token,200);request(get("/checkout/active-bookings"),token,200);}
 @Test void updateSynchronizesProfilesButNeverPasswordOrRoles()throws Exception{String token=login(admin),before=db.queryForObject("select password_hash from users where id=?",String.class,customer);var update=Map.of("fullName","Updated","email",customer+"@accounts-acceptance.invalid","phone","0987654321","address","New address","password","AttackerPassword!","roles",List.of("ADMIN"));request(body(put("/admin/accounts/"+customer),update),token,200);assertThat(db.queryForObject("select full_name from customers where id=?",String.class,customerProfile)).isEqualTo("Updated");assertThat(db.queryForObject("select password_hash from users where id=?",String.class,customer)).isEqualTo(before);assertThat(request(get("/admin/accounts/"+customer),token,200).path("roles").toString()).isEqualTo("[\"CUSTOMER\"]");var duplicate=new HashMap<String,Object>(update);duplicate.put("email",admin+"@accounts-acceptance.invalid");request(body(put("/admin/accounts/"+customer),duplicate),token,409);request(body(put("/admin/accounts/"+staff),update),token,409);var staffUpdate=new HashMap<String,Object>(update);staffUpdate.put("email",staff+"@accounts-acceptance.invalid");request(body(put("/admin/accounts/"+staff),staffUpdate),token,200);assertThat(db.queryForObject("select full_name from staffs where id=?",String.class,staffProfile)).isEqualTo("Updated");}
 @Test void locksRejectLoginAndPreviouslyIssuedAccessAndRefreshTokens()throws Exception{String actor=login(admin);var session=request(body(post("/auth/login"),Map.of("email",staff+"@accounts-acceptance.invalid","password","Acceptance123!")),null,200);String old=session.path("accessToken").asText();request(delete("/admin/accounts/"+admin),actor,400);request(delete("/admin/accounts/"+staff),actor,200);request(get("/pos/products"),old,401);request(body(post("/auth/refresh"),Map.of("refreshToken",session.path("refreshToken").asText())),null,401);request(body(post("/auth/login"),Map.of("email",staff+"@accounts-acceptance.invalid","password","Acceptance123!")),null,401);assertThat(db.queryForObject("select deleted_at is not null from staffs where id=?",Boolean.class,staffProfile)).isTrue();request(delete("/admin/accounts/"+customer),actor,200);assertThat(db.queryForObject("select deleted_at is not null from customers where id=?",Boolean.class,customerProfile)).isTrue();}
 @Test void staleElevatedClaimsDoNotBypassCurrentDatabaseRoles()throws Exception{String token=login(multi);db.update("delete from user_roles where user_id=? and role_id=(select id from roles where role_name='ADMIN')",multi);request(get("/admin/accounts"),token,403);request(get("/analytics/statistics"),token,403);request(get("/pos/products"),token,200);}
 @Test void concurrentAdminCrossLocksLeaveAnActiveAdmin()throws Exception{
  String a=login(admin),b=login(multi);
  var others=db.queryForList("select distinct u.id from users u join user_roles ur on ur.user_id=u.id join roles r on r.id=ur.role_id where r.role_name='ADMIN' and u.deleted_at is null and u.id not in (?,?)",UUID.class,admin,multi);
  var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
  try{
   for(UUID id:others)db.update("update users set deleted_at=current_timestamp where id=?",id);
   var gate=new java.util.concurrent.CountDownLatch(1);
   var first=pool.submit(()->{gate.await();return mvc.perform(delete("/admin/accounts/"+multi).header("Authorization","Bearer "+a)).andReturn().getResponse().getStatus();});
   var second=pool.submit(()->{gate.await();return mvc.perform(delete("/admin/accounts/"+admin).header("Authorization","Bearer "+b)).andReturn().getResponse().getStatus();});
   gate.countDown();var statuses=List.of(first.get(20,java.util.concurrent.TimeUnit.SECONDS),second.get(20,java.util.concurrent.TimeUnit.SECONDS));
   assertThat(statuses.stream().filter(x->x==200).count()).isEqualTo(1);
   assertThat(statuses).allMatch(x->x==200||x==401||x==403);
   assertThat(db.queryForObject("select count(*) from users where id in (?,?) and deleted_at is null",Long.class,admin,multi)).isEqualTo(1);
  }finally{pool.shutdownNow();for(UUID id:others)db.update("update users set deleted_at=null where id=?",id);}
 }

}
