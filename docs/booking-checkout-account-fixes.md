# Kết toán bỏ sót, trả sân sớm và tài khoản — 2026-11-10

Yêu cầu trực tiếp của người dùng mở rộng tích hợp Checkout/POS và tự đổi mật khẩu;
tiếp tục trên `develop`. Không thay schema, status hay tạo luồng Auth khác.

## Tự động kết toán

- `BookingAutoCheckoutJob` quét mỗi 5 phút, bắt đầu sau 5 phút; mặc định chờ **3 giờ
  sau giờ kết thúc cuối cùng** của toàn bộ booking, theo Asia/Ho_Chi_Minh.
- Chỉ booking PAID, đủ lịch chơi, không có hóa đơn DRAFT/PAID, và có đúng một
  biên nhận SUCCESS đầy đủ được xác minh mới được chọn. Biên nhận gateway phải
  là VNPAY/GATEWAY; biên nhận thủ công phải có người xác nhận. Đơn miễn phí cũ
  không cần tạo biên nhận giả. Tiền PENDING/lời khai khách không được coi là đã thu.
- Mỗi booking dùng một transaction riêng và khóa pessimistic booking; kiểm tra lại
  status/lịch/POS/biên nhận sau khóa. Tái sử dụng phần hoàn tất hóa đơn của CheckoutService.
  Hóa đơn PAID và booking COMPLETED commit/rollback cùng nhau; không thu tiền lần hai,
  không giả mạo nhân viên. Retry/cạnh tranh với kết toán tại quầy không tạo trùng hóa đơn.
- Đơn có POS đang chờ thu hoặc dữ liệu cần đối soát vẫn phải xử lý tại quầy.
  Không khôi phục NO_SHOW: dùng các status có trong schema.
- Doanh thu/Thống kê tiếp tục lấy từ hóa đơn PAID hiện có. Hóa đơn tự tạo được
  ghi nhận tại thời điểm job kết toán; không sửa ngược các kỳ kế toán đã qua.
- Cấu hình môi trường: `BOOKING_AUTO_CHECKOUT_ENABLED` (mặc định true),
  `BOOKING_AUTO_CHECKOUT_GRACE` (`PT3H`, phải dương),
  `BOOKING_AUTO_CHECKOUT_INTERVAL` (`PT5M`). Triển khai theo yêu cầu tiếp theo của người dùng; xem nhật ký triển khai bên dưới.
  Job có hiệu lực khi chạy backend phiên bản mới.

## Trả sân sớm và thu tiền

- GET/POST `/api/checkout/{bookingIdentifier}` cho kết toán từ **giờ bắt đầu sớm nhất**,
  gồm ngay tại mốc bắt đầu; vẫn chặn trước ca và khi thiếu lịch. Giữ nguyên giá/giảm giá
  sân đã lưu, không tự hoàn tiền khi khách rời sớm; tiền POS vẫn phải xác nhận đã thu.
- Web Admin → Trả sân: đơn PENDING có nút **Thu tiền tại quầy**, hiện số tiền sau
  giảm, chọn CASH/VNPAY tại quầy, mã tham chiếu và checkbox thực nhận tiền.
- Dùng POST `/api/pos/bookings/{id}/receive` hiện có; không cho xác nhận khi chưa
  tích checkbox. Sau thành công tải lại danh sách, hiện nút Kết toán cho đơn PAID.
  Backend tiếp tục chặn hết hạn/đã có giao dịch; không bỏ qua việc đối soát online.

## Tự đổi mật khẩu

- POST `/api/users/me/password`: `{currentPassword,newPassword}`. Identity từ JWT,
  không nhận ID tài khoản đích. Mật khẩu mới tối thiểu 8 ký tự, tối đa 72 ký tự và
  72 byte UTF-8 do BCrypt; phải khác mật khẩu hiện tại. Cần mật khẩu cũ đúng.
- Khóa tài khoản khi xác minh/cập nhật, chỉ lưu BCrypt hash, không trả hash/password.
  Sai mật khẩu/đầu vào trả 400, không đăng nhập trả 401. Không đổi role hoặc profile.
