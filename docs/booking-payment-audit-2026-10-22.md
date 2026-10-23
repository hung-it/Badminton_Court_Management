# Booking Engine & Payment — kiểm tra ngày 2026-10-22

## Git và tài liệu

- Nhánh đầu/cuối công việc: `feature/front-end-user`. Không checkout/switch/commit/push/pull/merge/reset.
- Diff ban đầu: `web-app/src/CustomerCheckout.jsx`, `web-app/src/customer-checkout.css` từ yêu cầu thêm form trước đó; tiếp tục trên diff đó.
- Tìm bằng `rg --files --hidden -g AGENTS.md -g plans.md -g SKILL.md`, loại node_modules/.git:
  chỉ có `AGENTS.md`, `plans.md`, `SKILL.md` ở gốc; không có AGENTS.md con.
- Đã đọc toàn bộ ba file trên; `db/migration.sql`, `backend/pom.xml`, application.yml,
  application-local.yml/application-h2.yml; tài liệu liên quan `docs/booking-engine-api.md`
  và đối chiếu ranh giới với `docs/payment-invoice-flow.md`.
- Quy tắc branch và giới hạn VNPAY-only trong kế hoạch/skill cũ được thay bằng yêu cầu trực tiếp mới.
- Khác tài liệu: bảng là `payment_transactions`, không phải `payments`; JWT/ownership và React UI
  đã có dù tài liệu cũ ghi chưa có; schema không có booking CANCELLED/CONFIRMED.
- Đã đọc constraint từ DB ứng dụng đang chạy: uq_booking_slot, uq_transaction_id, XOR/FK và trạng thái
  tương ứng migration. Khác biệt: CHECK payment method của DB cũ còn cho MOMO trong khi migration/source
  mới chỉ có CASH/BANK_TRANSFER/VNPAY. Không có payment nào trong DB ứng dụng lúc kiểm tra; không tự sửa CHECK
  hay reset dữ liệu. BANK_TRANSFER đã được CHECK thực tế cho phép, không cần migration cho thay đổi này.

## Ma trận đối chiếu trước khi sửa

Không dùng dấu hoàn thành lịch sử trong plans.md làm bằng chứng kiểm thử của lượt này.

| Nghiệp vụ | Tài liệu/yêu cầu | Code trước sửa | Thiếu/sai | Xử lý | Test thực thi |
|---|---|---|---|---|---|
| Availability | Dữ liệu thật, giờ tương lai | API truy vấn Court/Slot/Detail | Cho giờ quá khứ | Lọc start time giờ Việt Nam | AvailabilityPostgresTest; BookingPaymentLifecyclePostgresTest |
| Booking creation | Auth, atomic | Service transaction, owner resolver | Chưa chặn quá khứ | Kiểm tra sau Court lock | BookingPostgresTest; BookingWriteAuthorizationPostgresTest |
| Booking details | Nhiều slot, không trùng | Backend có list details | UI chỉ một slot | Bổ sung chọn nhiều và checkout list | BookingPostgresTest; browser E2E |
| Price calculation | Master price × multiplier | BigDecimal HALF_UP scale 2 | Không thấy sai công thức | Giữ snapshot, hiển thị tổng server | BookingPostgresTest |
| Slot locking | Hai request: 201/409 | Pessimistic Court + unique index | Cần chạy lại | Giữ nguyên cơ chế | BookingConcurrencyPostgresTest |
| Expiration | EXPIRED + delete atomic | Đã có service/job | Cần chính sách chuyển khoản | Giữ deadline; mặc định PT10M | BookingExpirationPostgresTest; lifecycle |
| Payment creation | Booking target, amount server | VNPAY, lock/reuse | Chưa hỗ trợ bank; thiếu chốt amount dương | Mở rộng tương thích | PaymentAttemptPostgresTest; lifecycle |
| CASH | Cấm booking, giữ POS | Initiation cấm; UI chọn được | Staff checkout còn bypass unpaid | Disable UI, chốt CheckoutService | lifecycle cash/staff/POS PostgreSQL |
| BANK_TRANSFER | Tạo yêu cầu PENDING | Form local chưa gửi | Chưa có DTO/service flow | DTO, validation, PENDING, redirect sau 201 | 15 lifecycle cases và browser E2E |
| VNPAY | Signed checkout | Có gateway/signer | Compose chưa truyền env | Truyền config, không thay credentials | VnPayGatewayTest; live URL attempt |
| Return | Verify, về /payment-result | JSON display-only | Chưa có redirect tùy cấu hình | 303 cấu hình; displayOnly giữ JSON | lifecycle verifiedReturn; controller tests |
| IPN | Verify/idempotent/transaction | Có HMAC, lock, atomic success | Failure bị bỏ qua; late chỉ log | FAILED terminal; note đối soát | callback suites; lifecycle retry |
| Payment result | Hai phương thức, refresh | UI VNPAY thành công là chính | Thiếu bank pending và resume từ URL | Đọc owned detail, polling có hạn, retry | browser E2E; history/backend suites |
| History | Danh sách, detail, ownership | API có; UI lịch gần nhất | Chưa có danh sách/chi tiết đầy đủ | Tái dùng trang courts và API | BookingHistoryPostgresTest; browser E2E |
| Cancellation/retry | Không sinh booking vô hạn | Reuse pending VNPAY | Terminal failure chưa mở retry | FAILED cho phép attempt mới; cùng booking | PaymentInitiationPostgresTest; lifecycle |
| Authorization | Server identity/ownership | JWT + CurrentCustomerService | Chưa đủ bằng chứng lượt này | Giữ Auth, chạy test sửa ID/JWT | CustomerAuthPostgresTest; BookingWriteAuthorizationPostgresTest |
| DB consistency | FK/XOR/unique/rollback | Schema/mapping phù hợp | Cần chạy DB thật | Không đổi migration/entity | BookingMappingPostgresTest; callback/locking suites |

