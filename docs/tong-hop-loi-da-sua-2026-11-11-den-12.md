# Tổng hợp lỗi P1, P2 và frontend đã sửa

**Thời gian:** 11–12/11/2026, giờ Việt Nam.
**Mốc chốt báo cáo:** commit `41f8bbc`, nhánh `fix/browser-flow-regressions`, sau khi ghép Develop tại `6c68077`.
**Phạm vi:** các đợt sửa P1, P2, khôi phục regression, sửa luồng web và xử lý tương thích khi đồng bộ Develop trong khoảng thời gian trên.

## 1. Tổng quan

| Nhóm | Khối lượng hoàn thành |
|---|---|
| P1 | 6 lỗi liên quan định danh, khóa dữ liệu khi đặt sân, voucher và hiển thị cơ sở. |
| P2 | 7 lỗi về dữ liệu sân/booking, tồn kho, mật khẩu, email và giá sân. |
| Luồng frontend phát hiện khi test tay | 4 lỗi R01, R02, R04, R05 đã sửa; thêm điều chỉnh hiển thị R03 theo yêu cầu. |
| Regression | Khắc phục 47 ca kiểm thử lỗi từ baseline; đồng thời sửa hai lỗi production trong luồng khởi tạo thanh toán. |
| Đồng bộ Develop | Tích hợp quản lý khách hàng, khóa/mở tài khoản và quyền xem hóa đơn cho thu ngân; giữ các bản sửa cũ khi giải quyết xung đột. |

**Lưu ý cách đếm:** P1/P2 là nhóm ưu tiên của danh sách nghiệp vụ ban đầu. R01–R05 là mã trong báo cáo kiểm tra trình duyệt sau đó; các lỗi frontend này cũng được đánh giá mức P2. Không cộng các cách phân loại thành số lỗi độc lập nếu chúng mô tả cùng một vấn đề.

## 2. Sáu lỗi P1 đã sửa

### P1 #1 — Refresh token có thể chuyển sang tài khoản khác khi email được dùng lại

- **Trước:** refresh token dựa vào email. Nếu A đổi email rồi B đăng ký email cũ, token của A có nguy cơ nhận quyền của B.
- **Đã sửa:** token mang `uid = users.id`; refresh chỉ tìm đúng người dùng còn hoạt động bằng UUID bất biến. Token thiếu/sai định danh hoặc tài khoản không còn hợp lệ bị từ chối với HTTP 401.
- **Sau khi ghép Develop:** bổ sung kiểm tra `token_version` để token đã bị thu hồi không sống lại sau mở khóa. Đổi email vẫn cho refresh vào đúng A, không theo email cũ sang B.
- **Nguồn chính:** `JwtUtil.java`, `AuthService.java`; kiểm thử `CustomerAuthPostgresTest`, `JwtUtilTest`.

### P1 #2 — Bảo trì/đóng sân số 1 làm biến mất cả cơ sở trên trang đặt sân

- **Trước:** frontend dùng sân có `branchCourtNumber === 1` làm đại diện; sân 1 vắng khỏi catalog thì các sân còn trống của cùng cơ sở cũng không được hiển thị.
- **Đã sửa:** nhóm theo `branchId`, chọn một sân hiện có làm đại diện ổn định; lựa chọn khung giờ được gắn với cơ sở.
- **Kết quả:** cơ sở vẫn xuất hiện và khách vẫn đặt được sân 2, 3… khi sân 1 không khả dụng.
- **Nguồn chính:** `CustomerCourts.jsx` và các helper nhóm/chọn sân; browser suite `p1-business.e2e.cjs`.

### P1 #3 — Xóa mềm sân chạy đua với thao tác tạo booking

- **Trước:** quản trị kiểm tra lịch rồi mới xóa, không dùng cùng khóa Court với luồng booking; sân có thể bị xóa sau khi khách vừa đặt thành công.
- **Đã sửa:** khóa Court trước khi kiểm tra và xóa mềm, kiểm tra lại lịch trong transaction.
- **Kết quả:** thao tác quản trị và đặt sân được tuần tự hóa; không bỏ qua booking vừa được tạo.
- **Nguồn chính:** `MasterDataService.java`; `P1MasterDataConcurrencyPostgresTest`.

