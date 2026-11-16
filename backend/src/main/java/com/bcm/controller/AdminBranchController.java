package com.bcm.controller;

import com.bcm.dto.response.ApiResponse;
import com.bcm.dto.response.BranchResponse;
import com.bcm.service.BranchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/admin/branches")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin Branch Management", description = "Quản lý trạng thái chi nhánh (Hoạt động / Đóng cửa)")
public class AdminBranchController {
    private final BranchService branchService;

    @GetMapping
    @Operation(summary = "Lấy tất cả chi nhánh (gồm cả hoạt động và đóng cửa)")
    public ApiResponse<List<BranchResponse>> listAll() {
        return ApiResponse.success(branchService.listAll());
    }

    @PatchMapping("/{id}/status")
    @Operation(summary = "Cập nhật hoặc đổi trạng thái chi nhánh")
    public ApiResponse<BranchResponse> updateStatus(
            @PathVariable UUID id,
            @RequestBody(required = false) Map<String, Object> body) {
        Boolean active = null;
        if (body != null) {
            if (body.containsKey("active")) {
                active = Boolean.valueOf(String.valueOf(body.get("active")));
            } else if (body.containsKey("status")) {
                String s = String.valueOf(body.get("status"));
                active = "AVAILABLE".equalsIgnoreCase(s) || "ACTIVE".equalsIgnoreCase(s) || "OPEN".equalsIgnoreCase(s);
            }
        }
        return ApiResponse.success(branchService.updateStatus(id, active));
    }

    @PutMapping("/{id}/status")
    @Operation(summary = "Cập nhật hoặc đổi trạng thái chi nhánh (PUT)")
    public ApiResponse<BranchResponse> updateStatusPut(
            @PathVariable UUID id,
            @RequestBody(required = false) Map<String, Object> body) {
        return updateStatus(id, body);
    }

    @PostMapping("/{id}/toggle-status")
    @Operation(summary = "Đổi trạng thái chi nhánh (Toggle)")
    public ApiResponse<BranchResponse> toggleStatus(@PathVariable UUID id) {
        return ApiResponse.success(branchService.updateStatus(id, null));
    }
}
