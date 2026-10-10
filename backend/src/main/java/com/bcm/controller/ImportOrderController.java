package com.bcm.controller;

import com.bcm.dto.request.ImportOrderRequest;
import com.bcm.dto.response.ApiResponse;
import com.bcm.dto.response.ImportOrderResponse;
import com.bcm.service.ImportOrderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/import-orders")
@RequiredArgsConstructor
public class ImportOrderController {

    private final ImportOrderService importOrderService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<ImportOrderResponse>>> findAll() {
        return ResponseEntity.ok(ApiResponse.success(importOrderService.findAll()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<ImportOrderResponse>> findById(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(importOrderService.findById(id)));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<ImportOrderResponse>> create(
            @Valid @RequestBody ImportOrderRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Tạo phiếu nhập thành công", importOrderService.create(request)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<ImportOrderResponse>> update(
            @PathVariable UUID id,
            @Valid @RequestBody ImportOrderRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Cập nhật phiếu nhập thành công",
                importOrderService.update(id, request)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable UUID id) {
        importOrderService.delete(id);
        return ResponseEntity.ok(ApiResponse.success("Xóa phiếu nhập thành công"));
    }

    @PostMapping("/{id}/approve")
    public ResponseEntity<ApiResponse<ImportOrderResponse>> approve(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success("Duyệt phiếu nhập thành công",
                importOrderService.approve(id)));
    }
}