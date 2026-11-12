# Sửa luồng POS, tiếp tục thanh toán và thông tin Trả sân

Ngày hoàn tất: 12/11/2026. Nhánh: `fix/browser-flow-regressions`.

## Phạm vi đã thực hiện

Theo kế hoạch đã duyệt: sửa bốn lỗi R01/R02/R04/R05 trong báo cáo browser ngày
11/11, đồng thời thực hiện yêu cầu đổi số tiền ở danh sách Trả sân sang giá sau
giảm. Phần tiền là điều chỉnh hiển thị theo yêu cầu, không phải sửa phép tính bill.
Không thay đổi chính sách giữ chỗ, phép tính hóa đơn, hoàn kho hoặc dữ liệu cũ.

| Nội dung | Hành vi sau sửa |
|---|---|
| Draft mất sau reload POS | Mục **Đơn POS chờ kết toán** tải từ DB, có tìm kiếm, lọc UUID booking, phân trang, xem mặt hàng và xác nhận hủy. Reload/đăng nhập lại vẫn truy cập được. |
| Thiếu đường tiếp tục thanh toán | Booking PENDING còn hạn có nút **Tiếp tục thanh toán** trong Lịch đặt. Checkout khôi phục từ bookingId và dùng lại API resume kiểm tra ownership/trạng thái; không tạo booking thay thế hoặc gia hạn. |
| Thiếu khoảng giờ | Bảng Trả sân hiển thị các khoảng theo sân/ngày; gộp đúng slot liền nhau, giữ riêng các khoảng rời/nhiều sân/nhiều ngày. |
| FAQ hardcode thời hạn | FAQ nhận thời hạn cấu hình từ backend, hỗ trợ phút/giây; API lỗi thì dùng mô tả chung. Thông báo checkout hết hạn cũng không còn hardcode 10 phút. |
| Tiền ở danh sách | Cột **Tiền sân sau giảm** dùng finalAmount, gồm cả giá trị 0; không cộng tiền hàng và không thay bằng giá gốc khi thiếu dữ liệu. |

Danh sách draft dùng snapshot giá/mặt hàng, không lấy giá bán hiện tại để tính lại.
Tên sản phẩm hiện tại chỉ làm fallback khi chứng từ cũ chưa có tên snapshot.
ADMIN/STAFF đang hợp lệ có thể tiếp quản draft của ca khác như quyền hủy hiện có.
Draft đã gộp/đã thanh toán không xuất hiện trong danh sách. Chứng từ legacy thiếu
provenance vẫn phải đối soát; UI không tự đổi trạng thái hoặc tự hoàn kho.

## API

- Mới: `GET /api/pos/drafts?page=0&size=20&bookingId=<UUID>&search=<text>`.
  Page không âm, size 1–100, search tối đa 255 ký tự; bookingId tùy chọn phải là UUID.
  Trả `content/page/size/totalElements/totalPages`, các header draft cùng items
  (tên snapshot, số lượng, đơn giá, giảm giá, tiền dòng). Headers/items được đọc
  theo trang/lô trong một snapshot nhất quán.
- Giữ nguyên `POST /api/pos/sales/{id}/cancel` và transaction Booking → Invoice →
  Product hiện có. Hủy lặp không hoàn kho hai lần; khi đã gộp thì không hủy riêng.
- `GET /api/checkout/active-bookings` thêm `courtSchedules`, mỗi khoảng gồm
  `courtId/courtNumber/courtName/bookingDate/startTime/endTime`. Giữ `courtDetails`
  để tương thích client cũ; frontend mới không còn dùng regex tách chuỗi.
- `GET /api/public/booking-clock` thêm `holdDurationSeconds`, từ cùng cấu hình
  `booking.hold-duration`; giữ serverTime/timeZone/today.
- History/resume không đổi wire contract. Nút dựa trên server clock và timestamp
  Việt Nam; máy chủ vẫn quyết định cuối cùng, kể cả release sớm trong lúc điều hướng.

Không có schema migration, status hay phương thức thanh toán mới.

## Kiểm chứng

- Backend sạch: **765 tests, 0 failures, 0 errors, 0 skips**.
- HTTP acceptance: **25 tests**, không lỗi/skip; tổng cuối **790/790**.
- 24 ca backend bổ sung: 8 ca draft/clock/auth/filter/pagination, 1 ca gộp lịch,
  15 lần kiểm tra cạnh tranh (hai lần hủy, hủy trước checkout, checkout trước hủy).
