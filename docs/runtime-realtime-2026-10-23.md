# Backend recovery, demo districts, realtime slots và ports — 2026-10-23

## A. Git / phạm vi

Branch trước/sau: `feature/front-end-user`. Ban đầu 50 modified + 13 untracked, bao gồm toàn bộ
diff Booking Engine/checkout trước đó. Không switch/commit/push/pull/merge/reset/restore/clean.
Cuối lượt 53 modified + 21 untracked; giữ diff cũ. Không thêm dependency/architecture/Auth flow.

Source sửa trong lượt này:

- `backend/src/main/java/com/bcm/controller/PublicCatalogController.java`: public booking clock.
- `backend/src/main/java/com/bcm/dto/response/BookingClockResponse.java`: response clock mới.
- `backend/src/main/java/com/bcm/dto/response/AvailabilityResponse.java`: mô tả đúng temporal availability.
- `backend/src/test/java/com/bcm/service/BookingModificationPostgresTest.java`: fixed-clock cases cho
  availability/create/edit, ngày trước/hôm nay/ngày sau và midnight clock API.
- `backend/src/test/java/com/bcm/service/BookingUxMigrationPostgresTest.java`: seed opt-in/idempotent,
  giữ nhãn đã sửa và không đổi dữ liệu booking/payment cũ.
- `db/demo-districts-seed.sql`: seed mới; `db/README.md`: cách chạy có điều kiện và backup.
- `web-app/src/lib/bookingTime.js`, `useBookingClock.js`: timezone/monotonic clock dùng chung.
- `web-app/src/CustomerCourts.jsx`, `CustomerCheckout.jsx`: future-only render, selection pruning,
  ngày/minimum backend, editor và ngày mai; không can thiệp lịch sử hoặc payment evidence.
- `web-app/booking-time.test.mjs`, `realtime-slots.e2e.cjs`, `runtime-smoke.e2e.cjs`: tests mới.
- Ba scripts E2E có sẵn đổi default customer URL sang 5174 (có thể override cho DB audit).
- `web-admin/vite.config.js`, `web-app/vite.config.js`: giữ strict ports, proxy tới IPv4 backend.
- `.env.example`, `docker-compose.yml`: default Return đúng customer 5174, không đổi merchant credentials.
- `docs/booking-engine-api.md`, `plans.md`, báo cáo này: contract/runtime/test evidence.

Giữ nguyên source migration lịch sử và SQL upgrade đã có; không đổi BookingService,
BookingModificationService, expiration, payment/VNPay/IPN/Return implementation, enums, JWT/Auth,
POS/CASH hoặc DB ddl-auto validate / SQL init never. Các validation giờ chơi trước đó đã dùng cùng
Clock instant và Asia/Ho_Chi_Minh; bổ sung tests chứng minh thay vì viết lại nghiệp vụ đúng.

## B. Crash và backup/migration trên database chính

Đã xác minh container `bcm-backend` kết nối chính xác:
`jdbc:postgresql://postgres:5432/badminton_court_db`, PostgreSQL container `bcm-postgres`, host port 5433.
Trước sửa backend Restarting, restart count 32; root log:

```text
Schema-validation: missing column [customer_note] in table [bookings]
```

information_schema xác nhận thiếu cả customer_note và district, không phải lỗi React/proxy.
Trước mọi schema/data change đã pg_dump -Fc ra file và kiểm tra pg_restore --list (144 dòng liệt kê).
Backup local riêng, không commit và không in nội dung dữ liệu/secrets:

```text
C:\Users\Tea\AppData\Local\Temp\bcm-before-runtime-20261023-144038.dump
59,622 bytes
SHA256 C29572D488003BF3C2DA3486682AC495EA1FB5E303C7A98058ECB01878EED681
```

Tái sử dụng nguyên `db/booking-ux-upgrade.sql`; đã apply trên main bằng psql ON_ERROR_STOP.
Schema sau: bookings.customer_note character varying(1000), courts.district character varying(150).
Không tạo bảng/duplicate migration, không ddl-auto update, không restore/reset/drop dữ liệu chính.
Sau migration restart backend thoát crash; sau verify build image và recreate riêng backend:

```text
docker compose build backend
docker compose up -d --no-deps --force-recreate backend
```

