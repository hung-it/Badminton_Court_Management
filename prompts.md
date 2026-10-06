# Prompt tái sử dụng — Booking Engine backend

Chọn một prompt ứng với task trong `plans.md`; chưa có prompt frontend.

## Inspect Booking Engine — Phase 1

Đọc `AGENTS.md` + `SKILL.md` + `db/migration.sql` trước. Inspect source, package,
build/config và tests; đối chiếu tiến độ `plans.md`, chỉ ra dependency/limitation có bằng chứng.
Không tự thay schema hoặc implement. Chạy `git diff --check`; nếu đề xuất sửa code,
yêu cầu test/build sau thay đổi, không tự đánh dấu task hoàn thành.

## Map Booking entities — Phase 1

Đọc `AGENTS.md` + `SKILL.md` + `db/migration.sql` trước. Map ba bảng Booking và
repository tối thiểu đúng schema; tránh kế thừa `BaseEntity` sai column, tái sử dụng
reference có sẵn. Không tự thay schema hoặc tạo CRUD module khác.
Chạy test mapping trên PostgreSQL và build sau thay đổi; báo kết quả thật.

## Connect Booking references — Phase 1

Đọc `AGENTS.md` + `SKILL.md` + `db/migration.sql` trước. Kết nối reference tối thiểu
customer/staff/court/time slot cho Booking và ghi rõ identity boundary/limitation Auth.
Không tự thay schema, viết Auth hoặc CRUD master data. Chạy test/build sau thay đổi.

## Implement availability — Phase 2

Đọc `AGENTS.md` + `SKILL.md` + `db/migration.sql` trước. Implement availability theo
ngày/sân bằng details hiện có và điều kiện sân hợp lệ; detail chưa release vẫn chặn slot.
Dùng response/exception hiện tại. Không tự thay schema. Chạy test query/API và build sau thay đổi.

## Implement create booking — Phase 3

Đọc `AGENTS.md` + `SKILL.md` + `db/migration.sql` trước. Implement create booking
PENDING có expires_at từ config, validate reference/slot và snapshot giá BigDecimal;
header/details atomic, tổng/rounding đúng `SKILL.md`. Không tự thay schema.
Chạy test pricing/validation/rollback và build sau thay đổi.

## Implement concurrency protection — Phase 3

Đọc `AGENTS.md` + `SKILL.md` + `db/migration.sql` trước. Bảo vệ create booking bằng
pessimistic lock trên row tồn tại, transaction và `uq_booking_slot`; xử lý lỗi flush/commit
thành 409 rõ ràng. Không tự thay schema. Chạy test hai request cùng slot trên PostgreSQL
và build sau thay đổi; xác nhận đúng một thành công, không có booking dở dang.

## Implement expiration — Phase 4

Đọc `AGENTS.md` + `SKILL.md` + `db/migration.sql` trước. Implement service/job expire
PENDING tại expires_at <= current time: khóa/kiểm tra lại, đổi EXPIRED và DELETE toàn bộ
booking_details trong cùng transaction; giữ header/payment history. Không tự thay schema.
Chạy test deadline, chạy job lặp, đặt lại slot và build sau thay đổi.

## Implement payment attempts — Phase 5A

Đọc `AGENTS.md` + `SKILL.md` + `db/migration.sql` trước. Implement payment_transactions
PENDING cho booking còn hạn và abstraction VNPay/MoMo theo layer hiện có; amount từ server,
target XOR đúng, gateway credentials từ config, dữ liệu không có column chỉ runtime.
Không tự thay schema. Chạy test payment initiation/gateway sandbox phù hợp và build sau thay đổi.

## Implement sandbox initiation — Phase 5A.5

Đọc instructions/schema/source trước. Giữ payment attempt PENDING; VNPay v2.1.0 tạo signed sandbox URL,
MoMo One-Time Wallet captureWallet ký request và gọi HTTP ngoài DB transaction/Booking lock.
Chỉ dùng official provider contracts; amount phía server, config từ environment, artifacts runtime.
MoMo Create Payment response signature EN/VI chưa thống nhất: không đoán verifier, giữ checkoutReady=false
và không expose checkout artifacts tới khi có authoritative clarification. Không implement callback/mutation.
Test deterministic signer/client, PostgreSQL initiation/retry/race, regression và full verify; smoke test opt-in.

## Implement callback verification — Phase 5B

Đọc `AGENTS.md` + `SKILL.md` + `db/migration.sql` trước. Implement callback/IPN/webhook
theo tài liệu chính thức provider; verify signature/checksum và target/method/amount trước mutation,
SUCCESS + PAID atomic, acknowledgment đúng contract. Không tự thay schema.
Chạy test callback hợp lệ/giả/sai tiền/thất bại và build sau thay đổi.

## Implement callback idempotency — Phase 5C

Đọc `AGENTS.md` + `SKILL.md` + `db/migration.sql` trước. Bảo vệ callback lặp/đồng thời
bằng transaction_id, `uq_transaction_id`, lock và status; phối hợp expiration, không hồi sinh EXPIRED.
Ghi rõ policy thanh toán muộn/limitation; không tự thêm refund flow hoặc thay schema.
Chạy test duplicate, concurrent callback, callback cạnh tranh expiry và build sau thay đổi.

## Write concurrency tests — Phase 6

Đọc `AGENTS.md` + `SKILL.md` + `db/migration.sql` trước. Viết test PostgreSQL với schema
gốc và hai request/transaction độc lập cùng khởi chạy đặt một date/court/time slot;
assert đúng một thành công, một lỗi 409, DB chỉ giữ một booking hợp lệ cho slot đó.
Không tự thay schema. Chạy test vừa viết và build sau thay đổi; báo cách đồng bộ request.

## Complete backend tests — Phase 6

Đọc `AGENTS.md` + `SKILL.md` + `db/migration.sql` trước. Bổ sung tests còn thiếu theo
acceptance criteria Phase 6: pricing/rollback, expiry/rebooking, callback verification/idempotency
và race với expiry; không mock DB để chứng minh locking. Không tự thay schema.
Chạy tests và `mvn -f backend/pom.xml verify` sau thay đổi.

## Implement booking history — Phase 6

Đọc `AGENTS.md` + `SKILL.md` + `db/migration.sql` trước. Implement history/detail/status
cần cho Web App, lọc customer và ownership theo Auth boundary; EXPIRED có details rỗng,
giữ court_fee, ghi limitation nếu identity chưa tích hợp. Không tự thay schema hoặc viết Auth.
Chạy test history/ownership/EXPIRED và build sau thay đổi.

## Document API contract — Phase 6

Đọc `AGENTS.md` + `SKILL.md` + `db/migration.sql` trước. Hoàn thiện Swagger/contract
cho backend đã implement: request/response/errors, expiry, callback acknowledgment, limitation;
dùng Springdoc hiện có và path `/api` đúng config. Không tự thay schema.
Chạy test contract liên quan và build sau thay đổi.

## Review against migration — Phase 6

Đọc `AGENTS.md` + `SKILL.md` + `db/migration.sql` trước. Review Booking Engine theo schema,
invariants và acceptance criteria `plans.md`; nêu finding kèm file/line và thiếu hụt test.
Không tự thay schema hoặc sửa module ngoài scope. Chạy tests/build để kiểm chứng;
nếu sửa finding trong scope, chạy lại test/build sau thay đổi.
