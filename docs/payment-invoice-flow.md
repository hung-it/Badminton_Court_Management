# Payment & Invoice Flow - Hệ thống Quản lý Sân Cầu Lông

## 1. Tổng Quan Luồng Nghiệp Vụ

### 1.1. Các Luồng Chính

```
┌─────────────────────────────────────────────────────────┐
│  LUỒNG 1: Đặt sân Online → Check-in → Mua thêm hàng    │
└─────────────────────────────────────────────────────────┘
Customer App:
  1. Chọn sân, khung giờ → Tạo booking (status: PENDING)
  2. Thanh toán online (VNPay Sandbox) → booking.status = PAID
  3. Đến sân check-in → booking.status = CHECKED_IN
                      → Tạo invoice (court_fee từ booking)
  4. Mua nước/cầu → Thêm invoice_details → Tính product_fee
  5. Thanh toán product_fee (cash/transfer)
  6. Chơi xong → booking.status = COMPLETED


┌─────────────────────────────────────────────────────────┐
│  LUỒNG 2: Khách vãng lai (Walk-in) - Không đặt trước   │
└─────────────────────────────────────────────────────────┘
Quầy Thu Ngân:
  1. Khách đến → Kiểm tra sân trống
  2. Tạo invoice (booking_id = NULL, court_fee = manual input)
  3. Khách mua hàng → Thêm invoice_details
  4. Tính total = court_fee + product_fee
  5. Thanh toán ngay (cash/bank_transfer) → invoice.status = PAID


┌─────────────────────────────────────────────────────────┐
│  LUỒNG 3: Đặt sân Online nhưng bùng kèo (NO_SHOW)      │
└─────────────────────────────────────────────────────────┘
Hệ Thống Tự Động:
  1. Booking đã PAID nhưng không check-in
  2. Sau khi quá giờ đặt 30 phút → Cron job đổi status = NO_SHOW
  3. KHÔNG tạo invoice (vì khách không đến)
  4. KHÔNG hoàn tiền (business rule)
  5. Báo cáo: Tổng thiệt hại từ NO_SHOW = SUM(bookings.court_fee WHERE status = NO_SHOW)
```

---

## 2. Sequence Diagrams

### 2.1. Luồng 1 - Đặt Sân Online & Check-in

```mermaid
sequenceDiagram
    participant C as Customer (App)
    participant API as Backend API
    participant DB as Database
    participant PG as Payment Gateway (VNPay Sandbox)
    participant Staff as Staff (POS)

    %% Phase 1: Booking & Payment
    C->>API: POST /bookings (court_id, time_slot_id, date)
    API->>DB: INSERT bookings (status=PENDING, court_fee=calculated)
    API->>DB: INSERT booking_details (price=base_price*multiplier)
    DB-->>API: booking_id
    API-->>C: booking_id, court_fee, payment_url

    C->>PG: Click payment_url
    PG->>C: QR Code / Payment form
    C->>PG: Xác nhận thanh toán
    PG->>API: Webhook callback (transaction_id, status=SUCCESS)
    API->>DB: INSERT payment_transactions (booking_id, status=SUCCESS, amount=court_fee)
    API->>DB: UPDATE bookings SET status=PAID
    DB-->>API: OK
    API-->>PG: 200 OK
    PG->>C: Thanh toán thành công

    %% Phase 2: Check-in
    Note over C,Staff: Khách đến sân (30 phút trước giờ đặt)
    C->>Staff: Đưa mã booking (QR code / số điện thoại)
    Staff->>API: POST /bookings/{id}/check-in
    API->>DB: SELECT bookings WHERE id=? AND status=PAID
    DB-->>API: booking record
    
    API->>DB: BEGIN TRANSACTION
    API->>DB: UPDATE bookings SET status=CHECKED_IN
    API->>DB: INSERT invoices (booking_id, customer_id, court_fee=booking.court_fee, product_fee=0, status=DRAFT)
    DB-->>API: invoice_id
    API->>DB: COMMIT
    API-->>Staff: Check-in thành công, invoice_id

    %% Phase 3: Mua hàng & Thanh toán product_fee
    Note over Staff: Khách mua nước, cầu lông
    Staff->>API: POST /invoices/{id}/items (product_id, quantity)
    API->>DB: BEGIN TRANSACTION
    API->>DB: SELECT products WHERE id=? FOR UPDATE (lock row)
    DB-->>API: product (stock=10, version=3)
    API->>DB: INSERT invoice_details (invoice_id, product_id, quantity, unit_price)
    API->>DB: UPDATE products SET stock_quantity=stock-quantity, version=version+1 WHERE id=? AND version=3
    DB-->>API: 1 row updated (success)
    API->>DB: UPDATE invoices SET product_fee = SUM(invoice_details.quantity * unit_price)
    API->>DB: COMMIT
    API-->>Staff: Đã thêm vào hóa đơn

    Staff->>API: POST /invoices/{id}/pay (payment_method=CASH)
    API->>DB: BEGIN TRANSACTION
    API->>DB: UPDATE invoices SET status=PAID, total_amount=court_fee+product_fee
    API->>DB: INSERT payment_transactions (invoice_id, payment_method=CASH, status=SUCCESS, amount=product_fee)
    API->>DB: COMMIT
    API-->>Staff: In hóa đơn

    %% Phase 4: Hoàn thành
    Note over Staff: Sau khi khách chơi xong
    Staff->>API: POST /bookings/{id}/complete
    API->>DB: UPDATE bookings SET status=COMPLETED
    API-->>Staff: Đã hoàn thành
```

