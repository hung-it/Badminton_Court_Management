package com.bcm.controller;

import com.bcm.dto.response.ApiResponse;
import com.bcm.dto.response.AvailabilityResponse;
import com.bcm.service.AvailabilityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

@RestController
@RequestMapping("/availability")
@RequiredArgsConstructor
@Tag(name = "Availability", description = "Read-only court and time-slot availability")
public class AvailabilityController {

    private final AvailabilityService availabilityService;

    @GetMapping
    @SecurityRequirement(name = "Bearer Authentication")
    @Operation(summary = "Get availability by date",
            description = "Returns AVAILABLE, non-deleted courts and all time-slot templates. "
                    + "An existing booking detail always occupies its slot. Past dates are allowed. "
                    + "Courts are ordered by court number; slots by start time, end time and ID.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Availability grid"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Missing or invalid date, or invalid court UUID", content = @io.swagger.v3.oas.annotations.media.Content(schema = @io.swagger.v3.oas.annotations.media.Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Filtered court does not exist or is not eligible for lookup", content = @io.swagger.v3.oas.annotations.media.Content(schema = @io.swagger.v3.oas.annotations.media.Schema(implementation = ApiResponse.class)))
    })
    public ApiResponse<AvailabilityResponse> getAvailability(
            @Parameter(description = "Required calendar date in YYYY-MM-DD format", example = "2026-10-05")
            @RequestParam("date") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @Parameter(description = "Optional court UUID; court must be AVAILABLE and not soft-deleted")
            @RequestParam(value = "courtId", required = false) UUID courtId) {
        return ApiResponse.success(availabilityService.getAvailability(date, courtId));
    }
}