### P1 #4 — Sửa/xóa khung giờ chạy đua với tạo booking

- **Trước:** quản trị có thể thấy slot chưa được tham chiếu trong lúc booking đang ghi detail, rồi thay giờ hoặc xóa mềm slot.
- **Đã sửa:** booking lấy shared lock trên các TimeSlot theo thứ tự; quản trị lấy exclusive lock trước khi kiểm tra tham chiếu. Thống nhất thứ tự khóa Booking hiện có → Courts → TimeSlots.
- **Kết quả:** slot đã được booking tham chiếu không bị sửa/xóa trái quy tắc; các booking độc lập vẫn có thể cùng đọc slot.
- **Nguồn chính:** `MasterDataService.java`, `BookingService.java`; kiểm thử concurrency PostgreSQL.

### P1 #8 — Voucher được nhả sớm khi thanh toán vẫn còn khả năng hoàn tất

- **Trước:** giới hạn lượt dùng có thể bỏ qua booking/payment đang chờ, tạo nguy cơ cấp cùng lượt ưu đãi cho booking khác trong thời gian callback còn hợp lệ.
- **Đã sửa:** booking PENDING có payment PENDING giữ lượt voucher đến khi thất bại cuối cùng hoặc hết hạn được xử lý nguyên tử, kể cả cửa sổ chờ callback. Giới hạn chung và NEWMEMBER dùng cùng quy tắc giữ lượt.
- **Kết quả:** không cấp lại lượt ưu đãi khi booking cũ vẫn có thể thanh toán hợp lệ. Việc nhả lượt có thể chờ job hết hạn, không nhả sớm.
- **Nguồn kiểm chứng:** `BookingPromotionPostgresTest` và các kiểm thử thanh toán liên quan.

### P1 #9 — Nhận diện/bảo vệ khách vãng lai POS bằng số điện thoại

- **Trước:** số điện thoại đặc biệt được dùng để nhận diện guest, dễ nhầm với tài khoản thông thường hoặc tài khoản có quyền khác cùng số.
- **Đã sửa:** cấu hình `BCM_POS_GUEST_USER_ID`; POS và quản trị nhận diện guest bằng UUID. Startup kiểm tra guest tồn tại, đang hoạt động, có customer profile và chỉ có quyền CUSTOMER.
- **Kết quả:** guest thật được bảo vệ; tài khoản khác cùng số điện thoại không tự trở thành guest. Đăng ký mới bị chặn dùng email/số điện thoại dành riêng cho guest.
- **Nguồn chính:** `WalkInGuestIdentity`, `WalkInGuestGuard`; `CustomerAuthPostgresTest`.

## 3. Bảy lỗi P2 đã sửa

