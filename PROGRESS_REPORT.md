# BÁO CÁO TIẾN TRÌNH — Quản lý sân cầu lông

**Ngày cập nhật:** 27/10/2026
**Người lập:** Thành viên 1 (Leader)
**Nhánh hiện tại:** `develop` (đã merge `feature/front-end-user` + `feature/booking-history-v2`)

---

## 1. Tóm tắt

Backend của cả 4 thành viên đã hoàn thành và **build/test thành công**. Frontend Web App
đã được hợp nhất vào `develop` (đủ trang sân cầu lông, hướng dẫn, liên hệ, khách vãng
lai, checkout, thanh toán VNPay). Trong phiên làm việc này đã:

- Tái hiện và sửa lỗi đăng nhập 403 (`Invalid CORS request`)
- Tách cổng `web-admin` (5173) khỏi `web-app` (5174) để tránh trùng cổng
- Nâng schema database lên đúng phiên bản nhánh hiện tại (35 chi nhánh / 210 sân)
- Bổ sung trang **Lịch đặt của tôi** (`/my-bookings`) — thay thế redirect cũ
- Chạy và xác nhận toàn bộ test backend (487) + test browser
- Merge `feature/front-end-user` → `develop`, rồi `feature/booking-history-v2` → `develop`

---

## 2. Trạng thái theo thành viên

| # | Thành viên | Phạm vi | Trạng thái |
|---|---|---|---|
| 1 | Leader | Backend nền tảng, Auth, tích hợp, review | ✅ Hoàn thành |
| 2 | Web Admin | Quản lý sân, hàng hóa, POS, thanh toán | ✅ Hoàn thành |
| 3 | Booking Engine | Đặt sân, VNPay sandbox, availability, branches | ✅ Hoàn thành |
| 4 | Front-end User | Trang khách: sân, hướng dẫn, liên hệ, checkout | ✅ Hoàn thành |
| 1 | Leader (bổ sung) | Trang lịch sử đặt sân, sửa lỗi hệ thống | ✅ Hoàn thành |

---

## 3. Những gì đã sửa trong phiên làm việc này

### 3.1 Lỗi đăng nhập 403 — "Bạn không có quyền thực hiện thao tác này"

**Nguyên nhân:** `CorsConfig.java` chỉ khai báo `http://localhost:5173`, nhưng Vite chạy
với `--host 127.0.0.1` nên origin thực tế của trình duyệt là `http://127.0.0.1:5174`
→ backend trả `403 Invalid CORS request`.

> Lưu ý: `curl` không gửi header `Origin` nên gọi tay vẫn trả 200 — dễ gây nhầm là
> lỗi mật khẩu. Phải kiểm tra bằng trình duyệt thật.

**Cách xác nhận:** dùng Playwright bắt network khi click nút "Đăng nhập", thấy
`POST /api/auth/login -> 403 Invalid CORS request`.

**Cách sửa:** bổ sung đủ origin `localhost`/`127.0.0.1` cho các cổng dev.

### 3.2 Trùng cổng dev server

`web-app` và `web-admin` cùng dùng cổng **5173** với `strictPort: true` → không chạy
đồng thời được. Đã tách:

| Ứng dụng | Cổng | Ghi chú |
|---|---|---|
| Web App (khách) | 5174 | `vite.config.js` dùng `BCM_API_PROXY_TARGET` |
| Web Admin | 5173 | trở lại cổng chuẩn |

### 3.3 Schema database

Nhánh `feature/front-end-user` yêu cầu schema mới (`branches`, `court_code`,
`checkout_release_at`, `promotion_code`…) nhưng database đang chạy mới chỉ có schema
cũ (8 sân, không có bảng `branches`). Đã áp theo đúng thứ tự `docker-compose.yml`:

```
db/migration.sql  →  db/booking-ux-upgrade.sql  →  db/branches-seed.sql
```

Kết quả: **35 chi nhánh, 210 sân, mỗi chi nhánh 6 sân.** Tất cả file đều idempotent
và có guard, không mất dữ liệu booking/payment.

### 3.4 Trang "Lịch đặt của tôi" (`/my-bookings`)

Trước đây route `/my-bookings` chỉ `Navigate` về `/courts#upcoming-booking`, dù Header,
Footer, Guide, Contact, PaymentResult đều trỏ link "Lịch đặt của tôi" tới đó. Đã thay
bằng trang thật.

**File mới:**

| File | Nội dung |
|---|---|
| `web-app/src/CustomerBookingHistory.jsx` | Trang lịch sử: danh sách, lọc trạng thái, phân trang, mở chi tiết, lịch sử thanh toán, retry |
| `web-app/src/customer-history.css` | Style theo design system `--cc-*` sẵn có của các trang khách |

**File sửa:** `App.jsx` (nối route), `CustomerLayout.jsx`, `CustomerGuide.jsx`,
`CustomerContact.jsx`, `PaymentResult.jsx` (đổi link sang `/my-bookings`).

> Trang dùng lại `tokenStore` / `apiClient` / `api()` / `readSession()` / `clearSession()`
> sẵn có của nhánh hiện tại → **không tạo cơ chế auth thứ hai**. Đăng nhập một lần dùng
> được mọi trang.

---

## 4. Kết quả kiểm thử

### 4.1 Backend

