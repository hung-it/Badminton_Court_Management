# Database Design Review - Toàn Bộ Thiết Kế

Tài liệu tổng kết review toàn bộ thiết kế database sau khi nâng cấp lên hệ thống khuyến mãi 3 tầng.

## ✅ Tổng Quan Sau Review

| Aspect | Status | Notes |
|--------|--------|-------|
| **Tổng số bảng** | 18 bảng | Tăng từ 15 → 18 (thêm promotions, discount_rules, customer_voucher_usage) |
| **Anti-double booking** | ✅ Hoàn chỉnh | DB Constraint + Pessimistic Lock |
| **Anti-negative stock** | ✅ Hoàn chỉnh | Optimistic Locking với @Version |
| **Payment flow** | ✅ Rõ ràng | Constraint: 1 transaction = 1 target (booking XOR invoice) |
| **Promotion system** | ✅ Enterprise-grade | 3 loại rules: PRODUCT/INVOICE_TOTAL/VOUCHER |
| **Invoice creation** | ✅ Xác định | Tạo lúc CHECKED_IN (DRAFT) |
| **Booking cancellation** | ✅ Loại bỏ | Không có CANCELLED status |
| **Indexes** | ✅ Đầy đủ | 30+ indexes cho performance |
| **Constraints** | ✅ Đầy đủ | CHECK, UNIQUE, FK với ON DELETE behaviors |
| **Soft delete** | ✅ Nhất quán | deleted_at trên tất cả master tables |

---

## 📊 Cấu Trúc Database (18 Bảng)

### Nhóm 1: Authentication & User Management (3 bảng)
1. **users** - Đăng nhập (email + password)
2. **customers** - Khách hàng (tham chiếu users.id)
3. **staffs** - Nhân viên (tham chiếu users.id)

### Nhóm 2: Court Management (3 bảng)
4. **courts** - Sân cầu lông (base_price)
5. **time_slots** - Khung giờ (price_multiplier, day_of_week)
6. **bookings** - Đơn đặt sân (PENDING → PAID → CHECKED_IN → COMPLETED/NO_SHOW)
7. **booking_details** - Chi tiết đặt sân (UNIQUE: date + court + timeslot)

### Nhóm 3: POS & Inventory (6 bảng)
8. **categories** - Danh mục sản phẩm
9. **products** - Sản phẩm (stock_quantity, version cho Optimistic Lock)
10. **invoices** - Hóa đơn (court_fee + product_fee - discount_amount)
11. **invoice_details** - Chi tiết hóa đơn (quantity × unit_price)
12. **suppliers** - Nhà cung cấp
13. **import_orders** - Phiếu nhập hàng (DRAFT → CONFIRMED → RECEIVED)
14. **import_order_details** - Chi tiết phiếu nhập

### Nhóm 4: Promotion System (3 bảng)
15. **promotions** - Master table (code, valid_from/to, is_active)
16. **discount_rules** - Chi tiết rules (PRODUCT/INVOICE_TOTAL/VOUCHER)
17. **customer_voucher_usage** - Tracking voucher đã dùng

### Nhóm 5: Payment (1 bảng)
18. **payment_transactions** - Giao dịch thanh toán (booking_id XOR invoice_id)

---

## 🎯 Các Quyết Định Thiết Kế Quan Trọng

### 1. ✅ Bỏ `CANCELLED` Status

**Trước:**
```sql
bookings.status IN ('PENDING', 'PAID', 'CHECKED_IN', 'COMPLETED', 'NO_SHOW', 'CANCELLED')
```

**Sau:**
```sql
bookings.status IN ('PENDING', 'PAID', 'CHECKED_IN', 'COMPLETED', 'NO_SHOW')
```

**Lý do:**
- Khách không được phép hủy sau khi đã thanh toán (business rule)
- CANCELLED gây confusion về refund policy
- Nếu cần cancel → Admin force update status sang NO_SHOW

---

### 2. ✅ Payment Transaction - XOR Constraint

**Constraint:**
```sql
ALTER TABLE payment_transactions
ADD CONSTRAINT chk_payment_target
CHECK (
    (booking_id IS NOT NULL AND invoice_id IS NULL) OR
    (booking_id IS NULL AND invoice_id IS NOT NULL)
);
```

**Lý do:**
- Mỗi transaction chỉ thanh toán cho 1 target (booking HOẶC invoice)
- Tránh ambiguity khi query báo cáo
- Dễ phân tách: Tiền sân (booking) vs Tiền hàng (invoice)