## Chính sách đã triển khai

- Slot đang có detail vẫn bị giữ; chỉ expiration xóa detail trong transaction mới giải phóng.
- Booking PENDING phải có deadline; mặc định 10 phút, override bằng BOOKING_HOLD_DURATION.
- BANK_TRANSFER được ghi nhận PENDING, không có chứng cứ đã nhận tiền. Dùng deadline của booking,
  hết hạn giữ payment history để hỗ trợ đối soát. Không có bank API hoặc chức năng nhân viên xác nhận
  trong source hiện tại; không tự thêm endpoint xác nhận tiền.
- Validate bankName/accountHolder/accountNumber/transferContent; không giữ dữ liệu tài khoản cá nhân
  trong DB/log/response. Form hiện tại là thông tin người chuyển, không phải tài khoản nhận được xác minh.
- Một pending attempt cho mỗi booking được tuần tự hóa bằng khóa Booking: cùng method reuse,
  method khác 409. CASH 400. Giá, status, target từ client không điều khiển dữ liệu.
- Payment SUCCESS đã tồn tại chặn tạo/settle khoản thứ hai ngay cả khi header bị giữ sai ở PENDING.
  Hai test dữ liệu legacy không nhất quán xác minh không có SUCCESS thứ hai và không sửa history cũ.
- Chỉ IPN hợp lệ được chuyển Payment SUCCESS + Booking PAID. Return không settle.
- Terminal VNPay status 02 chuyển PENDING thành FAILED; intermediate 01 không xác nhận thất bại.
  Status 09 là hoàn trả bị từ chối theo tài liệu VNPay, không dùng để mở retry thanh toán.
  Success đã xác minh không bị callback failure downgrade. Retry tạo attempt mới trên cùng booking còn hạn.
- Late receipt/non-payable receipt lưu note `RECONCILIATION_REQUIRED` với reference/amount/date đã verify;
  không hồi sinh EXPIRED, không thêm enum hay tự hoàn tiền. Xử lý tiền muộn vẫn cần nhân sự đối soát.
- Staff checkout có khóa Booking, yêu cầu PAID/CHECKED_IN và từ chối CASH;
  COMPLETED không được checkout lặp. PosService bán sản phẩm CASH không đổi.

## Kiểm thử backend đã chạy

Java 17.0.15 / Maven 3.9.9 trong image `maven:3.9.9-eclipse-temurin-17`;
PostgreSQL 15 trong container riêng `bcm-booking-audit-20261022`, cổng loopback 55439.
Nạp nguyên `db/migration.sql`; bỏ 8 hàng sân seed chỉ trong DB test để test cũ tự quản lý fixture.
Không chạm dữ liệu ứng dụng ở cổng 5433. Không dùng H2 để chứng minh lock/constraint.

Lệnh thực thi:

