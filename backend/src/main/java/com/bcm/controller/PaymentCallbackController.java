package com.bcm.controller;

import com.bcm.dto.response.VnPayIpnAcknowledgment;
import com.bcm.service.PaymentCallbackService;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/payments")
@RequiredArgsConstructor
@SecurityRequirements
@Tag(name = "Booking payment callbacks")
public class PaymentCallbackController {
    private final PaymentCallbackService service;

    @GetMapping(value = "/vnpay/ipn", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "VNPay v2.1.0 authoritative IPN", description = "Provider-facing: no application bearer token required. Checksum then exact attempt/method/amount validation; "
            + "only both status codes 00 may settle. JSON RspCode/Message after commit; no ApiResponse wrapper. "
            + "Same confirmed replay returns 02. Late/non-payable/conflicting results return 99 with a domain reason; "
            + "no dedicated VNPay late-payment code exists, and unsettled results must not claim 00/02. "
            + "Booking then Payment row locks serialize concurrent callbacks with expiration.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "Provider JSON only: 00 handled success/non-success, 01 unknown, 02 confirmed replay, "
                            + "04 amount mismatch, 97 invalid checksum, 99 domain conflict/late/nonpayable/system error")
    })
    public VnPayIpnAcknowledgment vnpay(@RequestParam MultiValueMap<String, String> parameters) {
        var fields = singleValues(parameters);
        return fields == null ? VnPayIpnAcknowledgment.of("99") : service.vnpay(fields);
    }

    @PostMapping(value = "/momo/ipn", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "MoMo captureWallet authoritative payment notification", description = "Provider-facing: no application bearer token required. HMAC-SHA256 notification "
            + "verification independent of CREATE response blocker; resultCode=0 may settle exact orderId=requestId attempt. "
            + "Handled results (including replay, late/non-payable and confirmed-data/transaction-ID conflicts) "
            + "return HTTP 204 with no body after DB outcome is known; this is transport acknowledgment, not settlement. "
            + "Invalid data/signature/correlation use local defensive empty 400/404; unavailable/system failure 503/500. "
            + "Provider docs do not define a rejection HTTP taxonomy. Internal business outcomes are logged without payload. "
            + "Signed raw field order: accessKey (config), amount, extraData, message, orderId, orderInfo, orderType, "
            + "partnerCode, payType, requestId, responseTime, resultCode, transId. Payload also includes signature. "
            + "Numeric fields are exact JSON integers; orderType=momo_wallet. "
            + "responseTime is notification time, so transactionDate stays null.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "Handled notification; empty transport acknowledgment, not a settlement claim", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid data/signature/amount or malformed JSON; empty body", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Unknown attempt or wrong provider/target; empty body", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "503", description = "Verification configuration unavailable; empty body", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "Unhandled system failure; empty body", content = @Content)
    })
    public ResponseEntity<Void> momo(@RequestBody(required = false) JsonNode body) {
        int status = switch (service.momo(body)) {
            case SUCCESS, ALREADY_CONFIRMED, IGNORED, LATE_EXPIRED, NOT_PAYABLE, CONFLICT, TRANSACTION_ID_CONFLICT -> 204;
            case NOT_FOUND -> 404;
            case INVALID_AMOUNT, INVALID_SIGNATURE, INVALID_DATA -> 400;
            case UNAVAILABLE -> 503;
            case SYSTEM_ERROR -> 500;
        };
        return ResponseEntity.status(status).build();
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Void> malformedJson() { return ResponseEntity.badRequest().build(); }

    static Map<String, String> singleValues(MultiValueMap<String, String> parameters) {
        var fields = new HashMap<String, String>();
        for (var entry : parameters.entrySet()) {
            if (!entry.getKey().startsWith("vnp_")) { continue; }
            if (entry.getValue().size() != 1) { return null; }
            fields.put(entry.getKey(), entry.getValue().get(0));
        }
        return fields;
    }
}
