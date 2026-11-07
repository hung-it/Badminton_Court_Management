package com.bcm.controller;

import com.bcm.dto.request.ProductPromotionRequest;
import com.bcm.dto.response.*;
import com.bcm.service.ProductPromotionService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/admin/product-promotions")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class ProductPromotionController {
    private final ProductPromotionService service;
    @GetMapping public ApiResponse<List<ProductPromotionResponse>> list() { return ApiResponse.success(service.list()); }
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ProductPromotionResponse> create(@Valid @RequestBody ProductPromotionRequest request) {
        return ApiResponse.success(service.save(null, request));
    }
    @PutMapping("/{id}")
    public ApiResponse<ProductPromotionResponse> update(@PathVariable UUID id, @Valid @RequestBody ProductPromotionRequest request) {
        return ApiResponse.success(service.save(id, request));
    }
    @DeleteMapping("/{id}") public ApiResponse<Void> remove(@PathVariable UUID id) {
        service.remove(id);
        return ApiResponse.success("Đã ngừng và xóa khuyến mãi");
    }
}
