package com.bcm;

import com.fasterxml.jackson.databind.*;
import java.util.*;
import java.time.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("h2")
@Transactional
@WithMockUser(roles="ADMIN")
class ProductPromotionIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    UUID product;
    LocalDate today = LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh"));

    @BeforeEach void fixture() {
        UUID category = UUID.randomUUID(); product = UUID.randomUUID();
        jdbc.update("insert into categories(id,category_name,created_at) values (?,?,CURRENT_TIMESTAMP)",category,"Promo test "+category);
        jdbc.update("insert into products(id,category_id,name,type,unit,price,stock_quantity,version,created_at) values (?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP)",
                product,category,"Quả cầu test","GOODS","quả",10000,100,0);
    }
    Map<String,Object> campaign(String name, int min, int percent, LocalDate from, LocalDate to, boolean active) {
        return new LinkedHashMap<>(Map.of("name",name,"productId",product,"minQuantity",min,"discountPercent",percent,
                "validFrom",from.toString(),"validTo",to.toString(),"active",active));
    }
    JsonNode create(Map<String,Object> body) throws Exception {
        return mapper.readTree(mvc.perform(post("/admin/product-promotions").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(body)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).get("data");
    }
    Map<String,Object> cart(int quantity) {
        return new LinkedHashMap<>(Map.of("paymentMethod","CASH","items",List.of(Map.of("productId",product,"quantity",quantity))));
    }
    JsonNode quote(Map<String,Object> body) throws Exception {
        return mapper.readTree(mvc.perform(post("/pos/quote").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(body)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).get("data");
    }

    @Test void thresholdIsInclusiveAndQuoteDoesNotChangeStock() throws Exception {
        create(campaign("Mua 10 giảm 10%",10,10,today,today,true));
        assertThat(quote(cart(9)).get("discountAmount").decimalValue()).isEqualByComparingTo("0");
        assertThat(quote(cart(10)).get("discountAmount").decimalValue()).isEqualByComparingTo("10000");
        assertThat(quote(cart(11)).get("totalAmount").decimalValue()).isEqualByComparingTo("99000");
        assertThat(jdbc.queryForObject("select stock_quantity from products where id=?",Integer.class,product)).isEqualTo(100);
    }

    @Test void choosesBestWithoutStackingAndAggregatesDuplicateCartLines() throws Exception {
        create(campaign("Giảm 10",10,10,today,today,true));
        create(campaign("Giảm 20",10,20,today,today,true));
        Map<String,Object> request=cart(10);
        request.put("items",List.of(Map.of("productId",product,"quantity",4),Map.of("productId",product,"quantity",6)));
        JsonNode result=quote(request);
        assertThat(result.get("discountAmount").decimalValue()).isEqualByComparingTo("20000");
        assertThat(result.get("lines")).hasSize(1);
        assertThat(result.get("lines").get(0).get("promotionName").asText()).isEqualTo("Giảm 20");
    }

    @Test void ignoresFutureExpiredDisabledAndSoftDeletedCampaigns() throws Exception {
        create(campaign("Chưa bắt đầu",10,90,today.plusDays(1),today.plusDays(3),true));
        create(campaign("Hết hạn",10,90,today.minusDays(3),today.minusDays(1),true));
        create(campaign("Đã tắt",10,90,today,today,false));
        JsonNode deleted=create(campaign("Xóa",10,90,today,today,true));
        mvc.perform(delete("/admin/product-promotions/"+deleted.get("id").asText())).andExpect(status().isOk());
        assertThat(quote(cart(10)).get("discountAmount").decimalValue()).isEqualByComparingTo("0");
    }

    @Test void salePersistsDiscountSnapshotAndLaterEditsDoNotChangeInvoice() throws Exception {
        Map<String,Object> original=campaign("Ưu đãi ban đầu",10,10,today,today,true);
        JsonNode promotion=create(original);
        JsonNode invoice=mapper.readTree(mvc.perform(post("/pos/sales").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(cart(10))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).get("data");
        UUID invoiceId=UUID.fromString(invoice.get("id").asText());
        assertThat(invoice.get("discountAmount").decimalValue()).isEqualByComparingTo("10000");
        assertThat(invoice.get("total_amount").decimalValue()).isEqualByComparingTo("90000");
        original.put("discountPercent",50); original.put("name","Đã sửa");
        mvc.perform(put("/admin/product-promotions/"+promotion.get("id").asText()).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(original))).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select promotion_name from invoice_details where invoice_id=?",String.class,invoiceId)).isEqualTo("Ưu đãi ban đầu");
        assertThat(jdbc.queryForObject("select discount_amount from invoice_details where invoice_id=?",java.math.BigDecimal.class,invoiceId)).isEqualByComparingTo("10000");
        assertThat(jdbc.queryForObject("select total_amount from invoices where id=?",java.math.BigDecimal.class,invoiceId)).isEqualByComparingTo("90000");
        assertThat(jdbc.queryForObject("select stock_quantity from products where id=?",Integer.class,product)).isEqualTo(90);
    }

    @Test void validatesPromotionInputsAndMissingProducts() throws Exception {
        for(String key:List.of("name","minQuantity","discountPercent")) {
            var body=campaign("Hợp lệ",10,10,today,today,true);
            body.put(key,key.equals("name")?" ":0);
            mvc.perform(post("/admin/product-promotions").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(body))).andExpect(status().isBadRequest());
        }
        var body=campaign("Sai tỷ lệ",10,101,today,today,true);
        mvc.perform(post("/admin/product-promotions").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(body))).andExpect(status().isBadRequest());
        body=campaign("Sai ngày",10,10,today,today.minusDays(1),true);
        mvc.perform(post("/admin/product-promotions").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(body))).andExpect(status().isBadRequest());
        body=campaign("Sai sản phẩm",10,10,today,today,true);body.put("productId",UUID.randomUUID());
        mvc.perform(post("/admin/product-promotions").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(body))).andExpect(status().isNotFound());
    }

    @Test void noStackingFailureRollsBackStockAndInvoice() throws Exception {
        create(campaign("Tự động",10,10,today,today,true));
        var request=cart(10); request.put("discount_amount",1);
        int count=jdbc.queryForObject("select count(*) from invoices",Integer.class);
        mvc.perform(post("/pos/sales").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(request))).andExpect(status().isBadRequest());
        // A caught service failure joins this test transaction; rollback is checked with a real
        // PostgreSQL request transaction by the live verifier, not by reading this shared transaction.
        assertThat(jdbc.queryForObject("select count(*) from invoices",Integer.class)).isEqualTo(count);
    }

    @Test @WithMockUser(roles="STAFF") void staffCanQuoteButCannotConfigure() throws Exception {
        mvc.perform(get("/pos/product-promotions")).andExpect(status().isOk());
        mvc.perform(get("/pos/products")).andExpect(status().isOk());
        mvc.perform(get("/admin/product-promotions")).andExpect(status().isForbidden());
        var body=campaign("Không được",10,10,today,today,true);
        mvc.perform(post("/admin/product-promotions").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(body))).andExpect(status().isForbidden());
        assertThat(quote(cart(1)).get("totalAmount").decimalValue()).isEqualByComparingTo("10000");
    }

    @Test @WithMockUser(roles="CUSTOMER") void customerCannotAccessStaffQuotes() throws Exception {
        mvc.perform(post("/pos/quote").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(cart(1))))
                .andExpect(status().isForbidden());
        mvc.perform(get("/pos/product-promotions")).andExpect(status().isForbidden());
    }

    @Test void roundsDiscountPerLineAndSupportsFullDiscount() throws Exception {
        jdbc.update("update products set price=0.05 where id=?",product);
        JsonNode campaign=create(campaign("Làm tròn",1,10,today,today,true));
        assertThat(quote(cart(1)).get("discountAmount").decimalValue()).isEqualByComparingTo("0.01");
        var body=campaign("Miễn phí",1,100,today,today,true);
        mvc.perform(put("/admin/product-promotions/"+campaign.get("id").asText()).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(body))).andExpect(status().isOk());
        assertThat(quote(cart(2)).get("totalAmount").decimalValue()).isEqualByComparingTo("0");
    }

    @Test void percentDiscountDoesNotAffectOtherProducts() throws Exception {
        create(campaign("Theo sản phẩm",10,10,today,today,true));
        UUID second=UUID.randomUUID();
        jdbc.update("insert into products(id,category_id,name,type,unit,price,stock_quantity,version,created_at) select ?,category_id,'Khác','GOODS','chai',10000,100,0,CURRENT_TIMESTAMP from products where id=?",second,product);
        var request=cart(10);request.put("items",List.of(Map.of("productId",product,"quantity",10),Map.of("productId",second,"quantity",2)));
        assertThat(quote(request).get("totalAmount").decimalValue()).isEqualByComparingTo("110000");
    }
}