| Mã | Lỗi trước sửa | Cách sửa và kết quả |
|---|---|---|
| **#5** | Mở form sửa sân gỗ rồi lưu làm loại sân thành `STANDARD_MAT`. | Giữ `item.type`; hỗ trợ chọn và hiển thị cả `STANDARD_MAT` lẫn `WOODEN_FLOOR`. Sửa tên/giá không tự đổi loại sân. |
| **#6** | Nhân viên thấy tên/SĐT hồ sơ cũ thay vì người liên hệ riêng của booking. | Danh sách booking tại quầy ưu tiên `contactFullName`/`contactPhone`; fallback từng trường về hồ sơ khi thiếu snapshot. |
| **#7** | Lịch sử đặt sân hiển thị giá gốc, thiếu số tiền sau ưu đãi. | API trả `discountAmount`, `finalAmount`; lịch sử hiện giá sau giảm và chi tiết giá gốc/giảm/còn lại. Ví dụ 440.000 − 30.000 = **410.000đ**. |
| **#10** | Đổi GOODS/SERVICE có thể xung đột với POS và làm sai hoàn kho khi hủy draft. | Sửa sản phẩm dùng cùng row lock với POS; chặn đổi loại khi có dòng hóa đơn DRAFT. Draft SERVICE legacy có provenance trừ kho bất nhất bị chặn nguyên tử bằng 409, không tự hoàn kho sai. |
| **#11** | Quy tắc mật khẩu không thống nhất; giới hạn ký tự không phản ánh giới hạn byte của BCrypt và có thể làm khó tài khoản legacy. | Mật khẩu mới tối thiểu 8 ký tự, tối đa 72 byte UTF-8. Mật khẩu hiện tại hỗ trợ đến 100 ký tự để kiểm tra/đổi mật khẩu legacy; giữ hash cũ. Frontend và backend thống nhất quy tắc. |
| **#12** | Email khác hoa/thường/khoảng trắng có thể tạo định danh trùng, kể cả khi đăng ký/sửa đồng thời. | Chuẩn hóa email tại đăng ký, đăng nhập và chỉnh sửa; unique index DB bảo vệ định danh, gồm tài khoản xóa mềm. Migration dừng khi có collision; không tự gộp/xóa/khóa tài khoản. |
| **#13** | Có thể tạo/sửa sân giá 0 và đưa sân đó vào luồng đặt sân. | Giá sân mới/chỉnh sửa bắt buộc dương. Sân legacy giá 0 vẫn để admin xử lý nhưng bị loại khỏi catalog, availability và quote; không sửa lại giá booking lịch sử. |

Nguồn triển khai chính: `web-admin/src/App.jsx`, `CheckoutService.java`, `BookingSummaryResponse.java`, `CustomerBookingHistory.jsx`, các service sản phẩm/POS/master data và `CredentialPolicy`. Migration email: `db/upgrades/email-identity-upgrade.sql`.

## 4. Các lỗi luồng frontend đã sửa sau khi kiểm tra web thật

### R01 — Mất đường hủy hóa đơn POS nháp sau reload

- **Trước:** nút hủy phụ thuộc lịch sử POS trong React state. Reload làm mất nút dù DB vẫn giữ DRAFT và hàng đã bị trừ tồn.
- **Đã sửa:** bổ sung `GET /api/pos/drafts` và mục **Đơn POS chờ kết toán**, tải dữ liệu từ DB; có tìm kiếm, lọc booking, phân trang, xem mặt hàng và xác nhận hủy.
- **Kết quả:** reload/đăng nhập lại vẫn mở và hủy được draft hợp lệ; giữ snapshot giá, quyền ADMIN/STAFF và quy tắc hủy/hoàn kho hiện có. Hủy lặp không hoàn kho hai lần; draft đã gộp/đã thanh toán không xuất hiện trong danh sách.

### R02 — Lịch sử không có đường tiếp tục thanh toán booking còn hạn

- **Trước:** rời checkout rồi mở lịch sử chỉ xem được chi tiết, không có nút quay lại thanh toán.
- **Đã sửa:** thêm **Tiếp tục thanh toán** cho booking PENDING còn hạn. Dùng server clock để xét hiển thị, mở lại checkout bằng bookingId và để backend kiểm tra ownership/trạng thái/thời hạn.
- **Kết quả:** dùng lại booking cũ, không tạo booking thứ hai, không gia hạn giữ chỗ; booking đã hết hạn hoặc không còn hợp lệ không được tiếp tục thanh toán.

### R04 — Danh sách Trả sân chỉ hiển thị slot đầu

- **Trước:** booking 06:00–08:00 nhưng cột Thời gian chỉ hiện 06:00–07:00 do lấy kết quả regex đầu tiên từ chuỗi detail.
- **Đã sửa:** backend trả `courtSchedules` có cấu trúc. Frontend hiển thị đầy đủ theo sân/ngày, gộp các slot thực sự liền nhau và giữ riêng khoảng rời/nhiều sân/nhiều ngày.
- **Kết quả:** booking hai slot liền nhau hiển thị đúng **06:00–08:00**, không nối nhầm các khoảng có giờ trống ở giữa.

