# Báo cáo tiến độ triển khai báo cáo doanh thu

## 1. Mục tiêu

Bổ sung báo cáo doanh thu chi tiết cho Web Admin theo:

- Nhân viên thu ngân.
- Khách hàng.
- Sản phẩm.
- Tiền sân theo sân.
- Bộ lọc theo quý hoặc cả năm, chọn năm bằng danh sách.
- Chọn từng quý để xem chi tiết sản phẩm, nhân viên thu ngân và khách hàng.

Báo cáo chỉ tính các hóa đơn đã thanh toán (`PAID`) và chưa bị xóa mềm.

## 2. Backend đã thực hiện

### API mới

Đã thêm endpoint:

```text
GET /api/analytics/revenue-breakdown?period=QUARTER&year=2025
GET /api/analytics/revenue-breakdown?period=YEAR&year=2025
GET /api/analytics/revenue-breakdown?period=QUARTER&year=2025&quarter=2&detail=true
```

Endpoint được bảo vệ bởi quyền `ADMIN`, thống nhất với các API Analytics hiện tại.

### Báo cáo theo nhân viên

- Nhân viên được xác định từ tài khoản đăng nhập trong JWT.
- Hệ thống ánh xạ `users.id` sang `staffs.id`.
- Không tin `staffId` do client gửi lên.
- POS và Checkout đều ghi nhân viên xác nhận vào `invoices.created_by` và `invoices.updated_by`.
- Hóa đơn không resolve được nhân viên được gom vào nhóm `Chưa gán nhân viên`.

### Báo cáo theo khách hàng

- Tổng hợp theo `invoices.customer_id`.
- Khách hàng bị xóa mềm hoặc không resolve được được gom vào nhóm `Khách vãng lai`.
- Nhóm Walk-in chỉ xuất hiện khi thực sự có dữ liệu tương ứng.

### Báo cáo theo sản phẩm

Doanh thu sản phẩm được phân bổ từ tổng hóa đơn để các tổng luôn khớp nhau:

```text
tổng hóa đơn = tổng tiền sản phẩm + tổng tiền sân
```

Cách phân bổ cho từng hóa đơn `PAID`:

1. Tổng sau giảm giá (`invoices.total_amount`) được chia giữa **phần sản phẩm** và **phần sân** theo tỷ lệ giá trị gốc của mỗi phần.
2. Phần sản phẩm được chia tiếp theo giá trị từng dòng hàng (`quantity * unit_price`).
3. Phần sân được chia theo giá của từng slot đã đặt (`booking_details.price`); hóa đơn có tiền sân nhưng không có booking rơi vào nhóm `Không xác định sân`.
4. Phần dư do làm tròn được đẩy vào dòng lớn nhất nên tổng luôn bằng đúng `total_amount` tới từng xu.

### Chi tiết theo quý

```text
GET /api/analytics/revenue-breakdown?period=QUARTER&year=2026&quarter=2&detail=true
```

- `quarter=1..4` chỉ trả chi tiết của quý được chọn; `detail=true` trả chi tiết mọi kỳ.
- Mỗi dòng chi tiết gồm sản phẩm, **nhân viên thu ngân**, **khách hàng**, số lượng và tiền dòng.
- Mỗi kỳ trả kèm `productTotal`, `courtTotal` và `invoiceTotal` để đối chiếu.

### Báo cáo tiền sân

- Trả về theo từng sân (`courts.name`), chỉ tính phần tiền sân của hóa đơn đã thanh toán.
- Không cộng tiền hàng hóa và dịch vụ vào slide này.

### Quy tắc thời gian và số tiền

- Khoảng thời gian dùng dạng `[from, to)`.
- Quý 1 kết thúc trước `01/04`, quý 2 bắt đầu từ `01/04`.
- Năm được tính từ ngày `01/01` đến trước ngày `01/01` của năm sau.
- Tiền được xử lý bằng `BigDecimal` và PostgreSQL `NUMERIC`.
- Không thêm cột hoặc bảng mới vào database.
- Không thêm migration vì schema hiện tại đã có đủ `created_by`, `customer_id` và `discount_amount`.

## 3. Frontend đã thực hiện

Đã thêm tab **Doanh thu theo đối tượng** trong Web Admin.

Chức năng gồm:

- Chọn báo cáo theo quý hoặc năm.
- Chuyển đổi giữa ba nhóm Nhân viên, Khách hàng và Sản phẩm.
- Tìm kiếm theo tên.
- Hiển thị tổng doanh thu và số lượng đối tượng.
- Hiển thị bảng theo từng kỳ.
- Hiển thị tổng cộng.
- Hiển thị số lượng sản phẩm.
- Hiển thị thanh tỷ lệ doanh thu bằng CSS.
- Có trạng thái đang tải, lỗi và không có dữ liệu.
- Responsive cho màn hình nhỏ.
- Không thêm thư viện biểu đồ hoặc dependency mới.