| Cách chạy | Kết quả |
|---|---|
| `mvn test` (không truyền DB) | 352 tests · 0 fail · 0 error · 251 skip |
| `mvn test` + `BCM_TEST_DB_URL` trỏ DB dev | 487 tests · 20 fail — **do giới hạn connection PostgreSQL (max_connections=100)** |
| `mvn test` + DB test riêng + `forkCount=1` | **487 tests · 0 fail · 0 error · BUILD SUCCESS** |

Lệnh chạy đầy đủ (không skip test PostgreSQL):

```bash
cd backend
BCM_TEST_DB_URL="jdbc:postgresql://127.0.0.1:5433/bcm_test_db" \
BCM_TEST_DB_USERNAME="bcm_admin" \
BCM_TEST_DB_PASSWORD="12345" \
mvn test -DforkCount=1 -DreuseForks=false -Dspring.datasource.hikari.maximum-pool-size=10
```

**Ghi chú quan trọng:** test PostgreSQL **phải** dùng database riêng. Chạy trên
database dev sẽ vỡ `FATAL: sorry, too many clients already` vì mỗi test class dựng
một Spring context riêng, mỗi context giữ một connection pool.

### 4.2 Frontend (Playwright, dữ liệu thật từ backend)

| Luồng | Kết quả |
|---|---|
| Đăng ký tài khoản mới | ✅ |
| Đăng nhập khách hàng | ✅ |
| Đăng nhập admin | ✅ |
| Trang `/` `/courts` `/guide` `/contact` `/login` `/register` | ✅ HTTP 200, hiển thị đúng |
| `/my-bookings` — chưa đăng nhập | ✅ Hiện yêu cầu đăng nhập |
| `/my-bookings` — khách, có booking | ✅ Hiện booking, trạng thái, giá, chi tiết khung giờ |
| Lọc theo trạng thái (PENDING/PAID/EXPIRED) | ✅ Lọc đúng |
| Mở chi tiết + lịch sử thanh toán | ✅ |
| Responsive mobile 390px | ✅ Không tràn ngang |
| Lỗi console / network | ✅ 0 lỗi |

### 4.3 Build

```text
web-app:    npm run build   → ✓ built in 8.21s
backend:    mvn -DskipTests package → ✓ BUILD SUCCESS
```

---

## 5. Trạng thái hệ thống đang chạy

| Thành phần | Địa chỉ | Trạng thái |
|---|---|---|
| PostgreSQL | `localhost:5433` | 🟢 healthy (35 branches / 210 courts) |
| Backend | `http://localhost:8080/api` | 🟢 UP |
| Swagger | `http://localhost:8080/api/swagger-ui.html` | 🟢 |
| Web App | `http://127.0.0.1:5174` | 🟢 |
| Web Admin | `http://127.0.0.1:5173` | 🟢 |

**Tài khoản demo**

```text
admin@bcm.com  / admin123     (ADMIN)
guest@bcm.com  / guest123     (CUSTOMER)
```

**Khởi động lại từ đầu**

```bash
# Terminal 1 — database + backend
docker compose up -d --build

# Terminal 2 — web app
cd web-app && npm install && npm run dev      # http://127.0.0.1:5174

# Terminal 3 — web admin
cd web-admin && npm install && npm run dev    # http://127.0.0.1:5173
```

---

## 6. Vấn đề còn lại & đề xuất

| # | Vấn đề | Mức độ | Ghi chú |
|---|---|---|---|
| 1 | `max_connections = 100` của PostgreSQL hơi thấp cho test suite | Thấp | Đã xử lý bằng DB test riêng + `forkCount=1`. Có thể nâng lên 200–300 |
| 2 | `web-app/dist/` đang được Git theo dõi | Thấp | Cân nhắc thêm `web-app/dist/` vào `.gitignore` |
| 3 | Biến môi trường VNPay trong `docker-compose.yml` đang để trống | Trung bình | `BOOKING_VNPAY_TMN_CODE` / `HASH_SECRET` cần điền để chạy thanh toán thật |
| 4 | `docker-compose.yml` có URL VNPay chứa ký tự ẩn (zero-width) | Trung bình | `https://sandbox.vnpayment.vn` — nên kiểm tra lại khi deploy |
| 5 | Trang `/profile` vẫn là placeholder | Thấp | Chưa có yêu cầu cụ thể |

---

## 7. Việc chưa làm

- **Chưa tạo PR** — đã merge thẳng vào `develop` theo yêu cầu.
- **Chưa xoá** `web-app/dist/` khỏi Git (thao tác khôi phục bị hệ thống chặn).
- **Chưa deploy** môi trường thật.

---

## 8. Lịch sử merge vào `develop`

```text
70154f0  Merge branch 'feature/front-end-user' into develop
7750141  Merge branch 'feature/booking-history-v2' into develop
6a83709  feat(web-app): them trang lich dat cua toi
```

Nhánh `feature/front-end-user` (của Thành viên 4) đã được merge vào `develop` trước,
bao gồm commit `275b228` "Xóa icon bị chê" sửa `web-admin/src/App.jsx` — build
web-admin sau thay đổi này vẫn PASS.

---

## 8. Ghi chú về quy trình

Trong phiên trước tôi đã tạo nhánh `feature/booking-history` từ `develop` (commit `7d27fb6`)
mà **không** đưa code từ `feature/front-end-user` vào — khiến toàn bộ phần việc của
Thành viên 4 vắng mặt khỏi nhánh đó. Phiên này đã sửa lại: lấy `feature/front-end-user`
làm nền, tạo nhánh `feature/booking-history-v2`, và chỉ bổ sung phần còn thiếu
(trang lịch sử) mà không sửa lại các trang đã hoàn thành.