---

### 2.2. Luồng 2 - Khách Vãng Lai (Walk-in)

```mermaid
sequenceDiagram
    participant C as Customer (Walk-in)
    participant Staff as Staff (POS)
    participant API as Backend API
    participant DB as Database

    C->>Staff: Hỏi sân trống
    Staff->>API: GET /courts/availability?date=today&time_slot_id=X
    API->>DB: SELECT booking_details WHERE booking_date=? AND time_slot_id=?
    DB-->>API: Danh sách sân đã đặt
    API-->>Staff: Sân 3, 5, 7 còn trống

    Staff->>C: Sân 5 trống, giá 150k/giờ
    C->>Staff: OK, cho 1 giờ + mua 2 chai nước

    Staff->>API: POST /invoices (customer_id, booking_id=NULL)
    API->>DB: INSERT invoices (status=DRAFT, court_fee=150000, product_fee=0)
    DB-->>API: invoice_id
    API-->>Staff: invoice_id

    Staff->>API: POST /invoices/{id}/set-court-fee (amount=150000, note="Sân 5, 18:00-19:00")
    API->>DB: UPDATE invoices SET court_fee=150000
    API-->>Staff: OK

    Staff->>API: POST /invoices/{id}/items (product_id=nước, quantity=2)
    API->>DB: BEGIN TRANSACTION
    API->>DB: INSERT invoice_details
    API->>DB: UPDATE products SET stock_quantity=stock-2, version=version+1
    API->>DB: UPDATE invoices SET product_fee=20000
    API->>DB: COMMIT
    API-->>Staff: Tổng: 170,000 VND

    C->>Staff: Thanh toán tiền mặt
    Staff->>API: POST /invoices/{id}/pay (payment_method=CASH)
    API->>DB: UPDATE invoices SET status=PAID, total_amount=170000
    API->>DB: INSERT payment_transactions (invoice_id, amount=170000, payment_method=CASH, status=SUCCESS)
    API-->>Staff: In hóa đơn
    Staff->>C: Hóa đơn + Sân 5
```

---

### 2.3. Luồng 3 - Bùng Kèo (NO_SHOW)

```mermaid
sequenceDiagram
    participant Cron as Cron Job (Scheduler)
    participant API as Backend API
    participant DB as Database
    participant Notify as Notification Service

    Note over Cron: Chạy mỗi 15 phút
    Cron->>API: GET /bookings/check-no-show
    API->>DB: SELECT bookings b JOIN booking_details bd<br/>WHERE b.status='PAID'<br/>AND bd.booking_date = TODAY<br/>AND (bd.start_time + 30 minutes) < NOW()
    DB-->>API: List of late bookings

    loop Mỗi booking trễ
        API->>DB: UPDATE bookings SET status='NO_SHOW' WHERE id=?
        DB-->>API: OK
        API->>Notify: Send notification (Khách {name} bùng kèo booking {id})
    end

    API-->>Cron: Đã xử lý X bookings

    Note over DB: KHÔNG tạo invoice<br/>KHÔNG hoàn tiền<br/>Báo cáo: SUM(court_fee) WHERE status=NO_SHOW
```

