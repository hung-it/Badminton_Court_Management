package com.bcm.service;

import com.bcm.entity.PaymentMethod;
import com.bcm.repository.PaymentTransactionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.time.LocalDate;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/** Opt-in: reviewed deployment backup restored to a dedicated rehearsal database. */
@SpringBootTest(properties={"booking.auto-checkout.enabled=false","booking.expiration-enabled=false","bcm.seed.enabled=false"})
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named="BCM_LEGACY_TEST_DB_URL",matches="jdbc:postgresql:.*bcm_deploy_rehearsal.*")
class LegacyPaymentCompatibilityPostgresTest {
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",()->System.getenv("BCM_LEGACY_TEST_DB_URL"));
        r.add("spring.datasource.username",()->System.getenv("BCM_TEST_DB_USERNAME"));
        r.add("spring.datasource.password",()->System.getenv("BCM_TEST_DB_PASSWORD"));
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired PaymentTransactionRepository payments;
    @Autowired AnalyticsService analytics;
    @Autowired PaymentGatewayPreparation gateway;
    @Autowired PosService pos;
    @Autowired org.springframework.test.web.servlet.MockMvc mvc;

    @Test void readsReviewedHistoryAndRevenueWithoutInventingReceiptActors() {
        var ids=jdbc.queryForList("SELECT id FROM payment_transactions WHERE payment_method='BANK_TRANSFER'",UUID.class);
        assertThat(ids).hasSize(5);
        for(var id:ids){var p=payments.findById(id).orElseThrow();assertThat(p.getPaymentMethod()).isEqualTo(PaymentMethod.BANK_TRANSFER);assertThat(p.getSource()).isEqualTo("LEGACY");}
        var invoices=jdbc.queryForList("SELECT id FROM invoices WHERE payment_method='BANK_TRANSFER'",UUID.class);
        assertThat(invoices).hasSize(3);
        for(var id:invoices){
            var report=analytics.invoice(id);
            assertThat(report.invoice().paymentMethod()).isEqualTo("BANK_TRANSFER");
            assertThat(report.receipts().stream().filter(r->r.source().equals("LEGACY")).map(r->r.actorName())).allMatch(name->!name.contains("Gateway"));
        }
        assertThat(analytics.getRevenue("MONTH",null,LocalDate.of(2026,1,1),LocalDate.of(2026,12,31)).getTotalAmount()).isEqualByComparingTo(
                jdbc.queryForObject("SELECT sum(total_amount) FROM invoices WHERE status='PAID' AND deleted_at IS NULL AND created_at>='2026-01-01' AND created_at<'2027-01-01'",java.math.BigDecimal.class));
        assertThat(analytics.history("MONTH",null,0,LocalDate.of(2026,1,1),LocalDate.of(2026,12,31)).invoices()).isNotEmpty();
    }

    @Test void sqlCannotCreateModifyDeleteOrRelabelHistoricalTransfers() {
        var id=jdbc.queryForObject("SELECT id FROM payment_transactions WHERE payment_method='BANK_TRANSFER' LIMIT 1",UUID.class);
        assertThatThrownBy(()->jdbc.update("UPDATE payment_transactions SET amount=amount+1 WHERE id=?",id)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.update("DELETE FROM payment_transactions WHERE id=?",id)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.update("INSERT INTO payment_transactions(id,booking_id,payment_method,status,amount,source) SELECT ?,booking_id,'BANK_TRANSFER','SUCCESS',amount,'LEGACY' FROM payment_transactions WHERE id=?",UUID.randomUUID(),id)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        var invoice=jdbc.queryForObject("SELECT id FROM invoices WHERE payment_method='BANK_TRANSFER' LIMIT 1",UUID.class);
        assertThatThrownBy(()->jdbc.update("UPDATE invoices SET payment_method='CASH' WHERE id=?",invoice)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.update("DELETE FROM invoices WHERE id=?",invoice)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        var cash=jdbc.queryForObject("SELECT id FROM invoices WHERE payment_method='CASH' LIMIT 1",UUID.class);
        assertThatThrownBy(()->jdbc.update("UPDATE invoices SET payment_method='BANK_TRANSFER' WHERE id=?",cash)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("SELECT sum(amount) FROM payment_transactions WHERE payment_method='BANK_TRANSFER'",java.math.BigDecimal.class)).isEqualByComparingTo("1260000");
    }

    @Test void legacyEnumDoesNotReopenAnyNewBankTransferPayment() throws Exception {
        assertThatThrownBy(()->gateway.validateMethod(PaymentMethod.BANK_TRANSFER)).hasMessageContaining("only support VNPAY");
        assertThatThrownBy(()->pos.manualMethod("BANK_TRANSFER")).hasMessageContaining("CASH or VNPAY");
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/pos/bookings/"+UUID.randomUUID()+"/receive").contextPath("/api")
            .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("staff").roles("STAFF"))
            .contentType("application/json").content("{\"paymentMethod\":\"BANK_TRANSFER\",\"paymentConfirmed\":true}"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
    }
}