- Web Admin: nút Đổi mật khẩu ở thanh trên, dùng được cho ADMIN/STAFF.
  Web khách hàng: `/profile`, cũng truy cập từ menu tài khoản, có form đổi mật khẩu.
- Giới hạn hiện có: access/refresh JWT đã cấp chưa bị thu hồi bởi việc đổi mật khẩu;
  vẫn hết hạn theo cấu hình cũ. Không thêm bảng token/version hay schema mới.

## Kiểm chứng

- PostgreSQL 15 riêng: container `bcm-booking-fixes-tests`, cổng 55439,
  database `bcm_regression_test`; khởi tạo từ migration.sql và các upgrade hiện có.
  Không chạy fixture trên database chính.
- `mvn.cmd -f backend/pom.xml -Dtest=CheckoutReceiptPostgresTest,PasswordChangePostgresTest,BookingExpirationPostgresTest,PaymentCallbackPostgresTest,PaymentCallbackConcurrencyPostgresTest -Dbooking.auto-checkout.enabled=false verify`.
- Kết quả verify mục tiêu: **94 tests, 0 failure/error/skip**. Hai frontend build và
  bộ test trình duyệt nêu dưới đều pass.
- Có test hai transaction khởi chạy có kiểm soát: tự động/tại quầy chỉ một hóa đơn;
  hai đổi mật khẩu với cùng mật khẩu cũ chỉ một thành công. Test mốc bắt đầu và
  đúng end+3h, latest slot, POS đang chờ, receipt không hợp lệ, thu CASH/VNPAY retry,
  hash/đăng nhập mật khẩu mới, anonymous/validation và độ dài UTF-8.
- Build `web-admin` và `web-app`; `web-admin/booking-fixes.e2e.cjs` dùng Edge headless
  trên preview 5175/5176, mock toàn bộ API, kiểm tra modal thu tiền, validation,
  form mật khẩu ở cả hai web và màn hình 390px. Không có thao tác ghi DB từ browser test.
- Full `mvn.cmd -f backend/pom.xml verify` vẫn lỗi ở test cũ trên develop:
  5 failure + 4 error thuộc AnalyticsIntegrationTest, MasterDataIntegrationTest,
  ProductPromotionIntegrationTest, RevenueBreakdownIntegrationTest,
  JwtAuthenticationFilterTest; đã tái hiện đúng 5 failure + 4 error trên source HEAD
  trước thay đổi trong `backend/target/baseline-verification`.
  Hai acceptance suite khác yêu cầu cố định database `bcm_acceptance_test` cổng 5433;
  chưa cấu hình DB đó nên có 25 lỗi khởi tạo context. Không coi full verify là pass.
- Sửa EnumSource của PaymentCallbackPostgresTest chỉ dùng các status còn tồn tại;
  test trước đó tham chiếu CHECKED_IN/NO_SHOW đã bị xóa khỏi enum trên develop.

## Rà soát bổ sung

- Bổ sung đối chiếu `expectedAmount` trong khóa booking khi thu tiền tại quầy.
  Nếu ưu đãi/số tiền đã đổi sau lúc mở modal, trả 409 và không tạo biên nhận;
  đóng modal sẽ tải lại danh sách để mở lại và xác nhận số tiền mới.
  Field này tùy chọn cho client API cũ; Web Admin luôn gửi số tiền hiển thị.
- Kiểm tra bằng JWT thật: tài khoản không đổi được mật khẩu người khác dù body
  gửi thêm userId khác. Kiểm tra lịch Spring khi auto-checkout enabled=true;
  job được đăng ký và CheckoutService vẫn là transaction proxy.
- Verify PostgreSQL bổ sung AutoCheckoutSchedulingPostgresTest vào danh sách test:
  **97 tests, 0 failure/error/skip**. Hai web build và browser E2E pass, bao gồm
  modal đang mở thì giá đổi, lỗi 409, tải lại giá mới và xác nhận lại.
- Các giới hạn và lỗi full verify có sẵn trên develop nêu trên vẫn giữ nguyên;
  backend chính chưa được triển khai, không thay dữ liệu database chính.

## Triển khai backend chính — 2026-11-10

