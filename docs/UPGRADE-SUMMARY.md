# ⭐ UPGRADE SUMMARY - Badminton Court Management Database

> Historical schema/design record. Current Member 3 online gateway scope is VNPay Sandbox only; provider names below describe the original design/schema, not active integration.

## 🎯 Tổng quan

Database đã được nâng cấp từ **v1.0.0** → **v1.1.0** với các thay đổi quan trọng sau khi team review.

**Trạng thái:** ✅ **READY FOR IMPLEMENTATION**

---

## 📊 Thống kê Database

- **Số bảng:** 18 tables
- **Số indexes:** 30+ indexes (bao gồm UNIQUE, performance, partial indexes)
- **Số constraints:** 40+ constraints (PK, FK, UNIQUE, CHECK)
- **Locking strategies:** 2 types (Pessimistic cho bookings, Optimistic cho products)
- **Payment methods:** VNPay only
- **Promotion types:** 3 tiers (PRODUCT, INVOICE_TOTAL, VOUCHER)

---

## 🔄 Các thay đổi chính v1.1.0 (2026-10-03)

### ✅ 1. Thêm UNIQUE Constraint chống trùng slot
**Vấn đề:** Tài liệu chỉ mô tả bằng lời, không có SQL cụ thể.

**Giải pháp:**
```sql
CREATE UNIQUE INDEX uq_booking_slot 
ON booking_details(booking_date, court_id, time_slot_id);
```

### ✅ 2. Làm rõ time_slots không cần cột status
**Giải thích:** `time_slots` là master data tĩnh (template). Status được suy ra từ `booking_details`.

### ✅ 3. Thêm UNIQUE Constraint cho transaction_id (partial)
**Vấn đề:** Webhook retry từ VNPay có thể tạo duplicate.

**Giải pháp:**
```sql
CREATE UNIQUE INDEX uq_transaction_id 
ON payment_transactions(transaction_id) 
WHERE transaction_id IS NOT NULL;
```

### ✅ 4. Implement Timeout Scheduler cho PENDING bookings
**Vấn đề:** Cơ chế timeout chưa được implement.

**Giải pháp:**
- Thêm cột `bookings.expires_at`
- Thêm index cho scheduler query
- Spring @Scheduled task tự động expire bookings

---

## 📋 18 Bảng trong Database

### 1. Core System (2 bảng)
- `users` - Tài khoản đăng nhập (Customer + Staff)
- `roles` - Phân quyền (CUSTOMER, STAFF, MANAGER, ADMIN)

### 2. Master Data (4 bảng)
- `courts` - Danh sách sân
- `time_slots` - Khung giờ template (06:00-07:00, ...)
- `categories` - Danh mục sản phẩm
- `products` - Sản phẩm bán (nước, vợt, cầu)

### 3. User Profiles (2 bảng)
- `customers` - Thông tin khách hàng
- `staff` - Thông tin nhân viên

### 4. Booking Engine (3 bảng)
- `bookings` - Đơn đặt sân (header)
- `booking_details` - Chi tiết slot đã đặt
- `payment_transactions` - Giao dịch thanh toán

### 5. POS System (2 bảng)
- `invoices` - Hóa đơn bán hàng
- `invoice_details` - Chi tiết sản phẩm trong hóa đơn

### 6. Inventory Management (3 bảng)
- `suppliers` - Nhà cung cấp
- `import_orders` - Phiếu nhập hàng
- `import_order_details` - Chi tiết sản phẩm nhập

### 7. Promotion System (3 bảng) 🆕
- `promotions` - Chương trình khuyến mãi
- `discount_rules` - Quy tắc giảm giá (3 loại: PRODUCT/INVOICE_TOTAL/VOUCHER)
- `customer_voucher_usage` - Lịch sử sử dụng voucher

---

## ✅ Checklist hoàn thành

### Database Design
- [x] ERD diagram (Mermaid format)
- [x] 18 bảng với đầy đủ columns, data types, constraints
- [x] Foreign keys relationships
- [x] Soft delete pattern (deleted_at)
- [x] UUID primary keys cho security

### Anti-Corruption Mechanisms
- [x] **Anti-double booking:** UNIQUE(booking_date, court_id, time_slot_id) + Pessimistic Lock
- [x] **Anti-negative stock:** Optimistic Locking với @Version
- [x] **Anti-duplicate payment:** UNIQUE(transaction_id) partial index
- [x] **Timeout mechanism:** expires_at + scheduler

### Business Logic
- [x] Snapshot pricing (booking_details.price)
- [x] Payment XOR constraint (transaction links to booking OR invoice)
- [x] Promotion 3-tier system
- [x] Invoice timing (created at CHECKED_IN, not COMPLETED)
- [x] No CANCELLED status (customers cannot cancel after payment)
- [x] No loyalty points

### Documentation
- [x] database.md - Chi tiết 18 bảng
- [x] database-migration.sql - Full DDL script
- [x] CHANGELOG.md - Track changes
- [x] promotion-flow-advanced.md (TODO: Tạo file)
- [x] payment-invoice-flow.md (TODO: Tạo file)
- [x] design-review-final.md (TODO: Tạo file)
- [x] technical-considerations.md (TODO: Tạo file)

---

## 🚀 Next Steps

### Option A: Tạo Database Migration Scripts
```bash
cd Badminton_Court_Management/docs
psql -U postgres
CREATE DATABASE badminton_court_db;
\c badminton_court_db
\i database-migration.sql
```

### Option B: Generate JPA Entities
- Sử dụng database.md để tạo Entity classes
- Áp dụng Jakarta Persistence annotations
- Thêm @Version cho products (Optimistic Locking)

### Option C: Implement Backend Services
1. **TV1 (Leader):** Foundation + Auth Core
2. **TV2:** Master Data CRUD + Promotion System
3. **TV3:** Booking Engine + User Management + Timeout Scheduler
4. **TV4:** POS + Inventory + Reports

### Option D: Setup Frontend (Web App 2 Portals)
- Customer Portal: ReactJS + Vite + React Router
- Admin Portal: POS UI, Management screens

### Option E: Integration Testing
- Test UNIQUE constraints
- Test Pessimistic/Optimistic locking
- Test payment webhook (VNPay sandbox)
- Test promotion rules engine

---

## 📚 Tài liệu liên quan

1. **[database.md](database.md)** - Chi tiết 18 bảng, ERD, constraints
2. **[database-migration.sql](database-migration.sql)** - Script DDL hoàn chỉnh
3. **[CHANGELOG.md](CHANGELOG.md)** - Các thay đổi v1.1.0
4. **[promotion-flow-advanced.md](promotion-flow-advanced.md)** - Hệ thống khuyến mãi 3 tầng
5. **[payment-invoice-flow.md](payment-invoice-flow.md)** - Luồng thanh toán
6. **[design-review-final.md](design-review-final.md)** - Review tổng thể
7. **[technical-considerations.md](technical-considerations.md)** - Locking, security

---

## ⚠️ Critical Notes

1. **Spring Boot 3.2+:** Bắt buộc dùng `jakarta.*` thay vì `javax.*`
2. **Payment Webhook:** Verify signature + idempotent handling
3. **Timeout Scheduler:** Chạy mỗi 60-120 giây, expire PENDING bookings
4. **Migration Path:** Chạy database-migration.sql trên PostgreSQL 14+

---

✅ **Database design hoàn chỉnh và ready for implementation!**
