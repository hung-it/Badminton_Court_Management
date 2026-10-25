package com.bcm.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@EnabledIfEnvironmentVariable(named="BCM_TEST_DB_URL", matches="jdbc:postgresql:.*")
class CourtMetadataPostgresTest {
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> System.getenv("BCM_TEST_DB_URL"));
        r.add("spring.datasource.username", () -> System.getenv("BCM_TEST_DB_USERNAME"));
        r.add("spring.datasource.password", () -> System.getenv("BCM_TEST_DB_PASSWORD"));
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;

    private ObjectNode body() {
        return mapper.createObjectNode().put("courtNumber", 908001).put("name", "Metadata test court")
            .put("type", "STANDARD_MAT").put("status", "AVAILABLE").put("basePrice", 123456)
            .put("district", "Quận 1").put("address", "Khu mô phỏng, Quận 1, TP. Hồ Chí Minh");
    }
    private String create(ObjectNode body) throws Exception {
        var response = mvc.perform(post("/admin/courts").contentType(MediaType.APPLICATION_JSON)
                .content(body.toString())).andExpect(status().isCreated()).andReturn();
        return mapper.readTree(response.getResponse().getContentAsString()).path("data").path("id").asText();
    }
    @Test @WithMockUser(roles="ADMIN")
    void createUpdateAndPublicCatalogPersistBothSurfacesWithoutChangingPrice() throws Exception {
        var b=body(); String id=create(b);
        b.put("type","WOODEN_FLOOR").put("address","Khu mới mô phỏng, Quận 1, TP. Hồ Chí Minh");
        mvc.perform(put("/admin/courts/"+id).contentType(MediaType.APPLICATION_JSON).content(b.toString()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.type").value("WOODEN_FLOOR"))
            .andExpect(jsonPath("$.data.address").value(b.path("address").asText()));
        assertThat(jdbc.queryForObject("select address from courts where id=?",String.class,UUID.fromString(id)))
            .isEqualTo(b.path("address").asText());
        mvc.perform(get("/public/courts")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data[?(@.id == '"+id+"')].address").value(b.path("address").asText()))
            .andExpect(jsonPath("$.data[?(@.id == '"+id+"')].basePrice").value(123456));
        mvc.perform(get("/admin/courts/"+id)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.district").value("Quận 1"));
    }
    @Test @WithMockUser(roles="ADMIN")
    void legacyOmittedNullAndBlankAddressCannotEraseMetadata() throws Exception {
        var b=body(); String id=create(b); String address=b.path("address").asText();
        b.remove("address"); b.remove("district");
        for (String value : new String[]{null,"","   "}) {
            if(value!=null)b.put("address",value);
            mvc.perform(put("/admin/courts/"+id).contentType(MediaType.APPLICATION_JSON).content(b.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.address").value(address));
        }
        b.putNull("address");
        mvc.perform(put("/admin/courts/"+id).contentType(MediaType.APPLICATION_JSON).content(b.toString()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.address").value(address));
    }
    @Test @WithMockUser(roles="ADMIN")
    void rejectsThirdTypeLongControlAddressAndExplicitDistrictMismatch() throws Exception {
        for(String type : new String[]{"VIP","SINGLE","INDOOR","OUTDOOR","STANDARD"}) {
            var b=body().put("type",type);
            mvc.perform(post("/admin/courts").contentType(MediaType.APPLICATION_JSON).content(b.toString()))
                .andExpect(status().isBadRequest());
        }
        for(String address : new String[]{"x".repeat(501),"Street\nName","Khu mô phỏng, Quận 7, TP. Hồ Chí Minh"}) {
            var b=body().put("address",address);
            mvc.perform(post("/admin/courts").contentType(MediaType.APPLICATION_JSON).content(b.toString()))
                .andExpect(status().isBadRequest());
        }
        var b=body().put("district","");
        mvc.perform(post("/admin/courts").contentType(MediaType.APPLICATION_JSON).content(b.toString()))
            .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("select count(*) from courts where court_code=908001",Integer.class)).isZero();
    }
    @Test @WithMockUser(roles="ADMIN")
    void changingOnlyDistrictRechecksStoredAddressAndRollsBack() throws Exception {
        var b=body();String id=create(b);b.remove("address");b.put("district","Quận 7");
        mvc.perform(put("/admin/courts/"+id).contentType(MediaType.APPLICATION_JSON).content(b.toString()))
            .andExpect(status().isBadRequest());
    }
    @Test void anonymousCannotEditMetadata() throws Exception {
        mvc.perform(post("/admin/courts").contentType(MediaType.APPLICATION_JSON).content(body().toString()))
            .andExpect(status().isUnauthorized());
    }
    @Test @WithMockUser(roles="CUSTOMER") void customerCannotEditMetadata() throws Exception {
        mvc.perform(post("/admin/courts").contentType(MediaType.APPLICATION_JSON).content(body().toString()))
            .andExpect(status().isForbidden());
    }
}
