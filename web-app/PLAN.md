# 📋 WEB APP — KẾ HOẠCH CHỈNH SỬA & ĐỒNG BỘ VỚI BACKEND

> **Ngày:** 2024-10-13
> **Phạm vi:** 6 file mockup PNG trong `web-app/` + đối chiếu API Backend
> **Nguyên tắc:** Ảnh PNG là **tài liệu thiết kế tham chiếu** (reference), không sửa trực tiếp trong file ảnh.
> Mọi thay đổi nội dung được ghi lại tại đây để **frontend implement khi code ReactJS**.

---

## 📌 PHẦN 1 — SỬA TỈNH THÀNH: TP HCM ONLY

> **Bối cảnh:** Đồ án chỉ triển khai ở **TP HCM**. Mockup hiện đang có 3 tỉnh thành do copy từ template.
> **Quyết định:** Xoá toàn bộ khái niệm "khu vực / tỉnh thành" khỏi UI, thay bằng **một địa điểm duy nhất: TP.HCM**.

### 1.1. Bảng thay thế nội dung

| Vị trí trong mockup | Hiện tại | Đổi thành |
|---|---|---|
| Footer (cả 5 trang) | `Hà Nội • TP.HCM • Đà Nẵng` | `TP. Hồ Chí Minh` |
| Hero - stat card (Trang chủ) | `Sân tại 3 tỉnh thành` / `Hà Nội • TP.HCM • Đà Nẵng` | `Sân tại TP.HCM` / `Quận trung tâm TP.HCM` |
| Hero - stat card | `+150 Sân tại 3 tỉnh thành` | `+150 Sân tại TP.HCM` |
| Hero - stat card | `0% Phí đặt sân khi học được` | giữ nguyên (không liên quan tỉnh) |
| Filter "Khu vực" (Trang đặt sân) | Dropdown `Hà Nội` + 3 checkbox | **BỎ HẾT** filter khu vực |
| Filter "Khu vực" → tên sân | Badge `📍 Hà Nội` / `📍 TP.HCM` / `📍 Đà Nẵng` | `📍 TP.HCM` cho tất cả |
| Trang Liên hệ - "Sân ở gần bạn" | 3 mục Hà Nội / TP.HCM / Đà Nẵng | 1 mục: `TP. Hồ Chí Minh` |
| Đăng nhập - feature list | `Hà Nội • TP.HCM • Đà Nẵng` | `TP. Hồ Chí Minh` |
| Hướng dẫn - "Địa điểm của bạn" | `Hà Nội` | `TP. Hồ Chí Minh` |
| Trang chủ - testimonial | `Hội nhóm cầu lông Hà Nội` | `Hội nhóm cầu lông TP.HCM` |

### 1.2. Thay đổi logic liên quan

| Mục | Thay đổi |
|---|---|
| Bộ lọc "Khu vực" (sidebar Trang đặt sân) | **Xoá khối "Khu vực"** + 3 checkbox. Chỉ giữ: Giá mỗi giờ/sân, Tiện ích sân, Đánh giá |
| Header filter "Địa điểm" (Trang đặt sân) | Đổi dropdown thành **text tĩnh** hoặc select 1 option duy nhất `TP. Hồ Chí Minh` |
| Trang chủ - form "Tìm sân phù hợp" | Select "Khu vực" → 1 option `TP. Hồ Chí Minh` (hoặc bỏ, dùng select sân) |
| Trang chủ - "3 tỉnh thành" nếu có trong copy | → "TP.HCM" |
| Ổ địa chỉ trong footer | `Hà Nội • TP.HCM • Đà Nẵng` → `TP. Hồ Chí Minh` |

### 1.3. Lưu ý về Backend

> **Backend KHÔNG có cột `province`/`district`/`city` trong bảng `courts`.**
> Bảng `courts` chỉ có: `id, court_number, name, type, status, base_price, created_at, updated_at, deleted_at`.
> → **Không được** thêm filter theo tỉnh/thành vào API. Mọi sân đều thuộc TP.HCM.
> Nếu cần hiển thị địa chỉ cụ thể, phải sửa schema (`db/migration.sql`) — **chưa nằm trong scope hiện tại**.

---

## 📌 PHẦN 2 — ĐỐI CHIẾU UI VỚI API BACKEND

> Nguồn: đọc trực tiếp source `backend/src/main/java/com/bcm/controller/` + `dto/` + `entity/`.