### R05 — FAQ ghi giữ sân 15 phút trong khi hệ thống dùng 10 phút

- **Trước:** nội dung hardcode không khớp cấu hình backend, khiến khách hiểu sai hạn thanh toán.
- **Đã sửa:** API booking clock trả `holdDurationSeconds` từ cùng cấu hình giữ chỗ; FAQ và thông báo hết hạn dùng thông tin tương ứng. API lỗi thì dùng mô tả chung, không tự hứa một thời hạn cố định.
- **Kết quả:** cấu hình 600 giây hiện 10 phút; đã kiểm tra thêm 900/90 giây và fallback.

### R03 — Điều chỉnh cột tiền theo yêu cầu, không phải lỗi tính bill

- **Xác nhận của người dùng:** bill khi bấm Kết toán đã hiện đúng **410.000đ**; không coi đây là lỗi phép tính hóa đơn.
- **Điều chỉnh đã làm:** danh sách Trả sân đổi từ giá gốc **440.000đ** sang **410.000đ**, nhãn **Tiền sân sau giảm**, lấy `finalAmount` và chấp nhận cả giá trị 0.
- **Phạm vi:** đây là tiền sân sau ưu đãi, không phải tổng tiền hàng cộng sân hoặc số tiền còn phải thu. Không thay đổi phép tính bill hay lịch sử khoản thu.

## 5. Sửa bổ sung trong quá trình khôi phục kiểm thử

Hai lỗi production được sửa cùng đợt P2:

1. **Response khởi tạo thanh toán không giữ đúng trạng thái booking:** bổ sung trạng thái booking vào response thay vì để mất thông tin khi chuẩn bị payment attempt.
2. **Thiếu cấu hình gateway che khuất lỗi booking:** kiểm tra chủ sở hữu, trạng thái và hạn booking trước bước kiểm tra cấu hình gateway; lỗi cấu hình không còn che việc booking đã hết hạn/không hợp lệ.

Đồng thời khôi phục **47 ca kiểm thử lỗi đã tái hiện trên baseline `f7d48ea`**. Không coi 47 ca này là 47 lỗi production riêng biệt: nhiều ca do fixture trạng thái cũ, receipt thiếu provenance, thiếu danh tính nhân viên hợp lệ hoặc chạy SQL PostgreSQL trên H2. Đã cập nhật fixture/test adapter theo contract hiện hành, không xóa test, bỏ test hoặc nới constraint để làm xanh.

## 6. Phần mới từ Develop đã tích hợp ngày 12/11

Đây là cập nhật của đồng đội, được review và ghép vào các bản sửa trên:

- Quản lý khách hàng, xem tài khoản đang hoạt động/đã khóa và lịch sử booking/hóa đơn.
- Khóa/mở tài khoản CUSTOMER-only; đồng bộ trạng thái user/customer. Token cũ vẫn bị thu hồi sau mở khóa, đăng nhập mới dùng bình thường. Thao tác lặp cùng trạng thái không tự tăng `token_version` lần nữa.
- Thu ngân được **xem** danh sách/chi tiết hóa đơn; vẫn bị chặn chỉnh sửa hóa đơn, quản lý tài khoản, thống kê và báo cáo doanh thu.
- Giao diện cập nhật menu theo quyền, hộp xác nhận khóa/mở và nhãn Quản lý/Thu ngân.

Khi giải quyết xung đột đã giữ refresh theo UUID, bảo vệ guest theo UUID, chính sách mật khẩu/email và các luồng POS/booking đã sửa. Refresh bị thu hồi trả HTTP 401. Các kiểm thử cũ được cập nhật theo quyền xem hóa đơn mới và API draft phân trang.

## 7. Kết quả kiểm chứng tại mốc cuối

