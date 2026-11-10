package com.bcm.controller;

import com.bcm.dto.request.PosSaleRequest;
import com.bcm.dto.response.ApiResponse;
import com.bcm.entity.Invoice;
import com.bcm.service.PosService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import org.springframework.security.access.prepost.PreAuthorize;

import org.springframework.web.bind.annotation.GetMapping;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/pos")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN', 'STAFF')")
public class PosController {

    private final PosService posService;
    private final com.bcm.service.ProductPriceService productPrices;
    private final com.bcm.repository.CustomerRepository customerRepository;
    private final com.bcm.repository.ProductRepository productRepository;
    private final com.bcm.service.ProductPromotionService productPromotions;

    private final com.bcm.service.BookingPromotionService bookingPromotions;
    @PostMapping("/bookings/{id}/promotion")
    public ApiResponse<?> courtPromotion(@org.springframework.web.bind.annotation.PathVariable java.util.UUID id,
            @Valid @RequestBody com.bcm.dto.request.BookingPromotionRequest request) {
        return ApiResponse.success(bookingPromotions.staffApply(id,request.code()));
    }

    public record CounterReceiptRequest(
        @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.Pattern(regexp="CASH|VNPAY") String paymentMethod,
        boolean paymentConfirmed, @jakarta.validation.constraints.Size(max=255) String reference,
        @jakarta.validation.constraints.DecimalMin("0.00") java.math.BigDecimal expectedAmount) {}
    @PostMapping("/bookings/{id}/receive")
    @io.swagger.v3.oas.annotations.Operation(summary="Thu tiền sân tại quầy", description="ADMIN/STAFF xác nhận thực nhận tiền cho booking PENDING chưa hết hạn, chưa có giao dịch thanh toán. CASH hoặc VNPAY thủ công, paymentConfirmed=true; số tiền lấy từ booking.finalAmount. expectedAmount tùy chọn để đối chiếu số tiền nhân viên đã thấy trong cùng khóa booking; khác trả 409 và không ghi biên nhận. Web Admin luôn gửi expectedAmount. Retry không tạo biên nhận thứ hai. Đơn đã có lần thanh toán online phải xử lý theo luồng giao dịch đó.")
    public ApiResponse<?> receive(@org.springframework.web.bind.annotation.PathVariable java.util.UUID id,@Valid @RequestBody CounterReceiptRequest request) {
        return ApiResponse.success(bookingPromotions.staffReceive(id,request.paymentMethod(),request.paymentConfirmed(),request.reference(),request.expectedAmount()));
    }

    @GetMapping("/products")
    public ApiResponse<List<com.bcm.entity.Product>> products() {
        return ApiResponse.success(productPrices.sellable());
    }

    @GetMapping("/product-promotions")
    public ApiResponse<List<com.bcm.dto.response.ProductPromotionResponse>> promotions() {
        return ApiResponse.success(productPromotions.list());
    }

    @GetMapping("/customers")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getCustomers() {
        return ResponseEntity.ok(ApiResponse.success(
                customerRepository.findAll().stream()
                        .map(c -> Map.<String, Object>of(
                                "id", c.getId(),
                                "fullName", c.getFullName(),
                                "phone", c.getPhone()
                        )).toList()));
    }

    @PostMapping("/sales/{id}/cancel")
    public ApiResponse<Invoice> cancelDraft(@org.springframework.web.bind.annotation.PathVariable java.util.UUID id) {
        return ApiResponse.success(posService.cancelDraft(id));
    }

    @PostMapping("/sales")
    @io.swagger.v3.oas.annotations.Operation(summary="Lưu đơn POS",description="idempotencyKey UUID bắt buộc cho /sales; retry giữ nguyên key/body. Bán lẻ yêu cầu paymentConfirmed=true và CASH/VNPAY thủ công; reference tùy chọn. Gắn Booking chỉ tạo DRAFT, payment_method NULL, trừ kho ngay; hủy nháp hoàn kho một lần. staffId không quyết định actor. promotionCode tùy chọn là mã khuyến mãi PRODUCT. Khi nhập mã, kiểm tra tổng số lượng giỏ và hiệu lực; áp tỷ lệ cho các mặt hàng trong đơn, không cần mặt hàng gốc. Chọn ưu đãi tốt nhất mỗi dòng, không cộng dồn và không thay đổi tiền sân. Kiểm tra lại khi lưu.")
    public ResponseEntity<ApiResponse<Invoice>> sell(@Valid @RequestBody PosSaleRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Bán hàng thành công", posService.sell(request)));
    }

    @PostMapping("/quote")
    @io.swagger.v3.oas.annotations.Operation(summary="Báo giá POS",description="promotionCode được kiểm tra phía server. Mã sai, hết hạn hoặc không đủ điều kiện trả 400. Không nhập mã vẫn tự áp dụng ưu đãi PRODUCT tốt nhất.")
    public ApiResponse<com.bcm.dto.response.PosQuoteResponse> quote(@Valid @RequestBody PosSaleRequest request) {
        return ApiResponse.success(posService.quote(request));
    }
}