## 4. Seed dữ liệu mẫu

Đã tạo file seed:

```text
db/seeds/revenue-breakdown-sample.sql
```

File seed:

- Không thuộc `db/migration.sql`.
- Chạy thủ công khi cần.
- Có thể chạy lại nhiều lần mà không tạo bản ghi trùng.
- Đã được áp dụng vào database PostgreSQL dev hiện tại.

### Dữ liệu mẫu năm 2025

| Nhóm | Giá trị |
|---|---:|
| Thu ngân mẫu | 545.000 VND |
| Chưa gán nhân viên | 40.000 VND |
| Nguyễn An | 195.000 VND |
| Trần Bình | 390.000 VND |
| Cầu lông mẫu | 4 sản phẩm, 105.000 VND |
| Nước suối mẫu | 5 sản phẩm, 50.000 VND |

Để kiểm tra trên giao diện:

1. Đăng nhập Web Admin bằng tài khoản ADMIN hiện có.
2. Mở tab **Doanh thu theo đối tượng**.
3. Chọn bộ lọc **Năm**.
4. Nhập năm `2025`.
5. Kiểm tra lần lượt ba tab Nhân viên, Khách hàng và Sản phẩm.

Tài khoản thu ngân mẫu chỉ phục vụ dữ liệu báo cáo và không có mật khẩu đăng nhập hợp lệ.

## 5. Kiểm thử đã chạy

### Backend

```text
mvn -f backend/pom.xml -Dtest=RevenueBreakdownIntegrationTest,CurrentStaffServiceTest test
PASS
```

```text
mvn -f backend/pom.xml verify
PASS
```

Đã kiểm tra các trường hợp:

- Chỉ tính hóa đơn `PAID`.
- Bỏ qua hóa đơn bị xóa mềm.
- Bỏ qua hóa đơn `DRAFT`.
- Biên giữa quý 1 và quý 2.
- Biên giữa các năm.
- Tính giảm giá theo dòng sản phẩm.
- Gom nhóm nhân viên chưa xác định.
- Gom nhóm khách vãng lai.
- Validate `period` không hợp lệ.
- Validate năm không hợp lệ.
- Không hiển thị nhóm 0đ khi kỳ báo cáo không có dữ liệu.
- Ánh xạ `users.id` sang `staffs.id`.

Test PostgreSQL riêng đã được thêm và chỉ chạy khi cấu hình database test độc lập thông qua `BCM_TEST_DB_URL`. Không dùng database dev chung làm database test để tránh làm nhiễm dữ liệu.

### Frontend

```text
cd web-admin
npm run build
PASS
```

### E2E báo cáo doanh thu

```text
cd web-admin
node revenue-breakdown.e2e.cjs
PASS
```

Đã kiểm tra:

- Bộ lọc quý/năm.
- Request query string.
- Giá trị trong bảng.
- Ba tab báo cáo.
- Tìm kiếm.
- Error state.
- Empty state.
- Responsive tại viewport 390px.

Các e2e hiện có khác của Web Admin cũng đã được chạy. Các bài POS, Checkout, Revenue Invoice và Product Approval pass. `inventory.e2e.cjs` còn timeout ở assertion text `Chưa duyệt`; lỗi này tồn tại độc lập ở luồng nhập kho và không liên quan đến thay đổi báo cáo doanh thu.

## 6. Các file chính

### Backend

- `backend/src/main/java/com/bcm/controller/AnalyticsController.java`
- `backend/src/main/java/com/bcm/service/RevenueBreakdownService.java`
- `backend/src/main/java/com/bcm/security/CurrentStaffService.java`
- `backend/src/main/java/com/bcm/service/PosService.java`
- `backend/src/main/java/com/bcm/service/CheckoutService.java`
- `backend/src/main/java/com/bcm/dto/response/RevenueBreakdownResponse.java`

### Frontend

- `web-admin/src/RevenueBreakdown.jsx`
- `web-admin/src/App.jsx`
- `web-admin/src/styles.css`
- `web-admin/revenue-breakdown.e2e.cjs`

### Dữ liệu và tài liệu

- `db/seeds/revenue-breakdown-sample.sql`
- `docs/revenue-breakdown-progress.md`

## 7. Giới hạn và lưu ý

- Hóa đơn lịch sử có `created_by IS NULL` vẫn nằm trong nhóm `Chưa gán nhân viên`; hệ thống không tự đoán lại người thu tiền.
- `invoices.customer_id` hiện là `NOT NULL` và có khóa ngoại, nhưng logic vẫn xử lý phòng trường hợp customer bị xóa mềm hoặc dữ liệu lịch sử không resolve được.
- Tổng doanh thu sản phẩm không nhất thiết bằng tổng doanh thu hóa đơn vì giảm giá cấp hóa đơn không được phân bổ xuống sản phẩm.
- Seed mẫu là dữ liệu phục vụ kiểm thử, không nên dùng trong môi trường production.
- Không có thay đổi schema database.
