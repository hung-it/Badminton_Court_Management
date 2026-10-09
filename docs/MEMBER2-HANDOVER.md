

## Phạm vi
BE Spring Boot, FE React Web Admin, DB PostgreSQL cho `courts`, `time_slots`, `categories`, `products`.
Không triển khai booking engine, hóa đơn, nhập/xuất kho, upload ảnh, Git hoặc deployment.

## Chạy trên máy hiện tại
Đã chuẩn bị JDK 17, Maven portable `.tools/apache-maven-3.9.9`, PostgreSQL portable `.tools/pgsql`; Node.js có sẵn.
Mở các terminal riêng ở thư mục gốc:
```powershell
powershell -ExecutionPolicy Bypass -File scripts/start-local-db.ps1 -Demo
powershell -ExecutionPolicy Bypass -File scripts/start-backend.ps1
powershell -ExecutionPolicy Bypass -File scripts/start-frontend.ps1
```
FE: http://127.0.0.1:5173; BE: http://localhost:8080/api; Swagger: http://localhost:8080/api/swagger-ui.html.
Admin phát triển: `admin@bcm.com` / `admin123` (nền tảng seed sẵn).
Database riêng: `bcm_member2_test`, cổng `55432`, user `postgres`. Cluster chỉ nghe 127.0.0.1 và dùng trust cho phát triển cục bộ, không dùng cấu hình này cho server chia sẻ/production.
Dừng FE/BE bằng Ctrl+C trong terminal tương ứng; dừng DB bằng `scripts/stop-local-db.ps1`.
Không đổi database cũ ở cổng 5433.

## Máy khác / leader
Cần JDK 17, Maven 3.8+, Node.js 22+ và PostgreSQL 15+.
`.tools` là công cụ/dữ liệu cục bộ, không commit. `node_modules`, `dist`, `target` cũng được bỏ qua.
1. Database mới: chạy `db/migration.sql`, rồi `db/member2-upgrade.sql`.
2. Database đã có schema: chỉ chạy `db/member2-upgrade.sql` sau khi sao lưu và kiểm tra dữ liệu hiện có.
3. Upgrade không ghi đè giá/tồn hiện có. Dữ liệu cũ trùng tên danh mục, chồng khung giờ hoặc giá/tồn không hợp lệ sẽ khiến migration dừng để sửa dữ liệu có chủ đích.
4. Seed demo là tùy chọn `db/member2-demo.sql`.
5. Đặt biến môi trường `DB_URL`, `DB_USER`, `DB_PASSWORD`; chạy backend với profile `local`, hoặc sửa cấu hình database theo môi trường của nhóm.
6. FE: `npm ci`, `npm run dev`; build: `npm run build`.

## Quy tắc và quyết định tích hợp
- Sân: số sân duy nhất kể cả sân xóa mềm; tên bắt buộc; loại STANDARD_MAT/WOODEN_FLOOR; trạng thái AVAILABLE/MAINTENANCE/CLOSED theo schema.
- Xóa mềm sân chỉ khi không có booking PENDING/PAID/CHECKED_IN. Giữ lịch sử booking_details.
- Khung giờ trong cùng ngày, chính xác đến phút, start < end, không overlap; các khung liền kề được phép.
- Hệ số mặc định: khung nằm trọn 17–21h là 1.50, giờ khác 1.00. Khung băng qua 17h/21h phải chia hoặc nhập hệ số riêng. Hệ số tùy chỉnh >0, tối đa 9.99.
- Giá mỗi khung = base_price × multiplier, làm tròn 2 chữ số; không nhân thêm số giờ, đúng ERD. Không ghi đè giá chốt booking_details.
- Khung đã có booking không được xóa hoặc sửa giờ; vẫn được sửa hệ số cho lượt đặt mới.
- Danh mục không trùng tên (không phân biệt hoa/thường); chặn xóa khi còn sản phẩm chưa xóa.
- Sản phẩm: tên/đơn vị bắt buộc, giá ≥0, tồn ≥0. SERVICE tồn =0. Tồn ban đầu nhập khi tạo; cập nhật metadata không chỉnh tồn. Các nghiệp vụ kho thuộc thành viên khác.
- Optimistic locking `version`: PUT sản phẩm cần gửi version đã đọc. Xung đột trả 409.
- `unit`, `image_url` thêm vào products; `deleted_at` thêm vào time_slots. Backend chuẩn bị trường imageUrl tùy chọn nhưng FE chưa xử lý hình ảnh theo yêu cầu.
- API quản trị chỉ ADMIN. Public API chỉ GET, không trả tồn kho hoặc audit fields sản phẩm.
- Booking engine khi tích hợp phải từ chối sân/khung xóa mềm, sân không hoạt động; phối hợp khóa/transaction với thao tác xóa để xử lý đặt sân đồng thời. Không triển khai module booking của thành viên khác.

