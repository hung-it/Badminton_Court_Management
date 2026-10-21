# 🗄️ Database Setup với Docker

Folder này chứa migration script để khởi tạo PostgreSQL database cho dự án.

## 📁 Files

- `migration.sql` - Script tạo 20 bảng + indexes + seed data
- `court-demo-seed.sql` - Seed cộng thêm 5 sân mẫu, an toàn khi chạy lại

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
