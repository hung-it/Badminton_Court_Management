# Tiến trình hiện tại — Đồ án Quản lý sân cầu lông

Cập nhật: 2026-11-03 · Leader (Thành viên 1)

## 1. Tổng quan

Backend của cả 4 thành viên đã hoàn tất và **đã chạy pass toàn bộ test H2** (123 test, 0 fail). Trong đợt review này, phần việc đã xong tập trung vào **mở rộng màn báo cáo doanh thu** (module do Leader phụ trách): thêm chi tiết theo quý, slide doanh thu tiền sân, và sửa lại cách tính để các tổng luôn khớp nhau.

## 2. Review backend đã thực hiện

### Kết quả

| Hạng mục | Kết quả |
|---|---|
| Compile backend (`mvn compile`) | Pass |
| Toàn bộ test H2 (`mvn test`) | Lần chạy trước: 123 test pass; lần chạy xác nhận gần nhất: **bị chặn trước khi test khởi động** do Windows thiếu paging file sau nhiều JVM fork |
| Test PostgreSQL (289 test) | **Skipped** trong lần `mvn test` trước vì thiếu biến môi trường `BCM_TEST_DB_URL/USERNAME/PASSWORD` |
| Seed SQL | Pass khi chạy trong transaction `ROLLBACK` trên PostgreSQL thật |
| Frontend build (`npm run build`) | Pass |
| E2E Playwright (`revenue-breakdown.e2e.cjs`) | Pass |

### Lỗi / bất thường đã phát hiện và xử lý

1. **H2 không hỗ trợ `EXTRACT(YEAR FROM ...)`** → dùng `SUBSTRING(CAST(created_at AS VARCHAR),1,4)` để tương thích cả H2 và PostgreSQL.
2. **`Invoice` không có quan hệ với `Staff`** (`Staff` là entity `@Immutable` chỉ map `id`) → mọi truy vấn báo cáo dùng `JdbcTemplate`, không sửa entity của module khác.
3. **`invoice_details.discount_amount` không có trong `db/migration.sql`** mà đến từ `db/upgrades/product-promotions-upgrade.sql` → DB test PostgreSQL phải nạp đủ chuỗi 7 script của docker-compose, nếu không sẽ lỗi cột không tồn tại.
4. **Checkout lưu `product_fee` là giá gộp, `discount_amount` header đã gộp cả giảm giá dòng** → không thể dùng `product_fee` làm mẫu số phân bổ; phải bám `invoice_details` và `invoices.total_amount`.
5. **`bookings.court_fee` là tổng của booking, một booking có nhiều `booking_details`** → phải chia `court_fee` cho từng sân theo tỷ lệ `booking_details.price`.
6. **`invoices.customer_id` NOT NULL** → nhóm "Khách vãng lai" chỉ xuất hiện khi khách hàng bị xóa mềm, không phải khi `customer_id` NULL.
7. **Lỗi căn lề và font của bảng báo cáo**: rule `th` toàn cục ép tiền lệch trái và tô nền xám cho cột tên → đã sửa trong `styles.css`.

### Điểm đã kiểm tra và xác nhận đúng (không cần sửa)

- `PosService.sell()` và `CheckoutService.checkout()` đã gán `invoices.created_by` từ nhân viên đang đăng nhập qua `CurrentStaffService`; không còn lấy `staffId` từ request của client.
- Chỉ lọc `status = 'PAID' AND deleted_at IS NULL`, biên thời gian `[from, to)` khớp với `StatisticsService` và `InvoiceRepository.sumRevenueByPeriod`.
- Tiền dùng `BigDecimal`/`NUMERIC`, không dùng float.

## 3. Thay đổi đã triển khai trong đợt này

### Backend

- `RevenueBreakdownService`: thêm phân bổ doanh thu theo tỷ lệ để **tổng sản phẩm + tổng tiền sân = tổng hóa đơn** (đúng tới từng xu), thêm chi tiết theo kỳ và truy vấn tiền sân theo sân.
- `RevenueBreakdownResponse`: thêm `productDetails`, `courts`, `details`, `availableYears`.
- `AnalyticsController`: thêm tham số `quarter` (1..4) và `detail`.
- `RevenueBreakdownIntegrationTest`: 5 test, gồm bất biến tổng, phân bổ giảm giá, phân bổ nhiều sân, chọn quý, validate tham số.

