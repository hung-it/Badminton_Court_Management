# Kế hoạch Booking Engine — Thành viên 3

Branch: `feature/booking-engine`. Mỗi phase phụ thuộc phase trước; chỉ tạo source khi thực hiện task.
Đã có foundation Phase 1, Availability API Phase 2 và create booking/concurrency Phase 3, kèm test PostgreSQL.
Đã có expiration/release slot tự động Phase 4 và payment attempt foundation Phase 5A.
Đã có VNPay sandbox checkout và MoMo request/signing/HTTP foundation Phase 5A.5.
Đã có callback/IPN verification và SUCCESS + PAID atomic Phase 5B.
Đã xác minh idempotency/concurrent callback/expiration races Phase 5C trên PostgreSQL.
MoMo checkout còn blocked do response signature contract; chưa có frontend.
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

## Phase 5 — Payment và callback

- [x] Tạo payment attempt PENDING và abstraction VNPay/MoMo phù hợp layer hiện tại.
  Tác động: `service/`, `repository/`, DTO/controller payment, config/environment.
  AC: amount từ server, booking còn hạn, target XOR đúng; reference attempt có thể derive
  từ ID hiện có; dữ liệu gateway không có column chỉ runtime; không giữ DB lock khi gọi gateway.
- [x] Implement callback/IPN/webhook và verification theo contract chính thức của provider.
  Tác động: controller/service tích hợp gateway, config và DTO liên quan.
  AC: signature/checksum, target/method/amount được kiểm tra; callback hợp lệ cập nhật
  SUCCESS + PAID atomic; failure không chuyển PAID; acknowledgment đúng provider.
- [x] Bảo vệ idempotency và race callback với expiration.
  Tác động: `service/`, query lock/lookup trong `repository/`, xử lý unique conflict.
  AC: dùng `transaction_id` + `uq_transaction_id` và trạng thái đã xử lý;
  callback lặp/đồng thời chỉ tác động một lần; không nhầm payment PENDING là đã hoàn tất;
  không hồi sinh EXPIRED. Ghi rõ policy thanh toán muộn và giữ lịch sử trước khi implement,
  không tự thêm refund feature/schema.

Phase 5A foundation trước khi tích hợp provider (contract hiện tại xem Phase 5A.5 bên dưới):
`POST /api/bookings/{bookingId}/payments`, body `{paymentMethod: "VNPAY" | "MOMO"}`.
HTTP 201 + `ApiResponse`: paymentAttemptId, bookingId, paymentMethod, PENDING, amount,
bookingExpiresAt và gatewayPreparation (provider, merchantReference, amount, checkoutReady=false).
400: input/method không hợp lệ hoặc bị disable, booking không PENDING hoặc expires_at <= now;
404: booking không tồn tại; 409: đã có bất kỳ payment PENDING cho booking (kể cả khác method).
Field JSON ngoài DTO bị bỏ qua; client không điều khiển amount/status/target/transaction ID.
Auth vẫn permitAll, chưa kiểm tra ownership: bookingId không phải bằng chứng danh tính.

PaymentAttemptService reuse Booking PESSIMISTIC_WRITE query của expiration, re-check status/deadline
theo cùng Clock sau lock. Không lock Court, không sửa booking hoặc DELETE details; Phase 4 tự expire.
Trong service transaction, amount snapshot từ court_fee, booking_id đúng target, invoice_id NULL;
status PENDING; transaction_id/transaction_date NULL. Không suy ra SUCCESS từ row hoặc transaction_id.
Khóa Booking serialize create attempts trước khi kiểm tra payment PENDING; không thêm unique index.
Policy retry: reject khi còn PENDING, không switch provider/tạo thêm attempt; history non-PENDING
được giữ nguyên, không tự quyết định booking đã PAID. Chỉ booking còn PENDING/còn hạn mới tạo attempt.

