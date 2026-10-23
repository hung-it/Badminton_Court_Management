# Courts / Checkout UI và nghiệp vụ chỉnh sửa — 2026-10-23

## A. Git và phạm vi

Nhánh trước/sau `feature/front-end-user`. Ban đầu 40 tracked modified + 5 untracked.
Cuối lượt 50 tracked modified + 13 untracked (63 paths), chưa stage/commit.
Giữ nguyên toàn bộ diff BANK_TRANSFER/expiration trước đó; không switch/commit/push/pull/merge/reset/restore/clean.
Không triển khai mobile, không đổi architecture/dependency/JWT hoặc nghiệp vụ xác minh thanh toán.

## B. Courts

Source khảo sát: `CustomerCourts.jsx`, stylesheet, PublicCatalogController, MasterDataService,
Court/CourtRequest/repositories, schema và migration upgrade của repo.
Checkbox extraSlots trước đó đã có key courtId; không kết luận sai rằng nó dùng một mảng toàn cục.
Nguyên nhân can thiệp liên sân là chip giờ chính gọi setSelectedSlot chung và effect [date,selectedSlot]
reset extraSlots của toàn grid. Bây giờ `courtSlots[courtId]` giữ giờ chính riêng,
`extraSlots[courtId]` giữ giờ bổ sung, keys dùng courtId + timeSlotId. Chỉ thay filter ngày/giờ chung
mới reset grid có chủ đích; sort/name/district/price không chuyển lựa chọn sang card khác.
Book button lọc availability và gửi đúng courtId cùng các slot của card được nhấn.
CSS hai nhóm cách nhau 10px, label có hàng riêng, chip wrap với row gap 8px, giữ thiết kế xanh.

Schema cũ không có địa chỉ/quận của sân, không có bảng location hay quy ước địa giới chuẩn hóa.
Địa chỉ User/Customer/Supplier không phải địa chỉ sân. Vì vậy không suy quận từ tên sân.
Thêm district nullable trong courts, optional admin request; public catalog trả field này.
Không hardcode danh mục quận; checkbox lấy nhãn xác minh đã lưu trong toàn catalog (API không pagination).
OR giữa các quận, AND với filter hiện có; xóa riêng/toàn bộ; scroll 216px; tìm kiếm khi >8 nhãn;
vùng click >=40px; NULL vẫn hiển thị khi không lọc quận. Không thêm index vì lọc trên full catalog.

## C. Checkout

Ghi chú booking lưu `bookings.customer_note`, tách hoàn toàn khỏi `payment_transactions.note`.
Textarea 1000 ký tự/counter, nút lưu, trạng thái đang lưu/đã lưu/thất bại; draft theo bookingId trong
sessionStorage giữ khi refresh/method switch hoặc bank validation lỗi. Không bật payment khi còn
ghi chú chưa lưu hoặc chưa tải được note từ backend. GET history summary/detail và result/upcoming hiển thị note.

`PATCH /api/bookings/{id}/notes` nhận note, validate length/control characters, owner và deadline;
khóa Booking, lưu transaction và trả owned detail. Chỉ PENDING; blank xóa về NULL; không đổi payment note.

`PATCH /api/bookings/{id}/details` nhận bookingDate và 1..48 timeSlotIds. Chỉ cùng một sân ban đầu,
PENDING, chưa hết hạn và không có bất kỳ payment attempt nào. Cấm thay customerId/courtId/giá/status/deadline.
Giữ khóa Booking rồi Court; đọc ID sân trước khóa để tránh cached entity stale; kiểm tra lại sau chờ khóa.
Kiểm tra slot active/tương lai/duplicate/conflict, giữ snapshot giá cho tuple không đổi, snapshot giá master
HALF_UP cho tuple mới và cộng BigDecimal trên server. DELETE/INSERT details + courtFee atomic;
unique index vẫn bảo vệ cuối, conflict rollback giữ nguyên hold cũ. Không tạo orphan/booking mới/gia hạn.

Chính sách attempt bảo thủ: mọi attempt, kể cả FAILED/REFUNDED, khóa sửa slot, tránh URL cũ sai tiền.
Note không thay nghĩa vụ thanh toán nên vẫn sửa được khi attempt pending và booking còn hạn.
UI có edit ngày/check nhiều slot/preview giá/save/cancel; conflict giữ nội dung để sửa tiếp, backend giữ
details cũ. Payment disabled khi edit/save; lost response phải GET lại trạng thái persisted trước khi thanh toán.
Payment preflight đọc lại để phát hiện tab khác vừa đổi booking và yêu cầu xem lại tổng tiền mới.
Không có trường contact riêng trên Booking nên không sửa hồ sơ Auth/User thay cho booking contact.
Không triển khai đổi sân/multi-court editing hoặc tự hủy/hoàn tiền attempt.

## D. Database và triển khai

Migration: `db/booking-ux-upgrade.sql`, additive/transaction/repeatable theo convention SQL thủ công
đang có. Thêm `bookings.customer_note VARCHAR(1000) NULL`, `courts.district VARCHAR(150) NULL`.
Không sửa `db/migration.sql` lịch sử. Compose mount upgrade sau schema gốc cho volume mới;
volume hiện có áp dụng theo `db/README.md` trước khi khởi động backend mới.
Giữ ddl-auto validate và SQL init never. Không delete/update dữ liệu sân/booking/payment cũ để backfill.
Đã áp dụng migration trên hai DB test riêng `bcm_booking_verify` và `bcm_booking_audit`.
Ứng dụng chính 8080/5433 chưa migrate/redeploy trong lượt này.

## E. Kiểm thử thực chạy

