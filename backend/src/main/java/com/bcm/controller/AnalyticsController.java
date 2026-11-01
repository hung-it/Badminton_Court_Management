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
    private final com.bcm.service.StatisticsService statisticsService;

    @GetMapping("/statistics")
    @Operation(summary="Thống kê thu, chi, lợi nhuận theo tháng/quý/năm/các năm", description="MONTH: 12 tháng; QUARTER: 4 quý; YEAR: năm được chọn; YEARS: mỗi năm trong fromYear..toYear, tối đa 50 năm. Thu từ hóa đơn PAID chưa xóa; chi từ phiếu nhập không CANCELLED, gồm phiếu chưa duyệt, theo import_date. Lợi nhuận = thu - chi.")
    public ApiResponse<com.bcm.dto.response.StatisticsResponse> statistics(
            @RequestParam(defaultValue="MONTH") String period,
            @RequestParam(required=false) Integer year,
            @RequestParam(required=false) Integer fromYear,
            @RequestParam(required=false) Integer toYear) {
        return ApiResponse.success(statisticsService.report(period,year==null?LocalDate.now().getYear():year,fromYear,toYear));
    }

    @GetMapping("/invoices")
    @Operation(summary="Lịch sử hóa đơn PAID trong kỳ doanh thu, 20 hóa đơn mỗi trang", description="from/to lọc khoảng ngày bao gồm cả hai ngày, ưu tiên hơn period/date; phải truyền đủ cả hai.")
    public ApiResponse<com.bcm.dto.response.InvoiceReportResponse.History> invoices(
            @RequestParam(defaultValue="DAY") String period,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(defaultValue="0") int page,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.success(analyticsService.history(period,date,page,from,to));
    }

    @GetMapping("/invoices/{id}")
    @Operation(summary="Chi tiết hóa đơn doanh thu", description="Giá, số lượng và giảm giá lấy từ dữ liệu hóa đơn đã lưu; tên sân/sản phẩm lấy từ danh mục hiện tại.")
    public ApiResponse<com.bcm.dto.response.InvoiceReportResponse> invoice(@org.springframework.web.bind.annotation.PathVariable java.util.UUID id) {
        return ApiResponse.success(analyticsService.invoice(id));
    }

    @GetMapping("/revenue")
    @Operation(summary = "Doanh thu theo kỳ hoặc khoảng ngày", description = "from và to có dạng yyyy-MM-dd, phải truyền cùng nhau; bao gồm cả hai ngày và ưu tiên hơn period/date.")
    public ResponseEntity<ApiResponse<RevenueReportResponse>> getRevenue(
            @RequestParam(defaultValue = "DAY") String period,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(ApiResponse.success(
                "Lấy báo cáo doanh thu thành công",
                analyticsService.getRevenue(period, date, from, to)));
    }
}
