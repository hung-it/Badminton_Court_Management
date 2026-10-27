package com.bcm.controller;

import com.bcm.dto.request.BookingPromotionRequest;
import com.bcm.dto.response.ApiResponse;
import com.bcm.dto.response.BookingPromotionResponse;
import com.bcm.service.BookingPromotionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/bookings/{bookingId}/promotion")
@RequiredArgsConstructor
@Tag(name="Booking promotions")
@SecurityRequirement(name="Bearer Authentication")
public class BookingPromotionController {
    private final BookingPromotionService service;
    @PostMapping("/preview")
    @Operation(summary="Preview one best discount without reserving a voucher", description="Owned, unexpired PENDING booking without payment attempts. Invalid voucher returns automatic quote plus Vietnamese message. Automatic wins ties; amounts supplied by clients are never accepted.")
    public ApiResponse<BookingPromotionResponse> preview(@PathVariable UUID bookingId, @Valid @RequestBody BookingPromotionRequest request) {
        return ApiResponse.success(service.quote(bookingId,request.code(),false));
    }
    @PostMapping("/apply")
    @Operation(summary="Persist best discount and reserve NEWMEMBER atomically", description="Same eligibility as preview; customer row lock serializes NEWMEMBER across bookings. 401 anonymous, 403 foreign, 404 missing, 409 expired/locked. KMKH10 counts distinct COMPLETED bookings with verified SUCCESS payments only. No expiry on seeded vouchers.")
    public ApiResponse<BookingPromotionResponse> apply(@PathVariable UUID bookingId, @Valid @RequestBody BookingPromotionRequest request) {
        return ApiResponse.success(service.quote(bookingId,request.code(),true));
    }
    @DeleteMapping
    @Operation(summary="Remove voucher and persist automatic invoice discount")
    public ApiResponse<BookingPromotionResponse> remove(@PathVariable UUID bookingId) {
        return ApiResponse.success(service.quote(bookingId,null,true));
    }
}