```text
docker exec bcm-booking-audit-20261022 psql -U bcm_test -d bcm_booking_audit -v ON_ERROR_STOP=1 -f /tmp/booking-schema.sql
docker run --rm --network container:bcm-booking-audit-20261022 [test environment + workspace/cache mounts] maven:3.9.9-eclipse-temurin-17 mvn -o -B -f backend/pom.xml verify
```

Kết quả cuối: **BUILD SUCCESS — 354 tests, 0 failures, 0 errors, 0 skipped**.
Lượt cuối dùng database `bcm_booking_verify` riêng, tách khỏi dữ liệu E2E ở `bcm_booking_audit`.
Log tại `%TEMP%/bcm-booking-audit-final-verify.log`, XML/txt tại backend/target/surefire-reports (ignored).
Các lượt trước có lỗi fixture/assertion và đã được xử lý; không dùng kết quả lỗi làm PASS.
Một lượt test đã PASS nhưng repackage lỗi do artifact đang được backend E2E mở; đã dừng runtime và chạy lại
verify thành công. Runtime thử sau đó chạy bản sao JAR trong container, không giữ artifact workspace.

| Nhóm | Bằng chứng |
|---|---|
| PostgreSQL locking | BookingConcurrencyPostgresTest: 11 PASS; hai HTTP request/transaction độc lập, một 201/một 409, không orphan |
| Expiration | BookingExpirationPostgresTest: 16 PASS; deadline, PAID giữ slot, delete/status rollback, release/rebook |
| Create/pricing | BookingPostgresTest: 28 PASS; nhiều detail, multiplier/HALF_UP, snapshot, rollback |
| History | BookingHistoryPostgresTest: 32 PASS; ownership, pagination, snapshot, expired details, payment target |
| Bank/new policy | BookingPaymentLifecyclePostgresTest: 15 PASS; JWT thật, validation, PENDING, concurrent reuse, CASH, POS CASH commit/stock, staff guard, retry, Return redirect, past time, inconsistent header/SUCCESS guard |
| Payment/callback/security | PaymentAttemptPostgresTest, PaymentInitiationPostgresTest, PaymentCallbackPostgresTest, PaymentCallbackConcurrencyPostgresTest, VnPayGatewayTest, VnPayIpnVerifierTest, BookingWriteAuthorizationPostgresTest, CustomerAuthPostgresTest: PASS |
| Existing module regression | AuthServiceTest, JwtAuthenticationFilterTest, JwtUtilTest, CurrentCustomerServiceTest, UserControllerTest, CorsConfigTest, MasterDataIntegrationTest, AnalyticsIntegrationTest, PosServiceTest: PASS |

Phân loại nghiệm thu theo phạm vi yêu cầu:

| Nghiệp vụ | Phân loại sau sửa | Bằng chứng/giới hạn |
|---|---|---|
| Availability | Đã hoạt động và có test chứng minh | 25 PostgreSQL cases + browser API thật; past start time bị chặn |
| Booking creation | Đã hoạt động và có test chứng minh | Transaction, JWT/customerId và real HTTP 201 |
| Booking details | Đã hoạt động và có test chứng minh | Multi-detail atomic; browser đặt hai slot |
| Price calculation | Đã hoạt động và có test chứng minh | HALF_UP snapshots; tổng DB = tổng details |
| Slot locking | Đã hoạt động và có test chứng minh | 11 concurrency cases; một 201/một 409 |
| Booking expiration | Đã hoạt động và có test chứng minh | 16 cases + bank expiration policy; slot tái sử dụng |
| Payment creation | Đã hoạt động và có test chứng minh | 29 payment-attempt cases; 15 lifecycle cases |
| CASH restriction | Đã hoạt động và có test chứng minh | UI disabled, API 400, staff bypass chặn; sản phẩm CASH vẫn commit |
| BANK_TRANSFER | Đã hoạt động và có test chứng minh cho tạo yêu cầu | API/DB PENDING, concurrent reuse, refresh; không có đối soát tiền tự động |
| VNPAY | Đã hoạt động cho checkout và giao dịch Sandbox/Return | Thẻ/OTP thật trên Sandbox; chưa có IPN public tới backend test |
| VNPay Return | Đã hoạt động và có test chứng minh | Return thật, chữ ký đúng, 303 và API displayOnly 200 |
| VNPay IPN | Chưa đủ điều kiện xác minh đầu cuối | Local unsigned GET trả 97; deterministic PostgreSQL callbacks/races PASS; live IPN BLOCKED |
| Payment result | Đã hoạt động cho bank/pending/refresh được E2E | SUCCESS/FAILED/EXPIRED có logic dựa DB; chưa chứng minh mọi trạng thái bằng browser/IPN thật |
| Booking history | Đã hoạt động và có test chứng minh | 32 cases; browser history/detail/resume dữ liệu thật |
| Cancellation / retry | Retry đã hoạt động; hủy chưa triển khai | Backend retry tested; không tự thêm CANCELLED ngoài schema |
| Authorization | Đã hoạt động và có test chứng minh | Auth/JWT suites + HTTP 401/403 thật; tampered customerId bị chặn |
| Database consistency | Đã hoạt động và có test chứng minh | FK/XOR/unique/rollback; không orphan; thêm SUCCESS guard cho header lỗi |

