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

import org.springframework.security.access.prepost.PreAuthorize;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/import-orders")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class ImportOrderController {

    private final ImportOrderService importOrderService;

    @PostMapping("/manual")
    @io.swagger.v3.oas.annotations.Operation(summary="Tạo phiếu bằng tên nhập tay",description="Tái sử dụng tên đang có (không phân biệt hoa thường); tên mới được tạo cùng transaction với phiếu. Sản phẩm mới: GOODS, đơn vị cái, giá bán 0, tồn kho 0 cho tới khi duyệt. Tổng tiền giữ chính xác 2 số thập phân, tên trùng nhiều bản ghi trả 409.")
    public ResponseEntity<ApiResponse<ImportOrderResponse>> createManual(@Valid @RequestBody com.bcm.dto.request.ManualImportOrderRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success("Tạo phiếu nhập thành công",importOrderService.createManual(request)));
    }

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
