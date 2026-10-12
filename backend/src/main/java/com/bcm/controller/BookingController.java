package com.bcm.controller;

import com.bcm.dto.request.CreateBookingRequest;
import com.bcm.dto.response.ApiResponse;
import com.bcm.dto.response.BookingResponse;
import com.bcm.dto.response.BookingHistoryResponse;
import com.bcm.dto.response.BookingHistoryDetailResponse;
import com.bcm.entity.BookingStatus;
import com.bcm.service.BookingHistoryService;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import com.bcm.service.BookingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/bookings")
@RequiredArgsConstructor
@Tag(name = "Bookings")
public class BookingController {
    private final BookingService bookingService;
    private final BookingHistoryService historyService;

    @GetMapping
    @SecurityRequirement(name = "Bearer Authentication")
    @Operation(summary = "List booking history by customer",
            description = "Authenticated CUSTOMER ownership is enforced through users.id to customers.user_id. "
                    + "Absent customerId uses the authenticated customer; mismatching customerId returns 403. Zero-based pagination; size 1..100. "
                    + "Ordered by createdAt DESC then bookingId DESC. Optional exact status filter. "
                    + "Includes EXPIRED headers; no details/payments are loaded for the list. "
                    + "Page beyond the end returns an empty page. Read-only; never expires bookings.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "CUSTOMER identity required or foreign customer/booking", content = @io.swagger.v3.oas.annotations.media.Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Page of booking headers"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid customerId, status or pagination", content = @io.swagger.v3.oas.annotations.media.Content(schema = @io.swagger.v3.oas.annotations.media.Schema(implementation = ApiResponse.class)))
    })
    public ApiResponse<BookingHistoryResponse> history(
            @Parameter(description = "Optional own customers.id; mismatch returns 403") @RequestParam(required = false) UUID customerId,
            @RequestParam(required = false) BookingStatus status,
            @Parameter(schema = @Schema(minimum = "0", defaultValue = "0")) @RequestParam(defaultValue = "0") int page,
            @Parameter(schema = @Schema(minimum = "1", maximum = "100", defaultValue = "20")) @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(historyService.history(customerId, status, page, size));
    }

    @GetMapping("/{bookingId}")
    @SecurityRequirement(name = "Bearer Authentication")
    @Operation(summary = "Get booking detail and payment history",
            description = "Read-only consistent snapshot; no expiration or settlement. CUSTOMER ownership is enforced: "
                    + "foreign bookings return 403 before details or payments are fetched. EXPIRED header remains visible with preserved courtFee "
                    + "and empty details after expiration deletes them; deleted slots are never reconstructed. "
                    + "Details ordered by bookingDate, courtId, timeSlotId, detailId ascending; prices are persisted snapshots. "
                    + "Court names/numbers and slot times are current master data. Payments target this booking only, "
                    + "ordered by createdAt DESC then paymentAttemptId DESC; no payments means an empty collection. "
                    + "Payment status is not inferred from booking status; unconfirmed transactionId/date may be null.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "CUSTOMER identity required or foreign customer/booking", content = @io.swagger.v3.oas.annotations.media.Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Booking header, details and booking-target payments"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Malformed booking UUID", content = @io.swagger.v3.oas.annotations.media.Content(schema = @io.swagger.v3.oas.annotations.media.Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Booking not found", content = @io.swagger.v3.oas.annotations.media.Content(schema = @io.swagger.v3.oas.annotations.media.Schema(implementation = ApiResponse.class)))
    })
    public ApiResponse<BookingHistoryDetailResponse> detail(@PathVariable UUID bookingId) {
        return ApiResponse.success(historyService.detail(bookingId));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @SecurityRequirement(name = "Bearer Authentication")
    @Operation(summary = "Create a PENDING booking",
            description = "Creates all details atomically. Prices use master data, rounded HALF_UP per detail to scale 2. "
                    + "Expiry uses configured hold duration. Past dates are allowed. "
                    + "Only customerId and details (bookingDate, courtId, timeSlotId) are accepted inputs; "
                    + "unknown JSON fields are ignored and cannot override price, status or expiry. "
                    + "Authenticated CUSTOMER ownership is required: customerId must match the trusted customers.id; mismatch returns 403 before court locks. "
                    + "Court rows are locked in UUID order and occupied slots are re-checked before insert. "
                    + "Existing details block regardless of booking status/expiry; the unique slot index is the final safeguard.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "CUSTOMER identity required or customerId mismatch; anonymous requests are rejected by the security filter", content = @io.swagger.v3.oas.annotations.media.Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Booking created"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid input, duplicate tuple, ineligible court or price overflow", content = @io.swagger.v3.oas.annotations.media.Content(schema = @io.swagger.v3.oas.annotations.media.Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Customer, court or time slot not found", content = @io.swagger.v3.oas.annotations.media.Content(schema = @io.swagger.v3.oas.annotations.media.Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Database constraint conflict, including an occupied slot", content = @io.swagger.v3.oas.annotations.media.Content(schema = @io.swagger.v3.oas.annotations.media.Schema(implementation = ApiResponse.class)))
    })
    public ApiResponse<BookingResponse> createBooking(@Valid @RequestBody CreateBookingRequest request) {
        return ApiResponse.success(bookingService.createBooking(request));
    }
}
