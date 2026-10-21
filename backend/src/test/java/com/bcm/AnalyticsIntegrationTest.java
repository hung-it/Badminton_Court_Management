package com.bcm;

import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("h2")
@Transactional
@WithMockUser(roles = "ADMIN")
class AnalyticsIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @Test
    void emptyPeriodReturnsZeroRevenue() throws Exception {
        mvc.perform(get("/analytics/revenue").param("period", "DAY").param("date", "2040-06-15"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.court_fee").value(0))
                .andExpect(jsonPath("$.data.product_fee").value(0))
                .andExpect(jsonPath("$.data.total_amount").value(0));
    }

    @Test
    void aggregatesOnlyPaidUndeletedInvoicesWithinPeriod() throws Exception {
        invoice("PAID", 100000, 20000, "2040-06-15T08:00:00", false);
        invoice("PAID", 150000, 10000, "2040-06-15T20:00:00", false);
        invoice("DRAFT", 900000, 90000, "2040-06-15T09:00:00", false);
        invoice("PAID", 800000, 80000, "2040-06-15T10:00:00", true);
        invoice("PAID", 700000, 70000, "2040-06-16T00:00:00", false);
        mvc.perform(get("/analytics/revenue").param("period", "DAY").param("date", "2040-06-15"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.court_fee").value(250000))
                .andExpect(jsonPath("$.data.product_fee").value(30000))
                .andExpect(jsonPath("$.data.total_amount").value(280000));
    }

    private void invoice(String status, int court, int product, String created, boolean deleted) {
        jdbc.update("insert into invoices(id,customer_id,status,payment_method,court_fee,product_fee,"
                        + "discount_amount,total_amount,created_at,updated_at,deleted_at) values (?,?,?,?,?,?,?,?,?,?,?)",
                UUID.randomUUID(), UUID.randomUUID(), status, "CASH", court, product,
                0, court + product, LocalDateTime.parse(created), LocalDateTime.parse(created),
                deleted ? LocalDateTime.parse(created) : null);
    }
}