- Phục hồi PostgreSQL: container cũ không lên vì bind mount hai file member2-upgrade
  đã bị xóa trên develop. Recreate riêng container bằng Compose hiện tại, giữ nguyên
  external volume `badminton_court_management_postgres_data`; không khởi tạo DB mới.
- Sao lưu vật lý khi volume đang dừng: `.backups/backend-deploy-20261110/postgres-before.tgz`,
  SHA256 `3249A66DE6BDF5D0D1B6EFE5EAA2E40C1AB3CB0EAD56FD525D10C20D7029FC4F`.
  Kiểm tra tar listing thành công. Logical backup `database-before.dump` cùng thư mục,
  SHA256 `892CE9455C0507EC803D794F9678F05F89413B0786CD28F29944B9B06A4950AE`;
  pg_restore --list và restore vào database rehearsal riêng đều thành công.
- Khi đọc được DB chính mới phát hiện còn BANK_TRANSFER cũ. Chạy thử nâng cấp
  nguyên bản trên clone bị chặn đúng guard. Người dùng sau đó đã xác nhận giữ lịch sử
  và bổ sung tương thích đọc, không cho thanh toán BANK_TRANSFER mới.
- `legacy-bank-transfer-upgrade.sql` opt-in đánh dấu metadata LEGACY và khóa ghi lịch sử;
  enum bổ sung chỉ để map dữ liệu đọc. CUSTOMER vẫn chỉ VNPAY; tại quầy CASH/VNPAY.
  Không giả mạo tác giả/gateway của biên nhận cũ. Backend/UI hiển thị nhãn lịch sử.
- Rehearsal chính xác trên `bcm_deploy_rehearsal_exact_20261110`, cổng 55439;
  áp legacy + booking UX + các phần DDL sản phẩm/khuyến mãi sân, không seed catalog.
  Chạy lặp upgrade thành công. Đối chiếu toàn bộ trường cũ của 11 bảng khớp hash/count.
- Verify có LegacyPaymentCompatibilityPostgresTest và 6 bộ hồi quy trước:
  **100 tests, 0 failures/errors/skipped**. Hai web build, browser fixture suite pass.
  Một bộ PaymentAttemptPostgresTest bổ sung chạy 27 case có 26 pass và 1 test Swagger
  cũ đòi description không còn đúng với source trên develop; không thay mô tả API
  nghiệp vụ chỉ để làm test cũ pass. Full verify vẫn có các lỗi baseline đã ghi trên.
- Trước áp dụng chính thức: dừng backend, dump thêm `database-pre-migration.dump`,
  kiểm tra archive và ghi baseline mới. Apply đúng SQL đã rehearsal; đối chiếu hash/count
  ngay sau migration và sau startup đều khớp cả 11 bảng.
- Main giữ 5 legacy receipts = 1,260,000 VND, 3 legacy invoices = 780,000 VND;
  11 PAID invoices tổng 2,367,000 VND, không có booking PAID chờ tự kết toán tại cutover.
- Image backend mới `sha256:4315b36fc774d224fefc7267541e6fd5d6c3268a942167ee78916cdd59089cb7`.
  Main 8080 health 200; API revenue/statistics/invoice history/active bookings/users me
  đều success. Đọc hóa đơn BANK_TRANSFER thật thành công; password API với body rỗng
  trả 400 validation, không đổi mật khẩu thật. Swagger có `/users/me/password` và
  CounterReceiptRequest.expectedAmount. RestartCount=0 ở lần kiểm tra sau triển khai.
- Auto checkout enabled theo application mặc định: grace PT3H, interval/initial PT5M.
  Chưa ghi nhận thay đổi tài chính tự động nào vì không có booking đủ điều kiện.
- Giữ các backup ngoài Git, không xóa volume. Backend cũ được giữ tag
  `bcm-backend:before-20261110`. Không restore toàn bộ backup đè lên giao dịch mới.

- Kiểm tra sau lần restart Docker để giải phóng cache: backend 8080 và PostgreSQL
  tự phục hồi, health 200. Hai web dev chính 5173/5174 đã khởi động và proxy health 200.
  Dừng các preview 5175/5176 và PostgreSQL test sau khi hoàn tất. Docker build cache
  không dùng được dọn 3.507 GB; không xóa image đang chạy hay volume dữ liệu.