### 2.1. Bảng mapping endpoint

| Màn hình trong mockup | Endpoint backend | Auth | Ghi chú |
|---|---|---|---|
| Trang chủ - "Sân nổi bật hôm nay" | `GET /api/public/courts` | ❌ Public | Chỉ trả court `AVAILABLE` |
| Trang chủ - form tìm sân | `GET /api/public/courts`<br>`GET /api/public/time-slots` | ❌ | |
| Trang chủ - giá sân | `GET /api/public/price-quote?courtId=&timeSlotId=` | ❌ | Trả `basePrice`, `priceMultiplier`, `price` |
| Đặt sân - ma trận slot | `GET /api/availability?date=YYYY-MM-DD` | ✅ Bearer | Trả `courts[].slots[].available` |
| Đặt sân - tạo booking | `POST /api/bookings` | ✅ Bearer | Body: `customerId` + `details[]` |
| Đặt sân - thanh toán | `POST /api/bookings/{id}/payments` | ✅ Bearer | Chỉ `VNPAY` |
| Đăng nhập | `POST /api/auth/login` | ❌ | |
| Đăng ký | `POST /api/auth/register` | ❌ | |
| Lịch đặt của tôi | `GET /api/bookings?page=&size=` | ✅ Bearer | Phân trang 0-based |
| Chi tiết vé | `GET /api/bookings/{id}` | ✅ Bearer | |

### 2.2. ⚠️ MISMATCH CẦN XỬ LÝ

#### ❌ M1. Đăng nhập: "Email hoặc số điện thoại" — Backend chỉ nhận Email
- **Mockup (Đăng nhập):** label *"Email hoặc số điện thoại"*, placeholder *"Nhập email hoặc số điện thoại"*
- **Backend `LoginRequest`:** `@Email` + `@NotBlank` → chỉ nhận email. Gửi số ĐT sẽ fail validation 400.
- **Sửa:** Đổi label → **"Email"**, placeholder → *"Nhập email của bạn"*.
  *(Nếu muốn login bằng SĐT thì phải mở rộng backend — không nằm trong scope frontend.)*

#### ❌ M2. Đăng ký: thiếu trường bắt buộc — Backend không có `fullName` trong request? → CÓ, nhưng mockup thiếu trường khác
- **Mockup (Đăng ký):** có `Họ và tên`, `Email`, `Số điện thoại`, `Mật khẩu`, `Xác nhận mật khẩu`
- **Backend `RegisterRequest`:** `email, password, fullName, phone, address` — `address` **optional**
- **Sửa:** Mockup đã đủ field bắt buộc ✅. Không cần thêm `address` (optional).
- **⚠️ Password rule:** Backend `@Size(min = 6, max = 100)`. Mockup ghi *"Ít nhất 8 ký tự, gồm chữ hoa, chữ thường và số."* → **MISMATCH**.

#### ❌ M3. Password rule không khớp backend
| Nơi | Mockup nói | Backend thực tế |
|---|---|---|
| Đăng ký | ≥ 8 ký tự, có chữ hoa + thường + số | `@Size(min = 6, max = 100)` — **không yêu cầu hoa/thường/số** |
| Đăng nhập | (không ghi rule) | `@Size(min = 6)` |

**Sửa (2 lựa chọn):**
- **(A) Chỉnh UI:** đổi hint thành *"Ít nhất 6 ký tử."* → đúng backend, không đụng backend. ✅ **Khuyến nghị**
- **(B) Siết backend:** nâng `RegisterRequest.password` lên `@Size(min = 8)` + `@Pattern` yêu cầu hoa/thường/số → phải update `AuthServiceTest` + `DataSeeder` password. ❌ Không khuyến nghị (đụng module TV1 đã duyệt)

#### ❌ M4. Số điện thoại: regex backend không hỗ trợ `+84`
- **Backend `RegisterRequest.phone`:** `@Pattern(regexp = "^[0-9]{10,20}$")` → chỉ số, **không có `+`, `-`, khoảng trắng**
- **Mockup placeholder:** *"Nhập số điện thoại"* (không ghi format) → user có thể gõ `0912 345 678` hoặc `+84 912...` → **400**
- **Sửa:** Thêm hint rõ ràng: *"10–20 chữ số, không khoảng trắng hoặc dấu + (VD: 0912345678)"*.
  *(Hoặc nới regex backend — nhưng đó là fix optional đã ghi ở PROGRESS_REPORT.)*

