package com.bcm.controller;

import com.bcm.dto.response.ApiResponse;
import com.bcm.service.MasterDataService;

import lombok.RequiredArgsConstructor;

import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/public")
@RequiredArgsConstructor
public class PublicCatalogController {
    private final MasterDataService service;
    private final java.time.Clock bookingExpirationClock;

    @GetMapping("/booking-clock")
    @io.swagger.v3.oas.annotations.Operation(summary = "Read the authoritative booking clock", description = "Slot starts must be strictly after server time in Asia/Ho_Chi_Minh; equality is already started. Does not change master templates or booking history.")
    public ApiResponse<com.bcm.dto.response.BookingClockResponse> bookingClock() {
        var instant = bookingExpirationClock.instant();
        return ApiResponse.success(new com.bcm.dto.response.BookingClockResponse(instant, "Asia/Ho_Chi_Minh",
                java.time.LocalDate.ofInstant(instant, java.time.ZoneId.of("Asia/Ho_Chi_Minh"))));
    }

    @GetMapping("/courts")
    public ApiResponse<?> courts() {
        return ApiResponse.success(
                service.courts().stream()
                        .filter(c -> c.getStatus() == com.bcm.entity.CourtStatus.AVAILABLE)
                        .map(c -> {
                            Map<String, Object> court = new LinkedHashMap<>();
                            court.put("id", c.getId());
                            court.put("courtNumber", c.getCourtNumber());
                            court.put("name", c.getName());
                            court.put("type", c.getType());
                            court.put("status", c.getStatus());
                            court.put("basePrice", c.getBasePrice());
                            court.put("district", c.getDistrict());
                            return court;
                        })
                        .toList());
    }

    @GetMapping("/time-slots")
    public ApiResponse<?> slots() {
        return ApiResponse.success(
                service.slots().stream()
                        .map(
                                s ->
                                        Map.of(
                                                "id",
                                                s.getId(),
                                                "startTime",
                                                s.getStartTime(),
                                                "endTime",
                                                s.getEndTime(),
                                                "priceMultiplier",
                                                s.getPriceMultiplier()))
                        .toList());
    }

    @GetMapping("/categories")
    public ApiResponse<?> categories() {
        return ApiResponse.success(
                service.categories().stream()
                        .map(c -> Map.of("id", c.getId(), "categoryName", c.getCategoryName()))
                        .toList());
    }

    @GetMapping("/products")
    public ApiResponse<?> products(@RequestParam(required = false) UUID categoryId) {
        return ApiResponse.success(
                service.products().stream()
                        .filter(p -> categoryId == null || p.getCategoryId().equals(categoryId))
                        .map(
                                p ->
                                        Map.of(
                                                "id",
                                                p.getId(),
                                                "categoryId",
                                                p.getCategoryId(),
                                                "name",
                                                p.getName(),
                                                "type",
                                                p.getType(),
                                                "unit",
                                                p.getUnit(),
                                                "price",
                                                p.getPrice()))
                        .toList());
    }

    @GetMapping("/price-quote")
    public ApiResponse<?> quote(@RequestParam UUID courtId, @RequestParam UUID timeSlotId) {
        return ApiResponse.success(service.quote(courtId, timeSlotId));
    }
}
