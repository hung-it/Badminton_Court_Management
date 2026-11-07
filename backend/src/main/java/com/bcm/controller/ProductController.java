package com.bcm.controller;

import com.bcm.dto.request.ProductRequest;
import com.bcm.dto.response.ApiResponse;
import com.bcm.entity.Product;
import com.bcm.service.MasterDataService;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/admin/products")
@RequiredArgsConstructor
public class ProductController {
    private final MasterDataService service;
    private final com.bcm.service.ProductPriceService prices;

    @PostMapping("/{id}/approve-price")
    @org.springframework.security.access.prepost.PreAuthorize("hasRole('ADMIN')")
    @io.swagger.v3.oas.annotations.Operation(summary="Duyệt giá niêm yết hàng nhập kho",description="Giá 0 là chờ duyệt, bị ẩn/chặn ở POS. Chỉ Admin duyệt giá >0; kiểm tra version và khóa sản phẩm để tránh ghi đè thay đổi tồn kho/giá. Không thêm trạng thái vào schema.")
    public ApiResponse<Product> approvePrice(@PathVariable UUID id,@Valid @RequestBody com.bcm.dto.request.ApproveProductPriceRequest request){
        return ApiResponse.success(prices.approve(id,request.price(),request.version()));
    }

    @GetMapping
    public ApiResponse<List<Product>> list() {
        return ApiResponse.success(service.products());
    }

    @GetMapping("/{id}")
    public ApiResponse<Product> get(@PathVariable UUID id) {
        return ApiResponse.success(service.product(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<Product> create(@Valid @RequestBody ProductRequest r) {
        return ApiResponse.success(service.saveProduct(null, r));
    }

    @PutMapping("/{id}")
    public ApiResponse<Product> update(
            @PathVariable UUID id, @Valid @RequestBody ProductRequest r) {
        return ApiResponse.success(service.saveProduct(id, r));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable UUID id) {
        service.deleteProduct(id);
        return ApiResponse.success("Đã xóa");
    }
}