PaymentGatewayPreparation là boundary chuẩn bị dữ liệu nội bộ cho VNPAY/MOMO, không phải wire protocol
chung và không tạo signed URL/QR, không gửi request sandbox/production. Merchant reference derive từ
payment UUID, không persist vào transaction_id. `checkoutReady=false` luôn rõ trong response/Swagger.
`booking.payment.enabled-methods` lấy từ `BOOKING_PAYMENT_ENABLED_METHODS`, mặc định VNPAY,MOMO;
chỉ bật tạo attempt, không bật checkout. Chưa thêm credentials/endpoint vì chưa có adapter dùng chúng.
Provider adapters sau này cần contract chính thức và config environment; mọi HTTP phải chạy sau commit,
ngoài Booking lock. Phase 5A không có external call, callback, SUCCESS/PAID mutation hoặc refund.

Verification Phase 5A: PaymentAttemptPostgresTest 32 PASS + PaymentGatewayPreparationTest 6 PASS;
regression Booking 27, concurrency 11, expiration 16, Availability 25 PASS (79 tests).
`mvn -f backend/pom.xml verify` BUILD SUCCESS: 132 tests, 0 failures/errors/skipped,
JDK 17/Maven 3.9.9 portable, PostgreSQL 15.10, reuse DB test riêng với schema gốc `db/migration.sql`.
Tests chứng minh amount/target/status phía server, exact deadline, history/PENDING policy, hai request
độc lập có đúng 201/409 (cùng hoặc khác provider), re-check state/deadline/Clock sau Booking lock,
expiration giữ payment history, provider selection/config binding và contract Swagger.
Shared diff Phase 5A chỉ thêm booking.payment.enabled-methods vào application.yml; backward-compatible.
Không sửa Invoice/reference, GlobalExceptionHandler, Security, common config hoặc BookingRepository.

### Phase 5A.5 — Sandbox initiation

- [x] VNPay Sandbox initiation: signed checkout URL và deterministic tests.
- [x] MoMo One-Time Wallet request/initiation foundation: captureWallet signing, HTTP client và tests.
- MoMo real checkout: **BLOCKED** pending authoritative Create Payment response signature clarification.
  MoMo Create Payment response signature contract is ambiguous in the official EN/VI documentation.
  Không đoán công thức hoặc implement response verifier; IPN riêng hoàn thành ở Phase 5B, idempotency/race đã xác minh ở Phase 5C.

Contract hiện tại giữ `POST /api/bookings/{bookingId}/payments` và request chỉ có paymentMethod.
HTTP 201 khi tạo/reuse attempt cùng method; khác method trên attempt PENDING trả 409.
Response giữ các field Phase 5A và thêm gatewayPreparation.checkoutUrl/qrCodeData/deeplink/checkoutBlocker.
VNPay checkoutReady=true với URL signed; MoMo checkoutReady=false, artifacts NULL và blocker rõ ràng,
kể cả khi HTTP Create Payment trả resultCode=0 và được correlate đúng. Đó không phải payment success.
Thiếu config provider trả 503 trước khi tạo attempt; HTTP timeout/non-2xx/malformed/result failure trả 502.
Booking không PENDING hoặc expires_at <= now trả 400; không tự expire hay DELETE details.

PaymentInitiationService dùng Propagation.NEVER; create/reuse và re-check đi qua transactional bean riêng,
khóa Booking rồi kiểm tra lại booking/payment trước provider work, commit và nhả lock trước HTTP.
Re-check sau provider work ngăn trả checkout khi booking đổi trạng thái/deadline trong lúc chờ.
Payment vẫn PENDING, booking vẫn PENDING; transaction_id/transaction_date NULL và schema không đổi.
Provider failure sau commit giữ attempt PENDING; explicit retry cùng method dùng cùng UUID, không tạo row mới.
Không retry HTTP tự động hoặc giữ transaction/Booking lock khi gọi MoMo; không lock Court.