Java 17.0.15/Maven 3.9.9 Docker, PostgreSQL 15.19/schema gốc + upgrade, database riêng.
Lượt targeted đầu PASS 19 tests; sau đó bổ sung signed amount/payment-vs-edit/fresh Court state tests.
Full suite bao gồm regressions có sẵn; test Booking/lock/unique/expiry chạy PostgreSQL thật,
không dùng H2 làm bằng chứng concurrency. Các module khác giữ profile test của repo.

```text
docker cp db/booking-ux-upgrade.sql bcm-booking-audit-20261022:/tmp/booking-ux-upgrade.sql
docker exec bcm-booking-audit-20261022 psql -U bcm_test -d bcm_booking_verify -v ON_ERROR_STOP=1 -f /tmp/booking-ux-upgrade.sql
docker exec bcm-booking-audit-20261022 psql -U bcm_test -d bcm_booking_audit -v ON_ERROR_STOP=1 -f /tmp/booking-ux-upgrade.sql
docker run --rm --network container:bcm-booking-audit-20261022 -v <workspace>:/workspace -v C:\Users\Tea\.m2:/root/.m2 -w /workspace -e BCM_TEST_DB_URL=jdbc:postgresql://localhost:5432/bcm_booking_verify -e BCM_TEST_DB_USERNAME=bcm_test -e BCM_TEST_DB_PASSWORD=<test-only> maven:3.9.9-eclipse-temurin-17 mvn -o -B -f backend/pom.xml verify
npm run build                         # working directory web-app
node web-app/courts-checkout-ux.e2e.cjs
node web-app/booking-payment.e2e.cjs    # BCM_E2E_DAY_OFFSET=30/40
node web-app/checkout-expiration.e2e.cjs
git diff --check
```

Log verify cuối: `%TEMP%/bcm-booking-ux-verify.log`; XML tại backend/target/surefire-reports.
Full `mvn verify` cuối **BUILD SUCCESS: 384 tests, 0 failures/errors/skipped**.
21 modification cases: note persistence/control/length/ownership, immutable states, expiry đúng 10 phút,
đổi ngày/slots/tính giá/retained snapshots, availability conflict giữ hold cũ, missing/deleted/past slots,
mọi payment status chặn sửa, hai owner cùng target đúng 200/409, clock và Court state sau chờ lock,
VNPay signed URL tiền mới, payment vs edit atomic amount và BANK_TRANSFER tách note.
Migration test tạo schema riêng bằng nguyên migration.sql, thêm legacy booking/detail/payment,
chạy upgrade hai lần và chứng minh các dữ liệu vẫn nguyên, không tự gán quận. Chỉ cleanup fixture test.

Playwright dùng backend riêng 8081 + Vite 5173 + audit PostgreSQL thật, không mock API/receipt:
state isolation/sort/filter, OR nhiều quận từ fixture nhãn vị trí giả lập (không phải dữ liệu sân thật),
name/price combination, cả hai card gửi đúng sân/slot, refresh không khôi phục sai sân,
pending attempt UI khóa edit, notes draft/DB refresh/foreign 403, edit giá/deadline/availability race,
BANK_TRANSFER sau sửa, note trong result/refresh và responsive 1440/820/375/320px PASS.
Hai suite E2E booking-payment/countdown có sẵn PASS, không uncaught frontend JS error.
Frontend build PASS (1660 modules). Không thêm export mới gây Fast Refresh boundary thay đổi.

## F. File sửa trong lượt này và giới hạn

Java paths tương đối `backend/src/main/java/com/bcm/`:

- Mới `dto/request/UpdateBookingRequest.java`, `UpdateBookingNoteRequest.java`,
  `service/BookingModificationService.java`: contract và transaction chỉnh sửa.
- Sửa `controller/BookingController.java`, `dto/response/BookingHistoryDetailResponse.java`,
  `dto/response/BookingSummaryResponse.java`, `service/BookingHistoryService.java`, `entity/Booking.java`, `repository/BookingRepository.java`, `repository/BookingDetailRepository.java`,
  `PaymentTransactionRepository.java`: route, note mapping, projections/conflict/deletion/attempt checks.
- Sửa `entity/Court.java`, `dto/request/CourtRequest.java`, `controller/PublicCatalogController.java`,
  `service/MasterDataService.java`: district nullable và tích hợp tối thiểu master data.
- Mới hai PostgreSQL tests `BookingModificationPostgresTest.java`, `BookingUxMigrationPostgresTest.java`.
- Sửa CustomerCourts/CustomerCheckout/PaymentResult và hai CSS hiện có; thêm courts-checkout-ux.e2e.cjs.
- Thêm SQL upgrade; sửa Docker Compose mount, db/README.md, API contract, plans và báo cáo này.

Giữ nguyên diff trước ở PaymentAttempt/Initiation/Callback/VNPay/IPN/Expiration/Create Booking/Auth/POS;
không sửa migration lịch sử, enum hoặc dependency. Không xóa PostgreSQL data/volume ứng dụng.

Dữ liệu quận sân thật còn thiếu: cần admin xác minh địa chỉ và nhập field district. Chức năng filter
đã triển khai/test, nhưng không điền nhãn giả cho records chưa rõ địa chỉ; các fixture E2E chỉ ở DB audit.
VNPay Sandbox settlement thật vẫn BLOCKED vì runtime test không có merchant config/public IPN;
backend signature/Return/IPN/late callback/expiry regressions PASS, không suy settlement từ redirect.
Đã dừng Vite 5173, backend test `bcm-booking-ux-http` và PostgreSQL test
`bcm-booking-audit-20261022`; giữ container/dữ liệu/volume. Backend chính không bị restart.
