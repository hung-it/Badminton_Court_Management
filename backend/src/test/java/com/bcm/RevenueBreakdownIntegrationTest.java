package com.bcm;

import com.bcm.service.RevenueBreakdownService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("h2")
@Transactional
@WithMockUser(roles = "ADMIN")
class RevenueBreakdownIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired RevenueBreakdownService reports;

    @BeforeEach
    void addStaffUserMappingForH2() {
        jdbc.execute("ALTER TABLE staffs ADD COLUMN IF NOT EXISTS user_id UUID");
        jdbc.execute("ALTER TABLE staffs ADD COLUMN IF NOT EXISTS full_name VARCHAR(255)");
        jdbc.execute("ALTER TABLE staffs ADD COLUMN IF NOT EXISTS phone VARCHAR(20)");
        jdbc.execute("ALTER TABLE staffs ADD COLUMN IF NOT EXISTS position VARCHAR(100)");
        jdbc.execute("ALTER TABLE staffs ADD COLUMN IF NOT EXISTS created_at TIMESTAMP");
        jdbc.execute("ALTER TABLE staffs ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP");
        jdbc.execute("ALTER TABLE staffs ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMP");
    }

    @Test
    void aggregatesInvoicesAndProductLinesByQuarterAndYear() throws Exception {
        UUID user = user("report-staff@example.test");
        UUID staff = staff(user, "Cashier One");
        UUID customerUser = user("report-customer@example.test");
        UUID customer = customer(customerUser, "Customer One");
        UUID deletedCustomerUser = user("report-deleted-customer@example.test");
        UUID deletedCustomer = customer(deletedCustomerUser, "Deleted Customer");
        jdbc.update("UPDATE customers SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?", deletedCustomer);
        UUID product = product("Badminton racket");

        // Q1: 100.000 court fee + 30.000 product line, no discount.
        invoice(customer, staff, "PAID", 100000, 30000, 0, 130000, "2040-03-31T23:59:59", false);
        // Q2: 200.000 court fee + 30.000 product line with a 5.000 line discount.
        UUID invoiceId = invoice(customer, staff, "PAID", 200000, 30000, 5000, 225000, "2040-04-01T00:00:00", false);
        invoice(customer, staff, "DRAFT", 500000, 500000, 0, 1000000, "2040-04-02T00:00:00", false);
        invoice(customer, staff, "PAID", 700000, 700000, 0, 1400000, "2040-04-03T00:00:00", true);
        invoice(deletedCustomer, null, "PAID", 40000, 0, 0, 40000, "2040-05-01T00:00:00", false);
        line(invoiceId, product, 3, 10000, 5000);

        mvc.perform(get("/analytics/revenue-breakdown").param("period", "QUARTER").param("year", "2040"))
                .andExpect(status().isOk());
        var quarter = reports.report("QUARTER", 2040);
        assertThat(quarter.periods()).hasSize(4);
        assertThat(quarter.staff()).filteredOn(row -> staff.equals(row.staffId())).singleElement()
                .satisfies(row -> {
                    assertThat(row.values().get("Q1/2040")).isEqualByComparingTo("130000.00");
                    assertThat(row.values().get("Q2/2040")).isEqualByComparingTo("225000.00");
                });
        assertThat(quarter.customers()).filteredOn(row -> customer.equals(row.customerId())).singleElement()
                .satisfies(row -> assertThat(row.total()).isEqualByComparingTo("355000.00"));
        assertThat(quarter.products()).filteredOn(row -> row.productId().equals(product)).singleElement()
                .satisfies(row -> {
                    assertThat(row.totalQuantity()).isEqualByComparingTo("3");
                    // The 5.000 line discount is already deducted from the allocated product value.
                    assertThat(row.total()).isEqualByComparingTo("29347.83");
                });
        assertThat(quarter.staff()).filteredOn(row -> row.staffId() == null).singleElement()
                .satisfies(row -> {
                    assertThat(row.staffName()).isEqualTo("Chưa gán nhân viên");
                    assertThat(row.total()).isEqualByComparingTo("40000.00");
                });
        assertThat(quarter.customers()).filteredOn(row -> row.customerId() == null).singleElement()
                .satisfies(row -> {
                    assertThat(row.customerName()).isEqualTo("Khách vãng lai");
                    assertThat(row.total()).isEqualByComparingTo("40000.00");
                });
        assertThat(reports.report("YEAR", 2040).staff()).filteredOn(row -> staff.equals(row.staffId()))
                .singleElement().satisfies(row -> assertThat(row.total()).isEqualByComparingTo("355000.00"));

        var empty = reports.report("QUARTER", 2041);
        assertThat(empty.staff()).isEmpty();
        assertThat(empty.customers()).isEmpty();
        assertThat(empty.products()).isEmpty();
    }

    @Test
    void splitsInvoiceTotalBetweenProductsAndCourtsSoBothSidesMatch() throws Exception {
        UUID user = user("split-staff@example.test");
        UUID staff = staff(user, "Cashier Split");
        UUID customer = customer(user("split-customer@example.test"), "Customer Split");
        UUID product = product("Split product");

        // 120.000 court fee + 3 x 20.000 product lines (60.000) minus a 10.000 invoice discount.
        UUID invoiceId = invoice(customer, staff, "PAID", 120000, 60000, 10000, 170000,
                "2041-02-10T10:00:00", false);
        line(invoiceId, product, 3, 20000, 0);

        var report = reports.report("QUARTER", 2041, null, true);
        var detail = report.details().stream().filter(d -> d.periodKey().equals("Q1/2041")).findFirst().orElseThrow();

        assertThat(detail.invoiceTotal()).isEqualByComparingTo("170000.00");
        assertThat(detail.productTotal()).isEqualByComparingTo("56666.67");
        assertThat(detail.courtTotal()).isEqualByComparingTo("113333.33");
        assertThat(detail.productTotal().add(detail.courtTotal())).isEqualByComparingTo(detail.invoiceTotal());
        assertThat(detail.products()).singleElement().satisfies(row -> {
            assertThat(row.productName()).isEqualTo("Split product");
            assertThat(row.staffName()).isEqualTo("Cashier Split");
            assertThat(row.customerName()).isEqualTo("Customer Split");
            assertThat(row.totalQuantity()).isEqualByComparingTo("3");
            assertThat(row.total()).isEqualByComparingTo("56666.67");
        });
    }

    @Test
    void splitsCourtFeeAcrossBookedCourtsAndKeepsUnbookedFeesSeparate() throws Exception {
        UUID user = user("court-staff@example.test");
        UUID staff = staff(user, "Cashier Court");
        UUID customer = customer(user("court-customer@example.test"), "Customer Court");
        UUID product = product("Court product");
        UUID courtOne = court("Court One");
        UUID courtTwo = court("Court Two");

        // One booking booked on two courts: 200.000 + 100.000 of booked price.
        UUID booking = booking(customer, "2041-05-01", 200000);
        bookingDetail(booking, courtOne, "2041-05-01", 120000);
        bookingDetail(booking, courtTwo, "2041-05-01", 80000);

        UUID invoiceId = invoice(customer, staff, "PAID", 200000, 50000, 50000, 200000, "2041-05-01T09:00:00", false);
        jdbc.update("UPDATE invoices SET booking_id = ? WHERE id = ?", booking, invoiceId);
        line(invoiceId, product, 1, 50000, 0);

        // A second invoice carries a court fee without any booking detail.
        UUID invoiceWithoutBooking = invoice(customer, staff, "PAID", 30000, 0, 0, 30000,
                "2041-05-02T09:00:00", false);
        jdbc.update("UPDATE invoices SET booking_id = NULL WHERE id = ?", invoiceWithoutBooking);

        var report = reports.report("QUARTER", 2041, null, true);
        var detail = report.details().stream().filter(d -> d.periodKey().equals("Q2/2041")).findFirst().orElseThrow();

        assertThat(detail.courtTotal()).isEqualByComparingTo("190000.00");
        assertThat(detail.productTotal().add(detail.courtTotal())).isEqualByComparingTo(detail.invoiceTotal());
        assertThat(detail.courts()).filteredOn(row -> courtOne.equals(row.courtId())).singleElement()
                .satisfies(row -> assertThat(row.total()).isEqualByComparingTo("96000.00"));
        assertThat(detail.courts()).filteredOn(row -> courtTwo.equals(row.courtId())).singleElement()
                .satisfies(row -> assertThat(row.total()).isEqualByComparingTo("64000.00"));
        assertThat(detail.courts()).filteredOn(row -> row.courtId() == null).singleElement()
                .satisfies(row -> {
                    assertThat(row.courtName()).isEqualTo("Không xác định sân");
                    assertThat(row.total()).isEqualByComparingTo("30000.00");
                });
    }

    @Test
    void returnsDetailsForTheSelectedQuarterOnly() throws Exception {
        UUID user = user("detail-staff@example.test");
        UUID staff = staff(user, "Cashier Detail");
        UUID customer = customer(user("detail-customer@example.test"), "Customer Detail");
        UUID product = product("Detail product");

        UUID firstQuarter = invoice(customer, staff, "PAID", 0, 40000, 0, 40000, "2042-02-01T10:00:00", false);
        line(firstQuarter, product, 2, 20000, 0);
        UUID secondQuarter = invoice(customer, staff, "PAID", 0, 60000, 0, 60000, "2042-05-01T10:00:00", false);
        line(secondQuarter, product, 3, 20000, 0);

        var report = reports.report("QUARTER", 2042, 2, true);
        assertThat(report.details()).singleElement().satisfies(detail -> {
            assertThat(detail.periodKey()).isEqualTo("Q2/2042");
            assertThat(detail.productTotal()).isEqualByComparingTo("60000.00");
        });
        assertThat(report.productDetails()).singleElement()
                .satisfies(row -> assertThat(row.total()).isEqualByComparingTo("60000.00"));

        mvc.perform(get("/analytics/revenue-breakdown").param("period", "QUARTER")
                .param("year", "2042").param("quarter", "2").param("detail", "true"))
                .andExpect(status().isOk());
    }

    @Test
    void rejectsInvalidPeriodYearAndQuarter() throws Exception {
        mvc.perform(get("/analytics/revenue-breakdown").param("period", "MONTH").param("year", "2040"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/analytics/revenue-breakdown").param("period", "YEAR").param("year", "0"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/analytics/revenue-breakdown").param("period", "QUARTER").param("year", "2040")
                .param("quarter", "5"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/analytics/revenue-breakdown").param("period", "YEAR").param("year", "2040")
                .param("quarter", "1"))
                .andExpect(status().isBadRequest());
    }

    private UUID user(String email) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO users(id,email,password_hash,full_name,phone,created_at,updated_at) VALUES (?,?,'test','Report test','0000000000',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", id, email);
        return id;
    }

    private UUID staff(UUID userId, String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO staffs(id,user_id,full_name,phone,position,created_at,updated_at) VALUES (?,?,?,'0000000000','Cashier',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", id, userId, name);
        return id;
    }

    private UUID customer(UUID userId, String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO customers(id,user_id,full_name,phone,created_at,updated_at) VALUES (?,?,?,'0000000000',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", id, userId, name);
        return id;
    }

    private UUID product(String name) {
        UUID category = UUID.randomUUID();
        jdbc.update("INSERT INTO categories(id,category_name,created_at,updated_at) VALUES (?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", category, "Report products");
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO products(id,category_id,name,type,price,unit,stock_quantity,version,created_at,updated_at) VALUES (?,?,?,'GOODS',10000,'cái',10,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", id, category, name);
        return id;
    }

    private UUID court(String name) {
        UUID id = UUID.randomUUID();
        int number = name.endsWith("One") ? 1 : 2;
        jdbc.update("INSERT INTO courts(id,court_code,court_number,name,type,status,base_price,created_at,updated_at) VALUES (?,?,?,?,'STANDARD_MAT','AVAILABLE',100000,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                id, -Math.abs(name.hashCode()) - 1, number, name);
        return id;
    }

    private UUID booking(UUID customerId, String date, int courtFee) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO bookings(id,customer_id,status,court_fee,discount_amount,created_at,updated_at,expires_at) VALUES (?,?,'PAID',?,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,?)",
                id, customerId, courtFee, LocalDateTime.parse(date + "T23:59:59"));
        return id;
    }

    private void bookingDetail(UUID bookingId, UUID courtId, String date, int price) {
        UUID slot = UUID.randomUUID();
        jdbc.update("INSERT INTO time_slots(id,start_time,end_time,price_multiplier,created_at,updated_at) VALUES (?,?,?,1.0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                slot, java.sql.Time.valueOf("06:00:00"), java.sql.Time.valueOf("07:00:00"));
        jdbc.update("INSERT INTO booking_details(id,booking_id,court_id,time_slot_id,booking_date,price,created_at) VALUES (?,?,?,?,?,?,CURRENT_TIMESTAMP)",
                UUID.randomUUID(), bookingId, courtId, slot, java.sql.Date.valueOf(date), price);
    }

    private UUID invoice(UUID customerId, UUID staffId, String state, int courtFee, int productFee, int discount,
                         int total, String at, boolean deleted) {
        UUID id = UUID.randomUUID();
        LocalDateTime created = LocalDateTime.parse(at);
        jdbc.update("INSERT INTO invoices(id,customer_id,created_by,payment_method,status,court_fee,product_fee,discount_amount,total_amount,created_at,updated_at,deleted_at) VALUES (?,?,?,'CASH',?,?,?,?,?,?,?,?)",
                id, customerId, staffId, state, courtFee, productFee, discount, total, created, created, deleted ? created : null);
        return id;
    }

    private void line(UUID invoiceId, UUID productId, int quantity, int price, int discount) {
        jdbc.update("INSERT INTO invoice_details(id,invoice_id,product_id,quantity,unit_price,discount_amount,discount_percent,created_at) VALUES (?,?,?,?,?,?,0,CURRENT_TIMESTAMP)",
                UUID.randomUUID(), invoiceId, productId, quantity, price, new BigDecimal(discount));
    }
}
