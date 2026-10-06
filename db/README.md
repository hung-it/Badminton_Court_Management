# 🗄️ Database Setup với Docker

Folder này chứa migration script để khởi tạo PostgreSQL database cho dự án.

## 📁 Files

- `migration.sql` - Script tạo 20 bảng + indexes + seed data

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