#### ❌ M5. Trang đặt sân: filter "Tiện ích sân" & "Đánh giá" — Backend KHÔNG CÓ dữ liệu
- **Mockup:** checkbox `Có bãi xe`, `Phòng thay đồ`, `Máy lạnh`, `Cho thuê vợt` + slider `Từ 4.5 sao trở lên` + badge `4.9 (128 đánh giá)`
- **Backend:** `courts` không có cột `amenities`, `rating`, `review_count`. `Product` có `type`/`unit` — cho thuê vợt là **product/dịch vụ**, không phải thuộc tính sân.
- **Sửa (2 lựa chọn):**
  - **(A) Bỏ filter, giữ badge rating hiển thị tĩnh** → UI đẹp nhưng rating là dữ liệu giả. ⚠️
  - **(B) Bỏ hoàn toàn rating + amenities filter, chỉ giữ filter giá** → trung thực với backend. ✅ **Khuyến nghị**
  - *(Lựa chọn C: thêm cột `amenities`/`rating` vào schema + API → cần TV2 sửa migration, ngoài scope frontend)*

#### ❌ M6. Trang đặt sân: hiển thị "Còn 3 sân / Chỉ còn 1 sân" — Backend KHÔNG có count
- **Mockup:** badge `Còn 3 sân` (Star City), `Chỉ còn 1 sân` (Green Court), `Còn 2 sân` (Sky Sports)
- **Backend `AvailabilityResponse`:** `SlotAvailability.available` chỉ là boolean cho **1 slot**, không có số lượng slot trống / số sân.
- **Sửa:** Frontend tự tính: `availableSlots = slots.filter(s => s.available).length` từ `GET /api/availability`.
  - Nếu `availableSlots === 0` → badge đỏ `"Hết chỗ"`
  - Nếu `availableSlots <= 2` → badge vàng `"Chỉ còn N khung giờ"`
  - Badge hiện tại ghi **"sân"** → phải đổi thành **"khung giờ"** cho đúng ngữ nghĩa.

#### ❌ M7. Trang đặt sân: "Giá trống: 18:00–19:00 / 19:00–20:00" — phải map từ time_slots
- **Mockup:** hiển thị 2 chip giờ trống cụ thể.
- **Backend:** `AvailabilityResponse.courts[].slots[]` có `timeSlotId, startTime, endTime, priceMultiplier, available`.
- **Sửa:** render từ `slots.filter(s => s.available)` → map `startTime.slice(0,5) – endTime.slice(0,5)`.

#### ❌ M8. Trang đặt sân: giá hiển thị `/giờ/sân` nhưng backend tính theo **khung giờ**
- **Mockup:** `180.000đ / giờ / sân`, footer form `Mức giá / giờ: 100.000đ – 250.000đ`
- **Backend:** `price = basePrice × priceMultiplier` — `basePrice` là giá **1 khung giờ** (mặc định 1 tiếng) → giá cả khung giờ.
- **Sửa:** Đổi nhãn `180.000đ / giờ / sân` → `180.000đ / khung giờ` hoặc giữ `/giờ` nếu bảo đảm **mọi time_slot đều đúng 1 tiếng** (DataSeeder cần kiểm tra).

#### ❌ M9. Trang chủ: "Ưu đãi 24/7" / "0% Phí đặt sân" — không có trong scope
- **Backend:** không có module khuyến mãi 3 tầng dù đã có bảng `promotions`/`discount_rules` — **TV2 chưa implement API** (chỉ có bảng trong migration.sql).
- **Sửa:** Bỏ stat card "0% phí đặt sân" hoặc đổi nội dung trung thực (vd: "Hỗ trợ VNPay"). Kiểm tra lại: `grep -rn "promotions" backend/src/main/java/com/bcm/controller/` → **không có controller nào**.

#### ❌ M10. Trang chủ: "Hỗ trợ thanh toán" — mockup ghi "Đặt giờ chính, thanh toán tại chỗ"
- **Backend:** `PaymentAttemptController` chỉ hỗ trợ **VNPAY** (online), không có cổng offline. `PosService`/`CheckoutService` (offline) là của **web-admin**, yêu cầu role ADMIN/STAFF.
- **Sửa:** Copy nên nói: *"Đặt giờ online, thanh toán qua VNPay. Trả sân tại quầy khi kết thúc."*

