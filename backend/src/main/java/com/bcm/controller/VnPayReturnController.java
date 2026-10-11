package com.bcm.controller;

import com.bcm.dto.response.ApiResponse;
import com.bcm.exception.PaymentNotificationException;
import com.bcm.service.PaymentNotification;
import com.bcm.service.VnPayIpnVerifier;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/payments/vnpay")
@RequiredArgsConstructor
@Tag(name = "Booking payment callbacks")
public class VnPayReturnController {
    private final VnPayIpnVerifier verifier;

    @GetMapping("/return")
    @SecurityRequirements
    @Operation(summary = "Verify browser return information without settling payment",
            description = "Public browser redirect: no application bearer token required. Display-only cryptographically verified provider information; providerSuccess is not database payment status. "
                    + "Only IPN can transition SUCCESS/PAID. This endpoint never reads or writes the database.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Verified display information, not merchant settlement status"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid or unverifiable browser return", content = @io.swagger.v3.oas.annotations.media.Content(schema = @io.swagger.v3.oas.annotations.media.Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "503", description = "VNPay verification configuration unavailable", content = @io.swagger.v3.oas.annotations.media.Content(schema = @io.swagger.v3.oas.annotations.media.Schema(implementation = ApiResponse.class)))
    })
    public ResponseEntity<ApiResponse<PaymentNotification>> browserReturn(@RequestParam MultiValueMap<String, String> parameters) {
        var fields = PaymentCallbackController.singleValues(parameters);
        if (fields == null) { return ResponseEntity.badRequest().body(ApiResponse.error("Invalid payment return")); }
        try {
            return ResponseEntity.ok(ApiResponse.success(verifier.verify(fields)));
        } catch (PaymentNotificationException ex) {
            int status = ex.getReason() == PaymentNotificationException.Reason.UNAVAILABLE ? 503 : 400;
            return ResponseEntity.status(status).body(ApiResponse.error("Payment return could not be verified"));
        }
    }
}
