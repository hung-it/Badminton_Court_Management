package com.bcm.service;

import com.bcm.controller.PaymentCallbackController;
import com.bcm.controller.VnPayReturnController;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.time.Clock;
import java.time.ZoneOffset;
import static com.bcm.service.PaymentCallbackFixtures.*;
import static com.bcm.service.PaymentCallbackTransactionService.Result.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class PaymentCallbackControllerTest {
    private final com.bcm.config.BookingPaymentConfig config = config();
    private final PaymentCallbackTransactionService transactions = mock(PaymentCallbackTransactionService.class);
    private final VnPayIpnVerifier verifier = new VnPayIpnVerifier(config, Clock.system(ZoneOffset.UTC));
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        var service = new PaymentCallbackService(verifier, new MoMoIpnVerifier(config), transactions);
        mvc = MockMvcBuilders.standaloneSetup(new PaymentCallbackController(service), new VnPayReturnController(verifier)).build();
        when(transactions.resolve(any())).thenReturn(new PaymentCallbackTransactionService.Resolution(ATTEMPT, null));
        when(transactions.settle(any(), any())).thenReturn(SUCCESS);
    }

    @ParameterizedTest
    @CsvSource({"SUCCESS,00,Confirm Success", "ALREADY_CONFIRMED,02,Order already confirmed", "NOT_FOUND,01,Order not found",
            "INVALID_AMOUNT,04,invalid amount", "LATE_EXPIRED,99,Booking expired or payment deadline passed",
            "NOT_PAYABLE,99,Booking or payment is not payable", "CONFLICT,99,Conflicting payment confirmation",
            "TRANSACTION_ID_CONFLICT,99,Transaction ID belongs to another payment", "IGNORED,00,Confirm Success"})
    void exactProviderAcknowledgment(PaymentCallbackTransactionService.Result result, String code, String message) throws Exception {
        when(transactions.settle(any(), any())).thenReturn(result);
        var request = get("/payments/vnpay/ipn");
        vnpay(ATTEMPT, config).forEach(request::param);
        mvc.perform(request).andExpect(status().isOk())
                .andExpect(content().json("{\"RspCode\":\"" + code + "\",\"Message\":\"" + message + "\"}", true));
    }

    @Test
    void invalidSignatureReturns97AndNeverTouchesDb() throws Exception {
        var fields = vnpay(ATTEMPT, config);
        fields.put("vnp_Amount", "1");
        var request = get("/payments/vnpay/ipn");
        fields.forEach(request::param);
        mvc.perform(request).andExpect(status().isOk())
                .andExpect(content().json("{\"RspCode\":\"97\",\"Message\":\"Invalid signature\"}", true));
        verifyNoInteractions(transactions);
    }

    @Test
    void commitFailureReturns99() throws Exception {
        when(transactions.settle(any(), any())).thenThrow(new DataIntegrityViolationException("test unique violation"));
        var request = get("/payments/vnpay/ipn");
        vnpay(ATTEMPT, config).forEach(request::param);
        mvc.perform(request).andExpect(status().isOk())
                .andExpect(content().json("{\"RspCode\":\"99\",\"Message\":\"Unknow error\"}", true));
    }

    @Test
    void duplicateQueryFieldsRejectedBeforeVerificationOrLookup() throws Exception {
        var request = get("/payments/vnpay/ipn");
        vnpay(ATTEMPT, config).forEach(request::param);
        request.param("vnp_Amount", "1");
        mvc.perform(request).andExpect(jsonPath("$.RspCode").value("99"));
        verifyNoInteractions(transactions);
    }

    @Test
    void signedReturnNeverCallsSettlementOrReadsDb() throws Exception {
        var request = get("/payments/vnpay/return");
        vnpay(ATTEMPT, config).forEach(request::param);
        mvc.perform(request).andExpect(status().isOk()).andExpect(jsonPath("$.data.providerSuccess").value(true));
        verifyNoInteractions(transactions);
    }

    @Test
    void invalidReturnRejected() throws Exception {
        mvc.perform(get("/payments/vnpay/return")).andExpect(status().isBadRequest());
        verifyNoInteractions(transactions);
    }

    @Test
    void validMomoIpnAcknowledged204WithEmptyBody() throws Exception {
        mvc.perform(post("/payments/momo/ipn").contentType(MediaType.APPLICATION_JSON)
                .content(new ObjectMapper().writeValueAsBytes(momo(ATTEMPT, config))))
                .andExpect(status().isNoContent()).andExpect(content().string(""));
    }

    @Test
    void invalidOrMalformedMomoDoesNotUseApiResponse() throws Exception {
        mvc.perform(post("/payments/momo/ipn").contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest()).andExpect(content().string(""));
        mvc.perform(post("/payments/momo/ipn").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest()).andExpect(content().string(""));
        verifyNoInteractions(transactions);
    }

    @Test
    void momoPersistenceFailureIsNotAcknowledgedAsHandled() throws Exception {
        when(transactions.settle(any(), any())).thenThrow(new DataIntegrityViolationException("test failure"));
        mvc.perform(post("/payments/momo/ipn").contentType(MediaType.APPLICATION_JSON)
                .content(new ObjectMapper().writeValueAsBytes(momo(ATTEMPT, config))))
                .andExpect(status().isInternalServerError()).andExpect(content().string(""));
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = PaymentCallbackTransactionService.Result.class,
            names = {"SUCCESS", "ALREADY_CONFIRMED", "IGNORED", "LATE_EXPIRED", "NOT_PAYABLE", "CONFLICT", "TRANSACTION_ID_CONFLICT"})
    void momoTransportAcknowledgesHandledBusinessOutcomesWithoutClaimingSettlement(PaymentCallbackTransactionService.Result result)
            throws Exception {
        when(transactions.settle(any(), any())).thenReturn(result);
        mvc.perform(post("/payments/momo/ipn").contentType(MediaType.APPLICATION_JSON)
                .content(new ObjectMapper().writeValueAsBytes(momo(ATTEMPT, config))))
                .andExpect(status().isNoContent()).andExpect(content().string(""));
    }

    @Test
    void realTransactionIdConstraintFailureIsHandledOnlyAfterRollback() throws Exception {
        var sql = new java.sql.SQLException("duplicate key violates unique constraint \"uq_transaction_id\"", "23505");
        when(transactions.settle(any(), any())).thenThrow(new DataIntegrityViolationException("test-only", sql));
        var service = new PaymentCallbackService(verifier, new MoMoIpnVerifier(config), transactions);
        org.assertj.core.api.Assertions.assertThat(service.momo(momo(ATTEMPT, config))).isEqualTo(TRANSACTION_ID_CONFLICT);
        mvc.perform(post("/payments/momo/ipn").contentType(MediaType.APPLICATION_JSON)
                .content(new ObjectMapper().writeValueAsBytes(momo(ATTEMPT, config))))
                .andExpect(status().isNoContent()).andExpect(content().string(""));
        var request = get("/payments/vnpay/ipn"); vnpay(ATTEMPT, config).forEach(request::param);
        mvc.perform(request).andExpect(content().json("{\"RspCode\":\"99\",\"Message\":\"Transaction ID belongs to another payment\"}", true));
    }

    @Test
    void unrelatedUniqueConstraintIsStillSystemFailure() throws Exception {
        var sql = new java.sql.SQLException("duplicate key violates unique constraint \"another_constraint\"", "23505");
        when(transactions.settle(any(), any())).thenThrow(new DataIntegrityViolationException("test-only", sql));
        mvc.perform(post("/payments/momo/ipn").contentType(MediaType.APPLICATION_JSON)
                .content(new ObjectMapper().writeValueAsBytes(momo(ATTEMPT, config))))
                .andExpect(status().isInternalServerError()).andExpect(content().string(""));
    }
}