Existing unit tests may mock dependencies. Lifecycle/concurrency suites use real PostgreSQL;
signed callback fixtures are deterministic tests, **not proof of live VNPay receipt**.
Existing master-data/analytics suites use their H2 profile; the new product-only CASH commit/stock test uses PostgreSQL.

## VNPay Sandbox — kết nối và giới hạn

Chạy từ container backend hiện có:

```text
docker exec bcm-backend curl -v --connect-timeout 10 --max-time 25 -o /dev/null https://sandbox.vnpayment.vn/paymentv2/vpcpay.html
```

- DNS: 103.220.84.173. TCP 443 kết nối được.
- TLSv1.3 / TLS_AES_256_GCM_SHA384, chứng thư CN sandbox.vnpayment.vn được xác minh hợp lệ.
- HTTP 302, Location `/paymentv2/Payment/Error.html?code=03`: request chưa có thông số giao dịch.
  Không phải bằng chứng thanh toán thành công.
- Container ứng dụng ban đầu không có BOOKING_VNPAY_TMN_CODE/HASH_SECRET/RETURN_URL.
- File .env (ignored) có TMN Code đúng định dạng và Hash Secret, thiếu RETURN_URL;
  BOOKING_PAYMENT_ENABLED_METHODS hiện chỉ VNPAY. Không in hoặc thay credentials.
- Backend E2E riêng dùng credentials có sẵn cùng Return URL loopback đã khai báo cho phiên thử;
  không thay .env hay backend ứng dụng. URL loopback không phục vụ IPN từ Internet.
- Cần URL HTTPS public và đăng ký merchant IPN để chứng minh giao dịch/Return/IPN đầu cuối.
- Tài liệu chính thức tham chiếu: https://sandbox.vnpayment.vn/apis/ .

## Browser E2E và giao dịch Sandbox thật

Frontend Vite cổng 5173 proxy tới backend E2E 8081; đăng ký customer qua API thật, JWT thật,
PostgreSQL thật. Không route interception/mock API hoặc tự gọi IPN với payload giả để chứng minh tích hợp.
Cổng 5184 thử ban đầu bị CORS hiện có từ chối; đã chuyển phiên thử sang 5173, không mở rộng CORS
hoặc dừng frontend của người dùng tại 5174.

```powershell
$env:BCM_API_PROXY_TARGET='http://127.0.0.1:8081'
# Chạy trong web-app:
npm run dev -- --port 5173
# Chạy từ repository root, chỉ dùng DB test riêng:
$env:BCM_E2E_COMPLETE_SANDBOX='1'
node web-app/booking-payment.e2e.cjs
```

`BCM_E2E_WEB_URL` cho phép chỉ định frontend test; `BCM_E2E_DAY_OFFSET` giúp chọn ngày khác khi chạy lại
mà không đụng booking test đang giữ slot. Script để dữ liệu test ở DB riêng phục vụ đối chiếu; không dùng trên DB ứng dụng.
Report không chứa key, JWT, số thẻ hoặc OTP: `%TEMP%/bcm-booking-e2e-report.json`.