#### ❌ M11. Trang Liên hệ: form "Mã đặt sân" + "Gửi yêu cầu" — Backend KHÔNG có contact/support endpoint
- **Mockup:** form gửi lời nhắn nhân hỗ trợ có `Mã đặt sân (không bắt buộc)`, upload ảnh, submit.
- **Backend:** **không có** controller nào cho support/contact. `PublicCatalogController` là read-only.
- **Sửa (chọn 1):**
  - **(A)** Bỏ form, chỉ giữ thông tin liên hệ (hotline, email, mạng xã hội) + FAQ → đơn giản, đúng backend. ✅ **Khuyến nghị**
  - **(B)** Implement mới `POST /api/public/support-requests` → cần bảng DB mới → **ngoài scope frontend**.

#### ❌ M12. Trang Liên hệ: "Gửi lời nhắn" cần backend support
→ gộp với M11.

#### ❌ M13. Đăng nhập/Đăng ký: nút "Tiếp tục với Google" — Backend KHÔNG có OAuth
- **Mockup:** nút Google ở cả 2 trang.
- **Backend:** chỉ có `/auth/login`, `/auth/register`, `/auth/refresh` (email+password). Không có OAuth2 client.
- **Sửa:** **Bỏ nút Google** hoặc để ở trạng thái disabled kèm tooltip *"Sắp có"*. ✅ **Khuyến nghị bỏ hẳn**

#### ❌ M14. Đăng nhập: "Quên mật khẩu?" — Backend KHÔNG có forgot-password
- **Mockup:** link "Quên mật khẩu?"
- **Backend:** không có endpoint reset password.
- **Sửa:** Bỏ link, hoặc đổi thành text *"Cần trợ giúp? Gọi 1900 1000"*.

#### ❌ M15. Header "Lịch đặt của tôi" badge số `1`
- **Mockup:** badge đếm số lượng booking đang active.
- **Backend:** `GET /api/bookings?page=0&size=20` trả `totalElements`.
- **Sửa:** Frontend gọi API và lấy `totalElements` (hoặc filter status `PENDING|PAID|CHECKED_IN` — cần gọi 3 lần hoặc đổi backend hỗ trợ nhiều status).

#### ❌ M16. Mockup có màn hình **"Lịch của tôi"** (tab header + footer link) — Backend CÓ, mockup CHƯA thiết kế
- **Backend:** `GET /api/bookings` + `GET /api/bookings/{id}` đầy đủ.
- **Sửa:** Cần **bổ sung thiết kế** màn hình "Lịch của tôi" (list + detail + QR). Đây là màn hình **bắt buộc** vì backend đã có.

#### ❌ M17. Mockup có màn hình **"Profile cá nhân"** — Backend CÓ, mockup CHƯA thiết kế
- **Backend:** `GET /api/users/me` trả `id, email, fullName, phone, address, roles`.
- **Sửa:** Cần **bổ sung thiết kế** màn hình profile (xem + sửa thông tin + đổi mật khẩu). *Đổi mật khẩu chưa có API — cần backend bổ sung.*

#### ❌ M18. Trang Hướng dẫn: "Mã đặt sân: SC-241026" — format khác backend
- **Mockup:** mã dạng `SC-241026` (prefix + ngày).
- **Backend:** booking id là **UUID** (`uuid_generate_v4()`), không có mã ngắn dạng `SC-...`.
- **Sửa:** Hiển thị **UUID** (rút gọn 8 ký tự đầu + "…") hoặc thêm cột `booking_code` vào schema (ngoài scope). ✅ **Khuyến nghị hiển thị UUID rút gọn**

#### ❌ M19. "QR xác nhận đặt sân" — Backend KHÔNG generate QR
- **Mockup (Hướng dẫn + vé):** có mã QR.
- **Backend:** không có endpoint generate QR. `BookingHistoryDetailResponse` không có field QR.
- **Sửa:** Thiết kế lại màn hình vé: dùng **UUID booking + thông tin giờ/sân** thay cho QR. Nếu cần QR thật → frontend generate từ `bookingId` bằng thư viện `qrcode` (client-side, không cần backend). ✅ *Khuyến nghị: client-side QR từ bookingId*

#### ❌ M20. Trang Hướng dẫn: "Sẵn sàng cho trận cầu tiếp theo" — hỗ trợ session
- **Sửa:** Implement bằng `localStorage` phía client (không cần backend).

