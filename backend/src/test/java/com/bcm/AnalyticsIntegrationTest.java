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

    @Test
    void revenueSubtractsRecordedDiscounts() throws Exception {
        invoice("PAID",100000,20000,"2040-06-15T08:00:00",false);
        jdbc.update("update invoices set discount_amount=10000,total_amount=110000 where created_at=?",
                LocalDateTime.parse("2040-06-15T08:00:00"));
        mvc.perform(get("/analytics/revenue").param("period","DAY").param("date","2040-06-15"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.discount_amount").value(10000))
                .andExpect(jsonPath("$.data.total_amount").value(110000));
    }

    @Test
    void customRangeIncludesBothDaysAcrossYearsAndMatchesHistory() throws Exception {
        invoice("PAID",100000,0,"2040-12-31T00:00:00",false);
        invoice("PAID",200000,0,"2041-01-02T23:59:59",false);
        invoice("PAID",900000,0,"2040-12-30T23:59:59",false);
        invoice("PAID",900000,0,"2041-01-03T00:00:00",false);
        invoice("DRAFT",900000,0,"2041-01-01T12:00:00",false);
        mvc.perform(get("/analytics/revenue").param("from","2040-12-31").param("to","2041-01-02"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.from").value("2040-12-31"))
                .andExpect(jsonPath("$.data.to").value("2041-01-02"))
                .andExpect(jsonPath("$.data.total_amount").value(300000));
        mvc.perform(get("/analytics/invoices").param("from","2040-12-31").param("to","2041-01-02"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(2));
        mvc.perform(get("/analytics/revenue").param("from","2040-12-31").param("to","2040-12-31"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total_amount").value(100000));
    }

    @Test
    void rejectsIncompleteAndReversedRangesForBothEndpoints() throws Exception {
        for(String endpoint : new String[]{"/analytics/revenue","/analytics/invoices"}) {
            mvc.perform(get(endpoint).param("from","2040-12-31")).andExpect(status().isBadRequest());
            mvc.perform(get(endpoint).param("to","2040-12-31")).andExpect(status().isBadRequest());
            mvc.perform(get(endpoint).param("from","2041-01-02").param("to","2040-12-31"))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void statisticsGroupsQuarterBoundariesAndSubtractsImportCosts() throws Exception {
        invoice("PAID",100000,0,"2040-03-31T23:59:59",false);
        invoice("PAID",200000,0,"2040-04-01T00:00:00",false);
        invoice("PAID",900000,0,"2041-01-01T00:00:00",false);
        invoice("DRAFT",900000,0,"2040-04-01T00:00:00",false);
        invoice("PAID",900000,0,"2040-04-02T00:00:00",true);
        UUID supplier=UUID.randomUUID();
        jdbc.update("insert into suppliers(id,name,created_at) values (?,'Stats',?)",supplier,LocalDateTime.now());
        for(String state:new String[]{"DRAFT","RECEIVED","CANCELLED"})
            jdbc.update("insert into import_orders(id,supplier_id,status,total_amount,import_date,created_at) values (?,?,?,?,?,?)",
                    UUID.randomUUID(),supplier,state,60000,java.time.LocalDate.parse("2040-03-31"),LocalDateTime.now());
        mvc.perform(get("/analytics/statistics").param("period","QUARTER").param("year","2040"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.rows.length()").value(4))
                .andExpect(jsonPath("$.data.rows[0].revenue").value(100000))
                .andExpect(jsonPath("$.data.rows[0].expense").value(120000))
                .andExpect(jsonPath("$.data.rows[0].profit").value(-20000))
                .andExpect(jsonPath("$.data.rows[1].revenue").value(200000))
                .andExpect(jsonPath("$.data.totalRevenue").value(300000))
                .andExpect(jsonPath("$.data.totalProfit").value(180000));
        mvc.perform(get("/analytics/statistics").param("period","MONTH").param("year","2040"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.rows.length()").value(12))
                .andExpect(jsonPath("$.data.rows[2].expense").value(120000));
        mvc.perform(get("/analytics/statistics").param("period","YEAR").param("year","2040"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.rows.length()").value(1))
                .andExpect(jsonPath("$.data.totalRevenue").value(300000));
        mvc.perform(get("/analytics/statistics").param("period","YEARS").param("fromYear","2040").param("toYear","2041"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.rows.length()").value(2))
                .andExpect(jsonPath("$.data.rows[1].revenue").value(900000));
    }

    @Test
    void statisticsRejectsInvalidPeriodsAndYears() throws Exception {
        mvc.perform(get("/analytics/statistics").param("period","DAY")).andExpect(status().isBadRequest());
        mvc.perform(get("/analytics/statistics").param("year","0")).andExpect(status().isBadRequest());
        mvc.perform(get("/analytics/statistics").param("period","YEARS").param("fromYear","2041").param("toYear","2040"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/analytics/statistics").param("period","YEARS").param("fromYear","2000").param("toYear","2050"))
                .andExpect(status().isBadRequest());
    }

    private void invoice(String status, int court, int product, String created, boolean deleted) {
        jdbc.update("insert into invoices(id,customer_id,status,payment_method,court_fee,product_fee,"
                        + "discount_amount,total_amount,created_at,updated_at,deleted_at) values (?,?,?,?,?,?,?,?,?,?,?)",
                UUID.randomUUID(), UUID.randomUUID(), status, "CASH", court, product,
                0, court + product, LocalDateTime.parse(created), LocalDateTime.parse(created),
                deleted ? LocalDateTime.parse(created) : null);
    }
}