---

## 3. Làm Rõ Câu Hỏi

### ❓ Invoice được tạo khi nào?
**Trả lời:**
- **Luồng Online:** Tạo lúc **CHECKED_IN** (khách đã đến sân)
- **Luồng Walk-in:** Tạo ngay khi **Staff nhập liệu** (khách đang ở quầy)
- **Luồng NO_SHOW:** **KHÔNG tạo invoice** (khách không đến)

### ❓ `invoices.court_fee` vs `bookings.court_fee` - Có duplicate?
**Trả lời:**
- **bookings.court_fee:** Tiền sân **đã thu trước** qua VNPay Sandbox (dùng cho payment gateway + báo cáo thiệt hại NO_SHOW)
- **invoices.court_fee:** Tiền sân **ghi vào hóa đơn tổng hợp** (dùng cho kế toán, in bill)

**Quan hệ:**
```
- Nếu có booking: invoices.court_fee = bookings.court_fee (copy value)
- Nếu walk-in: invoices.court_fee = staff nhập tay, bookings.court_fee = NULL (vì không có booking)
```

### ❓ Một `payment_transaction` có thể có CẢ `booking_id` VÀ `invoice_id`?
**Trả lời:** **KHÔNG**. Một transaction chỉ thuộc 1 trong 2:

**Case 1: Thanh toán tiền sân Online (trước khi check-in)**
```sql
INSERT INTO payment_transactions (
    booking_id = 'uuid-booking',
    invoice_id = NULL,  -- Chưa có invoice
    amount = 200000,
    payment_method = 'VNPAY',
    status = 'SUCCESS'
)
```

**Case 2: Thanh toán tiền hàng tại quầy (sau khi check-in)**
```sql
INSERT INTO payment_transactions (
    booking_id = NULL,
    invoice_id = 'uuid-invoice',
    amount = 50000,  -- Chỉ product_fee
    payment_method = 'CASH',
    status = 'SUCCESS'
)
```

**Constraint cần thêm:**
```sql
ALTER TABLE payment_transactions
ADD CONSTRAINT chk_payment_target 
CHECK (
    (booking_id IS NOT NULL AND invoice_id IS NULL) OR
    (booking_id IS NULL AND invoice_id IS NOT NULL)
);
```

### ❓ `customers.email` vs `users.email` - Tại sao 2 email?
**Trả lời:** **BỎ `customers.email`**. Chỉ giữ `users.email`.

**Lý do:**
- Khách hàng đăng ký tài khoản bằng email → Dùng `users.email` cho login
- Không có lý do business nào cần email thứ 2
- Nếu cần contact info khác → Dùng `customers.phone` (đã có sẵn)

**Migration:**
```sql
-- Bỏ cột customers.email
ALTER TABLE customers DROP COLUMN email;

-- Nếu cần email cho contact → JOIN với users.email
SELECT c.full_name, u.email, c.phone
FROM customers c
JOIN users u ON c.user_id = u.id;
```

---

## 4. Schema Updates

### 4.1. Bỏ CANCELLED và Loyalty Points

```sql
-- Update bookings.status enum
-- CHỈ GIỮ: PENDING, PAID, CHECKED_IN, COMPLETED, NO_SHOW

-- Bỏ cột loyalty_points
ALTER TABLE customers DROP COLUMN loyalty_points;

-- Bỏ cột email (dùng users.email)
ALTER TABLE customers DROP COLUMN email;
```

### 4.2. Thêm Constraints

