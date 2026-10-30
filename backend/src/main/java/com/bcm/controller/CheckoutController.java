package com.bcm.controller;

import com.bcm.dto.request.CheckoutRequest;
import com.bcm.dto.response.ActiveBookingResponse;
import com.bcm.dto.response.ApiResponse;
import com.bcm.dto.response.CheckoutResponse;
import com.bcm.service.CheckoutService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/checkout")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN', 'STAFF')")
public class CheckoutController {

    private final CheckoutService checkoutService;

    @GetMapping("/active-bookings")
    public ApiResponse<List<ActiveBookingResponse>> getActiveBookings() {
        return ApiResponse.success(checkoutService.getActiveBookings());
    }

    @GetMapping("/resolve/{query}")
    public ApiResponse<UUID> resolveBookingId(@PathVariable String query) {
        return ApiResponse.success(checkoutService.resolveBookingId(query));
    }

    @GetMapping("/{bookingIdentifier}")
    public ApiResponse<CheckoutResponse> preview(@PathVariable String bookingIdentifier) {
        UUID bookingId = checkoutService.resolveBookingId(bookingIdentifier);
        return ApiResponse.success(checkoutService.preview(bookingId));
    }

    @PostMapping("/{bookingIdentifier}")
    @io.swagger.v3.oas.annotations.Operation(summary="Kết toán tiền sân và hàng hóa", description="Giao dịch khách tự khai chuyển khoản cần courtReceiptConfirmed=true sau khi nhân viên kiểm tra đã nhận đủ tiền sân. Đối soát, hóa đơn PAID và hoàn tất booking cùng transaction. Doanh thu tính toàn bộ hóa đơn sau giảm, không chỉ phần còn phải thu.")
    public ResponseEntity<ApiResponse<CheckoutResponse>> checkout(
            @PathVariable String bookingIdentifier,
            @Valid @RequestBody CheckoutRequest request) {
        UUID bookingId = checkoutService.resolveBookingId(bookingIdentifier);
        return ResponseEntity.ok(checkoutService.checkout(bookingId, request.getPaymentMethod(), request.isCourtReceiptConfirmed()));
    }
}
