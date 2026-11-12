package com.bcm.service;

import com.bcm.TestActors;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"booking.expiration-enabled=false","booking.auto-checkout.enabled=false","booking.hold-duration=PT90S"})
@AutoConfigureMockMvc
@Transactional
@EnabledIfEnvironmentVariable(named="BCM_TEST_DB_URL",matches="jdbc:postgresql:.*")
class BrowserDraftPostgresTest {
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",()->System.getenv("BCM_TEST_DB_URL"));
        r.add("spring.datasource.username",()->System.getenv("BCM_TEST_DB_USERNAME"));
        r.add("spring.datasource.password",()->System.getenv("BCM_TEST_DB_PASSWORD"));
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    UUID owner,booking,product,invoice;
    com.bcm.security.UserPrincipal actor;
    @BeforeEach void setup() {
        actor=TestActors.admin(jdbc);
        UUID uid=UUID.randomUUID(),category=UUID.randomUUID();
        owner=UUID.randomUUID();booking=UUID.randomUUID();product=UUID.randomUUID();invoice=UUID.randomUUID();
        jdbc.update("INSERT INTO users(id,email,password_hash,full_name,phone) VALUES (?,?,'!','Profile name','0912345678')",uid,uid+"@draft.test");
        jdbc.update("INSERT INTO customers(id,user_id,full_name,phone) VALUES (?,?,'Profile name','0912345678')",owner,uid);
        jdbc.update("INSERT INTO bookings(id,customer_id,status,court_fee,contact_full_name,contact_phone) VALUES (?,?,'PAID',100000,'Booking contact','0987654321')",booking,owner);
        jdbc.update("INSERT INTO categories(id,category_name) VALUES (?,?)",category,"Draft test "+category);
        jdbc.update("INSERT INTO products(id,category_id,name,type,price,stock_quantity) VALUES (?,?,'Current product','GOODS',99999,0)",product,category);
        jdbc.update("INSERT INTO invoices(id,booking_id,customer_id,status,payment_method,product_fee,discount_amount,total_amount,created_by_user) VALUES (?,?,?,'DRAFT',NULL,10000,1000,9000,?)",invoice,booking,owner,UUID.fromString(actor.getId()));
        jdbc.update("INSERT INTO invoice_details(invoice_id,product_id,product_name,quantity,unit_price,discount_amount,stock_deducted) VALUES (?,?,'Snapshot product',1,10000,1000,true)",invoice,product);
    }
    @Test void persistedDraftCanBeReloadedWithSnapshotAndContact() throws Exception {
        for(int i=0;i<2;i++) mvc.perform(get("/pos/drafts").param("bookingId",booking.toString()).with(user(actor)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(1))
            .andExpect(jsonPath("$.data.content[0].id").value(invoice.toString()))
            .andExpect(jsonPath("$.data.content[0].customerName").value("Booking contact"))
            .andExpect(jsonPath("$.data.content[0].totalAmount").value(9000))
            .andExpect(jsonPath("$.data.content[0].items[0].productName").value("Snapshot product"))
            .andExpect(jsonPath("$.data.content[0].items[0].lineAmount").value(9000));
    }
    @Test void cancellationRemovesDraftAndRestoresInventoryOnce() throws Exception {
        for(int i=0;i<2;i++) mvc.perform(post("/pos/sales/"+invoice+"/cancel").with(user(actor))).andExpect(status().isOk());
        mvc.perform(get("/pos/drafts").param("bookingId",booking.toString()).with(user(actor)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(0));
        assertThat(jdbc.queryForObject("SELECT stock_quantity FROM products WHERE id=?",Integer.class,product)).isEqualTo(1);
    }
    @Test void filteringPaginationAndValidationAreEnforced() throws Exception {
        mvc.perform(get("/pos/drafts").param("search",invoice.toString()).param("size","1").with(user(actor)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.content[0].id").value(invoice.toString()));
        mvc.perform(get("/pos/drafts").param("bookingId",UUID.randomUUID().toString()).with(user(actor)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(0));
        for(String size:new String[]{"0","101"}) mvc.perform(get("/pos/drafts").param("size",size).with(user(actor))).andExpect(status().isBadRequest());
        mvc.perform(get("/pos/drafts").param("page","-1").with(user(actor))).andExpect(status().isBadRequest());
        mvc.perform(get("/pos/drafts").param("bookingId","bad").with(user(actor))).andExpect(status().isBadRequest());
    }
    @Test void mergedAndDeletedDraftsAreNotListed() throws Exception {
        jdbc.update("UPDATE invoices SET parent_invoice_id=id WHERE id=?",invoice);
        mvc.perform(get("/pos/drafts").param("bookingId",booking.toString()).with(user(actor))).andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(0));
        jdbc.update("UPDATE invoices SET parent_invoice_id=NULL,deleted_at=CURRENT_TIMESTAMP WHERE id=?",invoice);
        mvc.perform(get("/pos/drafts").param("bookingId",booking.toString()).with(user(actor))).andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(0));
    }
    @Test void anonymousAndCustomerCannotReadDrafts() throws Exception {
        mvc.perform(get("/pos/drafts")).andExpect(status().isUnauthorized());
        mvc.perform(get("/pos/drafts").with(user("customer").roles("CUSTOMER"))).andExpect(status().isForbidden());
    }
    @Test void staffCanRecoverDraftsAcrossCashiers() throws Exception {
        mvc.perform(get("/pos/drafts").param("bookingId",booking.toString()).with(user(TestActors.staff(jdbc))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.content[0].id").value(invoice.toString()));
    }
    @Test void draftPageHasStablePagination() throws Exception {
        for(int i=0;i<21;i++)jdbc.update("INSERT INTO invoices(booking_id,customer_id,status,payment_method,product_fee,total_amount) VALUES (?,?,'DRAFT',NULL,0,0)",booking,owner);
        mvc.perform(get("/pos/drafts").param("bookingId",booking.toString()).with(user(actor)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(22)).andExpect(jsonPath("$.data.content.length()").value(20));
        mvc.perform(get("/pos/drafts").param("bookingId",booking.toString()).param("page","1").with(user(actor)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.content.length()").value(2));
    }
    @Test void clockPublishesConfiguredDurationAndKeepsExistingFields() throws Exception {
        mvc.perform(get("/public/booking-clock")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.holdDurationSeconds").value(90))
            .andExpect(jsonPath("$.data.timeZone").value("Asia/Ho_Chi_Minh"))
            .andExpect(jsonPath("$.data.serverTime").isNotEmpty()).andExpect(jsonPath("$.data.today").isNotEmpty());
    }
}