Backend running, restart count 0; log Tomcat port 8080 /api + application started.
Public catalog/time slots/clock HTTP 200; authenticated availability 200; Vite admin/customer proxy 200.

## C. District demo

Năm sân trước đó khớp nguyên `db/court-demo-seed.sql`; runtime là local Compose demo.
Không có cột street address hoặc bảng location/branch trong schema, không dùng địa chỉ customer/supplier.
Không suy vị trí từ tên sân; năm record cũ giữ NULL. Bảy cơ sở mới đều có prefix `[DEMO]`,
tên cơ sở và district cùng nhãn khu vực. Không bịa địa chỉ đường/số nhà, không tuyên bố địa chỉ thật.
Đây là nhãn khu vực demo theo gợi ý của người dùng, không phải directory địa giới hiện hành.

Seed mới yêu cầu GUC `bcm.allow_demo_seed=on` trong cùng session; mặc định từ chối,
không tự mount chạy trong production. Stable UUID namespace và court_number 10001..10007,
ON CONFLICT(id) DO NOTHING; collision record không phải demo làm rollback; không ghi đè giá trị đã có.
Lần đầu INSERT 7; chạy lại INSERT 0. Main DB query thực tế:

| district | count |
|---|---:|
| Bình Thạnh | 1 |
| Gò Vấp | 1 |
| Quận 1 | 1 |
| Quận 3 | 1 |
| Quận 5 | 1 |
| Quận 7 | 1 |
| Tân Bình | 1 |

Tổng 12 sân, 7 district distinct; 5 sân cũ vẫn NULL.
Booking 10 -> 10; payment 6 -> 6; customer 7 -> 8 (một account chẩn đoán demo do live smoke test đăng ký);
master time_slots vẫn 16. Không tạo booking/payment chính khi smoke: POST past-slot bị 400, history rỗng.
E2E ghi booking/payment fixtures chạy riêng trên DB audit, không xóa dữ liệu ứng dụng.

Lệnh đã chạy (sau backup và migration):

```text
docker cp db/demo-districts-seed.sql bcm-postgres:/tmp/demo-districts-seed.sql
docker exec bcm-postgres psql -U bcm_admin -d badminton_court_db -v ON_ERROR_STOP=1 -c "SET bcm.allow_demo_seed=on" -f /tmp/demo-districts-seed.sql
docker exec bcm-postgres psql -U bcm_admin -d badminton_court_db -c "SELECT district, COUNT(*) FROM courts WHERE district IS NOT NULL GROUP BY district ORDER BY district;"
```

## D. Realtime time slots

`GET /api/public/booking-clock` trả ISO Instant + timeZone Asia/Ho_Chi_Minh + today theo zone này.
Cùng injectable bookingExpirationClock instant với availability/create/edit; không đổi timezone của
hold TIMESTAMP cũ hoặc gia hạn expires_at. Quy tắc strict: startDateTime > now; equality đã bắt đầu.
12:00 là noon; slot 12:00 không còn đặt được tại đúng 12:00 hoặc 12:30, slot 13:00 vẫn được đặt.
Ngày quá khứ không được tạo/sửa; availability historical vẫn đọc templates nhưng available=false.

Frontend dùng Intl.DateTimeFormat named zone và phần giờ/số chuẩn hóa; không dựa local date máy khách,
không cộng trừ bảy giờ thủ công, không dùng toISOString để lấy ngày chơi. Clock server được anchor
vào performance.now, tick 250ms, sync 30s/focus/visibility; availability poll 15s/focus.
Không sync được clock ban đầu thì không cho chọn/đặt theo giờ client tự đoán.
Dropdown/chips/extra checkboxes/editor render chỉ future slots; IDs hết giờ được prune, không disabled
mà vẫn render. Giữ lựa chọn future của sân khác và district/name/price filters. Khi midnight,
today/minimum/date picker tiến sang ngày mới; hết toàn bộ giờ có thông báo và nút chọn ngày mai.
Editor reuses cùng clock/policy, bỏ selection đã bắt đầu và không thể submit nó.
Không xóa master templates/lịch sử hoặc tự tạo booking/payment khi đồng hồ tick.

## E. Ports và Return

| Service | URL | Kết quả |
|---|---|---|
| web-admin | http://localhost:5173 | strictPort true, đang chạy, proxy 200 |
| web-app | http://localhost:5174 | strictPort true, đang chạy, /courts + proxy 200 |
| backend | http://localhost:8080/api | running, restart 0 |
| PostgreSQL | localhost:5433 | healthy, main DB đã migrate/seed |