#### ❌ M21. Tất cả số liệu "ảo" trong mockup
| Số | Nơi | Xử lý |
|---|---|---|
| `+150 sân` | Trang chủ | Thay bằng `GET /api/public/courts` length |
| `128 đánh giá` | Badge sân | Bỏ (M5) |
| `4.9 / 4.8 / 4.7` | Badge sân | Bỏ (M5) |
| `6 sân / 8 sân / 4 sân` | Mô tả sân | Bỏ — `courts` không có field số sân |
| `Bãi xe rộng`, `Phòng thay đồ`, `Máy lạnh`, `Trần cao`, `Ánh sáng tự nhiên` | Amenities | Bỏ (M5) |
| `24/7`, `1900 1000` | Hotline | Giữ (static config) |
| `support@sancaulong.vn` | Email | Giữ (static config) |
| `Hơn 150 sân chất lượng` | Đăng nhập/Đăng ký | Bỏ hoặc thay bằng số thật |

### 2.3. ✅ NHỮNG THỨ ĐÃ KHỚP BACKEND

| Mockup | Backend | OK? |
|---|---|---|
| 3 bước đặt sân (Chọn khung giờ → Xem giá → Thanh toán) | `availability` → `price-quote` → `payments` | ✅ |
| Kết quả khung giờ 18:00–20:00 | `time_slots` 06:00–21:00 | ✅ |
| Giá `180.000đ` = `100.000đ × 1.8`? | `price = basePrice × priceMultiplier` | ✅ (mockup dùng 180k base) |
| `Đặt sân ngay` → VNPay redirect | `POST /bookings/{id}/payments` → `checkoutUrl` | ✅ |
| Badge trạng thái booking: `Sắp tới / Hoàn thành / Đã hủy` | `BookingStatus`: PENDING/PAID/CHECKED_IN/COMPLETED/NO_SHOW/EXPIRED | ⚠️ map sang: Sắp tới = PENDING+PAID, Hoàn thành = COMPLETED, Đã hủy = EXPIRED+NO_SHOW |
| Phương thức thanh toán | `CASH / BANK_TRANSFER / VNPAY` (checkout) — customer chỉ dùng VNPAY | ⚠️ |

---

## 📌 PHẦN 3 — THIẾT KẾ BỔ SUNG CẦN LÀM

| # | Màn hình | Trạng thái | API dùng |
|---|---|---|---|
| 1 | **Lịch của tôi** (list) | ❌ Chưa thiết kế | `GET /api/bookings?page&size&status` |
| 2 | **Chi tiết vé / QR** | ❌ Chưa thiết kế | `GET /api/bookings/{id}` + QR client-side |
| 3 | **Profile cá nhân** | ❌ Chưa thiết kế | `GET /api/users/me` (PUT chưa có → cần backend) |
| 4 | **Trang kết quả thanh toán** (VNPay return) | ❌ Chưa thiết kế | `GET /api/payments/vnpay/return` là server-side; client poll `GET /api/bookings/{id}` |
| 5 | **Confirm booking (review)** | ❌ Chưa thiết kế | `POST /api/bookings` |

---

## 📌 PHẦN 4 — YÊU CẦU KỸ THUẬT WEB APP

### 4.1. Setup
| Mục | Giá trị |
|---|---|
| Build | Vite + React 18 (giống `web-admin`) |
| Dev server | `http://localhost:5173` — **đã có trong `CorsConfig` allowedOrigins** ✅ |
| Proxy | `/api` → `http://localhost:8080` (copy từ `web-admin/vite.config.js`) |
| API base | `/api` (context path của Spring Boot) |

### 4.2. Auth flow
```
POST /api/auth/login  →  { accessToken, refreshToken, tokenType, user }
   ↓
Lưu accessToken (memory) + refreshToken (localStorage)
   ↓
Axios interceptor:
  request  → gắn "Authorization: Bearer <accessToken>"
  response 401 → gọi POST /api/auth/refresh → retry request
  refresh fail → logout + redirect /login
```
> ⚠️ **Lưu ý bug vừa fix:** backend giờ trả **401** (không phải 403) khi thiếu/hết hạn token → interceptor bắt đúng.

### 4.3. Response envelope
Mọi API trả về:
```json
{ "success": true, "message": "...", "data": {...}, "timestamp": "..." }
```
→ Frontend **phải** đọc `response.data.data`, không phải `response.data`.

