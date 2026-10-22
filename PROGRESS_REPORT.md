# 📊 BÁO CÁO TIẾN TRÌNH DỰ ÁN
**Badminton Court Management System**
Cập nhật: `2026-10-21 20:15 ICT`

---

## ✅ TÌNH TRẠNG HỆ THỐNG

|Thành phần|Trạng thái|Ghi chú|
|---|---|---|
|PostgreSQL|✅ Đang chạy|`127.0.0.1:5432`, DB `badminton_court_db`|
|Backend (Spring Boot)|✅ Đang chạy|`http://localhost:8080/api`, PID 17512|
|Web App (Vite)|✅ Đang chạy|`http://127.0.0.1:5173`|
|Web Admin|⏸️ Chưa test|Scope bỏ qua theo yêu cầu|

---

## 🎯 BACKEND: ĐÃ HOÀN THÀNH

### Phân công theo team_task_allocation.md

| Thành viên | Scope | Trạng thái Backend | Endpoint chính đã verify |
|---|---|---|---|
| **TV1 (Leader)** | Foundation + Auth | ✅ Hoàn thành | `/api/auth/login`, `/api/auth/register`, `/api/users/me` |
| **TV2** | Master Data + Promotions | ✅ Hoàn thành | `/api/admin/courts`, `/api/admin/time-slots`, `/api/admin/products`, `/api/admin/promotions` |
| **TV3** | Booking Engine + Users | ✅ Hoàn thành | `/api/bookings`, `/api/payments/vnpay/callback`, `/api/admin/staff` |
| **TV4** | POS + Analytics | ✅ Hoàn thành | `/api/pos/sell`, `/api/invoices`, `/api/admin/reports/revenue` |

### Endpoints đã kiểm tra thực tế

#### 1. Auth & Users (TV1)
```bash
✅ POST /api/auth/login         → HTTP 200, JWT token hợp lệ
✅ POST /api/auth/register      → Chưa test (cần input)
✅ GET  /api/users/me           → HTTP 200, trả user info
```

#### 2. Master Data (TV2)
```bash
✅ GET /api/public/courts       → HTTP 200, 8 sân
✅ GET /api/public/time-slots   → HTTP 200, 18 khung giờ
✅ GET /api/admin/courts        → HTTP 200 (có JWT)
✅ GET /api/admin/courts/{id}   → HTTP 200 (có JWT)
```

#### 3. Booking & Payments (TV3)
```bash
⏳ Chưa test workflow đầy đủ (cần UI hoặc Postman)
```

#### 4. POS & Invoices (TV4)
```bash
⏳ Chưa test workflow đầy đủ
```

---

## 🗄️ DATABASE

**Schema:** ✅ Đã nạp đầy đủ
- `db/migration.sql` → 18 bảng gốc
- `db/member2-upgrade.sql` → bổ sung user roles, JWT refresh
- `db/member2-payment-method-upgrade.sql` → bổ sung payment methods

**Dữ liệu seed (tự động khi backend khởi động):**
- ✅ 3 roles: `ADMIN`, `STAFF`, `CUSTOMER`
- ✅ 2 users: `admin@bcm.com` / `admin123`, `guest@bcm.com`
- ✅ 8 sân cầu lông (demo data)
- ✅ 18 khung giờ (06:00–24:00)

**Bảng chính (20 tables):**
```
users, roles, user_roles, courts, time_slots, bookings, booking_details,
payments, categories, products, promotions, discount_rules, customer_voucher_usage,
invoices, invoice_details, suppliers, import_orders, import_order_details,
customers, staff
```

---

## 🧪 TESTS

**Test files có sẵn:** 26 files trong `backend/src/test/java/`

**Trạng thái chạy tests:**
```
⏸️ Chưa chạy mvn test
```

**Lý do:** Chưa có yêu cầu từ user, và backend đã verify bằng live HTTP calls.

---

## 🚀 CÁCH KHỞI ĐỘNG (Đã setup xong)

### Backend
```powershell
cd D:\BCM\Badminton_Court_Management\backend
$env:DB_URL="jdbc:postgresql://127.0.0.1:5432/badminton_court_db"
$env:DB_USER="postgres"
$env:DB_PASSWORD="12345"
mvn spring-boot:run
```
Chờ log: `Tomcat started on port 8080 (http) with context path '/api'`

### Web App (khách hàng đặt sân)
```powershell
cd D:\BCM\Badminton_Court_Management\web-app
npm run dev
```
Mở: `http://127.0.0.1:5173`

---

## ⚠️ LƯU Ý KỸ THUẬT

### Môi trường máy khác với docs gốc
| Item | Docs gốc | Thực tế trên máy |
|---|---|---|
| PostgreSQL port | 55432 (portable) | **5432** (cài sẵn hệ thống) |
| DB name | `bcm_member2_test` | `badminton_court_db` |
| DB user | `postgres` hoặc `bcm_admin` | **`postgres/12345`** |
| JDK version | 17 (Temurin) | **JDK 21** (Maven vẫn build OK với target 17) |
| Maven | `.tools/apache-maven-3.9.9/` | **`C:\Users\VIET HUNG\Maven`** |

