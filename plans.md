# Kế hoạch Booking Engine — Thành viên 3

Branch: `feature/booking-engine`. Mỗi phase phụ thuộc phase trước; chỉ tạo source khi thực hiện task.
Đã có foundation Phase 1, Availability API Phase 2 và create booking/concurrency Phase 3, kèm test PostgreSQL.
Đã có expiration/release slot tự động Phase 4 và payment attempt foundation Phase 5A.
Đã có VNPay Sandbox checkout Phase 5A.5; scope hiện tại chỉ hỗ trợ VNPay.
Đã có callback/IPN verification và SUCCESS + PAID atomic Phase 5B.
Đã xác minh idempotency/concurrent callback/expiration races Phase 5C trên PostgreSQL.
Payment scope and schema are VNPay only. Chưa có frontend.
Phase 6 COMPLETE: authenticated customer history/detail/payment ownership, tests and API contract
verified after the Auth handoff. Phase 7 remains unimplemented.
Checklist hoàn thành dựa trên code và verification thực tế, không lấy từ docs cũ.
Đường dẫn layer Java bên dưới tương đối với `backend/src/main/java/com/bcm/`.

## Phase 1 — Architecture và entity mapping

- [x] Đối chiếu lại architecture khi bắt đầu implement và map Booking theo `db/migration.sql`.
  Tác động: `entity/`, `repository/` cho `bookings`, `booking_details`, `payment_transactions`.
  AC: UUID/BigDecimal/date/time/audit/nullability/status/FK khớp schema;
  không kế thừa `BaseEntity` sai column; startup với `ddl-auto: validate` thành công.
- [x] Kết nối reference tối thiểu đến customer/staff/court/time slot và identity boundary.
  Tác động: mapping/query ở `entity/`, `repository/`, boundary service khi cần.
  AC: tái sử dụng code nếu đã có; đúng customer/staff ID; chỉ đọc master data;
  ghi rõ giới hạn Auth hiện tại, không tạo CRUD hoặc User Management.

Verification: JDK 17 + Maven 3.9.9 portable; targeted test và `mvn -f backend/pom.xml verify`
PASS, 15 tests, 0 skipped trên PostgreSQL 15.10 riêng, khởi tạo bằng nguyên `db/migration.sql`.
Giữ `ddl-auto: validate`, SQL init `never`; không sửa schema/build config/BaseEntity.
Reference: Customer/Staff/Invoice chỉ có ID; Court/TimeSlot map dữ liệu đọc tối thiểu,
`@Immutable`, quan hệ LAZY một chiều, không cascade hoặc repository quản trị.
Identity boundary: customer/staff là domain ID, không phải user ID; reference không cấp quyền.
Auth hiện `permitAll()`, chưa có principal mapping/ownership; phần tích hợp API phải dùng Auth
của module phụ trách, không coi UUID do client gửi là bằng chứng danh tính.
Chạy lại test mapping bằng DB test riêng đã nạp migration và các biến `BCM_TEST_DB_URL`,
`BCM_TEST_DB_USERNAME`, `BCM_TEST_DB_PASSWORD`; thiếu URL thì test được skip, không chứng minh schema.

## Phase 2 — Availability query

- [x] Tra cứu sân/slot trống theo ngày, có thể lọc sân.
  Tác động: `repository/`, `service/`, `controller/`, `dto/response/`, exception handler.
  AC: ngày hợp lệ; chỉ sân AVAILABLE chưa xóa; derive từ details;
  detail còn tồn tại vẫn chặn slot; response theo `ApiResponse`, không ghi master data.

Contract: `GET /api/availability?date=YYYY-MM-DD&courtId=<UUID>`; date bắt buộc,
courtId tùy chọn, cho phép ngày quá khứ. HTTP 400 khi thiếu/sai date hoặc sai UUID;
404 khi court được lọc không tồn tại/không đủ điều kiện. DTO trả date, courts và slots
với boolean `available`; không expose entity hoặc persist availability.
Tối đa 3 SELECT (courts, templates, occupied slot projection); không đọc booking status/deadline.
Verification: `AvailabilityPostgresTest` PASS 25 tests trên PostgreSQL 15.10 dùng schema gốc;
`mvn -f backend/pom.xml verify` PASS tổng 40 tests, 0 skipped. Test xác nhận 3 SELECT
khi grid tăng và dữ liệu 5 bảng trước/sau query không đổi; reuse biến `BCM_TEST_DB_*` của Phase 1.

## Phase 3 — Create booking, pricing và concurrency

- [x] Tạo booking giữ slot với snapshot giá và deadline từ config.
  Tác động: `service/`, `repository/`, DTO/controller Booking, `application.yml` nếu cần.
  AC: validate reference và slot lặp trong request; tính giá/tổng theo `SKILL.md`;
  rounding rõ ràng; PENDING có expires_at; header/details commit hoặc rollback toàn bộ.