### 4.4. Xử lý lỗi chuẩn
| HTTP | Nghĩa | UI |
|---|---|---|
| 400 | Validation fail | Hiển thị `message` + field errors (`data` chứa map field → message) |
| 401 | Chưa đăng nhập / token hết hạn | Refresh token → retry, fail thì redirect login |
| 403 | Không đủ quyền | Toast "Bạn không có quyền thực hiện thao tác này" |
| 404 | Không tìm thấy | Toast rỗng / điều hướng về trang trước |
| 409 | Conflict (slot đã có người đặt) | **"Sân đã có người đặt. Vui lòng chọn khung giờ khác."** ← DoD của TV3 |

### 4.5. Biến môi trường
```
VITE_API_BASE_URL=/api
```

---

## 📌 PHẦN 5 — CHECKLIST IMPLEMENT

### Phase A — Chỉnh nội dung tỉnh thành (M1–M13 phần nội dung)
- [ ] Xoá mọi "Hà Nội" / "Đà Nẵng" trong 6 mockup (theo bảng 1.1)
- [ ] Bỏ filter "Khu vực" (sidebar + header dropdown)
- [ ] Đổi badge địa điểm thành `TP.HCM`
- [ ] Footer: `Hà Nội • TP.HCM • Đà Nẵng` → `TP. Hồ Chí Minh`
- [ ] "Sân tại 3 tỉnh thành" → "Sân tại TP.HCM"

### Phase B — Sửa mismatch API
- [ ] M1: Login label → "Email"
- [ ] M2/M3: Password hint → "Ít nhất 6 ký tự" (**hoặc** siết backend — chọn 1)
- [ ] M4: Thêm hint format SĐT
- [ ] M5: Bỏ filter tiện ích + slider đánh giá + badge rating
- [ ] M6: Tính badge "Còn N khung giờ" từ `availability`
- [ ] M8: Đổi `/giờ/sân` → `/khung giờ`
- [ ] M9: Bỏ stat "0% phí đặt sán"
- [ ] M10: Sửa copy "Hỗ trợ thanh toán"
- [ ] M11/M12: Bỏ form liên hệ, giữ thông tin + FAQ
- [ ] M13: Bỏ nút Google
- [ ] M14: Bỏ link "Quên mật khẩu?"
- [ ] M15: Badge "Lịch đặt của tôi" lấy từ `totalElements`
- [ ] M18: Mã đặt sân → UUID rút gọn
- [ ] M19: QR generate client-side từ `bookingId`

### Phase C — Thiết kế bổ sung
- [ ] M16: Màn hình **Lịch của tôi**
- [ ] M17: Màn hình **Profile cá nhân**
- [ ] M20: Màn hình **Kết quả thanh toán** (VNPay return)
- [ ] M21: Màn hình **Xác nhận booking** (review trước khi thanh toán)

### Phase D — Dựng ReactJS app
- [ ] Scaffold Vite + React 18, copy `vite.config.js` từ web-admin
- [ ] `AuthContext` + `useAuth` (login/logout/refresh)
- [ ] Axios instance + interceptors (Bearer + 401 refresh)
- [ ] Router: `/`, `/courts`, `/booking`, `/login`, `/register`, `/my-bookings`, `/my-bookings/:id`, `/profile`, `/payment/result`
- [ ] `ProtectedRoute` (check token + role CUSTOMER)
- [ ] Shared: `Layout`, `Header`, `Footer`, `LoadingSpinner`, `EmptyState`, `Toast`
- [ ] Mobile-first responsive (theo yêu cầu: 320px+ → tablet → desktop)

### Phase E — Verify
- [ ] `npm run build` pass
- [ ] Login flow: đăng ký → login → token → gọi API protected
- [ ] Đặt sân: xem ma trận slot → chọn → tạo booking → 409 khi trùng slot
- [ ] Thanh toán VNPay sandbox → return → poll status → COMPLETED
- [ ] Lịch của tôi hiển thị đúng booking của user đang đăng nhập
- [ ] Test 2 browser cùng đặt 1 slot → 1 thành công, 1 báo 409

---

## 📌 PHẦN 6 — BACKEND CẦN BỔ SUNG (gửi về các TV)

> Đây là các hạng mục **backend thiếu** mà mockup/nghiệp vụ web-app cần. Không sửa ngay — cần Leader xác nhận phân công.

