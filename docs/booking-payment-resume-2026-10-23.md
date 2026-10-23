# Khôi phục BANK_TRANSFER và checkout 10 phút — 2026-10-23

## A. Git

Nhánh trước/sau: `feature/front-end-user`. Không switch/commit/push/pull/merge/reset/restore/clean.
Ban đầu 39 tracked modified và 3 untracked; `git diff --check` không có whitespace error.
Cuối lượt: 40 tracked modified và 5 untracked (45 paths), không stage/commit; diff cũ còn nguyên.
Giữ toàn bộ diff cũ, không dùng thành công ở checkpoint cũ làm bằng chứng cho lượt này.

Các file đã có thay đổi trước khi tiếp tục (đường dẫn Java tương đối với `backend/src/main/java/com/bcm/`):

- `.env.example`, `docker-compose.yml`, `backend/src/main/resources/application.yml`.
- `config/BookingPaymentConfig.java`.
- `controller/AvailabilityController.java`, `BookingController.java`, `PaymentAttemptController.java`, `VnPayReturnController.java`.
- `dto/request/CreatePaymentAttemptRequest.java`, `dto/response/BookingHistoryDetailResponse.java`.
- `service/AvailabilityService.java`, `BookingHistoryService.java`, `BookingService.java`, `CheckoutService.java`,
  `PaymentAttemptService.java`, `PaymentCallbackService.java`, `PaymentCallbackTransactionService.java`,
  `PaymentGatewayPreparation.java`, `PaymentInitiationService.java`, `PaymentNotification.java`, `VnPayIpnVerifier.java`.
- Trong `backend/src/test/java/com/bcm/service/`: `AvailabilityPostgresTest.java`, `BookingConcurrencyPostgresTest.java`,
  `BookingExpirationPostgresTest.java`, `BookingHistoryPostgresTest.java`, `BookingPostgresTest.java`,
  `PaymentAttemptPostgresTest.java`, `PaymentCallbackConcurrencyPostgresTest.java`, `PaymentCallbackPostgresTest.java`,
  `PaymentInitiationPostgresTest.java`, `VnPayIpnVerifierTest.java`.
- `docs/booking-engine-api.md`, `plans.md`.
- `web-app/src/App.jsx`, `CustomerCheckout.jsx`, `CustomerCourts.jsx`, `PaymentResult.jsx`,
  `customer-checkout.css`, `customer-courts.css`.
- Untracked cũ: `backend/src/test/java/com/bcm/service/BookingPaymentLifecyclePostgresTest.java`,
  `docs/booking-payment-audit-2026-10-22.md`, `web-app/booking-payment.e2e.cjs`.

## B. BANK_TRANSFER

Trước: request hợp lệ chỉ tạo payment PENDING và booking PENDING, có thể bị expiration giải phóng.
Sau: authenticated CUSTOMER đúng chủ sở hữu, form hợp lệ, courtFee dương lấy từ DB,
booking còn PENDING với `expiresAt > server now` sau Booking row lock thì atomically chuyển booking PAID.
Payment vẫn PENDING, không có transactionId/transactionDate giả, không lưu account information.
Note: `CUSTOMER_DECLARED; receipt=UNVERIFIED; declaredAt=<server local timestamp>`.
Retry cùng booking trả cùng attempt kể cả sau deadline khi đã xác nhận; không tạo payment thứ hai.

Response thêm bookingStatus/confirmationMethod tương thích trường cũ; history/detail trả confirmationMethod.
UI hiển thị đã xác nhận đặt sân và chuyển khoản tự khai báo, chưa xác minh nhận tiền;
không hiển thị đã thanh toán toàn bộ. Result chỉ GET dữ liệu thật khi refresh.
Booking PAID giữ slot và được bỏ qua bởi expiration. Staff checkout chặn xuất invoice PAID khi
payment CUSTOMER_DECLARED vẫn PENDING, tránh biến xác nhận giữ chỗ thành thu tiền kế toán.
Không có bank reconciliation API trong scope; cần quy trình xác minh thực thu trước khi xuất hóa đơn đã thu tiền.

## C. Expiration và race

Thời gian bắt đầu từ backend create hold; mặc định PT10M, deadline persist trong expires_at.
Owned `/bookings/{bookingId}/checkout-clock` trả deadline/status/serverTime, không gia hạn.
Frontend dùng chênh lệch server timestamps và performance.now, poll 15 giây/on focus;
không phụ thuộc đồng hồ/timezone máy khách. Refresh/tab mới từ URL giữ nguyên booking/deadline.
StrictMode không tạo hold lần hai; nút thanh toán có ref chống double-click và disabled lúc gửi.
Tại zero có 00:00, thông báo hết hạn đầy đủ và link `/`; không tự tạo booking mới.

Payment và expiry dùng cùng Booking pessimistic lock, đọc lại trạng thái/thời gian sau khóa.
Đúng deadline bị từ chối; EXPIRED + DELETE details giữ nguyên transaction atomic đã có.
Payment thắng trước deadline thì PAID không bị expiry xóa details.
Hai payment request đồng thời cùng method trả một attempt; pending khác method bị chặn.
VNPay callback muộn sau declaration chỉ ghi dấu vết cần đối soát cho prior attempt, không settle lần hai.

## D. Kiểm thử thực chạy

Java local ban đầu trỏ Java 26 dù dùng đường dẫn JDK cũ, compile lỗi Lombok TypeTag UNKNOWN.
Đã chuyển sang cách Docker Java 17 có trong tài liệu, không sửa build target/dependency để né lỗi.