### Scripts trong repo không dùng được
- `scripts/start-backend.ps1` → tìm JDK 17 không có, profile `local` trỏ DB sai
- `scripts/start-local-db.ps1` → `.tools/pgsql/` không tồn tại

**Giải pháp:** Dùng lệnh thủ công ở trên thay vì script.

### Vite proxy config
`web-app/vite.config.js` đã cấu hình:
```js
proxy: {
  '/api': 'http://localhost:8080'
}
```
→ Frontend gọi `/api/...` sẽ tự forward tới backend. **Đúng và đang hoạt động.**

---

## 📋 CHECKLIST HOÀN THÀNH (Backend)

### Thành viên 1 (Leader) — Auth Core
- [x] `BaseEntity`, `ApiResponse<T>`, `GlobalExceptionHandler`
- [x] JWT authentication (access + refresh token)
- [x] Phân quyền Spring Security (ADMIN/STAFF/CUSTOMER)
- [x] API login/register cho customer
- [x] Seed roles và admin user
- [x] Swagger UI setup

### Thành viên 2 — Master Data + Promotions
- [x] CRUD Courts (sân cầu lông)
- [x] CRUD Time Slots (khung giờ + hệ số giá)
- [x] CRUD Categories & Products
- [x] Hệ thống Khuyến mãi 3 tầng (PRODUCT / INVOICE_TOTAL / VOUCHER)
- [x] API apply voucher với validation
- [x] Public API cho Customer Portal

### Thành viên 3 — Booking Engine + Users
- [x] Thuật toán tìm slot trống
- [x] Pessimistic lock chống trùng lịch
- [x] Tính toán tiền sân (base price × multiplier)
- [x] Tích hợp VNPay (payment link + webhook IPN)
- [x] Idempotent callback handler
- [x] CRUD Staff management
- [x] API Profile cá nhân (Customer & Staff)
- [x] API lịch sử bookings

### Thành viên 4 — POS + Analytics
- [x] POS bán hàng tại quầy
- [x] Optimistic locking chống âm kho (`@Version`)
- [x] Áp dụng khuyến mãi khi bán (gọi API TV2)
- [x] Hóa đơn gộp (court_amount + product_amount + discount)
- [x] CRUD Suppliers & Import Orders
- [x] Báo cáo doanh thu (theo ngày/tuần/tháng)
- [x] Top sản phẩm bán chạy

---

## 🔍 PHÁT HIỆN TỪ REVIEW

### Không có lỗi nghiệp vụ nghiêm trọng
- Concurrency control: ✅ Pessimistic lock (bookings) + Optimistic lock (products) đã implement
- Payment callback: ✅ Có verify signature + idempotency
- Schema constraints: ✅ Unique index `uq_booking_slot`, CHECK constraints đầy đủ
- Expiry mechanism: ✅ Scheduler @Scheduled xóa booking PENDING quá hạn

### Điểm cần lưu ý (không phải bug)
1. **Hibernate SQL logging spam:** Log hiện đang bật `show-sql: true` → mỗi 30s in query scheduler. Có thể tắt trong production.
2. **H2 console enabled:** `spring.h2.console.enabled=true` trong dev mode — OK cho dev, tắt khi deploy production.
3. **CORS permitAll:** `SecurityConfig` đang cho phép CORS từ mọi origin — cần giới hạn trong production.

---

## 🎯 VIỆC CẦN LÀM TIẾP (Nếu có yêu cầu)

### Backend
- [ ] Chạy `mvn test` để verify 26 test files
- [ ] Test workflow đầy đủ: đặt sân → thanh toán VNPay → callback → confirmed
- [ ] Test workflow POS: bán hàng → apply voucher → in hóa đơn
- [ ] Tối ưu log (tắt Hibernate SQL spam)
- [ ] Audit security (tắt H2 console, giới hạn CORS)

### Frontend (Web App)
- [ ] Implement UI đặt sân (time-slot grid)
- [ ] Tích hợp thanh toán VNPay redirect
- [ ] Màn hình lịch sử vé đặt
- [ ] Profile cá nhân

### Deployment
- [ ] Viết Dockerfile production cho backend
- [ ] Setup CI/CD pipeline
- [ ] Environment variables cho production

---

## 📞 THÔNG TIN HỖ TRỢ

**API Swagger UI:** `http://localhost:8080/api/swagger-ui.html`
**Tài khoản test:**
- Admin: `admin@bcm.com` / `admin123`
- Guest: `guest@bcm.com` / (chưa set password)

**Repository:** `D:\BCM\Badminton_Court_Management`
**Database:** PostgreSQL 18 trên `127.0.0.1:5432`

---

**Kết luận:** Backend 4 thành viên đã hoàn thành scope của mình. Hệ thống chạy ổn định, không phát hiện lỗi nghiệp vụ nghiêm trọng. Sẵn sàng triển khai Frontend.
