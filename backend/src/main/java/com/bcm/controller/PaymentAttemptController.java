package com.bcm.controller;

import com.bcm.dto.request.CreatePaymentAttemptRequest;
import com.bcm.dto.response.ApiResponse;
import com.bcm.dto.response.PaymentAttemptResponse;
import com.bcm.service.PaymentInitiationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/bookings/{bookingId}/payments")
@RequiredArgsConstructor
@Tag(name = "Booking payments")
public class PaymentAttemptController {
    private final PaymentInitiationService service;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @SecurityRequirement(name = "Bearer Authentication")
    @Operation(summary = "Create/reuse a PENDING attempt and initiate sandbox checkout",
            description = "CUSTOMER self-service booking payments accept VNPAY only. Unsupported payment methods return 400 before persistence. "
                    + "Ownership, expiry, promotion snapshots and same-method pending-attempt reuse remain enforced. "
                    + "Existing transfers are retained; conflicting attempts return 409 and require staff assistance. "
                    + "VNPay callback verification is required to confirm payment; browser redirects do not settle bookings.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Authentication required"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "CUSTOMER identity required or foreign booking", content = @io.swagger.v3.oas.annotations.media.Content(schema = @io.swagger.v3.oas.annotations.media.Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "PENDING attempt committed/reused; VNPay Sandbox checkout URL returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid/disabled method, invalid input or ineligible booking", content = @io.swagger.v3.oas.annotations.media.Content(schema = @io.swagger.v3.oas.annotations.media.Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Booking not found", content = @io.swagger.v3.oas.annotations.media.Content(schema = @io.swagger.v3.oas.annotations.media.Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Different method on an existing PENDING attempt or database constraint conflict", content = @io.swagger.v3.oas.annotations.media.Content(schema = @io.swagger.v3.oas.annotations.media.Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "502", description = "Provider initiation failed; committed attempt remains PENDING for same-method retry", content = @io.swagger.v3.oas.annotations.media.Content(schema = @io.swagger.v3.oas.annotations.media.Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "503", description = "Provider configuration/contract is unavailable", content = @io.swagger.v3.oas.annotations.media.Content(schema = @io.swagger.v3.oas.annotations.media.Schema(implementation = ApiResponse.class)))
    })
    public ApiResponse<PaymentAttemptResponse> create(@PathVariable UUID bookingId,
                                                      @Valid @RequestBody CreatePaymentAttemptRequest request,
                                                      HttpServletRequest servletRequest) {
        return ApiResponse.success(service.initiate(bookingId, request, servletRequest.getRemoteAddr()));
    }
}
