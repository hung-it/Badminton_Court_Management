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
            description = "Supports paymentMethod VNPAY only. Unsupported methods return 400 before persistence. Amount comes from booking courtFee. "
                    + "Booking must be PENDING with expiresAt strictly after now. "
                    + "Reuses the existing PENDING VNPay attempt. "
                    + "Unknown JSON fields are ignored; clients cannot set amount, target, status or transaction ID. "
                    + "When configured, VNPay returns checkoutReady=true and a signed sandbox checkoutUrl. "
                    + "Gateway artifacts are runtime only. Initiation leaves payment and booking PENDING. "
                    + "Client IP is request remote address; forwarded headers are not trusted. "
                    + "Authenticated CUSTOMER ownership is required before creating or reusing an attempt; foreign bookings return 403.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "CUSTOMER identity required or foreign booking; anonymous requests are rejected by the security filter", content = @io.swagger.v3.oas.annotations.media.Content(schema = @io.swagger.v3.oas.annotations.media.Schema(implementation = ApiResponse.class))),
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