## Khôi phục theo yêu cầu người dùng — 2026-11-10

Tại bước này đã quay về database và backend trước triển khai. Sau đó người dùng
yêu cầu triển khai lại theo phương án ban đầu, giữ nguyên dữ liệu cũ; xem mục cuối.

- Dừng backend và chụp thêm `.backups/backend-deploy-20261110/database-before-restore.dump`.
  Kiểm tra archive trước khi làm tiếp. Restore thử `database-pre-migration.dump`
  trên database test `bcm_restore_predeploy_20261110` thành công.
- Đối chiếu toàn bộ trường có trong backup của tất cả **21 bảng public** với trạng thái
  chính lúc backend đã dừng: count/hash khớp hoàn toàn, không phát hiện dữ liệu mới.
- Giữ database sau triển khai dưới tên `badminton_court_db_after_deploy_20261110`,
  tắt nhận kết nối để dự phòng. Tạo lại `badminton_court_db` với đúng owner/encoding/locale
  (`bcm_admin`, UTF8, en_US.utf8) và restore archive gốc trong single transaction.
- Sau restore và sau khi backend cũ khởi động: count/hash tất cả 21 bảng vẫn khớp backup.
  Schema dump (--no-owner/--no-privileges, bỏ khóa restrict ngẫu nhiên) khớp bản restore tham chiếu.
  payment_transactions.source và bcm_guard_legacy_bank_transfer() không còn tồn tại.
  Vẫn giữ nguyên 5 BANK_TRANSFER receipts tổng 1,260,000 VND như trước.
- Retag image chính về `bcm-backend:before-20261110`, image ID
  `sha256:b45b7383da59f4f7767e1a33e3d94986499c0130fe4b42b7647e89d5105f788b`.
  Compose up dùng --no-build; backend 8080 health 200, RestartCount=0;
  hai proxy 5173/5174 health 200. Image mới được giữ tag bcm-backend:after-deploy-20261110.
- Giữ nguyên source thay đổi để rà soát; không Git reset, không commit/push.
  Bốn phần sửa mới hiện chưa áp dụng trên backend đang chạy. Không chạy lại migration
  hoặc rebuild source mới lên database vừa phục hồi khi chưa có phương án không sửa DB.

## Triển khai lại theo yêu cầu mới nhất — 2026-11-10

Người dùng đã đổi yêu cầu từ không sửa cấu trúc sang dùng lại phương án ban đầu,
nhưng bắt buộc giữ nguyên dữ liệu đang có. Không triển khai nhánh thử nghiệm sửa
mapping legacy-schema; không có thay đổi code nào từ hướng khảo sát đó.

- Giữ nguyên image đã đạt 100 kiểm thử liên quan, không build lại hoặc seed dữ liệu.
  Image đang chạy: 4315b36fc774d224fefc7267541e6fd5d6c3268a942167ee78916cdd59089cb7.
- Dừng backend, sao lưu trạng thái mới nhất vào
  `.backups/backend-redeploy-20261110/database-before.dump`, kiểm tra archive.
- Áp đúng legacy + booking UX + phần DDL sản phẩm/khuyến mãi sân đã rehearsal.
  Có bổ sung cấu trúc và metadata ở cột mới; không sửa/xóa bất kỳ giá trị trường cũ nào.
- So sánh count/hash tất cả trường cũ trong **21 bảng**, trước/sau nâng cấp và sau
  startup: đều khớp. 5 BANK_TRANSFER receipts vẫn 1,260,000 VND; 3 hóa đơn chuyển
  khoản vẫn 780,000 VND. Không tạo dữ liệu/giao dịch mẫu trên database chính.
- Backend 8080, proxy web 5173/5174 health 200; RestartCount=0. API doanh thu,
  thống kê, lịch sử hóa đơn, active bookings và users/me success. Swagger có API
  mật khẩu mới và expectedAmount. Password request rỗng trả 400, không đổi mật khẩu thật.
- Bốn phần sửa mới đang áp dụng trên backend chính. Tự kết toán bật theo mặc định
  3 giờ sau ca, quét mỗi 5 phút; nghiệp vụ mới vẫn ghi dữ liệu bình thường khi phát sinh.
  Không commit/push trong bước triển khai này.