- [x] Bảo vệ create booking trước request đồng thời.
  Tác động: query lock trong `repository/`, transaction ở `service/`, exception handler nếu cần.
  AC: khóa row tồn tại bằng PESSIMISTIC_WRITE, thứ tự khóa ổn định;
  `uq_booking_slot` vẫn là bảo vệ cuối; lỗi flush/commit trả 409 rõ ràng, không lưu booking dở dang.

Phase 3A contract: `POST /api/bookings`, body `{customerId, details: [{bookingDate, courtId, timeSlotId}]}`.
HTTP 201 + `ApiResponse` chứa bookingId/status/courtFee/expiresAt/details (kèm snapshot price).
400: input sai, tuple lặp, sân không đủ điều kiện hoặc vượt NUMERIC(10,2); 404: reference không tồn tại;
409: constraint conflict qua handler hiện có. Field JSON ngoài DTO bị bỏ qua, không điều khiển giá/status/expiry.
Giá từng detail làm tròn HALF_UP scale 2 trước khi cộng; deadline dùng `booking.hold-duration`
(`${BOOKING_HOLD_DURATION:PT15M}`, ISO-8601 dương), theo giờ local của ứng dụng như audit hiện tại.
Auth vẫn permitAll: customerId chỉ là customers.id tạm thời, không chứng minh identity/ownership.
Service transaction commit/rollback cả header + details; concurrency được bổ sung ở Phase 3B bên dưới.
Verification Phase 3A: `BookingPostgresTest` 27 tests; regression `AvailabilityPostgresTest` 25 tests;
`mvn -f backend/pom.xml verify` PASS 67 tests, 0 failures/errors/skipped trên PostgreSQL 15.10,
DB riêng với schema gốc. Test quan sát commit/rollback ngoài test transaction, gồm lỗi unique slot khi persist.

Phase 3B: batch Court PESSIMISTIC_WRITE theo `ORDER BY id` (Hibernate PostgreSQL phát SQL
`FOR NO KEY UPDATE`), rồi validate locked state và re-check exact tuples từ booking_details.
Không lọc booking status/deadline; giữ unique index + flush và handler 409 hiện có làm fallback.
Verification: `BookingConcurrencyPostgresTest` PASS 11 tests; hai worker/transaction độc lập,
latch + quan sát hai DB sessions chờ Court lock; đúng 201/409, không partial/orphan data.
Đảo thứ tự Court không deadlock; không overlap thì cả hai thành công; state sau chờ lock được kiểm tra lại.
Booking regression PASS 27 tests (gồm DB unique violation thật + rollback + handler 409), Availability PASS 25;
`mvn -f backend/pom.xml verify` PASS 78 tests, 0 failures/errors/skipped, PostgreSQL 15.10, schema gốc.

## Phase 4 — Expiration và release slot

- [x] Thêm expiration service và job theo config, chỉ xử lý PENDING đã đến deadline.
  Tác động: `service/`, `repository/`, scheduling/config cần thiết, `application.yml`.
  AC: khóa và kiểm tra lại booking; EXPIRED + DELETE mọi details trong một transaction;
  giữ header/court_fee/payment history; chạy lại an toàn; slot đặt lại được.
  Availability chỉ báo trống sau release; không expire booking đã PAID.

Phase 4: candidate IDs theo expires_at + ID; job gọi service proxy, mỗi booking một transaction.
Khóa Booking PESSIMISTIC_WRITE, re-check PENDING và expires_at <= Clock hiện tại sau khóa,
bulk UPDATE status + DELETE details atomic; giữ header/court_fee/audit timestamps/payment history.
Không đổi create/Availability/Court locks/schema; availability trống nhờ DELETE, POST đặt lại trả 201.
Config: `booking.expiration-enabled` mặc định true (`BOOKING_EXPIRATION_ENABLED`),
`booking.expiration-check-interval` mặc định PT30S (`BOOKING_EXPIRATION_CHECK_INTERVAL`),
fixed delay và initial delay; Clock local giống create booking. Lỗi từng booking rollback và retry lần poll sau.
Verification PostgreSQL 15.10/schema gốc: expiration 16 PASS (deadline, re-check PAID/deadline sau lock,
DELETE lock-timeout rollback, rerun, giữ history/audit, release/rebooking, đăng ký scheduler/service proxy).
Regression: Booking 27, concurrency 11, Availability 25 PASS; `mvn -f backend/pom.xml verify`
PASS 94 tests, 0 failures/errors/skipped. Test scope tắt job nền; expiration test bật với delay PT24H và gọi trực tiếp.

