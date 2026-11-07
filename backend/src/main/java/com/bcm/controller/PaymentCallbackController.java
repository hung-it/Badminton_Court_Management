package com.bcm.controller;

import com.bcm.dto.response.VnPayIpnAcknowledgment;
import com.bcm.service.PaymentCallbackService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
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
