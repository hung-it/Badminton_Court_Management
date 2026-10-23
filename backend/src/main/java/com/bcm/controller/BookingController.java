package com.bcm.controller;

import com.bcm.dto.request.CreateBookingRequest;
import com.bcm.dto.response.ApiResponse;
import com.bcm.dto.response.BookingResponse;
import com.bcm.dto.response.BookingHistoryResponse;
import com.bcm.dto.response.BookingHistoryDetailResponse;
import com.bcm.dto.response.BookingCustomerResponse;
import com.bcm.security.CurrentCustomerService;
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
    private final CurrentCustomerService currentCustomer;
    private final com.bcm.service.BookingModificationService modificationService;
    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.beans.factory.annotation.Qualifier("bookingExpirationClock")
    private java.time.Clock expirationClock;

    @GetMapping("/customer-profile")
    @SecurityRequirement(name = "Bearer Authentication")
    @Operation(summary = "Resolve the current booking customer",
            description = "Returns the active customers.id needed by POST /bookings. Resolves the authenticated "
                    + "CUSTOMER principal server-side; accepts no user/customer ID. No profile is created or changed.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Own active customer ID"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Authentication required"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "CUSTOMER role or active profile unavailable")
    })
    public ApiResponse<BookingCustomerResponse> customerProfile() {
        return ApiResponse.success(new BookingCustomerResponse(currentCustomer.requireCustomerId()));
    }

    @GetMapping
    @SecurityRequirement(name = "Bearer Authentication")
    @Operation(summary = "List booking history by customer",
            description = "Authenticated CUSTOMER ownership is enforced through users.id to customers.user_id. "
                    + "Absent customerId uses the authenticated customer; mismatching customerId returns 403. Zero-based pagination; size 1..100. "
                    + "Ordered by createdAt DESC then bookingId DESC. Optional exact status filter. "
                    + "Includes EXPIRED headers; no details/payments are loaded for the list. "
                    + "Page beyond the end returns an empty page. Read-only; never expires bookings.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Authentication required"),
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
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Authentication required"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "CUSTOMER identity required or foreign customer/booking", content = @io.swagger.v3.oas.annotations.media.Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Booking header, details and booking-target payments"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Malformed booking UUID", content = @io.swagger.v3.oas.annotations.media.Content(schema = @io.swagger.v3.oas.annotations.media.Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Booking not found", content = @io.swagger.v3.oas.annotations.media.Content(schema = @io.swagger.v3.oas.annotations.media.Schema(implementation = ApiResponse.class)))
    })
    public ApiResponse<BookingHistoryDetailResponse> detail(@PathVariable UUID bookingId) {
        return ApiResponse.success(historyService.detail(bookingId));
    }

    @PatchMapping("/{bookingId}/notes")
    @SecurityRequirement(name = "Bearer Authentication")
    @Operation(summary = "Save an owned pending booking note", description = "Maximum 1000 characters. Enforces original deadline under Booking lock. Independent of payment evidence; never renews the hold.")
    public ApiResponse<BookingHistoryDetailResponse> updateNote(@PathVariable UUID bookingId,
            @Valid @RequestBody com.bcm.dto.request.UpdateBookingNoteRequest request) {
        return ApiResponse.success(modificationService.updateNote(bookingId, request));
    }

    @PatchMapping("/{bookingId}/details")
    @SecurityRequirement(name = "Bearer Authentication")
    @Operation(summary = "Edit date and slots of an owned pending single-court booking", description = "Same court only. Requires no payment attempts and an unexpired original deadline. Booking and Court locks plus unique slot index protect atomic replacement. Retained tuples keep snapshot prices; new tuples use current master prices rounded HALF_UP. Returns owned detail and authoritative total; never changes identity, payment state or deadline. 409 for unavailable slots, expiry, confirmed bookings or an existing payment attempt.")
    public ApiResponse<BookingHistoryDetailResponse> updateDetails(@PathVariable UUID bookingId,
            @Valid @RequestBody com.bcm.dto.request.UpdateBookingRequest request) {
        return ApiResponse.success(modificationService.updateDetails(bookingId, request));
    }

    @GetMapping("/{bookingId}/checkout-clock")
    @SecurityRequirement(name = "Bearer Authentication")
    @Operation(summary = "Read owned booking deadline and server time", description = "No renewal or mutation. Local timestamps use the same clock as booking expiration.")
    public ApiResponse<java.util.Map<String, Object>> checkoutClock(@PathVariable UUID bookingId) {
        var booking = historyService.detail(bookingId); // Enforce authenticated ownership.
        var result = new java.util.HashMap<String, Object>();
        result.put("serverTime", java.time.LocalDateTime.now(expirationClock));
        result.put("expiresAt", booking.expiresAt());
        result.put("status", booking.status());
        return ApiResponse.success(result);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @SecurityRequirement(name = "Bearer Authentication")
    @Operation(summary = "Create a PENDING booking",
            description = "Creates all details atomically. Prices use master data, rounded HALF_UP per detail to scale 2. "
                    + "Expiry uses configured hold duration. Start time must be in the future in Asia/Ho_Chi_Minh. "
                    + "Only customerId and details (bookingDate, courtId, timeSlotId) are accepted inputs; "
                    + "unknown JSON fields are ignored and cannot override price, status or expiry. "
                    + "Authenticated CUSTOMER ownership is required: customerId must match the trusted customers.id; mismatch returns 403 before court locks. "
                    + "Court rows are locked in UUID order and occupied slots are re-checked before insert. "
                    + "Existing details block regardless of booking status/expiry; the unique slot index is the final safeguard.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Authentication required"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "CUSTOMER identity required or customerId mismatch", content = @io.swagger.v3.oas.annotations.media.Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Booking created"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid input, duplicate tuple, ineligible court or price overflow", content = @io.swagger.v3.oas.annotations.media.Content(schema = @io.swagger.v3.oas.annotations.media.Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Customer, court or time slot not found", content = @io.swagger.v3.oas.annotations.media.Content(schema = @io.swagger.v3.oas.annotations.media.Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Database constraint conflict, including an occupied slot", content = @io.swagger.v3.oas.annotations.media.Content(schema = @io.swagger.v3.oas.annotations.media.Schema(implementation = ApiResponse.class)))
    })
    public ApiResponse<BookingResponse> createBooking(@Valid @RequestBody CreateBookingRequest request) {
        return ApiResponse.success(bookingService.createBooking(request));
    }
}