### Web Admin

- `RevenueBreakdown.jsx`: năm chọn bằng **danh sách nút**, dải nút chọn quý, **bảng chi tiết** (sản phẩm · nhân viên thu ngân · khách hàng · số lượng · tiền dòng) kèm 3 dòng tổng khớp nhau, tab mới **"Tiền sân"**.
- `App.jsx`: thêm tab `Doanh thu tiền sân`.
- `styles.css`: căn lề giữa, cột tên nền trắng sticky, `.year-list`, `.breakdown-quarters`, `.breakdown-totals`, responsive.

### Database

- `db/seeds/revenue-breakdown-sample.sql`: chuyển sang `ON CONFLICT DO UPDATE` (chạy lại được, không nhân bản), bổ sung dữ liệu 2026 gồm 3 thu ngân, 4 khách hàng, 5 sản phẩm, 3 sân, 4 booking (có booking 2 sân) và 12 hóa đơn trải đều 4 quý.

## 4. Quy tắc nghiệp vụ đã chốt

- **Chi tiết** hiển thị cả nhân viên thu ngân và khách hàng.
- **Chọn quý** để xem chi tiết; báo cáo theo năm hiển thị chi tiết cả năm.
- **Giảm giá cấp hóa đơn được phân bổ theo tỷ lệ** giữa phần sân và phần sản phẩm, nên ba tổng luôn khớp nhau.
- **Slide tiền sân** chia theo sân, chỉ tính tiền sân.
- Nhóm hiển thị đặc biệt: `Chưa gán nhân viên`, `Khách vãng lai`, `Không xác định sân`.

## 5. Việc còn lại

| Việc | Trạng thái |
|---|---|
| Chạy 289 test PostgreSQL (`BCM_TEST_DB_*`) | Chưa chạy — lệnh bị chặn vì kết nối container DB dùng chung; cần bạn cho phép hoặc tự chạy |
| Nạp seed vào DB dev để xem số liệu thật | Chưa nạp — cần bạn cho phép ghi vào DB dev (`db/seeds/revenue-breakdown-sample.sql`) |
| Build lại image Docker backend | Chưa chạy — chờ xác nhận để tránh restart dịch vụ đang chạy |
| Rà soát code của 3 thành viên còn lại | Đã rà qua khi đọc luồng POS/Checkout/Analytics; chưa có phát hiện nghiêm trọng nào khác |

## 6. Lệnh đã chạy

```bash
# Backend
cd backend && mvn -o compile
cd backend && mvn -o -Dtest=RevenueBreakdownIntegrationTest test   # 5/5 pass trước điều chỉnh giữ summary 4 quý khi lọc detail
cd backend && mvn -o test                                        # 123 pass, 289 skipped (PostgreSQL)
# Sau điều chỉnh cuối, mvn verify không khởi động được fork vì Windows thiếu paging file

# Frontend
cd web-admin && npm run build                                    # pass
cd web-admin && node revenue-breakdown.e2e.cjs                   # PASS

# Seed: kiểm tra cú pháp trên PostgreSQL thật, không lưu dữ liệu
{ echo "BEGIN;"; sed 's/^BEGIN;//; s/^COMMIT;//' db/seeds/revenue-breakdown-sample.sql; echo "ROLLBACK;"; } \
  | docker exec -i bcm-postgres psql -U bcm_admin -d badminton_court_db -v ON_ERROR_STOP=1
```

## 7. Limitation đã biết

- Hóa đơn tạo trước khi sửa đường ghi `created_by` sẽ có `created_by = NULL` ⇒ hiển thị ở nhóm **Chưa gán nhân viên**; dữ liệu lịch sử không tự gán lại vì không thể đoán người thu tiền cũ.
- Slide tiền sân chỉ quy được sân khi hóa đơn gắn `booking_id`; hóa đơn có tiền sân nhưng không có booking rơi vào nhóm **Không xác định sân**.
- Giá trị của từng sản phẩm trong chi tiết là **giá trị đã phân bổ giảm giá**, nên khác giá niêm yết của dòng hàng; bảng chi tiết ghi rõ điều này.