## API
Prefix `/api`.
| Đường dẫn | Phương thức | Quyền |
|---|---|---|
| `/admin/courts` | GET, POST | ADMIN |
| `/admin/courts/{id}` | GET, PUT, DELETE | ADMIN |
| `/admin/time-slots` | GET, POST | ADMIN |
| `/admin/time-slots/{id}` | GET, PUT, DELETE | ADMIN |
| `/admin/categories` | GET, POST | ADMIN |
| `/admin/categories/{id}` | GET, PUT, DELETE | ADMIN |
| `/admin/products` | GET, POST | ADMIN |
| `/admin/products/{id}` | GET, PUT, DELETE | ADMIN |
| `/public/courts` | GET (chỉ sân hoạt động) | Public |
| `/public/time-slots` | GET | Public |
| `/public/categories` | GET | Public |
| `/public/products?categoryId={uuid}` | GET, filter tùy chọn | Public |
| `/public/price-quote?courtId={uuid}&timeSlotId={uuid}` | GET | Public |

Request mẫu:
```json
{"courtNumber":1,"name":"Sân 01","type":"STANDARD_MAT","status":"AVAILABLE","basePrice":100000}
```
```json
{"startTime":"17:00","endTime":"18:00","priceMultiplier":1.5}
```
```json
{"categoryName":"Nước giải khát"}
```
```json
{"categoryId":"UUID","name":"Nước suối","type":"GOODS","unit":"chai","price":10000,"stockQuantity":50}
```
POST trả 201; PUT/GET/DELETE trả 200; 400 validation/nghiệp vụ; 404 không tồn tại; 409 trùng/xung đột. Phản hồi theo ApiResponse hiện có.

## Kiểm thử
- JUnit/MockMvc: 8 bài, 0 lỗi, chạy profile H2 độc lập. Bao gồm CRUD, validation, overlap, hệ số tùy chỉnh, giá cao điểm, booking chưa hoàn tất, giữ lịch sử, service stock, version, quyền Admin/public.
- Live HTTP PostgreSQL: 32 kiểm tra đạt, dùng JWT thật và schema migration thật. Script `scripts/verify-member2.py` chỉ chạy với DB kiểm thử riêng, backend đang bật và PostgreSQL portable hiện tại. Yêu cầu Python 3. Test tạo/xóa fixture; master data xóa mềm vẫn giữ trong DB test.
- FE build đạt; npm audit 0 vulnerabilities sau cập nhật Vite 6.4.4.
- Chrome headless: đăng nhập, CRUD sân, tính giá cao điểm, thêm/xóa danh mục & sản phẩm, validation form, viewport 390px không tràn ngang; 0 lỗi JavaScript. Đã xem ảnh giao diện desktop/mobile.
- Script kiểm thử UI: `scripts/verify-member2-ui.mjs`, cần Playwright và Chrome. Chỉ dùng database demo/test đang chạy.

Chạy test backend:
```powershell
mvn -f backend/pom.xml test
```

## Demo nghiệm thu
1. Đăng nhập Admin, xem sơ đồ bốn sân và trạng thái bảo trì.
2. Thêm/sửa sân; kiểm tra tên rỗng và giá âm.
3. Xem khung 17–18h ×1.5; chọn sân gốc 100.000 → giá 150.000.
4. Thử tạo khung giờ chồng lấn; kiểm tra bị từ chối.
5. Thêm danh mục, hàng hóa và dịch vụ; dịch vụ tồn 0.
6. Thử xóa danh mục còn sản phẩm; kiểm tra bị chặn.
7. Kiểm tra public API không cần token, admin API có phân quyền.
8. Test booking chưa hoàn tất bằng bộ test; không cần triển khai UI booking ngoài phạm vi.
