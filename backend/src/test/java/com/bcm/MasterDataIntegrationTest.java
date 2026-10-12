package com.bcm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.*;

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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("h2")
@Transactional
class MasterDataIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;

    private record BookingFixture(UUID id, UUID detailId) {}

    private BookingFixture persistBooking(UUID courtId, UUID timeSlotId, String status) {
        UUID userId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        UUID detailId = UUID.randomUUID();
        jdbc.update(
                "insert into users(id,email,password_hash,full_name,phone,created_at)"
                        + " values (?,?,?,?,?,CURRENT_TIMESTAMP)",
                userId,
                userId + "@master-data-test.invalid",
                "test-only",
                "Master data fixture",
                "0900000000");
        jdbc.update(
                "insert into customers(id,user_id,full_name,phone,created_at)"
                        + " values (?,?,?,?,CURRENT_TIMESTAMP)",
                customerId,
                userId,
                "Master data fixture",
                "0900000000");
        jdbc.update(
                "insert into bookings(id,customer_id,status,court_fee,expires_at,created_at,updated_at)"
                        + " values (?,?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                bookingId,
                customerId,
                status,
                BigDecimal.valueOf(100000));
        jdbc.update(
                "insert into booking_details(id,booking_id,court_id,time_slot_id,booking_date,price,created_at)"
                        + " values (?,?,?,?,?,?,CURRENT_TIMESTAMP)",
                detailId,
                bookingId,
                courtId,
                timeSlotId,
                LocalDate.of(2030, 1, 15),
                BigDecimal.valueOf(100000));
        return new BookingFixture(bookingId, detailId);
    }

    private JsonNode create(String path, String json) throws Exception {
        return mapper.readTree(
                        mvc.perform(
                                        post("/admin/" + path)
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(json))
                                .andExpect(status().isCreated())
                                .andReturn()
                                .getResponse()
                                .getContentAsString())
                .get("data");
    }

    private String courtBody(int number) {
        return "{\"courtNumber\":"
                + number
                + ",\"name\":\"Sân"
                + " A\",\"type\":\"STANDARD_MAT\",\"status\":\"AVAILABLE\",\"basePrice\":100000}";
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void courtCrudAndDuplicate() throws Exception {
        String id = create("courts", courtBody(1)).get("id").asText();
        mvc.perform(
                        post("/admin/courts")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(courtBody(1)))
                .andExpect(status().isConflict());
        mvc.perform(
                        put("/admin/courts/" + id)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(courtBody(1).replace("STANDARD_MAT", "WOODEN_FLOOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.type").value("WOODEN_FLOOR"));
        mvc.perform(delete("/admin/courts/" + id)).andExpect(status().isOk());
        mvc.perform(get("/admin/courts/" + id)).andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void blocksUnfinishedBookingButPreservesHistory() throws Exception {
        String court = create("courts", courtBody(2)).get("id").asText();
        String slot =
                create("time-slots", "{\"startTime\":\"06:00\",\"endTime\":\"07:00\"}")
                        .get("id")
                        .asText();
        BookingFixture booking = persistBooking(UUID.fromString(court), UUID.fromString(slot), "PENDING");
        UUID b = booking.id();
        for (String state : List.of("PENDING", "PAID", "CHECKED_IN")) {
            jdbc.update("update bookings set status=? where id=?", state, b);
            mvc.perform(delete("/admin/courts/" + court)).andExpect(status().isBadRequest());
        }
        jdbc.update("update bookings set status='COMPLETED' where id=?", b);
        mvc.perform(delete("/admin/courts/" + court)).andExpect(status().isOk());
        assertThat(
                        jdbc.queryForObject(
                                "select price from booking_details where id=?", Integer.class, booking.detailId()))
                .isEqualTo(100000);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void slotsValidateOverlapAndPeakPrice() throws Exception {
        String court = create("courts", courtBody(3)).get("id").asText();
        String slot =
                create("time-slots", "{\"startTime\":\"17:00\",\"endTime\":\"18:00\"}")
                        .get("id")
                        .asText();
        mvc.perform(get("/public/price-quote").param("courtId", court).param("timeSlotId", slot))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.price").value(150000));
        mvc.perform(
                        post("/admin/time-slots")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"startTime\":\"17:30\",\"endTime\":\"18:30\"}"))
                .andExpect(status().isConflict());
        mvc.perform(
                        post("/admin/time-slots")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"startTime\":\"19:00\",\"endTime\":\"18:00\"}"))
                .andExpect(status().isBadRequest());
        create(
                "time-slots",
                "{\"startTime\":\"18:00\",\"endTime\":\"19:00\",\"priceMultiplier\":1.25}");
        mvc.perform(
                        put("/admin/time-slots/" + slot)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"startTime\":\"17:00\",\"endTime\":\"18:00\",\"priceMultiplier\":2}"))
                .andExpect(status().isOk());
        mvc.perform(get("/public/price-quote").param("courtId", court).param("timeSlotId", slot))
                .andExpect(jsonPath("$.data.price").value(200000));
        mvc.perform(delete("/admin/time-slots/" + slot)).andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void cannotChangeOrDeleteReferencedSlot() throws Exception {
        String slot =
                create("time-slots", "{\"startTime\":\"06:00\",\"endTime\":\"07:00\"}")
                        .get("id")
                        .asText();
        String court = create("courts", courtBody(6)).get("id").asText();
        persistBooking(UUID.fromString(court), UUID.fromString(slot), "PENDING");
        mvc.perform(delete("/admin/time-slots/" + slot)).andExpect(status().isBadRequest());
        mvc.perform(
                        put("/admin/time-slots/" + slot)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"startTime\":\"07:00\",\"endTime\":\"08:00\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void catalogCrudStockAndVersion() throws Exception {
        String category =
                create("categories", "{\"categoryName\":\"Nước giải khát\"}").get("id").asText();
        String body =
                "{\"categoryId\":\""
                        + category
                        + "\",\"name\":\"Nước"
                        + " suối\",\"type\":\"GOODS\",\"unit\":\"chai\",\"price\":10000,\"stockQuantity\":20}";
        JsonNode product = create("products", body);
        String id = product.get("id").asText();
        mvc.perform(delete("/admin/categories/" + category)).andExpect(status().isBadRequest());
        mvc.perform(
                        put("/admin/products/" + id)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body.substring(0, body.length() - 1) + ",\"version\":99}"))
                .andExpect(status().isConflict());
        mvc.perform(
                        put("/admin/products/" + id)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        body.replace("10000", "15000")
                                                        .substring(
                                                                0,
                                                                body.replace("10000", "15000")
                                                                                .length()
                                                                        - 1)
                                                + ",\"version\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.price").value(15000));
        mvc.perform(delete("/admin/products/" + id)).andExpect(status().isOk());
        mvc.perform(delete("/admin/categories/" + category)).andExpect(status().isOk());
        mvc.perform(get("/public/products")).andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void invalidNamesPricesEnumsAndServicesRejected() throws Exception {
        mvc.perform(
                        post("/admin/courts")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        courtBody(4).replace("Sân A", " ").replace("100000", "-1")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.name").exists())
                .andExpect(jsonPath("$.data.basePrice").exists());
        mvc.perform(
                        post("/admin/courts")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(courtBody(4).replace("STANDARD_MAT", "INVALID")))
                .andExpect(status().isBadRequest());
        String category = create("categories", "{\"categoryName\":\"Dịch vụ\"}").get("id").asText();
        mvc.perform(
                        post("/admin/products")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"categoryId\":\""
                                                + category
                                                + "\",\"name\":\"Thuê"
                                                + " vợt\",\"type\":\"SERVICE\",\"unit\":\"lượt\",\"price\":20000,\"stockQuantity\":1}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "STAFF")
    void staffCannotMutateMasterData() throws Exception {
        mvc.perform(
                        post("/admin/courts")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(courtBody(5)))
                .andExpect(status().isForbidden());
    }

    @Test
    void publicCatalogNeedsNoToken() throws Exception {
        for (String path : List.of("courts", "time-slots", "categories", "products"))
            mvc.perform(get("/public/" + path)).andExpect(status().isOk());
        mvc.perform(get("/admin/courts")).andExpect(status().is4xxClientError());
    }
}