```text
docker start bcm-booking-audit-20261022
docker run --rm --network container:bcm-booking-audit-20261022 -v <workspace>:/workspace -v C:\Users\Tea\.m2:/root/.m2 -w /workspace -e BCM_TEST_DB_URL=jdbc:postgresql://localhost:5432/bcm_booking_verify -e BCM_TEST_DB_USERNAME=bcm_test -e BCM_TEST_DB_PASSWORD=<test-only> maven:3.9.9-eclipse-temurin-17 mvn -o -B -f backend/pom.xml verify
cd web-app
npm run build
node web-app/booking-payment.e2e.cjs              # từ repo root, BCM_E2E_DAY_OFFSET=10/20
node web-app/checkout-expiration.e2e.cjs          # từ repo root
git diff --check
```

Full verify PASS: **362 tests, 0 failures/errors/skipped**, Java 17.0.15, PostgreSQL 15.19.
Booking/payment integration/concurrency dùng PostgreSQL với schema gốc; các test module khác
vẫn giữ cấu hình riêng của repo. Không dùng H2 làm bằng chứng locking.
23 lifecycle cases gồm boundary 599/600/601s, form validation, amount tampering, ownership,
retry/concurrent same attempt, payment vs expiration ở cả hai phía deadline, confirmed/expired,
clock no renewal, history marker, late VNPay receipt, CASH rejection và product-only POS CASH.
Regression callback/concurrency/expiration/pricing/authorization/VNPay/IPN đều PASS trong full suite.
Log: `%TEMP%/bcm-bank-resume-verify.log`; XML tại `backend/target/surefire-reports`.

Frontend build PASS. Playwright trên backend riêng cổng 8081 + PostgreSQL `bcm_booking_audit`,
Vite 5173 PASS: multi-slot, form validation, responsive 375/320px, bank redirect/result/refresh,
authoritative amount/status/ownership, no duplicate writes, no uncaught JS errors.
Countdown E2E PASS: refresh, new tab không có draft, method switch, khoảng 10:00/05:30/00:59,
warning, natural tick về 00:00, API quá hạn 409, disabled payment và home route.
Fixture deadline chỉ sửa đúng booking test trên DB audit, không sửa dữ liệu ứng dụng.
Một lượt E2E phát hiện hiển thị 00:00 hai lần; đã bỏ phần lặp và chạy lại PASS.

VNPay Sandbox thật lượt này **BLOCKED**: runtime test không có merchant credentials, initiation 503.
Không dùng callback ký bằng key test hoặc Return làm bằng chứng Sandbox settlement.
Không đọc .env thật để lấy merchant secret; runtime test không triển khai lên ứng dụng chính.

## E. Source thay đổi trong lượt khôi phục

| File | Lý do |
|---|---|
| `backend/src/main/java/com/bcm/dto/response/PaymentAttemptResponse.java` | Thêm bookingStatus/confirmationMethod, giữ constructor cũ |
| `backend/src/main/java/com/bcm/service/PaymentAttemptService.java` | Response rõ declaration; bỏ note tạm bị ghi đè |
| `backend/src/main/java/com/bcm/controller/PaymentAttemptController.java` | Swagger phân biệt VNPay initiation với bank confirmation |
| `backend/src/main/java/com/bcm/service/CheckoutService.java` | Ngăn invoice đã thu tiền từ declaration chưa xác minh |
| `backend/src/test/java/com/bcm/service/BookingPaymentLifecyclePostgresTest.java` | Sửa expectation cũ và bổ sung deadline/race/history/accounting tests |
| `web-app/src/CustomerCheckout.jsx` | Khôi phục hold theo URL/tab mới; bỏ create dự phòng; dùng kết quả backend; countdown/UI expiry |
| `web-app/src/PaymentResult.jsx` | Nhãn tự khai báo chính xác; không nhận FAILED/REFUNDED là thành công; bỏ stale draft attempt khác booking |
| `web-app/src/CustomerCourts.jsx` | Link result từ upcoming booking và hiển thị declaration |
| `web-app/booking-payment.e2e.cjs` | Đồng bộ test policy mới |
| `web-app/checkout-expiration.e2e.cjs` | Thêm E2E countdown/refresh/new tab/expiry trên API và PostgreSQL thật |
| `docs/booking-engine-api.md`, `plans.md`, báo cáo này | Contract và bằng chứng mới, giữ checkpoint lịch sử |

Giữ nguyên trong lượt này: migration/schema/enums/entities/repositories, expiration service/job,
booking service, Auth/JWT, VNPay/IPN implementation, PosService, shared base entity, dependencies,
manifest/lockfile, Docker Compose/env, layout/CSS và các diff trước khác.

## F. Giới hạn

Chưa chạy Sandbox settlement thật do thiếu cấu hình merchant trên runtime test.
Muốn kiểm tra đầu cuối cần runtime có merchant sandbox config và public IPN URL đăng ký đúng.
Bank receipt/reconciliation vẫn chưa tích hợp; declaration không phải receipt, không ghi SUCCESS giả.
Hold có thể đã commit nhưng mất response khi tạo booking: UI không tự gửi create lại;
cần kiểm tra lịch đặt thay vì tạo hold trùng. Không thêm idempotency-key schema ngoài phạm vi.
Không deploy/restart ứng dụng chính cổng 8080/5433; cần rebuild/restart khi đưa source vào sử dụng.
Đã dừng Vite 5173 và hai container thử khởi động trong lượt này (`bcm-bank-resume-http`,
`bcm-booking-audit-20261022`); giữ dữ liệu test và volume, không xóa container.