Hai dev servers đã tồn tại đúng thư mục/port; Vite reload config khi chỉnh, không kill runtime người dùng.
Proxy `/api` target `http://127.0.0.1:8080` để kết nối loopback IPv4 rõ ràng, root crash vẫn là thiếu schema.
Compose/.env.example default Return `http://localhost:5174/payment-result`; inspect runtime xác nhận đúng URL,
không về 5173. Không đổi/in merchant credentials; .env thật không sửa.
E2E audit dùng Vite 5175 -> backend 8081 riêng; runtime chính giữ 5173/5174/8080.

## F. Tests / bằng chứng

- **PASS** full `mvn -o -B -f backend/pom.xml verify` Docker Java 17.0.15/Maven 3.9.9:
  **394 tests, 0 failures/errors/skipped**, PostgreSQL 15.19/schema gốc + upgrade cho Booking suites.
  Log `%TEMP%/bcm-realtime-verify.log`, XML `backend/target/surefire-reports`.
  Các module khác giữ profile test riêng của repository; không dùng H2 làm bằng chứng locking.
- **PASS** 31 BookingModificationPostgresTest cases, trong đó fixed Clock ở 00:00/06:59/07:00/
  11:59/12:00/12:30/23:59, yesterday/today/tomorrow, occupied slot, create/edit và midnight.
  Regression availability, concurrency, notes, expiry 10 phút, history, ownership, VNPay/IPN/Return,
  CUSTOMER_DECLARED bank và CASH/POS PASS trong full verify.
- **PASS** `node --test web-app/booking-time.test.mjs`: 11 tests, boundary equality, running slot,
  midnight/leap-calendar, monotonic advancement, multiple courts, missing clock fails closed.
- **PASS** `npm run build` web-app (1662 modules), web-admin (27 modules). Lượt npm --prefix
  trong sandbox ban đầu EPERM ở profile path; đã chạy lại đúng cwd với escalation, cả hai PASS.
- **PASS** `realtime-slots.e2e.cjs` với clock-only fixture và real audit backend/catalog/availability/JWT:
  tất cả mốc giờ trên, dropdown/chips không chứa past starts, đang mở tự ẩn slot vừa bắt đầu,
  giữ checkbox sân khác/filters, midnight và checkout editor, browser timezone America/Los_Angeles.
  Clock-only fixture là kiểm thử UI; không dùng nó làm bằng chứng backend chặn quá khứ.
- **PASS** backend fixed-clock PostgreSQL tests + live `runtime-smoke.e2e.cjs` không mock clock/API
  chứng minh server thật từ chối POST past slot 400, không orphan header, availability/time slots 200.
  Live customer UI có đúng 7 district choices, OR/reset đúng, giờ quá khứ không xuất hiện.
- **PASS** ba suites `booking-payment.e2e.cjs` (day offset 60), `checkout-expiration.e2e.cjs`,
  `courts-checkout-ux.e2e.cjs` trên DB audit: multi-slot/court state/filter/notes/edit/accounting/ownership,
  bank/result/refresh, countdown/expiry/no expired home button, responsive 1440/820/375/320px,
  không uncaught frontend JavaScript errors. Không thêm mixed exports vào Fast Refresh boundary.
- **PASS** `git diff --check`, branch vẫn feature/front-end-user.

## G. Runtime cuối và giới hạn

Main DB đã backup/migrate/seed; backend chính đã rebuild/recreate và API 200. Hai frontend chính
giữ chạy đúng port. PostgreSQL chính/volume không recreate/xóa. Dữ liệu booking/payment cũ được giữ.
Các sân seed là demo, không phải địa chỉ thật được chứng thực. Full live VNPay Sandbox settlement/IPN
từ provider chưa thực hiện trong lượt này; signature/IPN/Return regression tests PASS và Return URL đúng.
Không đổi merchant credentials hoặc dùng browser redirect làm bằng chứng đã thu tiền.
Sau kiểm thử đã dừng riêng Vite audit 5175, `bcm-booking-ux-http` và
`bcm-booking-audit-20261022`; giữ container/data test, không xóa volume. Hai frontend chính
5173/5174 và backend/PostgreSQL chính tiếp tục chạy.
