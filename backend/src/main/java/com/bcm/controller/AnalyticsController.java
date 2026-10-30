package com.bcm.controller;

import com.bcm.dto.response.ApiResponse;
import com.bcm.dto.response.RevenueReportResponse;
import com.bcm.service.AnalyticsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import org.springframework.security.access.prepost.PreAuthorize;

import java.time.LocalDate;

@RestController
@RequestMapping("/analytics")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Analytics", description = "Revenue reports and analytics")
public class AnalyticsController {

    private final AnalyticsService analyticsService;

    @GetMapping("/invoices")
    @Operation(summary="Lịch sử hóa đơn PAID trong kỳ doanh thu, 20 hóa đơn mỗi trang")
    public ApiResponse<com.bcm.dto.response.InvoiceReportResponse.History> invoices(
            @RequestParam(defaultValue="DAY") String period,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(defaultValue="0") int page) {
        return ApiResponse.success(analyticsService.history(period,date,page));
    }

    @GetMapping("/invoices/{id}")
    @Operation(summary="Chi tiết hóa đơn doanh thu", description="Giá, số lượng và giảm giá lấy từ dữ liệu hóa đơn đã lưu; tên sân/sản phẩm lấy từ danh mục hiện tại.")
    public ApiResponse<com.bcm.dto.response.InvoiceReportResponse> invoice(@org.springframework.web.bind.annotation.PathVariable java.util.UUID id) {
        return ApiResponse.success(analyticsService.invoice(id));
    }

    @GetMapping("/revenue")
    @Operation(summary = "Get revenue report by day, week, or month")
    public ResponseEntity<ApiResponse<RevenueReportResponse>> getRevenue(
            @RequestParam(defaultValue = "DAY") String period,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ResponseEntity.ok(ApiResponse.success(
                "Lấy báo cáo doanh thu thành công",
                analyticsService.getRevenue(period, date)));
    }
}
