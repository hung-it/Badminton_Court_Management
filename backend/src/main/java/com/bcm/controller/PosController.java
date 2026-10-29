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
    private final com.bcm.repository.CustomerRepository customerRepository;
    private final com.bcm.repository.ProductRepository productRepository;
    private final com.bcm.service.ProductPromotionService productPromotions;

    private final com.bcm.service.BookingPromotionService bookingPromotions;
    @PostMapping("/bookings/{id}/promotion")
    public ApiResponse<?> courtPromotion(@org.springframework.web.bind.annotation.PathVariable java.util.UUID id,
            @Valid @RequestBody com.bcm.dto.request.BookingPromotionRequest request) {
        return ApiResponse.success(bookingPromotions.staffApply(id,request.code()));
    }

    public record CounterReceiptRequest(@jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Pattern(regexp="CASH|BANK_TRANSFER") String paymentMethod) {}
    @PostMapping("/bookings/{id}/receive")
    public ApiResponse<?> receive(@org.springframework.web.bind.annotation.PathVariable java.util.UUID id,@Valid @RequestBody CounterReceiptRequest request) {
        return ApiResponse.success(bookingPromotions.staffReceive(id,request.paymentMethod()));
    }

    @GetMapping("/products")
    public ApiResponse<List<com.bcm.entity.Product>> products() {
        return ApiResponse.success(productRepository.findByDeletedAtIsNullOrderByNameAsc());
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

    @PostMapping("/sales")
    public ResponseEntity<ApiResponse<Invoice>> sell(@Valid @RequestBody PosSaleRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Bán hàng thành công", posService.sell(request)));
    }

    @PostMapping("/quote")
    public ApiResponse<com.bcm.dto.response.PosQuoteResponse> quote(@Valid @RequestBody PosSaleRequest request) {
        return ApiResponse.success(posService.quote(request));
    }
}
