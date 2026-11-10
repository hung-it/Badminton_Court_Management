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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.util.UriComponentsBuilder;
import java.net.URI;

@RestController
@RequestMapping("/payments/vnpay")
@RequiredArgsConstructor
@Tag(name = "Booking payment callbacks")
public class VnPayReturnController {
    private final VnPayIpnVerifier verifier;
    @Value("${booking.payment.frontend-result-url:}")
    private String frontendResultUrl = "";

    @GetMapping("/return")
    @SecurityRequirements
    @Operation(summary = "Verify browser return information without settling payment",
            description = "Public browser redirect: no application bearer token required. Display-only cryptographically verified provider information; providerSuccess is not database payment status. "
                    + "Only IPN can transition SUCCESS/PAID. This endpoint never reads or writes the database.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Verified display information, not merchant settlement status"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "303", description = "Verified browser return redirected to configured /payment-result; no settlement"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid or unverifiable browser return", content = @io.swagger.v3.oas.annotations.media.Content(schema = @io.swagger.v3.oas.annotations.media.Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "503", description = "VNPay verification configuration unavailable", content = @io.swagger.v3.oas.annotations.media.Content(schema = @io.swagger.v3.oas.annotations.media.Schema(implementation = ApiResponse.class)))
    })
    public ResponseEntity<ApiResponse<PaymentNotification>> browserReturn(@RequestParam MultiValueMap<String, String> parameters,
                                           @RequestParam(defaultValue = "false") boolean displayOnly) {
        var fields = PaymentCallbackController.singleValues(parameters);
        if (fields == null) { return ResponseEntity.badRequest().body(ApiResponse.error("Invalid payment return")); }
        try {
            var verified = verifier.verify(fields);
            if (!displayOnly && !frontendResultUrl.isBlank()) {
                var target = URI.create(frontendResultUrl);
                if (target.getHost() == null || target.getUserInfo() != null || target.getFragment() != null
                        || target.getQuery() != null || !("https".equals(target.getScheme()) || "http".equals(target.getScheme()))
                        || !"/payment-result".equals(target.getPath())) {
                    return ResponseEntity.status(503).body(ApiResponse.error("Payment result URL is unavailable"));
                }
                var redirect = UriComponentsBuilder.fromUri(target);
                fields.forEach(redirect::queryParam);
                return ResponseEntity.status(HttpStatus.SEE_OTHER).location(redirect.build().encode().toUri()).build();
            }
            return ResponseEntity.ok(ApiResponse.success(verified));
        } catch (PaymentNotificationException ex) {
            int status = ex.getReason() == PaymentNotificationException.Reason.UNAVAILABLE ? 503 : 400;
            return ResponseEntity.status(status).body(ApiResponse.error("Payment return could not be verified"));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.status(503).body(ApiResponse.error("Payment result URL is unavailable"));
        }
    }
}