**Use Cases:**
- `booking_id != NULL, invoice_id = NULL` → Thanh toán tiền sân online
- `booking_id = NULL, invoice_id != NULL` → Thanh toán tiền hàng tại quầy

---

### 3. ✅ Invoice Creation Timing: CHECKED_IN

**Quyết định:**
- Invoice được tạo tự động khi booking chuyển sang **CHECKED_IN** (status DRAFT)
- Khách vãng lai: Invoice tạo khi bắt đầu mua hàng

**Timeline:**
```
15:30  →  15:35  →  17:50        →  18:30
Tạo      Thanh    CHECK-IN       Mua hàng
booking  toán     (Tạo invoice   (Cập nhật
         online   DRAFT)         invoice)
```

**Lý do:**
- Check-in = khách đã đến sân = bắt đầu phát sinh chi phí
- DRAFT invoice cho phép thu ngân thêm sản phẩm linh hoạt
- Khi chuyển sang PAID → Invoice finalized, không sửa được nữa

---

### 4. ✅ Bỏ `customers.email`, Chỉ Dùng `users.email`

**Trước:**
```sql
users.email       -- Email đăng nhập
customers.email   -- Email liên hệ (có thể khác users.email)
```

**Sau:**
```sql
users.email       -- DUY NHẤT 1 email
-- BỎ customers.email
```

**Lý do:**
- Single source of truth
- Tránh confusion khi gửi email (dùng email nào?)
- Đơn giản hóa logic reset password, email marketing

---

### 5. ✅ Nâng Cấp Promotion: 1 Bảng → 3 Bảng

**Trước (Phương án B - Tối giản):**
```sql
promotions (
    code, discount_type, discount_value, 
    min_invoice_amount, valid_from, valid_to
)
```

**Sau (Phương án A - Nâng cao):**
```sql
promotions (
    code, name, valid_from, valid_to, is_active
)

discount_rules (
    promotion_id,
    rule_type,              -- PRODUCT | INVOICE_TOTAL | VOUCHER
    target_product_id,      -- Cho PRODUCT
    min_invoice_amount,     -- Cho INVOICE_TOTAL
    voucher_code,           -- Cho VOUCHER
    max_usage_per_customer, -- Cho VOUCHER
    discount_type,          -- PERCENT | AMOUNT
    discount_value
)

customer_voucher_usage (
    customer_id,
    discount_rule_id,
    invoice_id,
    used_at
)
```

**Lý do:**
- Linh hoạt hơn: 1 promotion có thể có nhiều rules
- Pattern chuẩn Shopee/Tiki/Lazada
- Hỗ trợ 3 loại KM phức tạp:
  - PRODUCT: Giảm 15% cho Nước Suối
  - INVOICE_TOTAL: Giảm 50k cho hóa đơn >= 500k
  - VOUCHER: Mã VIP20, mỗi người dùng 1 lần

**Tradeoff:**
- ❌ Phức tạp hơn (3 bảng thay vì 1)
- ❌ Tốn thêm 1-2 tuần implement
- ✅ Nhưng giống enterprise, scalable

---

### 6. ✅ Indexes Đầy Đủ (30+ Indexes)

**Nhóm quan trọng:**
- `idx_bookings_customer_created` → Lịch sử booking của khách
- `idx_booking_details_date_court` → Tìm sân trống theo ngày
- `idx_invoices_customer_created` → Lịch sử hóa đơn
- `idx_promotions_code_active` → Validate mã khuyến mãi
- `idx_discount_rules_voucher` → Tìm voucher code
- `idx_voucher_usage_customer` → Kiểm tra khách đã dùng voucher chưa

**Partial Indexes (WHERE clause):**
```sql
-- Chỉ index records active
CREATE INDEX idx_promotions_code_active 
ON promotions(code) 
WHERE is_active = true AND deleted_at IS NULL;

-- Chỉ index GOODS (không index SERVICE)
CREATE INDEX idx_products_type_stock 
ON products(type, stock_quantity) 
WHERE type = 'GOODS';
```

---

## 🔒 Locking Strategies

### Pessimistic Lock (Booking)

**Use Case:** Chống trùng lịch đặt sân