| # | Hạng mục | TV | Mức độ | Ghi chú |
|---|---|---|---|---|
| B1 | `GET/PUT /api/users/me` (sửa profile) | TV1 | Cao | Hiện chỉ có `GET /users/me` |
| B2 | `POST /api/users/me/password` (đổi mật khẩu) | TV1 | Trung bình | |
| B3 | `POST /api/auth/forgot-password` | TV1 | Thấp | Hoặc bỏ UI |
| B4 | `GET /api/promotions` + apply voucher | TV2 | **Cao** | Bảng `promotions`/`discount_rules` đã có nhưng **chưa có controller**. Mockup cần "ưu đãi". |
| B5 | `GET /api/public/support-requests` (POST) | TV1/TV4 | Thấp | Hoặc bỏ form liên hệ |
| B6 | `POST /api/bookings` hỗ trợ nhiều slot đặt 1 lúc | TV3 | Trung bình | Mockup cho phép chọn nhiều khung giờ 1 lần — API hiện nhận `details[]` ✅ đã đủ |
| B7 | `GET /api/bookings?status=A,B,C` (multi-status) | TV3 | Thấp | Hiện chỉ 1 status |
| B8 | Cột `booking_code` (mã ngắn `SC-241026`) | TV3 | Thấp | Hoặc dùng UUID |
| B9 | `court.amenities` / `court.rating` | TV2 | Trung bình | Nếu muốn giữ filter trong mockup |
| B10 | CORS: thêm origin web-app khi deploy | TV1 | Thấp | Hiện chỉ localhost:5173/3000 |

---

## 📌 PHẦN 7 — GHI CHÚ KỸ THUẬT BACKEND LIÊN QUAN

### 7.1. `ddl-auto: validate` — schema là source of truth
Mọi thay đổi DB phải qua `db/migration.sql`. **Không** thêm field vào entity nếu bảng chưa có cột
(nếu không app sẽ crash lúc startup với `validate`).

### 7.2. Ràng buộc đã biết
| Ràng buộc | Ảnh hưởng web-app |
|---|---|
| `uq_booking_slot (booking_date, court_id, time_slot_id)` | 1 slot chỉ 1 booking → UI phải xử lý 409 |
| `chk_pending_expires` | Booking PENDING bắt buộc có `expires_at` |
| `chk_payment_target` | Payment thuộc booking HOẶC invoice, không cả hai |
| `court.base_price >= 0` | Giá âm bị DB chặn |
| `time_slot.price_multiplier > 0` | |

### 7.3. Endpoint **CHƯA** có trong backend (mockup dùng)
| Mockup cần | Endpoint | Trạng thái |
|---|---|---|
| Đếm số booking active (badge header) | `GET /api/bookings` → `totalElements` | ✅ có |
| Số khung giờ trống của 1 sân | `GET /api/availability` → tính client | ✅ có |
| Tổng tiền booking | `BookingResponse.courtFee` | ✅ có |
| QR code vé | client-side từ `bookingId` | ✅ không cần backend |
| Tìm sân theo giá/ngày/giờ | `GET /api/availability?date=` + filter client | ✅ có |
| Thanh toán VNPay | `POST /api/bookings/{id}/payments` | ✅ có |

---

## ✅ TÓM TẮT

| Hạng mục | Số lượng |
|---|---|
| Sửa nội dung tỉnh thành → TP.HCM | 11 vị trí trong 6 mockup |
| Mismatch UI ↔ API cần sửa | 21 (M1–M21) |
| Màn hình cần thiết kế bổ sung | 5 |
| Hạng mục backend cần bổ sung | 10 (B1–B10) |
| Trang chủ / Đăng nhập / Đăng ký | Chỉnh tỉnh thành + 4 mismatch (M1, M3, M4, M13) |
| Trang đặt sân | Chỉnh tỉnh thành + 5 mismatch (M5, M6, M7, M8) |
| Trang hướng dẫn | Chỉnh tỉnh thành + 2 mismatch (M18, M19) |
| Trang liên hệ | Chỉnh tỉnh thành + 2 mismatch (M11, M12) |

**Ưu tiên xử lý trước khi code:** M1, M5, M6, M13, M16, M17 — vì ảnh hưởng trực tiếp luồng đăng nhập + đặt sân + 2 màn hình còn thiếu.
