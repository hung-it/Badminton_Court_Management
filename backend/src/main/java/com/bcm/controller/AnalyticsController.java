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
    private final com.bcm.service.RevenueBreakdownService revenueBreakdownService;

    @GetMapping("/revenue-dashboard")
    @Operation(summary="Dashboard doanh thu theo khoảng ngày", description="Chỉ ADMIN. Hóa đơn PAID chưa xóa, ghi nhận theo created_at. Hai ngày biên được tính đủ. Kỳ trước có cùng số ngày. Phân bổ và phương thức thiếu căn cứ được báo riêng.")
    public ApiResponse<com.bcm.service.RevenueBreakdownService.Dashboard> dashboard(
            @RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.success(revenueBreakdownService.dashboard(from,to));
    }

    @GetMapping("/statistics")
    @Operation(summary="Thống kê thu và giá trị hàng đã nhận theo tháng/quý/năm/các năm", description="MONTH: 12 tháng; QUARTER: 4 quý; YEAR: năm được chọn; YEARS: mỗi năm trong fromYear..toYear, tối đa 50 năm. Thu từ hóa đơn PAID chưa xóa; Giá trị phiếu đã nhận chỉ tính RECEIVED, nhóm theo ngày trên phiếu nhập (import_date), không phải ngày duyệt/nhận thực tế. Trường profit tương thích là chênh lệch thu – nhập hàng.")
    public ApiResponse<com.bcm.dto.response.StatisticsResponse> statistics(
            @RequestParam(defaultValue="MONTH") String period,
            @RequestParam(required=false) Integer year,
            @RequestParam(required=false) Integer fromYear,
            @RequestParam(required=false) Integer toYear) {
        return ApiResponse.success(statisticsService.report(period,year==null?LocalDate.now().getYear():year,fromYear,toYear));
    }

    @GetMapping("/revenue-breakdown")
    @Operation(summary = "Doanh thu theo nhân viên, khách hàng, sản phẩm và tiền sân",
            description = "Chỉ tính hóa đơn PAID chưa xóa. QUARTER trả bốn quý; YEAR trả cả năm. Giảm sân và giảm sản phẩm giữ đúng nguồn; chỉ phần giảm toàn hóa đơn còn lại phân bổ theo giá trị sau giảm riêng, làm tròn khớp tổng hóa đơn. Doanh thu nhân viên từ khoản thu SUCCESS được xác nhận bởi staffs.id; tiền online/Admin không gán cho nhân viên Checkout. Chi tiết sản phẩm kèm người thêm hàng, khách hàng và số lượng; tiền sân chia theo sân.")
    public ApiResponse<com.bcm.dto.response.RevenueBreakdownResponse> revenueBreakdown(
            @RequestParam(defaultValue = "QUARTER") String period,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer quarter,
            @RequestParam(defaultValue = "false") boolean detail) {
        return ApiResponse.success(revenueBreakdownService.report(period,
                year == null ? LocalDate.now().getYear() : year, quarter, detail));
    }

    @GetMapping("/invoices")
    @PreAuthorize("hasAnyRole('ADMIN', 'STAFF')")
    @Operation(summary="Lịch sử hóa đơn PAID trong kỳ doanh thu, 20 hóa đơn mỗi trang", description="from/to lọc khoảng ngày bao gồm cả hai ngày, ưu tiên hơn period/date; phải truyền đủ cả hai.")
    public ApiResponse<com.bcm.dto.response.InvoiceReportResponse.History> invoices(
            @RequestParam(defaultValue="DAY") String period,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(defaultValue="0") int page,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue="") String search,@RequestParam(defaultValue="") String method,
            @RequestParam(required=false) java.util.UUID customerId) {
        return ApiResponse.success(analyticsService.history(period,date,page,from,to,search,method,customerId));
    }

    @GetMapping("/invoices/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'STAFF')")
    @Operation(summary="Chi tiết hóa đơn doanh thu", description="Giá, số lượng và giảm giá lấy từ dữ liệu hóa đơn đã lưu; Tên sản phẩm dùng snapshot; dòng cũ thiếu snapshot được đánh dấu. Trả kèm khoản thu và actor xác nhận.")
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

    @GetMapping("/statistics/matrix")
    @Operation(summary="Bảng thống kê theo đối tượng", description="type: TIME, DATE, IMPORT, STAFF, CUSTOMER, PRODUCT, COURT (tiền sân); dùng cùng type và bộ lọc cho Excel.")
    public ApiResponse<com.bcm.service.StatisticsService.Matrix> matrix(
       @RequestParam(defaultValue="TIME") String type,@RequestParam(defaultValue="MONTH") String period,
       @RequestParam(defaultValue="2026") int year,@RequestParam(defaultValue="2024") int fromYear,@RequestParam(defaultValue="2026") int toYear,
       @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate from,
       @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate to,@RequestParam(defaultValue="") String search){
       return ApiResponse.success(statisticsService.matrix(type,period,year,fromYear,toYear,from,to,search));
    }
    @GetMapping("/statistics/excel")
    public ResponseEntity<byte[]> excel(
       @RequestParam(defaultValue="TIME") String type,@RequestParam(defaultValue="MONTH") String period,
       @RequestParam(defaultValue="2026") int year,@RequestParam(defaultValue="2024") int fromYear,@RequestParam(defaultValue="2026") int toYear,
       @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate from,
       @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate to,@RequestParam(defaultValue="") String search){
       var report=statisticsService.matrix(type,period,year,fromYear,toYear,from,to,search);
       return ResponseEntity.ok().header("Content-Disposition","attachment; filename=BCM-"+type+"-"+System.currentTimeMillis()+".xlsx")
       .header("Content-Type","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
       .body(statisticsService.excel(report,"period="+period+", year="+year+", years="+fromYear+"-"+toYear+", from="+from+", to="+to+", đối tượng="+(search.isBlank()?"Tất cả":report.rows().isEmpty()?"Không tìm thấy":report.rows().stream().map(com.bcm.service.StatisticsService.MatrixRow::label).distinct().collect(java.util.stream.Collectors.joining(", ")))));
    }
}
