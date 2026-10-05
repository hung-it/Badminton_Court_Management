package com.bcm.controller;

import com.bcm.dto.request.CreateBookingRequest;
import com.bcm.dto.response.ApiResponse;
import com.bcm.dto.response.BookingResponse;
import com.bcm.service.BookingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
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

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @SecurityRequirements
    @Operation(summary = "Create a PENDING booking",
            description = "Creates all details atomically. Prices use master data, rounded HALF_UP per detail to scale 2. "
                    + "Expiry uses configured hold duration. Past dates are allowed. "
                    + "Only customerId and details (bookingDate, courtId, timeSlotId) are accepted inputs; "
                    + "unknown JSON fields are ignored and cannot override price, status or expiry. "
                    + "Auth is not integrated: customerId is a customers.id reference, not proof of identity/ownership. "
                    + "Court rows are locked in UUID order and occupied slots are re-checked before insert. "
                    + "Existing details block regardless of booking status/expiry; the unique slot index is the final safeguard.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Booking created"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid input, duplicate tuple, ineligible court or price overflow"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Customer, court or time slot not found"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Database constraint conflict, including an occupied slot")
    })
    public ApiResponse<BookingResponse> createBooking(@Valid @RequestBody CreateBookingRequest request) {
        return ApiResponse.success(bookingService.createBooking(request));
    }
}
