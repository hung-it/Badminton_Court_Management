# 🚨 URGENT: Team Discussion Required - Booking Timeout Design

**Raised by:** Thành viên 3 (Booking Engine)  
**Date:** 2026-10-04  
**Priority:** HIGH - Blocking TV3's implementation

---

## ⚠️ Vấn đề phát hiện

PDF yêu cầu: **Tự động giải phóng slot sau 5-10 phút nếu PENDING không thanh toán**

Database hiện tại có **CONFLICT**:

```sql
-- ✅ Có timeout mechanism
bookings.expires_at TIMESTAMP
bookings.status = 'EXPIRED'

-- ❌ Nhưng có UNIQUE constraint cứng
CREATE UNIQUE INDEX uq_booking_slot ON booking_details(
  booking_date, court_id, time_slot_id
);
```

### Kịch bản lỗi:

1. User A đặt slot (2024-10-05, Court 1, 8:00-9:00) → PENDING
2. Sau 10 phút: Cronjob đổi status → EXPIRED
3. **booking_details vẫn còn** (với unique index)
4. User B đặt cùng slot → **❌ UNIQUE VIOLATION**

**Kết luận:** Slot không bao giờ được giải phóng!

---

## 🎯 Cần quyết định: XÓA hay GIỮ booking_details?

### Option 1: XÓA booking_details khi EXPIRED ⭐ (Khuyến nghị)

**Cách hoạt động:**
```
PENDING timeout → EXPIRED → DELETE booking_details
                          → Slot freed for others
                          → bookings record vẫn còn
```

**Ưu điểm:**
- Slot giải phóng ngay
- Đơn giản (CASCADE sẵn có)
- Phù hợp business: timeout = hủy

**Nhược điểm:**
- Mất chi tiết "slot nào bị timeout"

**Code:**
```java
// Cronjob chỉ cần:
bookingRepository.deleteById(expiredBooking.getId());
// → booking_details tự động xóa (CASCADE)
```

---

### Option 2: GIỮ booking_details + Sửa UNIQUE INDEX

**Cách hoạt động:**
```
PENDING timeout → EXPIRED → Giữ booking_details
                          → Unique chỉ apply cho ACTIVE bookings
```

**Ưu điểm:**
- Giữ lịch sử đầy đủ

**Nhược điểm:**
- **PostgreSQL không support** partial index với subquery
- Phức tạp, performance kém

**Không khả thi!**

---

### Option 3: Soft Delete booking_details

**Cách hoạt động:**
```
PENDING timeout → EXPIRED → UPDATE booking_details SET deleted_at = NOW()
```

**Ưu điểm:**
- Giữ lịch sử, có thể restore

**Nhược điểm:**
- Phức tạp nhất
- Cần migration thêm column
- Mọi query phải `WHERE deleted_at IS NULL`

---

## 💡 Recommendation: **Option 1**

### Lý do:

1. **Business logic phù hợp:**  
   Timeout = User bỏ rơi đơn → Xóa hoàn toàn hợp lý

2. **Audit vẫn đủ:**
   ```sql
   SELECT id, customer_id, court_fee, expires_at 
   FROM bookings WHERE status = 'EXPIRED';
   ```
   → Biết ai timeout, khi nào, bao nhiêu tiền  
   → Chỉ thiếu "slot cụ thể nào" (ít quan trọng)

3. **Đơn giản:**  
   ON DELETE CASCADE sẵn có → 0 migration change

4. **Performance:**  
   Unique index đơn giản, không JOIN

---

## 📋 Action Items

### 1. Team Leader (Ngay)
- [ ] Review ADR-001-booking-timeout-strategy.md
- [ ] Quyết định Option 1/2/3
- [ ] Thông báo team

### 2. TV3 - Booking Engine (Chờ quyết định)
- [ ] Implement cronjob theo option đã chọn
- [ ] Viết test: timeout → slot giải phóng
- [ ] Document trong README

### 3. TV4 - Payment (Cần biết)
- [ ] Nếu Option 1: Payment job phải check booking tồn tại
- [ ] VNPay callback có thể nhận booking đã bị xóa

---

## 🕐 Timeline

**Cần quyết định trong:** 24 giờ  
**Lý do:** TV3 cần biết để implement expiration logic

---

## 📞 Discussion

**Where:** Team meeting / Slack / GitHub Issue  
**When:** ASAP

**Questions to answer:**
1. Có cần giữ chi tiết slot nào bị timeout? (Audit requirement)
2. Có case nào cần restore expired booking không?
3. Team có bandwidth cho Option 3 phức tạp không?

---

**Full technical details:** `docs/ADR-001-booking-timeout-strategy.md`
