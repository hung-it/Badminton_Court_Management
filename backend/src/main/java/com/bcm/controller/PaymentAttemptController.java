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
            description = "Accepts paymentMethod VNPAY or MOMO. Amount comes from booking courtFee. "
                    + "Booking must be PENDING with expiresAt strictly after now. "
                    + "Reuses a PENDING attempt with the same method; a different method returns 409. "
                    + "Unknown JSON fields are ignored; clients cannot set amount, target, status or transaction ID. "
                    + "When configured, VNPay returns checkoutReady=true and a signed sandbox checkoutUrl. "
                    + "MoMo signs/sends captureWallet and correlates response, but returns checkoutReady=false with checkoutBlocker; "
                    + "artifacts are withheld pending official Create Payment response-signature clarification. "
                    + "Gateway artifacts are runtime only. Initiation leaves payment and booking PENDING. "
                    + "Client IP is request remote address; forwarded headers are not trusted. "
                    + "Customer ownership is not integrated: bookingId is not proof of identity/ownership.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "PENDING attempt committed/reused; VNPay checkout URL or MoMo checkout blocker returned"),
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