- PostgreSQL race quan sát transaction chờ khóa, kiểm tra stock và số receipt;
  không thay bằng H2/mock cho bằng chứng khóa DB.
- Node: **50/50**. Rules resume/timezone và thời hạn được kiểm tra thêm dưới UTC,
  Asia/Ho_Chi_Minh và America/Los_Angeles.
- Hai frontend build thành công.
- Bốn bộ browser có fixture API đạt: P1, P2, booking-fixes và browser-flow-fixes.
  API mock chỉ thuộc các bộ fixture; font Google được tách khỏi kiểm tra chức năng
  để không phụ thuộc DNS ngoài. Không dùng kết quả fixture để tuyên bố VNPay đạt.

Các lệnh tái kiểm tra:

```powershell
powershell -NoProfile -File scripts/verify-p1.ps1
powershell -NoProfile -File scripts/verify-p1-acceptance.ps1
node --test web-app/*.test.mjs
npm --prefix web-app run build
npm --prefix web-admin run build
```

`web-app/browser-flow-fixes.e2e.cjs` dùng customer Vite 5184, admin Vite 5185;
đặt `PLAYWRIGHT_MODULE` tới module `playwright/test` đã cài. Nó kiểm tra reload,
hủy/khôi phục lỗi tải draft, resume không tạo thêm booking, hết hạn khi vừa mở,
net tiền, khoảng giờ, FAQ 600/900/90 giây, fallback và mobile.

Runner nghiệm thu dùng container **bcm-browser-acceptance**, port **55438** và
database **bcm_acceptance_test** với role riêng không phải superuser, tránh cổng
5433 của ứng dụng. Guard test chỉ nhận hai URL loopback cố định có đúng tên DB:
55438 cho runner độc lập và 5433 cho runner legacy. Không cho chọn DB dự án,
không bỏ guard và không chạy acceptance trong volume của môi trường test tay.

## Kiểm tra bản đang chạy với API thật

Đã cập nhật backend và hai frontend Docker đang phục vụ localhost:5173/5174,
giữ override trỏ vào **bcm_manual_p2_20261111**. Không chạy migration hay reset DB.

- Bảng Trả sân: booking `03d51e60-5eba-49c0-bb23-1767ec575e30` hiện **410.000đ**,
  lịch **12/11/2026 · Sân 1 · 06:00–08:00**.
- Draft demo `3c8911bc-ef92-5cb4-b98e-722e8d8bd417` vẫn xuất hiện sau reload;
  không hủy draft demo này trong smoke test.
- FAQ hiện **10 phút**, khớp API cấu hình **600 giây**.
- Tạo đúng một booking QA `e0be1ad8-fc30-45f8-922d-55100075a017`, mở lại từ lịch
  sử vẫn cùng UUID, nút thanh toán khả dụng và không có POST tạo booking thứ hai.
  Sau test đã dùng Quay lại để lên lịch release; DB xác nhận EXPIRED qua scheduler,
  checkout_release_at không vượt expires_at. Không ép trạng thái bằng SQL.
- Không có pageerror trong live smoke; chỉ font ngoài được thay thế. Tất cả API
  ứng dụng trong live smoke gọi backend thật, đăng nhập bằng UI.

Ảnh local trong `.tools/browser-flow-live-20261112/`:

- [Bảng Trả sân](../.tools/browser-flow-live-20261112/counter-net-and-schedule.png)
- [Draft sau reload](../.tools/browser-flow-live-20261112/drafts-after-reload.png)
- [FAQ theo cấu hình](../.tools/browser-flow-live-20261112/configured-faq.png)
- [Checkout mở lại đúng booking](../.tools/browser-flow-live-20261112/resumed-owned-booking.png)

Docker source build gặp lỗi DNS tới Docker Hub. Để cập nhật môi trường local,
đã đóng gói JAR từ `mvn verify` và hai thư mục dist từ build thành công lên các
image ứng dụng có sẵn bằng builder không truy cập registry. Runtime Java/nginx,
entrypoint và cấu hình proxy giữ nguyên; live smoke phía trên chạy trên các image
này. Dockerfile nguồn vẫn giữ nguyên để build bình thường khi registry truy cập lại.

VNPay end-to-end vẫn cần cấu hình sandbox; không tính vào các luồng đã nghiệm thu.
Hai điểm nội dung/điều khoản P3 trong báo cáo trước chưa sửa trong phạm vi này.
