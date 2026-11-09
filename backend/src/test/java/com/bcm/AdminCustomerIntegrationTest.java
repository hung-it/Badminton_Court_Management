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
@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("h2") @Transactional
class AdminCustomerIntegrationTest {
 @Autowired AdminCustomerService service; @Autowired JdbcTemplate jdbc; @Autowired PasswordEncoder encoder; @Autowired MockMvc mvc;
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
  service.disable(id);
  assertThat(jdbc.queryForObject("select count(*) from customers where id=? and deleted_at is not null",Long.class,id)).isEqualTo(1);
 }
 @Test void rejectsDuplicateEmailAndMissingPassword(){String email=UUID.randomUUID()+"@example.com";service.save(null,request(email));assertThatThrownBy(()->service.save(null,request(email))).isInstanceOf(com.bcm.exception.DuplicateResourceException.class);assertThatThrownBy(()->service.save(null,new AdminCustomerRequest("Test","other@example.com","0912345678",null,null))).isInstanceOf(com.bcm.exception.BadRequestException.class);}
 @Test @WithMockUser(roles="STAFF") void staffCannotManageCustomersOrInvoices() throws Exception {mvc.perform(get("/admin/customers")).andExpect(status().isForbidden());mvc.perform(get("/analytics/invoices")).andExpect(status().isForbidden());}
 @Test @WithMockUser(roles="CUSTOMER") void customerCannotManageCustomers() throws Exception {mvc.perform(get("/admin/customers")).andExpect(status().isForbidden());}
 @Test @WithMockUser(roles="ADMIN") void validatesCustomerInput() throws Exception {mvc.perform(post("/admin/customers").contentType("application/json").content("{}" )).andExpect(status().isBadRequest());}
}