| Kiểm tra | Kết quả |
|---|---|
| Backend regression | **788 passed**, 0 failure/error/skip. |
| HTTP acceptance trên DB riêng | **26 passed**, 0 failure/error/skip. |
| Tổng backend + HTTP | **814 passed**; không cộng dồn các lần chạy lịch sử. |
| Frontend Node | **50 passed**. |
| Build React | Web-app và web-admin đều thành công. |
| Trình duyệt Edge với API fixture | **7 bộ đạt:** P1, P2, browser-flow-fixes, booking-fixes, pos-customer-mode, checkout-list, revenue-invoices. |
| Web thật sau đồng bộ | Đăng nhập quản lý/thu ngân/khách; quyền xem hóa đơn; chặn menu tài khoản của thu ngân; tiền sân 410.000đ và lịch 06:00–08:00; draft còn sau reload; lịch sử khách tải được. Không có page error trong smoke check. |
| Migration `token_version` | Phục hồi backup vào DB riêng, chạy migration hai lần trên bản phục hồi và một lần trên DB test tay; hash dữ liệu user/customer/booking/invoice/payment hiện có không đổi, bỏ qua cột mới khi so user. |
| Kiểm tra diff | `git diff --check` đạt. |

Các kiểm thử race dùng PostgreSQL với transaction độc lập; kết quả UI tuần tự không được dùng thay cho bằng chứng khóa DB. Các suite browser có fixture chặn API để kiểm tra giao diện; live smoke gọi backend thật là bằng chứng riêng.

Các tổng cũ 735, 766 hoặc 790 trong báo cáo từng giai đoạn phản ánh thời điểm/phạm vi khác nhau. Mốc cuối của tài liệu này là **814 backend/HTTP + 50 frontend**, không cộng chúng với tổng các giai đoạn trước.

## 8. Môi trường và phần còn lại

- Môi trường test tay: DB **`bcm_manual_p2_20261111`**, admin <http://localhost:5173>, web khách <http://localhost:5174>. Tại lượt đồng bộ Develop không migrate/chọn DB cũ `badminton_court_db`, không thay volume.
- Sau bổ sung `token_version`, phiên cũ thiếu claim phiên bản cần đăng nhập lại một lần.
- **Chưa kiểm chứng VNPay end-to-end với nhà cung cấp:** môi trường chưa có đủ cấu hình sandbox. Không dùng các bài test callback/fixture để tuyên bố giao dịch thật đã đạt.
- Suite tương thích backup BANK_TRANSFER legacy là suite opt-in riêng, ngoài tổng bắt buộc trên; không có test mới bị skip để đạt kết quả.
- **Chưa sửa trong phạm vi này:** R06 về nội dung hướng dẫn/quảng bá chưa thống nhất và R07 về link Điều khoản/Chính sách chưa dẫn tới nội dung đúng nhãn. Đây là các nhận xét P3, không nằm trong danh sách đã hoàn thành.
- Chưa có kết luận nghiệm thu toàn bộ hệ thống về tải, accessibility hoặc mọi trình duyệt/thiết bị. Các bản sửa đã kiểm chứng không đồng nghĩa hệ thống chắc chắn không còn lỗi khác.

## 9. T?i li?u v? b?ng ch?ng

- [Ki?m th? v? b?ng 47 ca regression](testing.md).
- [Schema v? n?ng c?p DB](../db/README.md).
- [Thanh to?n / h?a ??n](payment-invoice-flow.md).
- [Khuy?n m?i](promotions.md).

M?c commit: P1 k?t th?c ? 3dd9211; P2 ? 8751911; browser fixes ? 38cc4ab; ??ng b? Develop ? 41f8bbc. B?ng ch?ng cu?i: .tools/p1-results/develop-sync-all.json, log develop-sync-*.log v? ?nh .tools/develop-sync-20261112/screenshots/.

B?o c?o ki?m tra c? ???c h?p nh?t v?o t?i li?u n?y. B?n g?c tr??c d?n d?p ???c l?u local trong .tools/cleanup-before-20261112.tar.