| Bước thực tế | Kết quả | Bằng chứng |
|---|---|---|
| Checkout responsive | PASS | Hai slot; CASH disabled; validation; chỉ chữ số; retain form; gap 14px; hai cột desktop/một cột 375px |
| Chuyển khoản | PASS | POST booking/payment 201; hai details; tổng snapshot đúng; booking/payment PENDING |
| Refresh result | PASS | Đọc lại owned detail; không thêm POST/payment |
| HTTP policy/ownership | PASS | CASH 400, same-slot 409, foreign booking 403, anonymous booking 401 |
| History/detail | PASS | Hiển thị booking thật; checkout/history không tràn ngang 320px sau CSS wrap header scoped cho các trang Booking |
| Checkout URL | PASS | HTTP 201, URL domain Sandbox, amount từ DB; HMAC kiểm tra độc lập bằng merchant key đang cấu hình |
| Redirect/payment page | PASS | Trình duyệt mở "Chọn phương thức thanh toán (Test)", tiếp tục ngân hàng NCB |
| Thẻ test/OTP | PASS | Dùng thông tin test công bố trong tài liệu chính thức, không hardcode giao dịch thành công |
| Return thật | PASS | Provider trả thành công; backend verify checksum, redirect /payment-result; API displayOnly verify 200 |
| IPN thật tới backend test | BLOCKED | Chưa có public HTTPS merchant IPN URL tới backend 8081 |
| DB sau Return thiếu IPN | PASS | Vẫn PENDING; UI "Đang xác minh kết quả thanh toán", không suy Return thành PAID |
| DB SUCCESS/PAID từ IPN thật | BLOCKED | Chưa nhận IPN thật; chỉ chứng minh bằng suite PostgreSQL deterministic, không gọi đó là Sandbox settlement |

