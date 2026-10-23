# 🗄️ Database Setup với Docker

Folder này chứa migration script để khởi tạo PostgreSQL database cho dự án.

## 📁 Files

- `migration.sql` - Script tạo 20 bảng + indexes + seed data
- `court-demo-seed.sql` - Seed cộng thêm 5 sân mẫu, an toàn khi chạy lại
- `booking-ux-upgrade.sql` - Upgrade cộng cột nullable `bookings.customer_note` (1000 ký tự)
  và `courts.district` (150 ký tự), chạy lặp an toàn, không backfill/quy đổi địa giới.
- `demo-districts-seed.sql` - Opt-in bảy cơ sở `[DEMO]` với UUID/số sân cố định,
  bảy nhãn khu vực demo theo yêu cầu đồ án; không phải địa chỉ thật hoặc danh mục địa giới hiện hành.

## Seed bảy khu vực cho môi trường demo đã xác nhận

Backup trước, áp dụng `booking-ux-upgrade.sql`, xác nhận database local/demo rồi mới bật opt-in:

```powershell
docker compose cp db/demo-districts-seed.sql postgres:/tmp/demo-districts-seed.sql
docker compose exec -T postgres psql -U bcm_admin -d badminton_court_db -v ON_ERROR_STOP=1 -c "SET bcm.allow_demo_seed=on" -f /tmp/demo-districts-seed.sql
docker compose exec -T postgres psql -U bcm_admin -d badminton_court_db -c "SELECT district, COUNT(*) FROM courts WHERE district IS NOT NULL GROUP BY district ORDER BY district;"
```

Không bật opt-in trong production. Script mặc định từ chối nếu chưa `SET bcm.allow_demo_seed=on`;
không mount tự chạy khi Docker init. Nhãn demo: Quận 1, Quận 3, Quận 5, Quận 7,
Bình Thạnh, Tân Bình, Gò Vấp. UUID namespace `bcdd0000-0000-4000-8000-000000000001..007`,
số sân `10001..10007`; xung đột định danh với record khác sẽ dừng transaction.
Chạy lại không thêm trùng và không ghi đè district/giá/status của record đã tồn tại.
Không gán quận cho năm sân cũ dù tên sân chứa địa danh. Schema không có cột địa chỉ sân:
vị trí của từng cơ sở DEMO được biểu diễn đúng bằng nhãn district, không bịa số nhà/tên đường.

Lượt khôi phục 2026-10-23 đã backup main DB, chạy migration và seed: 7 district, 12 sân
(5 cũ giữ district NULL, 7 mới có nhãn DEMO). Booking/payment vẫn 10/6; chi tiết kiểm chứng và backup
ở `docs/runtime-realtime-2026-10-23.md`. Không reset hoặc xóa volume để sửa missing-column.

## Upgrade Courts / Checkout trên database hiện có

Repository dùng SQL chạy thủ công và Docker init scripts; không có Flyway/Liquibase.
Không sửa `migration.sql` lịch sử. Trước khi chạy backend có mapping mới, áp dụng:

```powershell
docker cp db/booking-ux-upgrade.sql bcm-postgres:/tmp/booking-ux-upgrade.sql
docker exec bcm-postgres psql -U bcm_admin -d badminton_court_db -v ON_ERROR_STOP=1 -f /tmp/booking-ux-upgrade.sql
```

Compose chạy upgrade này sau schema gốc khi tạo volume mới. Với volume cũ, init scripts
không chạy lại nên cần lệnh trên; giữ `ddl-auto: validate`, SQL init `never`.
Upgrade không xóa bảng/dữ liệu, không đổi deadline/status/payment note và không cần index mới
vì catalog sân hiện trả toàn bộ danh sách để lọc ở client.

Quận/huyện là nhãn vị trí do quản trị viên xác minh qua dữ liệu địa chỉ thực tế,
không suy từ tên sân và không lấy địa chỉ khách hàng làm địa chỉ sân. Điền bằng field optional
`district` trong API quản lý sân hiện có (`POST/PUT /api/admin/courts`). Bỏ field khi update
giữ giá trị cũ; chuỗi trống xóa nhãn về NULL. Sân chưa xác minh giữ NULL, vẫn xuất hiện khi
không áp dụng bộ lọc quận. Danh sách checkbox lấy từ catalog backend, không dùng danh mục địa giới hardcode.

## 🚀 Quick Start

```bash
# Khởi động database
docker-compose up -d

# Kiểm tra logs
docker-compose logs -f

# Dừng database
docker-compose down

# Xóa database và tạo lại từ đầu
docker-compose down -v
docker-compose up -d
```

Compose chạy `migration.sql` rồi `court-demo-seed.sql` khi khởi tạo volume PostgreSQL mới.
Với volume đã tồn tại, init scripts không được chạy lại. Từ PowerShell tại thư mục gốc,
nạp seed cộng thêm bằng lệnh sau:

```powershell
$seedSql = Get-Content .\db\court-demo-seed.sql -Raw -Encoding utf8
docker compose exec -T postgres psql -X -U bcm_admin -d badminton_court_db -v ON_ERROR_STOP=1 -c $seedSql
if ($LASTEXITCODE -ne 0) { throw 'Court demo seed failed.' }
```

Lệnh này chỉ thêm các sân còn thiếu theo `court_number`; nếu các số sân 1–5 đã có dữ liệu
khác, transaction dừng và giữ nguyên dữ liệu hiện có. Không cần xóa hoặc tạo lại volume.

## 🔗 Kết nối Database

```yaml
Host: localhost
Port: 5433
Database: badminton_court_db
Username: bcm_admin
Password: 12345
```

## 📊 Verify Database

```bash
# Kiểm tra tables
docker exec bcm-postgres psql -U bcm_admin -d badminton_court_db -c "\dt"

# Kiểm tra roles
docker exec bcm-postgres psql -U bcm_admin -d badminton_court_db -c "SELECT * FROM roles;"

# Kiểm tra time slots
docker exec bcm-postgres psql -U bcm_admin -d badminton_court_db -c "SELECT * FROM time_slots;"
```
