# CHANGELOG - Database Design Updates

> Historical schema/design record. Current Member 3 online gateway scope is VNPay Sandbox only; provider names below describe the original design/schema, not active integration.

## [1.1.0] - 2026-10-03

### 🔧 Fixed Critical Issues (Phản hồi từ team review)

#### 1. ✅ Thêm UNIQUE Constraint chống trùng slot booking
**Vấn đề:** Tài liệu chỉ mô tả bằng lời, không có SQL cụ thể.

**Giải pháp:**
```sql
CREATE UNIQUE INDEX uq_booking_slot 
ON booking_details(booking_date, court_id, time_slot_id);
```

**Lý do:** Ngăn 2 người đặt cùng 1 slot (date + court + time_slot) cùng lúc.

---

#### 2. ✅ Làm rõ time_slots.status không cần thiết
**Vấn đề:** Team thắc mắc tại sao `time_slots` không có cột `status`.

**Giải thích:**
- `time_slots` là **master data tĩnh** (template khung giờ: 06:00-07:00, 07:00-08:00, ...)
- Status "đã đặt" hay "còn trống" được **suy ra từ bảng `booking_details`**:
  - Có record `(date, court, time_slot)` → BOOKED
  - Không có record → AVAILABLE

**Cập nhật tài liệu:** Thêm phần giải thích chi tiết trong section 3.2 và 7.2.

---

#### 3. ✅ Thêm UNIQUE constraint cho transaction_id (partial)
**Vấn đề:** `transaction_id` nullable nhưng không có UNIQUE → có thể duplicate webhook từ VNPay.

**Giải pháp:**
```sql
CREATE UNIQUE INDEX uq_transaction_id 
ON payment_transactions(transaction_id) 
WHERE transaction_id IS NOT NULL;
```

**Lý do:**
- `transaction_id` nullable hợp lý (CASH/BANK_TRANSFER không có mã GD)
- UNIQUE partial index ngăn duplicate từ webhook retry
- Tránh charge khách 2 lần cho cùng 1 giao dịch

---

#### 4. ✅ Triển khai cơ chế Timeout Scheduler
**Vấn đề:** Giữ slot dựa trên PENDING + created_at nhưng scheduler chưa được implement.

**Giải pháp:**

**A. Thêm cột `expires_at` vào bảng `bookings`:**
```sql
ALTER TABLE bookings ADD COLUMN expires_at TIMESTAMP;

ALTER TABLE bookings ADD CONSTRAINT chk_pending_expires 
CHECK (status != 'PENDING' OR expires_at IS NOT NULL);
```

**B. Thêm index cho scheduler query:**
```sql
CREATE INDEX idx_bookings_expires 
ON bookings(expires_at) 
WHERE status = 'PENDING' AND expires_at IS NOT NULL;
```

**C. Spring Scheduler implementation:**
```java
@Scheduled(fixedRate = 60000) // Chạy mỗi 60 giây
public void releaseExpiredBookings() {
    int updated = bookingRepository.expireBookings();
    // UPDATE bookings SET status = 'EXPIRED' 
    // WHERE status = 'PENDING' AND expires_at < NOW()
    
    if (updated > 0) {
        log.info("Released {} expired bookings", updated);
    }
}
```

**D. Logic khi tạo booking:**
```java
booking.setStatus(BookingStatus.PENDING);
booking.setExpiresAt(LocalDateTime.now().plusMinutes(15)); // 15 phút timeout
bookingRepository.save(booking);
```

**Kết quả:** 
- Slot PENDING được giữ trong 15 phút
- Scheduler tự động chuyển sang EXPIRED nếu không thanh toán
- Giải phóng slot cho khách khác đặt

---

### 📝 Các thay đổi khác

#### Updated ERD (Mermaid diagram)
- Thêm cột `bookings.expires_at`
- Cập nhật relationship descriptions

#### Updated Section 3.3 (Booking Engine)
- Bổ sung giải thích chi tiết về Timeout Mechanism
- Thêm code example cho Scheduler
- Làm rõ `transaction_id` nullable + UNIQUE partial

#### Updated Section 7 (Design Decisions)
- Thêm section 7.2: Time Slots Design (giải thích không cần status)
- Thêm section 7.4: Payment Transaction Constraints (chi tiết hơn)
- Thêm section 7.7: Critical DDL Statements (tập hợp SQL quan trọng)

#### New File: database-migration.sql
- Full DDL script để tạo database từ đầu
- Bao gồm tất cả tables, constraints, indexes
- Seed data mẫu cho roles và time_slots

---

### 🎯 Checklist hoàn thành

- [x] Thêm UNIQUE constraint chống trùng slot
- [x] Giải thích time_slots không cần status
- [x] Thêm UNIQUE constraint cho transaction_id (partial)
- [x] Implement timeout scheduler mechanism
- [x] Cập nhật ERD diagram
- [x] Cập nhật tài liệu database.md
- [x] Tạo file migration SQL
- [x] Đồng bộ tài liệu với database design

---

### 📚 Files đã thay đổi

1. `database.md` - Cập nhật sections 3.2, 3.3, 7.x
2. `database-migration.sql` - **NEW** - Full DDL script
3. `CHANGELOG.md` - **NEW** - Track changes

---

### 🚀 Next Steps

1. **Team Review:** Xem lại các thay đổi trong `database.md`
2. **Run Migration:** Execute `database-migration.sql` trên PostgreSQL local
3. **Test Constraints:** Verify các constraints hoạt động đúng
4. **Implement Scheduler:** Code Spring @Scheduled task theo mẫu trên
5. **Unit Tests:** Viết tests cho timeout mechanism

---

## [1.0.0] - 2026-10-01

### Initial Release
- 18 bảng với promotion system 3-tier
- Loại bỏ loyalty points
- Loại bỏ CANCELLED status
- Xóa customers.email (dùng users.email)
- Payment flow rõ ràng (booking vs invoice)