Nguồn thông tin thẻ test: [VNPay — Giới thiệu](https://sandbox.vnpayment.vn/apis/docs/gioi-thieu/).
Bảng status: [VNPay — Thanh toán PAY](https://sandbox.vnpayment.vn/apis/docs/thanh-toan-pay/pay.html).
Không ghi giá trị thẻ hoặc OTP vào source báo cáo/log. Không có kết luận "VNPay đầu cuối hoàn tất" khi IPN vẫn bị chặn.

Frontend build: `npm run build` trong web-app **PASS** (1660 modules).
`node --check web-app/booking-payment.e2e.cjs` và `git diff --check`: **PASS**.
Hash Secret trong .env được so với diff chỉ bằng kiểm tra boolean: **không xuất hiện trong diff**.

## Phạm vi file và giới hạn còn lại

Các đường dẫn Java trong bảng sau có tiền tố `backend/src/main/java/com/bcm/`.

| File đã đọc/sửa | Trạng thái | Lý do và ảnh hưởng |
|---|---|---|
| config/BookingPaymentConfig.java | Sửa | Mặc định cho hai method; thêm cấu hình trang kết quả |
| controller/AvailabilityController.java, controller/BookingController.java | Sửa | Swagger mô tả chặn giờ quá khứ |
| controller/PaymentAttemptController.java | Sửa | Contract bank form, PENDING/reuse, CASH 400 |
| controller/VnPayReturnController.java | Sửa | Verify rồi 303 có cấu hình; giữ JSON displayOnly và schema response |
| dto/request/CreatePaymentAttemptRequest.java | Sửa | Nested validated payer form, giới hạn độ dài, che toString |
| service/AvailabilityService.java, service/BookingService.java | Sửa | Clock, giờ Việt Nam, kiểm tra sau lock; giữ pricing/unique/transaction |
| service/PaymentAttemptService.java | Sửa | Form validation, amount dương, reuse đúng method, note không chứa tài khoản |
| service/PaymentGatewayPreparation.java, service/PaymentInitiationService.java | Sửa | BANK_TRANSFER không gọi VNPay; CASH luôn bị chặn |
| service/PaymentNotification.java, service/VnPayIpnVerifier.java | Sửa | Phân biệt terminal payment error 02 với pending/refund states |
| service/PaymentCallbackService.java, service/PaymentCallbackTransactionService.java | Sửa | FAILED, không downgrade SUCCESS; ghi bằng chứng cần đối soát |
| service/CheckoutService.java | Sửa tích hợp tối thiểu | Khóa booking, chặn CASH và unpaid/expired/completed bypass; không sửa bán sản phẩm |
| backend/src/main/resources/application.yml | Sửa | Allowlist hai method, default hold 10 phút, frontend result URL tùy cấu hình |
| docker-compose.yml, .env.example | Sửa | Truyền cấu hình môi trường; ví dụ không có credential |
| backend/src/test/java/com/bcm/service/BookingPaymentLifecyclePostgresTest.java | Thêm | 15 test JWT/PostgreSQL thật, gồm bank concurrent, POS CASH và hai case dữ liệu không nhất quán |
| backend/src/test/java/com/bcm/service/VnPayIpnVerifierTest.java | Sửa | 7 test status 01/02/04/05/06/07/09 theo bảng mã chính thức |
| backend/src/test/java/com/bcm/service/BookingPostgresTest.java, BookingConcurrencyPostgresTest.java, BookingExpirationPostgresTest.java, AvailabilityPostgresTest.java | Sửa fixture | Booking hợp lệ dùng ngày tương lai thay vì ngày quá khứ cố định |
| backend/src/test/java/com/bcm/service/BookingHistoryPostgresTest.java | Sửa | Fixture tương lai và assertion contract hai method |
| backend/src/test/java/com/bcm/service/PaymentCallbackPostgresTest.java, PaymentCallbackConcurrencyPostgresTest.java | Sửa | Assert chỉ note đối soát đổi khi nhận late receipt; status/amount/transaction/history vẫn được bảo vệ |
| backend/src/test/java/com/bcm/service/PaymentAttemptPostgresTest.java, PaymentInitiationPostgresTest.java | Sửa | Chính sách mới: SUCCESS history + PENDING header phải chặn khoản thanh toán thứ hai, không tiếp tục kỳ vọng 201 |
| web-app/src/CustomerCheckout.jsx | Sửa trên diff ban đầu | Multi-slot quote/snapshot, gửi bank form, redirect sau 201, CASH disabled |
| web-app/src/CustomerCourts.jsx | Sửa | Chọn thêm slot, history/detail/pagination/resume, date min |
| web-app/src/PaymentResult.jsx | Sửa | Bank pending, URL resume, bounded polling, failed retry, actual refunded display |
| web-app/src/App.jsx | Sửa | Giữ alias /payment/result, đưa /my-bookings tới lịch sử có dữ liệu thật |
| web-app/src/customer-checkout.css | Giữ diff ban đầu | Card mới, focus và responsive; không viết lại layout |
| web-app/src/customer-courts.css | Sửa một rule scoped | Cho utility header của các trang Booking xuống dòng dưới 390px; checkout/history đã test không tràn ở 320px |
| web-app/booking-payment.e2e.cjs | Thêm | Playwright gọi API thật; Sandbox test tùy chọn, không mock receipt/IPN |
| plans.md, docs/booking-engine-api.md, docs/booking-payment-audit-2026-10-22.md | Cập nhật | Kế hoạch, contract, ma trận và bằng chứng/giới hạn |

Đã đọc nhưng không sửa: Booking/BookingDetail/PaymentTransaction và enum; Court/TimeSlot/Customer;
BookingRepository/BookingDetailRepository/CourtRepository/TimeSlotRepository/PaymentTransactionRepository;
BookingHistoryService/BookingExpirationService/Job; VnPayGateway/Signer/PaymentSignature;
SecurityConfig/JwtAuthenticationFilter/CurrentCustomerService/JwtUtil/AuthService;
GlobalExceptionHandler/ApiResponse; CheckoutController/PosService/PosSaleRequest;
web-app/src/lib/apiClient.js, lib/authContext.jsx, package.json/Vite config và các suite regression đang có.

- Backend thay service/DTO/controller booking-payment, cấu hình hiện có, test tương ứng.
- Tích hợp dùng chung tối thiểu: docker-compose.yml truyền env; CheckoutService chặn bypass booking.
  Không thay Auth, master CRUD, product/inventory, invoice schema, PosService hoặc reporting.
- Frontend tái dùng CustomerCourts, CustomerCheckout, PaymentResult, App và stylesheet checkout sẵn có.
  Thêm `web-app/booking-payment.e2e.cjs` gọi API thật; không thêm dependency mới vào manifest.
- Không thêm schema/status/table/endpoint trùng. /payment/result là alias frontend tương thích.
- BANK_TRANSFER receipt/reconciliation không tự động: không có bank callback hoặc màn hình nhân viên mới.
- Cancellation/reschedule/refund không triển khai vì schema/workflow chưa có trong scope.
- Court names/slot times trong lịch sử vẫn đọc master hiện tại; giá là snapshot theo thiết kế hiện có.
- Git status cuối: 37 file tracked thay đổi và 3 file mới; không commit/stage/push. Hai file checkout
  có sẵn từ đầu được giữ và mở rộng. Manifest/lockfile, .env, migration và cấu hình Auth không đổi.
- Kết thúc kiểm thử đã dừng Vite 5173 và hai container thử `bcm-booking-audit-http`,
  `bcm-booking-audit-20261022`; dữ liệu test/report được giữ để đối chiếu. Instance ứng dụng 8080/5433
  không bị thay bằng runtime thử. Cần rebuild/restart backend chính sau khi cấu hình môi trường phù hợp.
