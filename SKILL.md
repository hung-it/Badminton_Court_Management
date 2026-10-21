---
name: booking-engine
description: Implement và review Booking Engine của Thành viên 3 theo schema PostgreSQL hiện có; áp dụng cho availability, booking, expiration, payment và concurrency tests.
---

# Booking Engine — Thành viên 3

## Scope

- Phụ trách availability theo ngày, tạo booking/giữ slot, concurrency, expiration,
  court fee, online payment, callback và booking history cho ReactJS Web App.
- Ngoài scope: Auth/User Management, CRUD master data, POS, Inventory, Promotion,
  Reporting và nghiệp vụ Invoice; chỉ dùng reference tối thiểu cần cho booking/payment.

## Database invariants

- Đọc trực tiếp `db/migration.sql`; đây là SOURCE OF TRUTH, kể cả khi docs mâu thuẫn.
- Các column được persist trong module:

| Table | Columns |
| --- | --- |
| `bookings` | `id`, `customer_id`, `status`, `court_fee`, `expires_at`, `created_by`, `updated_by`, `created_at`, `updated_at` |
| `booking_details` | `id`, `booking_id`, `court_id`, `time_slot_id`, `booking_date`, `price`, `created_at` |
| `payment_transactions` | `id`, `booking_id`, `invoice_id`, `payment_method`, `transaction_id`, `status`, `amount`, `transaction_date`, `created_at`, `note` |

- UUID cho ID; DATE/TIME/TIMESTAMP map đúng kiểu và thống nhất timezone khi so sánh expiry.
- `customer_id` tham chiếu `customers.id`; `created_by`/`updated_by` tham chiếu
  `staffs.id`, nullable khi khách tự đặt; không thay bằng `users.id`.
- Ba bảng trên không có `deleted_at`; details/payment không có `updated_at`.
  Không kế thừa trực tiếp `BaseEntity` vì sẽ map column không tồn tại.
- `chk_pending_expires`: booking `PENDING` phải có `expires_at`.
- `chk_payment_target`: đúng một trong `booking_id`/`invoice_id` khác NULL;
  thanh toán booking dùng `booking_id`, để `invoice_id = NULL`.
- Dữ liệu không có column chỉ được transient/runtime hoặc derive; ghi limitation
  nếu không thể persist. Không thêm schema, migration, column, index hoặc status.

## Booking lifecycle và availability

- Booking status chỉ: `PENDING`, `PAID`, `CHECKED_IN`, `COMPLETED`, `NO_SHOW`, `EXPIRED`.
- Tạo mới: `PENDING` với deadline giữ slot lấy từ configuration.
- Thanh toán thành công hợp lệ: `PENDING -> PAID`.
- Hết hạn khi `expires_at <= current time`: `PENDING -> EXPIRED`.
- Các status còn lại được map/đọc; không tự triển khai check-in/POS hoặc luồng khác.
- `time_slots` là template, không có status; availability theo
  `(booking_date, court_id, time_slot_id)` được derive từ `booking_details`.
- Chỉ cho đặt sân `courts.status = AVAILABLE` và `courts.deleted_at IS NULL`.
  Detail còn tồn tại vẫn giữ slot; không chỉ lọc bỏ booking hết deadline để báo trống.

## Slot và concurrency

- `uq_booking_slot(booking_date, court_id, time_slot_id)` là bảo vệ cuối cùng.
- Tạo header + toàn bộ details + giá trong một transaction; xung đột rollback toàn bộ.
- Dùng `PESSIMISTIC_WRITE`/`SELECT FOR UPDATE` trên row tồn tại phù hợp,
  ưu tiên `courts`; query slot chưa tồn tại không khóa được row trống.
- Nếu khóa nhiều row, dùng thứ tự ổn định; giữ transaction ngắn.
- Availability pre-check không đủ. Xử lý unique violation tại flush/commit;
  trả lỗi slot không khả dụng theo pattern HTTP 409 hiện có, không commit một phần.
- Callback và expiration phải khóa cùng booking, đọc lại status/deadline sau khóa.
  Không giữ DB lock trong lúc gọi gateway hoặc đợi khách thanh toán.

## Expiration bắt buộc

- Trong cùng transaction: khóa booking, kiểm tra lại `PENDING` và deadline,
  cập nhật `bookings.status = EXPIRED`, rồi
  `DELETE FROM booking_details WHERE booking_id = :bookingId` (parameter binding).
- Không delete `bookings` hoặc payment history hợp lệ; chỉ đổi status không giải phóng slot.
- Job gọi service transaction qua Spring proxy; chạy lại an toàn, không expire booking đã PAID.
- Booking EXPIRED vẫn xuất hiện trong history với details rỗng; không dựng lại slot/giá
  từng detail đã xóa. Giữ nguyên `court_fee` đã chốt trên header.

## Pricing

- `booking_details.price = courts.base_price * time_slots.price_multiplier`.
- `bookings.court_fee = SUM(booking_details.price)` tại thời điểm tạo booking.
- Dùng `BigDecimal`; snapshot giá, không tính lại booking cũ theo master data mới.
- Phù hợp `NUMERIC(10,2)`; thống nhất rounding về scale 2 trước khi cộng tổng,
  ghi rõ policy và test; không dùng float/double hoặc invent pricing attribute.

## Payment và idempotency

- Persist vào `payment_transactions`; method chỉ `VNPAY`;
  status chỉ `PENDING`, `SUCCESS`, `FAILED`, `REFUNDED`; online scope chỉ là VNPay Sandbox.
- Tạo payment `PENDING` cho booking còn hạn; amount lấy từ `court_fee` phía server.
- Callback/IPN/webhook: verify signature/checksum, đối chiếu target/method/amount
  và status gateway trước mutation; return URL của trình duyệt không chứng minh đã thanh toán.
- Khóa booking/payment theo thứ tự thống nhất; payment `SUCCESS` và booking `PAID`
  cập nhật atomically chỉ khi booking còn `PENDING`, chưa hết deadline.
- `uq_transaction_id` unique toàn bảng khi `transaction_id IS NOT NULL`;
  NULL không chống duplicate. Phân biệt reference booking/payment attempt với mã GD gateway.
- Xử lý payment `PENDING` đã tồn tại; không coi có row/transaction_id là đã hoàn tất.
  Callback lặp hoặc đồng thời phải nhận kết quả ổn định, không tạo giao dịch trùng,
  cộng tiền, hạ trạng thái đã thành công hoặc chạy side-effect lần hai.
- Không hồi sinh EXPIRED khi callback đến muộn; bảo toàn payment history đã verify,
  ghi rõ policy xử lý thanh toán muộn trước khi implement, không tự thêm refund flow.
- Secret/API key từ environment/application config; không hard-code hoặc log secret.

## Definition of Done

- Mapping/schema, lifecycle, snapshot giá và payment XOR đúng; API theo convention repo.
- Test PostgreSQL chứng minh 2 request cùng slot: đúng 1 thành công, 1 lỗi nghiệp vụ;
  test expiry xóa details/đặt lại slot và callback lặp/callback cạnh tranh với expiry.
- Build/tests phù hợp task pass; API contract/OpenAPI khớp hành vi và limitation đã ghi rõ.