```sql
-- Payment transaction phải có đúng 1 target
ALTER TABLE payment_transactions
ADD CONSTRAINT chk_payment_target 
CHECK (
    (booking_id IS NOT NULL AND invoice_id IS NULL) OR
    (booking_id IS NULL AND invoice_id IS NOT NULL)
);

-- Court fee không âm
ALTER TABLE bookings
ADD CONSTRAINT chk_bookings_court_fee_positive
CHECK (court_fee >= 0);

ALTER TABLE invoices
ADD CONSTRAINT chk_invoices_amounts_positive
CHECK (court_fee >= 0 AND product_fee >= 0 AND total_amount >= 0);

-- Stock không âm
ALTER TABLE products
ADD CONSTRAINT chk_products_stock_positive
CHECK (stock_quantity >= 0);

-- Time slot hợp lệ
ALTER TABLE time_slots
ADD CONSTRAINT chk_time_slots_valid
CHECK (start_time < end_time);
```

### 4.3. Thêm Indexes

```sql
-- Booking lookups
CREATE INDEX idx_bookings_customer_status ON bookings(customer_id, status, created_at DESC);
CREATE INDEX idx_bookings_status_date ON bookings(status) 
    WHERE status IN ('PAID', 'CHECKED_IN');

-- Check availability
CREATE INDEX idx_booking_details_availability 
    ON booking_details(booking_date, time_slot_id, court_id);

-- Invoice lookups  
CREATE INDEX idx_invoices_booking ON invoices(booking_id) 
    WHERE booking_id IS NOT NULL;
CREATE INDEX idx_invoices_customer ON invoices(customer_id, created_at DESC);

-- Payment transactions
CREATE INDEX idx_payment_trans_booking ON payment_transactions(booking_id) 
    WHERE booking_id IS NOT NULL;
CREATE INDEX idx_payment_trans_invoice ON payment_transactions(invoice_id) 
    WHERE invoice_id IS NOT NULL;

-- Products stock check
CREATE INDEX idx_products_category_active 
    ON products(category_id, stock_quantity) 
    WHERE deleted_at IS NULL;
```

---

## 5. State Machine - Booking Status

```
┌─────────┐
│ PENDING │ Khách vừa tạo booking, chưa thanh toán
└────┬────┘
     │ Thanh toán VNPay Sandbox thành công
     ▼
┌─────────┐
│  PAID   │ Đã thanh toán, chờ đến sân
└────┬────┘
     │ Check-in tại quầy → Tạo invoice
     ▼
┌────────────┐
│ CHECKED_IN │ Đã check-in, đang chơi
└─────┬──────┘
      │ Staff đánh dấu hoàn thành
      ▼
┌───────────┐
│ COMPLETED │ Đã chơi xong
└───────────┘

┌─────────┐
│  PAID   │
└────┬────┘
     │ Quá giờ 30 phút, không check-in
     │ (Cron job tự động)
     ▼
┌─────────┐
│ NO_SHOW │ Bùng kèo - KHÔNG hoàn tiền
└─────────┘
```

**Transitions:**
- `PENDING → PAID`: Webhook từ payment gateway
- `PAID → CHECKED_IN`: Staff scan QR/nhập số điện thoại
- `CHECKED_IN → COMPLETED`: Staff click "Hoàn thành"
- `PAID → NO_SHOW`: Cron job (sau 30 phút quá giờ)

**Không cho phép:**
- ❌ PAID → PENDING (không thể "undo" thanh toán)
- ❌ CHECKED_IN → PAID (không quay lại)
- ❌ Bất kỳ status nào → CANCELLED (đã bỏ)

---

## 6. Báo Cáo Tài Chính

### 6.1. Doanh Thu Tiền Sân
```sql
-- Doanh thu thực tế (đã check-in)
SELECT SUM(court_fee) 
FROM bookings 
WHERE status IN ('CHECKED_IN', 'COMPLETED')
AND DATE(created_at) = '2024-01-15';

-- Thiệt hại từ NO_SHOW
SELECT SUM(court_fee)
FROM bookings
WHERE status = 'NO_SHOW'
AND DATE(created_at) = '2024-01-15';
```

### 6.2. Doanh Thu Hàng Hóa
```sql
SELECT SUM(product_fee)
FROM invoices
WHERE status = 'PAID'
AND DATE(created_at) = '2024-01-15';
```

### 6.3. Tổng Doanh Thu
```sql
SELECT SUM(total_amount)
FROM invoices
WHERE status = 'PAID'
AND DATE(created_at) = '2024-01-15';
```

---

Xong! Còn câu hỏi nào chưa rõ không?