## Phase 5 — VNPay Sandbox payment and callback

- [x] PENDING payment attempt with server-calculated amount, XOR target and owned Booking.
- [x] VNPay signed checkout URL generated locally, outside database locks and transactions.
- [x] Validated HMAC-SHA512 IPN with exact merchant/reference/amount correlation.
- [x] Booking then Payment lock ordering; SUCCESS + PAID committed atomically before deadline.
- [x] Retry reuses the pending attempt; duplicate callback preserves confirmed metadata.
- [x] Concurrent callbacks, unique transaction-ID rollback and both expiration race winners verified on PostgreSQL.
- [x] Browser Return verifies display data and never settles or reads/writes the database.

Current paymentMethod enum and both schema payment-method CHECKs allow VNPAY only.
Configuration uses BOOKING_VNPAY_TMN_CODE, BOOKING_VNPAY_HASH_SECRET,
BOOKING_VNPAY_RETURN_URL and BOOKING_VNPAY_PAYMENT_URL. Credentials come from runtime configuration.
Public routes: GET /api/payments/vnpay/ipn and GET /api/payments/vnpay/return.
Existing local/dev databases with removed payment methods may need recreation from current migration.
Application data is never automatically reset. Phase 7 remains unimplemented.

## Phase 6 — Tests, history và API contract

- [x] Hoàn thiện unit/integration/concurrency tests; test mục tiêu chạy cùng từng phase trước.
  Tác động: `backend/src/test/java/com/bcm/`, test resources, `backend/pom.xml` chỉ nếu cần.
  AC: pricing/rounding/rollback, expiry tại deadline + đặt lại slot, callback giả/sai tiền/lặp,
  callback cạnh tranh expiry; PostgreSQL test có đúng 1/2 request cùng slot thành công;
  request còn lại 409 và DB không có bản ghi dở dang; `mvn -f backend/pom.xml verify` pass.
- [x] Bổ sung booking history/detail và trạng thái thanh toán cần cho Web App.
  Tác động: repository/service/controller Booking, DTO response.
  AC: lọc theo customer, thứ tự ổn định theo thời gian tạo, ownership qua Auth boundary;
  EXPIRED trả details rỗng và court_fee đã chốt, không dựng lại dữ liệu đã xóa.
  Functional and ownership AC PASS: CurrentCustomerService consumes the owner UserPrincipal.users.id,
  resolves active customers.user_id to customers.id, and constrains CUSTOMER history in the database.
  Foreign customerId/detail returns 403 before details/payments are exposed; EXPIRED data is preserved.
  Registration creates User + CUSTOMER role + Customer atomically; legacy missing profiles return 403.
- [x] Hoàn thiện Swagger/API contract và review toàn module với migration.
  Tác động: annotations controller/DTO và tài liệu contract backend cần thiết.
  AC: request/response/400/404/409, expiry, callback acknowledgment và limitation khớp code;
  không nhân đôi `/api`; build/test pass; không có mapped column/status ngoài schema.

### Phase 6 — Implementation và verification (historical checkpoint)

Scope thực hiện cả 3 tasks trong cùng lượt, không chia phase hoặc mở frontend/Phase 7.
Coverage inventory ở [Booking Engine API contract](docs/booking-engine-api.md): pricing/HALF_UP,
rollback, exact deadline, release/rebook, one 201 + one 409/no orphan, callback verification/replay/races
đã có executable PostgreSQL coverage Phase 1–5C, reuse thay vì duplicate tests.

Added GET /api/bookings với required customerId, page=0, size=20 (1..100), optional status;
SQL projection + DB pagination/customer filter, created_at DESC/id DESC. Unknown customer/page vượt cuối
trả empty page. Added GET /api/bookings/{bookingId}: header + details + payments; không thêm payments GET
vì collection trong detail đủ cho Web. Missing header 404; no payment 200 + payments []. EXPIRED header
vẫn visible, court_fee/payment history preserved, details [] sau expiration; không dựng lại deleted slots.
Payment rows chỉ booking_id target/invoice_id NULL, stable created_at DESC/id DESC và nullable metadata.
Không infer PaymentStatus từ BookingStatus. Display court names/numbers/times dùng current master data.

BookingHistoryService readOnly REPEATABLE_READ đảm bảo consistent snapshot khi expiration commit
giữa header và details query. Không lifecycle lock/mutation/provider call. Page tối đa 2 SELECT,
detail đúng 3 SELECT khi collection tăng; không N+1 hoặc đổi global LAZY mappings.
BookingRepository/BookingDetailRepository/PaymentTransactionRepository chỉ thêm query methods,
backward-compatible; creation/expiration/payment mutation methods giữ nguyên.