VNPay theo [official Payment Gateway v2.1.0](https://sandbox.vnpayment.vn/apis/docs/thanh-toan-pay/pay.html):
amount nhân 100 exact bằng BigDecimal, fields sort/URL-encode rồi HMAC-SHA512.
vnp_TxnRef là attempt UUID bỏ dấu gạch (32 hex), recover UUID bằng cách chèn lại dấu tại 8/12/16/20.
Create/expire time explicit GMT+7; booking TIMESTAMP được diễn giải theo Clock zone hiện tại;
vnp_ExpireDate làm tròn xuống giây và không vượt booking expiry.
IP lấy request remote address; không tin forwarded headers khi chưa có trusted proxy integration.

MoMo theo [official Wallet EN](https://developers.momo.vn/v3/docs/payment/api/wallet/onetime/)
và [Wallet VI](https://developers.momo.vn/v3/vi/docs/payment/api/wallet/onetime/):
requestType=captureWallet, amount whole VND 1000..50000000 exact, HMAC-SHA256 với request field order:
accessKey, amount, extraData, ipnUrl, orderId, orderInfo, partnerCode, redirectUrl, requestId, requestType.
orderId=requestId=attempt UUID (36 ký tự), deterministic payload cho retry theo
[official requestId idempotency](https://developers.momo.vn/v3/vi/docs/payment/api/result-handling/idempotency/).
Không dùng UUID làm gateway transaction_id; response partnerCode/orderId/requestId/amount được correlate.
JDK HTTP client JSON, connect timeout 5s/request timeout 30s, không follow redirects và không log payload/secret.
qrCodeUrl là QR data từ provider, chỉ parse nội bộ; mọi checkout artifacts bị giữ lại tới khi xác minh contract.
Không gửi expiry field tự chế cho MoMo; booking deadline vẫn được kiểm tra ở service, payment success chỉ thuộc Phase 5B.

Config: giữ BOOKING_PAYMENT_ENABLED_METHODS; điền BOOKING_VNPAY_TMN_CODE/HASH_SECRET/RETURN_URL
và BOOKING_MOMO_PARTNER_CODE/ACCESS_KEY/SECRET_KEY/REDIRECT_URL/IPN_URL bằng environment.
BOOKING_VNPAY_PAYMENT_URL và BOOKING_MOMO_CREATE_PAYMENT_URL mặc định official sandbox và chỉ chấp nhận sandbox;
BOOKING_MOMO_CONNECT_TIMEOUT/REQUEST_TIMEOUT là ISO-8601. Không commit credentials hoặc persist artifacts.
Public redirect/IPN URLs cần được cấu hình khi smoke test; Phase này không tạo IPN handler.
Shared diff chỉ application.yml thêm booking payment provider config; additive/backward-compatible config,
endpoint nâng cấp retry từ reject sang reuse cùng method. Không sửa module owner/reference/common infrastructure.

Verification Phase 5A.5: targeted VnPayGatewayTest 14 + MoMoGatewayTest 36 + PaymentInitiationPostgresTest 34
PASS (84 tests, 0 failures/errors/skipped). Regression PaymentAttempt/GatewayPreparation/Booking/
Concurrency/Expiration/Availability PASS 117 tests. `mvn -f backend/pom.xml verify` BUILD SUCCESS:
216 tests, 0 failures/errors/skipped; JDK 17, Maven 3.9.9, PostgreSQL 15.10, schema gốc không đổi.
Tests chứng minh Booking lock được nhả trước HTTP bằng transaction độc lập `FOR UPDATE NOWAIT`,
same-method concurrent retry chỉ có một payment row, failure không làm SUCCESS/PAID,
và re-check deadline/status ngăn expose checkout sau thay đổi state. Test server đã stop sau verify.
Smoke test VNPay và MoMo: NOT RUN — merchant sandbox credentials not provided yet.

### Phase 5B — IPN verification và atomic settlement

VNPay: `GET /api/payments/vnpay/ipn` theo
[official PAY v2.1.0 IPN contract](https://sandbox.vnpayment.vn/apis/docs/thanh-toan-pay/pay.html).
Chỉ ký các query fields `vnp_*`; reuse VnPaySigner sort/URL-encode, bỏ SecureHash/SecureHashType,
HMAC-SHA512 và so sánh decoded signature bằng MessageDigest.isEqual. Reject duplicate query fields.
Kiểm tra TmnCode; recover exact attempt UUID từ 32 lowercase hex TxnRef; method VNPAY,
domain amount bằng vnp_Amount / 100 exact. Chỉ cả ResponseCode và TransactionStatus = 00 mới settle.
transaction_id là vnp_TransactionNo thật (numeric 1..15 ký tự, positive khi success).
PayDate nếu có được parse strict yyyyMMddHHmmss ở GMT+7 rồi chuyển sang application Clock zone
để lưu TIMESTAMP như convention hiện tại; optional thiếu thì NULL, invalid thì reject, không lấy giờ server.
IPN trả HTTP 200 với JSON riêng RspCode/Message: 00 handled, 01 unknown reference/provider/target,
02 same confirmed sequential callback, 04 amount mismatch, 97 invalid checksum, 99 rejected/system error.
Không wrap ApiResponse; exception tại flush hoặc transactional proxy commit không thể trả success.
IPN merchant URL cần public HTTPS và đăng ký với VNPay; localhost chỉ phục vụ test nội bộ.

VNPay browser Return: `GET /api/payments/vnpay/return`, xác minh cùng wire contract và trả
ApiResponse chứa thông tin provider normalized, không đọc hoặc ghi DB. providerSuccess chỉ là kết quả
provider, không phải trạng thái đã settle. Local BOOKING_VNPAY_RETURN_URL:
`http://localhost:8080/api/payments/vnpay/return`. Chỉ IPN có authority SUCCESS/PAID.

MoMo: `POST /api/payments/momo/ipn`, JSON theo
[official captureWallet payment-result parameters](https://developers.momo.vn/v3/docs/payment/api/wallet/onetime/)
và [official notification acknowledgment](https://developers.momo.vn/v3/docs/payment/api/result-handling/notification/).
IPN raw HMAC-SHA256 order đã đối chiếu EN/VI: accessKey, amount, extraData, message, orderId,
orderInfo, orderType, partnerCode, payType, requestId, responseTime, resultCode, transId.
Không URL-encode raw values; dùng accessKey từ config; verify signature trước DB lookup.
Require JSON signed fields đúng type, không coercion string/fraction sang Long; validate partnerCode,
orderType=momo_wallet và orderId=requestId=exact canonical attempt UUID. Match method MOMO/amount.
Chỉ resultCode=0 là payment success; transaction_id là Long.toString(transId), không truncate/scientific notation.
responseTime được docs mô tả là thời gian trả kết quả, không xác nhận payment timestamp;
transaction_date giữ NULL, không invent timestamp/unit cho nghiệp vụ thanh toán.
Checkpoint Phase 5B: handled success/duplicate/non-success trả HTTP 204 empty body. Rejected requests trả empty 400
(invalid data/signature/amount), 404 (unknown attempt/provider/target), 409 (lifecycle rejection),
503 (missing verifier config), 500 (persistence/system error). Các mã rejection là local policy,
không tự nhận là mã business acknowledgment MoMo. Phase 5C bên dưới thay 409 và unique-ID collision bằng 204
cho notification đã xử lý an toàn; system failure chưa xử lý vẫn 500. Malformed JSON không bị ApiResponse wrap.
IPN verifier không phụ thuộc Create Payment response verifier hoặc enabled-methods của initiation:
credential verifier vẫn bắt buộc; disabled initiation không vô hiệu xác thực notification của attempt đã tồn tại.
MoMo Create Payment response blocker giữ nguyên; checkoutReady vẫn false, không bật local MOMO.

Transaction boundary: PaymentCallbackService NEVER để verify/resolve/correlate trước mutation;
resolution read-only transaction chỉ tìm exact payment và validate booking target/method/amount.
Provider non-success được acknowledge handled mà không mutation hoặc đổi FAILED.
Settlement qua transactional bean riêng: lock Booking bằng query expiration hiện có,
rồi lock/re-read Payment PESSIMISTIC_WRITE; re-check target/method/amount/status và Clock sau lock.
Chỉ Payment PENDING + Booking PENDING + expires_at > now mới cập nhật SUCCESS + PAID trong cùng transaction.
Không lock Court, không provider HTTP, không đổi giá/details/schema. uq_transaction_id là final guard.
Confirmed sequential callback cùng transaction ID/date không overwrite; contradictory confirmation bị reject.
SUCCESS/FAILED/REFUNDED không bị hạ/đổi trạng thái; non-PENDING Booking không được transition.

Late success policy Phase 5B: booking đã EXPIRED hoặc deadline <= now giữ nguyên cả payment/booking,
không lưu transaction metadata mới, không recreate details/refund. VNPay trả 99; MoMo trả 409.
Payment history đã có được bảo toàn; chưa persist bằng chứng late settlement/reconciliation vì policy chưa xác định.
Failure/intermediate provider results cũng giữ nguyên cả hai; không tự map mọi nonzero sang FAILED.
Concurrent callback idempotency, transaction-ID races và callback-vs-expiration được xác minh ở Phase 5C bên dưới.

Verification: targeted VnPayIpnVerifierTest 27 + MoMoIpnVerifierTest 39 + PaymentCallbackControllerTest 14
+ PaymentCallbackPostgresTest 41 = 121 PASS, 0 failures/errors/skipped.
Real PostgreSQL tests chứng minh success/amount/provider/reference/date validation, exact deadline/past/expired,
non-PENDING state rejection, sequential replay và browser Return không mutate.
Rollback test dùng uq_transaction_id thật; test-only trigger/sequence trong disposable DB quan sát Booking
SQL UPDATE đã xảy ra trước Payment unique failure, rồi assert BOTH rollback; probe được drop trong finally.
Không thêm production failure hook hoặc migration.
Full `mvn -f backend/pom.xml verify` BUILD SUCCESS: 337 tests, 0 failures/errors/skipped,
JDK 17.0.20.1 portable, Maven 3.10.0, PostgreSQL 15.19 từ image local postgres:15-alpine,
test database riêng nạp nguyên db/migration.sql. Gồm toàn bộ 216 regression tests trước Phase 5B.
PostgreSQL test container đã stop sau verify; kiểm tra còn 0 test probe.
Lượt sandbox đầu lỗi compiler resource; chạy lại ngoài sandbox thành công. Không đổi build/config để né lỗi.
Shared file Phase 5B: root .gitignore được tạo lại vì .env đang untracked; /.env verified ignored,
backward-compatible. PaymentTransactionRepository thêm callback lock method, additive/backward-compatible.
BookingRepository reuse unchanged; application.yml/Security/GlobalExceptionHandler/ApiResponse/reference/schema unchanged.
Sandbox smoke: VNPay NOT RUN (runtime chưa có đủ env credential/Return URL; không đọc .env),
MoMo NOT RUN (chưa có merchant credentials). Callback [x]; idempotency/race [ ].

### Phase 5C — Callback idempotency và lifecycle races (verified)

Global lock order giữ nguyên Booking PESSIMISTIC_WRITE -> Payment PESSIMISTIC_WRITE;
expiration chỉ lock Booking. Verify chữ ký/correlation trước mutation transaction; re-check state/deadline
sau khi lấy locks. Không provider HTTP, JVM locks, retry persistence context lỗi hoặc schema mới.
Callback transaction commit/rollback xong mới map acknowledgment.

PaymentCallbackTransactionService trả outcome riêng: SUCCESS, ALREADY_CONFIRMED, LATE_EXPIRED,
NOT_PAYABLE, CONFLICT và các lỗi validation. Exact replay giữ nguyên transaction ID/date, amount,
audit fields, booking details/court_fee; signed conflicting replay không overwrite hoặc downgrade.
PaymentCallbackService nhận uq_transaction_id SQLSTATE 23505 sau rollback của transactional proxy,
phân biệt TRANSACTION_ID_CONFLICT với persistence error khác. Booking/Payment loser giữ nguyên toàn bộ.
Unique transaction_id vẫn global, kể cả VNPay/MoMo trùng textual ID; không đổi sang provider-scoped index.

VNPay: settlement 00, exact replay 02, unknown 01, amount mismatch 04, signature invalid 97.
Conflicting replay, unique-ID collision, late/nonpayable dùng 99 với Message mô tả domain outcome.
Official contract không có late-payment code; 00/02 mang nghĩa đã xử lý/cập nhật nên không dùng
để khẳng định settlement khi cả hai domain rows chưa thay đổi. Đây là mapping ứng dụng trong phạm vi
codes chính thức, có thể dẫn tới provider retry; không gọi late outcome là generic system failure.

MoMo: tách internal settlement outcome khỏi HTTP transport acknowledgment.
Notification xác thực đã xử lý an toàn (success/replay/non-success/late/nonpayable/conflict/unique collision)
trả HTTP 204 body rỗng theo official notification contract; sửa Phase 5B late HTTP 409 -> 204.
204 không khẳng định Booking đã PAID. Invalid data/signature/amount vẫn 400, unknown target 404,
missing verifier config 503, unhandled system error 500, tất cả body rỗng. Negative HTTP taxonomy này
là defensive local policy; official docs không định nghĩa taxonomy tương ứng.
Late/expired giữ nguyên Booking/Payment, không recreate details hoặc persist metadata mới;
safe log chỉ outcome/provider/attempt ID, không payload/secrets. Chưa có durable late-payment evidence,
refund hoặc reconciliation. Provider failure/intermediate không mutate, kể cả race với SUCCESS.

PaymentCallbackConcurrencyPostgresTest: 26 tests trên independent transactions/connections,
barrier/latch, actual pg_stat_activity/pg_blocking_pids và test-only gates trước commit; không sleep.
Bao gồm concurrent duplicate cả hai providers + 10 deliveries, conflicting replay,
unique-ID race cùng/cross provider, callback thắng expiration với stale candidates,
expiration thắng callback, request chờ lock qua exact deadline, actual Payment lock + global hierarchy,
invalid signature hoàn tất khi Booking đang bị lock, failure/intermediate race không downgrade.
Assert workers hoàn tất trong bounded timeout, không deadlock, acks sau commit và không partial state.
Callback thắng giữ details/slot occupied; expiration thắng xóa details/slot available.

Verification: targeted 157 PASS; full `mvn -f backend/pom.xml verify` BUILD SUCCESS:
375 tests, 0 failures, 0 errors, 0 skipped. PostgreSQL 15.19, JDK 17.0.20.1, Maven 3.10.0;
test DB riêng dùng nguyên db/migration.sql. Concurrency 26 + controller/ack 26 + callback PostgreSQL 41;
VNPay verifier 27, MoMo verifier 39 và toàn bộ payment/booking/availability/expiration regression PASS.
Lượt targeted đầu có 8 lỗi assertion test availability do migration seed nhiều slot templates;
đã sửa assertion chọn đúng fixture timeSlotId, không đổi production availability.
Shared infrastructure không sửa trong Phase 5C; Booking/Payment repositories và expiration code reuse.
MoMo Create Payment response signature blocker/checkoutReady=false giữ nguyên. Không chạy real sandbox,
không đọc local .env hoặc yêu cầu merchant credentials cho tests. Idempotency/race [x]; Phase 6 chưa làm.

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
RspCode/Message; MoMo IPN explicit 204/empty và defensive error codes, không ApiResponse wrapper.
Browser Return explicit 200/400/503 và no-mutation; public operation security overrides shared placeholder
Bearer declaration, không giả claim ownership. MoMo checkoutReady=false/blocker giữ nguyên.
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

### Phase 6 - Auth handoff completion

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

Exact public provider routes: GET /payments/vnpay/ipn, POST /payments/momo/ipn,
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
access/refresh token-purpose distinction, MoMo Create response-signature ambiguity/checkoutReady=false,
late-payment reconciliation/refund and global transaction_id uniqueness remain unchanged.

## Phase 7 — ReactJS Web UI

- [ ] Tích hợp ReactJS Web App sau khi Phase 1–6 đạt acceptance criteria.
  Tác động: frontend hiện chưa có; xác định cấu trúc khi bắt đầu phase này.
  AC: dùng backend contract đã ổn định cho booking/payment/history; không mở scope mobile.
