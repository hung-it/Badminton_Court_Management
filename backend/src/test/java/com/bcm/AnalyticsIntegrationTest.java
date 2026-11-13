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
    @Autowired com.bcm.service.RevenueBreakdownService breakdown;

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
                .andExpect(jsonPath("$.data.rows[0].expense").value(60000))
                .andExpect(jsonPath("$.data.rows[0].profit").value(40000))
                .andExpect(jsonPath("$.data.rows[1].revenue").value(200000))
                .andExpect(jsonPath("$.data.totalRevenue").value(300000))
                .andExpect(jsonPath("$.data.totalProfit").value(240000));
        mvc.perform(get("/analytics/statistics").param("period","MONTH").param("year","2040"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.rows.length()").value(12))
                .andExpect(jsonPath("$.data.rows[2].expense").value(60000));
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

    @Test
    void paidInvoiceHistoryFiltersExactCustomerWithoutLosingPaginationOrSearch() throws Exception {
        UUID customer=UUID.randomUUID();
        invoice("PAID",100,0,"2042-01-01T10:00:00",false);
        invoice("DRAFT",200,0,"2042-01-01T11:00:00",false);
        invoice("PAID",300,0,"2042-01-01T12:00:00",true);
        jdbc.update("update invoices set customer_id=? where created_at>=? and created_at<?",customer,
                LocalDateTime.parse("2042-01-01T00:00:00"),LocalDateTime.parse("2042-01-02T00:00:00"));
        invoice("PAID",400,0,"2042-01-01T13:00:00",false);
        for(String role:new String[]{"ADMIN","STAFF"}) {
            mvc.perform(get("/analytics/invoices").with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("reader").roles(role))
                    .param("from","2042-01-01").param("to","2042-01-01").param("customerId",customer.toString()))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(1))
                    .andExpect(jsonPath("$.data.invoices[0].customerId").value(customer.toString()))
                    .andExpect(jsonPath("$.data.invoices[0].invoiceStatus").value("PAID"));
        }
        mvc.perform(get("/analytics/invoices").param("from","2042-01-01").param("to","2042-01-01")
                .param("customerId",customer.toString()).param("page","1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.invoices.length()").value(0));
        mvc.perform(get("/analytics/invoices").param("from","2042-01-01").param("to","2042-01-01")
                .param("customerId",customer.toString()).param("search","no-match"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(0));
        mvc.perform(get("/analytics/invoices").param("from","2042-01-01").param("to","2042-01-01"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(2));
        mvc.perform(get("/analytics/invoices").with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("customer").roles("CUSTOMER"))
                .param("customerId",customer.toString())).andExpect(status().isForbidden());
    }

    @Test
    void dashboardUsesInclusiveDaysEqualPreviousPeriodAndIncludesAllPaidInvoicesExcludesDraftDeleted() throws Exception {
        invoice("PAID",0,40,"2045-12-31T00:00:00",false);
        invoice("PAID",0,60,"2046-01-01T23:59:59",false);
        invoice("PAID",0,100,"2046-01-02T00:00:00",false);
        invoice("PAID",0,200,"2046-01-03T23:59:59",false);
        invoice("DRAFT",0,999,"2046-01-03T12:00:00",false);
        invoice("PAID",0,999,"2046-01-03T13:00:00",true);
        UUID sample=dashboardInvoice(0,777,"2046-01-03T14:00:00");
        dashboardLine(sample,UUID.randomUUID(),777,false,"Test fixture");
        UUID sampleReceipt=dashboardInvoice(0,888,"2046-01-03T15:00:00");
        dashboardReceipt(sampleReceipt,"VNPAY",888,"BCM-VNPAY-FIXTURE");
        invoice("PAID",0,999,"2046-01-04T00:00:00",false);
        mvc.perform(get("/analytics/revenue-dashboard").param("from","2046-01-02").param("to","2046-01-03"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalRevenue").value(1965))
                .andExpect(jsonPath("$.data.previousRevenue").value(100)).andExpect(jsonPath("$.data.growthPercent").value(1865))
                .andExpect(jsonPath("$.data.invoiceCount").value(4)).andExpect(jsonPath("$.data.averageInvoice").value(491.25))
                .andExpect(jsonPath("$.data.previousFrom").value("2045-12-31")).andExpect(jsonPath("$.data.previousTo").value("2046-01-01"))
                .andExpect(jsonPath("$.data.trend[0].current").value(100)).andExpect(jsonPath("$.data.trend[1].current").value(1865));
    }

    @Test
    void dashboardReconcilesMixedReceiptsAndPreservesUnknownHistoricalTypes() {
        UUID id=dashboardInvoice(0,100,"2047-01-01T12:00:00");
        dashboardLine(id,UUID.randomUUID(),60,true,null);
        dashboardLine(id,UUID.randomUUID(),40,false,null);
        dashboardReceipt(id,"CASH",40,null);dashboardReceipt(id,"VNPAY",60,null);
        jdbc.update("insert into payment_transactions(id,invoice_id,payment_method,status,amount,source,created_at) values (?, ?, 'CASH', 'FAILED', 999, 'MANUAL', CURRENT_TIMESTAMP)",UUID.randomUUID(),id);
        var d=breakdown.dashboard(java.time.LocalDate.of(2047,1,1),java.time.LocalDate.of(2047,1,1));
        org.assertj.core.api.Assertions.assertThat(d.totalRevenue()).isEqualByComparingTo("100");
        org.assertj.core.api.Assertions.assertThat(d.invoiceCount()).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(d.growthPercent()).isNull();
        org.assertj.core.api.Assertions.assertThat(d.payments().get(0).amount()).isEqualByComparingTo("40");
        org.assertj.core.api.Assertions.assertThat(d.payments().get(1).amount()).isEqualByComparingTo("60");
        org.assertj.core.api.Assertions.assertThat(d.paymentUnallocated()).isZero();
        org.assertj.core.api.Assertions.assertThat(d.composition().get(1).amount()).isEqualByComparingTo("60");
        org.assertj.core.api.Assertions.assertThat(d.composition().get(3).amount()).isEqualByComparingTo("40");
        org.assertj.core.api.Assertions.assertThat(d.warnings()).isNotEmpty();
        UUID missing=dashboardInvoice(0,50,"2047-01-01T13:00:00");
        dashboardReceipt(missing,"CASH",40,null);
        var incomplete=breakdown.dashboard(d.from(),d.to());
        org.assertj.core.api.Assertions.assertThat(incomplete.totalRevenue()).isEqualByComparingTo("150");
        org.assertj.core.api.Assertions.assertThat(incomplete.paymentUnallocated()).isEqualByComparingTo("50");
        org.assertj.core.api.Assertions.assertThat(incomplete.payments().get(0).amount()).isEqualByComparingTo("40");
    }

    @Test
    void dashboardDoesNotDuplicateBookingPrepaymentOrCourtSlotsForSubInvoices() {
        UUID user=UUID.randomUUID(),customer=UUID.randomUUID(),booking=UUID.randomUUID(),court=UUID.randomUUID(),slot=UUID.randomUUID();
        jdbc.update("insert into users(id,email,password_hash,full_name,phone,created_at,updated_at) values (?,?,'test','Dashboard','0900000000',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",user,user+"@test.local");
        jdbc.update("insert into customers(id,user_id,full_name,phone,created_at,updated_at) values (?,?,'Dashboard','0900000000',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",customer,user);
        jdbc.update("insert into bookings(id,customer_id,status,court_fee,discount_amount,created_at,updated_at) values (?,?,'COMPLETED',80,0,'2048-01-01','2048-01-01')",booking,customer);
        jdbc.update("insert into courts(id,court_code,name,type,status,base_price,created_at,updated_at) values (?,99999,'Dashboard court','STANDARD_MAT','AVAILABLE',80,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",court);
        jdbc.update("insert into time_slots(id,start_time,end_time,price_multiplier,created_at,updated_at) values (?,'08:00','09:00',1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",slot);
        jdbc.update("insert into booking_details(id,booking_id,court_id,time_slot_id,booking_date,price,created_at) values (?,?,?,?,'2048-01-01',80,'2048-01-01')",UUID.randomUUID(),booking,court,slot);
        UUID main=dashboardInvoice(80,20,"2048-01-01T10:00:00"),child=dashboardInvoice(0,10,"2048-01-01T11:00:00");
        jdbc.update("update invoices set booking_id=? where id in (?,?)",booking,main,child);
        jdbc.update("update invoices set parent_invoice_id=? where id=?",main,child);
        dashboardLine(main,UUID.randomUUID(),20,true,null);dashboardLine(child,UUID.randomUUID(),10,true,null);
        jdbc.update("insert into payment_transactions(id,booking_id,payment_method,status,amount,source,created_at) values (?,?,'VNPAY','SUCCESS',80,'GATEWAY','2048-01-01')",UUID.randomUUID(),booking);
        dashboardReceipt(main,"CASH",20,null);dashboardReceipt(child,"CASH",10,null);
        jdbc.update("update courts set base_price=999999 where id=?",court);
        var d=breakdown.dashboard(java.time.LocalDate.of(2048,1,1),java.time.LocalDate.of(2048,1,1));
        org.assertj.core.api.Assertions.assertThat(d.totalRevenue()).isEqualByComparingTo("110");
        org.assertj.core.api.Assertions.assertThat(d.invoiceCount()).isEqualTo(2);
        org.assertj.core.api.Assertions.assertThat(d.topCourts()).singleElement().satisfies(r->org.assertj.core.api.Assertions.assertThat(r.amount()).isEqualByComparingTo("80"));
        org.assertj.core.api.Assertions.assertThat(d.composition().stream().map(r->r.amount()).reduce(java.math.BigDecimal.ZERO,java.math.BigDecimal::add)).isEqualByComparingTo("110");
        org.assertj.core.api.Assertions.assertThat(d.payments().get(0).amount()).isEqualByComparingTo("30");
        org.assertj.core.api.Assertions.assertThat(d.payments().get(1).amount()).isEqualByComparingTo("80");
    }

    @Test
    void dashboardFillsEmptyMonthsValidatesDatesAndRejectsStaffAndCustomer() throws Exception {
        var d=breakdown.dashboard(java.time.LocalDate.of(2050,1,15),java.time.LocalDate.of(2050,4,2));
        org.assertj.core.api.Assertions.assertThat(d.trend()).hasSize(4);
        org.assertj.core.api.Assertions.assertThat(d.invoiceCount()).isZero();
        org.assertj.core.api.Assertions.assertThat(d.totalRevenue()).isZero();
        org.assertj.core.api.Assertions.assertThat(d.averageInvoice()).isZero();
        org.assertj.core.api.Assertions.assertThat(d.growthPercent()).isNull();
        for(String role:new String[]{"STAFF","CUSTOMER"})mvc.perform(get("/analytics/revenue-dashboard")
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("reader").roles(role))
                .param("from","2050-01-01").param("to","2050-01-01")).andExpect(status().isForbidden());
        mvc.perform(get("/analytics/revenue-dashboard").param("from","2050-02-01").param("to","2050-01-01")).andExpect(status().isBadRequest());
        mvc.perform(get("/analytics/revenue-dashboard").param("from","2050-01-01")).andExpect(status().isBadRequest());
        mvc.perform(get("/analytics/revenue-dashboard").param("from","2000-01-01").param("to","2050-01-01")).andExpect(status().isBadRequest());
    }

    @Test
    void dashboardGrowthHandlesDecreaseAndZeroAndKeepsKpisWhenAllocationIsUnavailable() {
        dashboardInvoice(0,100,"2051-01-01T12:00:00");
        UUID current=dashboardInvoice(0,50,"2051-01-02T12:00:00");
        var d=breakdown.dashboard(java.time.LocalDate.of(2051,1,2),java.time.LocalDate.of(2051,1,2));
        org.assertj.core.api.Assertions.assertThat(d.growthPercent()).isEqualByComparingTo("-50");
        jdbc.update("update invoices set total_amount=200 where id=?",current);
        var inconsistent=breakdown.dashboard(d.from(),d.to());
        org.assertj.core.api.Assertions.assertThat(inconsistent.totalRevenue()).isEqualByComparingTo("200");
        org.assertj.core.api.Assertions.assertThat(inconsistent.composition()).isEmpty();
        org.assertj.core.api.Assertions.assertThat(inconsistent.topCourts()).isEmpty();
        org.assertj.core.api.Assertions.assertThat(inconsistent.warnings()).isNotEmpty();
        jdbc.update("update invoices set status='DRAFT' where id=?",current);
        var zero=breakdown.dashboard(d.from(),d.to());
        org.assertj.core.api.Assertions.assertThat(zero.growthPercent()).isEqualByComparingTo("-100");
    }

    @Test void historicalSnapshotClassificationSurvivesProductTypeChanges() {
        UUID category=UUID.randomUUID(),goods=UUID.randomUUID(),service=UUID.randomUUID();
        jdbc.update("insert into categories(id,category_name,created_at,updated_at) values (?,'Snapshot',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",category);
        for(UUID id:java.util.List.of(goods,service))jdbc.update("insert into products(id,category_id,name,type,price,unit,stock_quantity,version,created_at,updated_at) values (?,?,'Snapshot','GOODS',999,'unit',0,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",id,category);
        UUID invoice=dashboardInvoice(0,100,"2052-01-01T12:00:00");
        dashboardLine(invoice,goods,60,false,null);dashboardLine(invoice,service,40,false,null);
        jdbc.update("update invoice_details set product_type=case when product_id=? then 'GOODS' else 'SERVICE' end where invoice_id=?",goods,invoice);
        var from=java.time.LocalDate.of(2052,1,1);var before=breakdown.dashboard(from,from);
        jdbc.update("update products set type=case when id=? then 'SERVICE' else 'GOODS' end where id in (?,?)",goods,goods,service);
        var after=breakdown.dashboard(from,from);
        org.assertj.core.api.Assertions.assertThat(after.composition()).isEqualTo(before.composition());
        org.assertj.core.api.Assertions.assertThat(after.composition()).hasSize(3);
        org.assertj.core.api.Assertions.assertThat(after.composition().get(1).amount()).isEqualByComparingTo("60");
        org.assertj.core.api.Assertions.assertThat(after.composition().get(2).amount()).isEqualByComparingTo("40");
    }

    private UUID dashboardInvoice(int court,int product,String created) {
        UUID id=UUID.randomUUID();
        jdbc.update("insert into invoices(id,customer_id,status,payment_method,court_fee,product_fee,discount_amount,total_amount,created_at,updated_at) values (?,?,'PAID','CASH',?,?,0,?,?,?)",id,UUID.randomUUID(),court,product,court+product,LocalDateTime.parse(created),LocalDateTime.parse(created));
        return id;
    }
    private void dashboardLine(UUID invoice,UUID product,int amount,boolean deducted,String note) {
        jdbc.update("insert into invoice_details(id,invoice_id,product_id,quantity,unit_price,discount_amount,discount_percent,stock_deducted,note,created_at) values (?,?,?,1,?,0,0,?,?,CURRENT_TIMESTAMP)",UUID.randomUUID(),invoice,product,amount,deducted,note);
    }
    private void dashboardReceipt(UUID invoice,String method,int amount,String reference) {
        jdbc.update("insert into payment_transactions(id,invoice_id,payment_method,status,amount,source,created_at,reference) values (?,?,?,'SUCCESS',?,'MANUAL',CURRENT_TIMESTAMP,?)",UUID.randomUUID(),invoice,method,amount,reference);
    }

    private void invoice(String status, int court, int product, String created, boolean deleted) {
        jdbc.update("insert into invoices(id,customer_id,status,payment_method,court_fee,product_fee,"
                        + "discount_amount,total_amount,created_at,updated_at,deleted_at) values (?,?,?,?,?,?,?,?,?,?,?)",
                UUID.randomUUID(), UUID.randomUUID(), status, "CASH", court, product,
                0, court + product, LocalDateTime.parse(created), LocalDateTime.parse(created),
                deleted ? LocalDateTime.parse(created) : null);
    }
}