Swagger review toàn Booking Engine: availability/create/history/detail/initiation/browser Return/IPNs.
Normal APIs giữ ApiResponse; error schemas mô tả envelope thay vì DTO thành công. VNPay IPN JSON
RspCode/Message, không ApiResponse wrapper.
Browser Return explicit 200/400/503 và no-mutation; public operation security overrides shared placeholder
Bearer declaration, không giả claim ownership.
Review Booking/BookingDetail/PaymentTransaction columns/enums/FKs/audit/nullability/XOR/index với
db/migration.sql không phát hiện mapped field ngoài schema; migration/entities không sửa.

Verification: targeted BookingHistoryPostgresTest 25 PASS; full `mvn -f backend/pom.xml verify`
BUILD SUCCESS: 400 tests, 0 failures, 0 errors, 0 skipped trên PostgreSQL 15.19,
JDK 17.0.20.1/Maven 3.10.0. Gồm nguyên 375 regression tests và 25 Phase 6 cases: customer/status/order/
pagination/normal detail/real expiration/payment history/invoice exclusion/null metadata/GET no mutation/
constant query counts/concurrent read snapshot/OpenAPI/errors. Các lượt targeted ban đầu phát hiện
test coordination gọi abstract repository method và assertion JsonNode enum; đã sửa test mechanism/assertion,
không đổi business semantics để né lỗi. PostgreSQL container test được dừng sau verify.
Không đọc .env, không sandbox network/real credentials; .env vẫn ignored và không staged.
Shared infrastructure application.yml/Security/SwaggerConfig/GlobalExceptionHandler/ApiResponse/BaseEntity/
reference entities/docker-compose/.gitignore không sửa trong Phase 6; giữ pre-existing working-tree changes.

At the initial checkpoint, ownership was blocked. The Auth handoff completion below
resolves this dependency; earlier phase notes are retained as historical verification.

### Phase 6 - Auth handoff completion (historical verification)

Owner JWT/filter/UserPrincipal/User/Role preserved. Added CurrentCustomerService server-side
users.id -> active customers.user_id -> customers.id, no JWT parsing or automatic profile creation
on GET. CUSTOMER role required; ADMIN/STAFF unrestricted Booking policy is not invented.
GET /bookings: customerId optional; absent/matching resolves own data, mismatch 403.
GET detail: own header/details/payments only, foreign 403, missing booking 404.
EXPIRED keeps court_fee/payments and empty deleted details. Reads remain readOnly REPEATABLE_READ;
SQL bounded at 3 queries for page and 4 for detail including identity lookup, no N+1/lifecycle locks.

Existing validated registration fullName/phone/address reused with unchanged request fields.
User + CUSTOMER role association + Customer creation share one transaction; real PostgreSQL
Customer insert rejection proves no orphan User or role association after rollback. Duplicate
email 409, unique customers.user_id preserved. No ADMIN/STAFF registration flow added.
Customer mapping follows current migration (including deleted_at and users.password_hash);
no schema change. Missing/soft-deleted customer profile 403, no repair during GET.

Exact public provider routes: GET /payments/vnpay/ipn,
GET /payments/vnpay/return; cryptographic checks unchanged. Public dev docs now match configured
/api-docs/** and Swagger UI relative to context /api. Booking history/detail remain authenticated;
no /bookings/** permitAll. OpenAPI reflects ownership, 403 and public provider/registration contracts.

Verification: compile PASS; targeted Auth/history/resolver/gateway 98 PASS; Booking/payment/callback
regression 310 PASS. Full `mvn -f backend/pom.xml verify`: BUILD SUCCESS, **423 tests, 0 failures,
0 errors, 0 skipped**, PostgreSQL **15.19**, JDK 17.0.20.1/Maven 3.10.0.
Baseline 402 -> 423: 4 new history ownership/JWT cases + 12 Auth/registration/security PostgreSQL
cases + 5 resolver unit cases. PostgreSQL schema validation/mapping tests executed.
Test DB container stopped after verification; no real provider requests or local .env read.

Phase 6 all three tasks [x]; Phase 7 remains [ ]. Remaining limitations: POST booking/payment
initiation domain-ID ownership outside this task, undefined broader ADMIN/STAFF Booking policy,
access/refresh token-purpose distinction,
late-payment reconciliation/refund and global transaction_id uniqueness remain unchanged.

## Phase 7 — ReactJS Web UI

- [ ] Tích hợp ReactJS Web App sau khi Phase 1–6 đạt acceptance criteria.
  Tác động: frontend hiện chưa có; xác định cấu trúc khi bắt đầu phase này.
  AC: dùng backend contract đã ổn định cho booking/payment/history; không mở scope mobile.