**Implementation:**
```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT c FROM Court c WHERE c.id = :id")
Optional<Court> findByIdForUpdate(@Param("id") UUID id);

@Transactional
public Booking createBooking(BookingRequest request) {
    // Lock court row
    Court court = courtRepository.findByIdForUpdate(courtId)
        .orElseThrow();
    
    // DB constraint sẽ bắt duplicate nếu có
    BookingDetail detail = new BookingDetail();
    detail.setBookingDate(request.getDate());
    detail.setCourtId(courtId);
    detail.setTimeSlotId(timeSlotId);
    
    try {
        bookingDetailRepository.save(detail);
    } catch (DataIntegrityViolationException e) {
        throw new ConflictException("Sân đã được đặt");
    }
}
```

**Constraint:**
```sql
ALTER TABLE booking_details
ADD CONSTRAINT uq_booking_slot 
UNIQUE (booking_date, court_id, time_slot_id);
```

**Lý do dùng Pessimistic:**
- ❌ Optimistic Lock không phù hợp vì:
  - Conflict xảy ra thường xuyên (giờ vàng)
  - Không thể retry (booking fail = mất khách)
  - Cần fail fast ở DB level

---

### Optimistic Lock (Products)

**Use Case:** Chống âm kho

**Implementation:**
```java
@Entity
@Table(name = "products")
public class Product {
    @Version
    private Integer version;
    
    private Integer stockQuantity;
}

@Transactional
public void sellProduct(UUID productId, int quantity) {
    Product product = productRepository.findById(productId)
        .orElseThrow();
    
    if (product.getStockQuantity() < quantity) {
        throw new OutOfStockException();
    }
    
    product.setStockQuantity(product.getStockQuantity() - quantity);
    
    try {
        productRepository.save(product);
        // JPA tự động check version và tăng lên
    } catch (OptimisticLockException e) {
        // Retry hoặc thông báo khách
        throw new ConflictException("Sản phẩm vừa được bán hết");
    }
}
```

**Lý do dùng Optimistic:**
- ✅ Conflict ít xảy ra (không phải giờ rush)
- ✅ Có thể retry (load lại stock mới)
- ✅ Performance tốt hơn (không lock row)

---

## 📋 Checklist Hoàn Thành

### Database Schema
- [x] 18 bảng với mối quan hệ rõ ràng
- [x] Soft delete (deleted_at) cho master tables
- [x] Audit trail (created_by, created_at, updated_at)
- [x] UUID cho security
- [x] NUMERIC cho tiền tệ (không dùng FLOAT)

### Constraints
- [x] UNIQUE constraints (email, code, booking_slot)
- [x] CHECK constraints (status, dates, amounts)
- [x] Foreign keys với ON DELETE behaviors
- [x] Custom constraint: payment_target XOR

### Indexes
- [x] Primary keys (auto-indexed)
- [x] Foreign keys (indexed)
- [x] Query patterns (customer_id, date, status)
- [x] Partial indexes (WHERE is_active, deleted_at IS NULL)

### Business Logic
- [x] Booking status flow (no CANCELLED)
- [x] Invoice creation timing (CHECKED_IN)
- [x] Payment transaction target (XOR)
- [x] Promotion system (3 layers)
- [x] Locking strategies (Pessimistic vs Optimistic)

### Documentation
- [x] database.md (ERD + mô tả)
- [x] payment-invoice-flow.md (luồng nghiệp vụ)
- [x] promotion-flow-advanced.md (3 loại rules)
- [x] database-constraints.md (chi tiết constraints)
- [x] design-review.md (tổng kết review)
- [x] technical-considerations.md (lưu ý kỹ thuật)

---

## 🚀 Sẵn Sàng Implement

Database design đã hoàn chỉnh và sẵn sàng để:

1. **Viết SQL Migration Script** (CREATE TABLE, CONSTRAINT, INDEX)
2. **Generate JPA Entities** (từ database schema)
3. **Implement Repositories** (với custom queries)
4. **Write Service Layer** (business logic)
5. **Build REST APIs** (controllers)
6. **Testing** (unit + integration tests)

---

## ⚠️ Lưu Ý Cuối Cùng

### Không Làm (Out of Scope)
- ❌ Loyalty points (đã bỏ theo quyết định)
- ❌ Notification system (email/SMS) - làm sau
- ❌ Report/Analytics tables - dùng views/queries thay vì denormalized tables
- ❌ Audit logs table - dùng trigger hoặc app-level logging

### Có Thể Thêm Sau (Phase 2)
- 🔄 Review system (customers review courts)
- 🔄 Membership tiers (Bronze/Silver/Gold)
- 🔄 Recurring bookings (đặt sân theo lịch cố định)
- 🔄 Court maintenance schedule (bảo trì định kỳ)

---

*Database design hoàn chỉnh - 18 bảng với promotion system enterprise-grade.*
