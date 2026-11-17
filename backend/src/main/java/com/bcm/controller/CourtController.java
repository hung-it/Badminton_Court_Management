package com.bcm.controller;

import com.bcm.dto.request.CourtRequest;
import com.bcm.dto.request.CourtBatchRequest;
import com.bcm.dto.response.ApiResponse;
import com.bcm.entity.Court;
import com.bcm.service.MasterDataService;
import com.bcm.service.CourtBatchService;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/admin/courts")
@RequiredArgsConstructor
public class CourtController {
    private final MasterDataService service;
    private final CourtBatchService batchService;

    @PostMapping("/batch")
    @ResponseStatus(HttpStatus.CREATED)
    @io.swagger.v3.oas.annotations.Operation(summary = "Tạo nhiều sân theo số lượng, có thể tạo kèm chi nhánh mới; toàn bộ thao tác cùng một transaction")
    public ApiResponse<List<Court>> createBatch(@Valid @RequestBody CourtBatchRequest r) {
        return ApiResponse.success(batchService.create(r));
    }

    @GetMapping
    public ApiResponse<List<Court>> list() {
        return ApiResponse.success(service.courts());
    }

    @GetMapping("/{id}")
    public ApiResponse<Court> get(@PathVariable UUID id) {
        return ApiResponse.success(service.court(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<Court> create(@Valid @RequestBody CourtRequest r) {
        return ApiResponse.success(service.saveCourt(null, r));
    }

    @PutMapping("/{id}")
    public ApiResponse<Court> update(@PathVariable UUID id, @Valid @RequestBody CourtRequest r) {
        return ApiResponse.success(service.saveCourt(id, r));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable UUID id) {
        service.deleteCourt(id);
        return ApiResponse.success("Đã xóa");
    }
}
