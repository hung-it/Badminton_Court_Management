# Review và tích hợp web khách hàng — 22/10/2026

Source so sánh: `feature/front-end-user` tại `8be4805` và `origin/develop`
tại `7d27fb6`. Kết quả tích hợp nằm trên `feature/booking-engine`.

## Các lỗi đã sửa

| Vấn đề | Hành vi sau tích hợp |
| --- | --- |
| Router mới dùng placeholder cho các màn Booking, entry cũ chuyển `/` sang `/courts` | Một BrowserRouter; Trang chủ mới tại `/`, các màn Booking hiện có hoạt động; mỗi trang chỉ có một header/footer |
| Hai phần dùng khóa session/API client khác nhau | Dùng chung tokenStore và API client có refresh; đăng nhập dùng được ngay trong checkout; logout xóa cả draft |
| Checkout yêu cầu customerId nhưng Auth chỉ trả users.id | Endpoint Booking `/bookings/customer-profile` lấy customers.id từ principal; vẫn kiểm tra ownership phía server |
| Checkout reload/retry kiểm tra availability của slot chính booking vừa giữ | Đọc booking đã xác thực ownership và giá snapshot; không tạo lại booking khi thử thanh toán lần nữa |
| Bộ lọc Trang chủ không được đọc bởi danh sách sân | Truyền ngày, slot, courtId và sort; `/booking` tương thích các link cũ; reset xóa lọc sân |
| Chuyển Login/Register làm mất redirect; navigate được gọi trong render | Giữ đích checkout giữa hai form, dùng Navigate, chỉ nhận URL nội bộ |
| Số điện thoại có khoảng trắng qua validation frontend nhưng bị backend từ chối | Validate nhất quán với RegisterRequest; thêm giới hạn độ dài họ tên |
| Ghi nhớ đăng nhập không khôi phục access token khi mở lại phiên | Khôi phục qua refresh hiện có; không ghi nhớ thì profile/refresh token ở sessionStorage |
| Sai mật khẩu hiển thị thông báo phiên hết hạn | Thông báo email hoặc mật khẩu không đúng |
| Nhiều request refresh thất bại lồng redirect vào chính trang Login | Thông báo hết phiên một lần; giữ đích checkout; không chuyển tiếp lại từ Login/Register |
| Footer trỏ tới route/anchor chưa có | Nối về Hướng dẫn/Liên hệ hiện có; bỏ link Tin tức chưa triển khai |
| Vite mới dùng 5173 trùng Web Admin | Web khách hàng dùng 5174; proxy backend có thể cấu hình cho kiểm thử |
| Hero dùng cột cố định; email ở footer tràn tại 1024px | Cột co giãn, footer xuống hàng và email ngắt dòng; giữ riêng typography giữa hai layout |
| Test Booking kỳ vọng anonymous 403 khác SecurityConfig hiện tại | Test/OpenAPI nhất quán: anonymous 401; identity/ownership không hợp lệ 403 |
| Commit develop chứa dependency và output build | Merge bỏ node_modules/dist/crash log khỏi Git index, giữ source/package-lock và ignore tương ứng |

## Review sơ bộ DB/backend

- DDL và mapping Booking giữ nguyên; không thêm bảng, cột, status hay migration.
  Develop chỉ bổ sung seed sân mẫu vào migration hiện có.
- Dùng BigDecimal, snapshot giá, court row lock và unique slot index; các test
  PostgreSQL kiểm tra transaction độc lập cùng tranh slot, rollback, expiry giải
  phóng details, callback lặp và callback cạnh tranh expiry.
- Booking/customer/payment ownership vẫn kiểm tra trong service. Endpoint mới
  chỉ gọi CurrentCustomerService, không viết lại Auth hoặc tạo profile khi đọc.
- Cleanup path matcher của SecurityConfig và cập nhật cổng DB Docker là thay đổi
  kế thừa từ develop; không mở public các API Booking.
- Giữ `ddl-auto: validate`, SQL init `never`, kế hoạch Booking trong `plans.md`
  và toàn bộ màn đặt sân/checkout/kết quả thanh toán cũ.

## Kiểm chứng

- Maven verify: PostgreSQL 15.10, database riêng nạp migration; 325 tests,
  0 failures/errors/skipped. JDK 17 và Maven 3.9.9 portable có sẵn trong target.
- `npm ci` và `npm run build` cho web-app.
- `scripts/verify-booking-web.mjs`: Edge headless, backend riêng cổng 8088,
  database UI riêng từ migration, không sử dụng tài khoản hoặc DB thật.
  Kiểm tra tìm sân, redirect, validation, đăng ký, đăng nhập, customer ID,
  tạo booking, reload/retry khi VNPay trả 503, logout, nhớ phiên, refresh chung,
  route trực tiếp, layout và responsive. Script không thanh toán tại provider.

## Giới hạn còn lại

- Cần cấu hình VNPay Sandbox và merchant return/IPN URL có thể truy cập để thử
  thanh toán end-to-end với provider. Callback/settlement đã được test phía backend.
- `/my-bookings` hiện dẫn tới tóm tắt lịch sắp tới, chưa có trang lịch sử đầy đủ;
  `/profile` vẫn là placeholder. Backend history/detail đã có API.
- Chưa có đổi/hủy/hoàn tiền trực tuyến hoặc email xác nhận tự động. Nội dung
  Trang chủ/checkout đã bỏ cam kết hủy miễn phí và email tự động không có backend.
- Ảnh sân và testimonials trên Trang chủ là nội dung tĩnh, chưa lấy từ DB.
- `plans.md` có các đoạn lịch sử chưa phản ánh frontend hiện tại; không đánh dấu
  toàn Phase 7 hoàn tất vì trang lịch sử và các phần trên còn thiếu.
