# Sơ đồ Activity (Hoạt động) - Hệ thống Quản lý Sân Cầu Lông (Bản Đồng Bộ V5)

Các sơ đồ Hoạt động mô phỏng luồng nghiệp vụ thực tế của người dùng và hệ thống, có điều kiện logic (if/else), đồng bộ 100% với backend Spring Boot và cơ sở dữ liệu PostgreSQL.

> **Hướng dẫn xem hình:** Copy từng đoạn mã PlantUML bên dưới và dán vào công cụ xem trực quan như **[PlantText](https://www.planttext.com/)** hoặc **[Mermaid Live Editor](https://mermaid.live/)**.

---

## 1. Luồng Tìm Kiếm, Đặt Sân & Thanh Toán Online (Khách Hàng)
```plantuml
@startuml
title Activity Diagram - Khách Hàng Đặt Sân & Thanh Toán Online (VNPay)
skinparam maxMessageSize 150

|Khách Hàng|
start
:Mở Web App;
:Chọn Ngày và Chi nhánh / Sân cầu;

|Hệ thống|
:Truy vấn danh sách sân & khung giờ khả dụng;
:Lọc các ca đã được giữ chỗ hoặc đã đặt;
:Hiển thị lưới trạng thái sân (AVAILABLE, HELD, BOOKED, PAST);

|Khách Hàng|
:Chọn khung giờ trống;
:Nhập thông tin liên hệ;
:Áp dụng mã giảm giá / Voucher (nếu có);
:Nhấn nút "Xác nhận đặt sân";

|Hệ thống|
:Khóa PESSIMISTIC_WRITE các sân được chọn;
:Kiểm tra lại tính khả dụng (re-check occupied);
if (Khung giờ còn trống?) then (Còn trống)
  :Tính tiền = base_price * price_multiplier;
  :Ghi nhận booking (status: PENDING);
  :Thiết lập thời hạn giữ chỗ: expires_at = now + 10 phút;
  :Lưu chi tiết vào booking_details (price snapshot);
  :Tạo giao dịch payment_transactions (status: PENDING);
  :Khởi tạo Payment URL cổng VNPay (HMAC SHA-512);
  :Trả về URL thanh toán cho khách;
else (Đã có người đặt trước)
  :Báo lỗi 409 "Khung giờ vừa có người đặt";
  stop
endif

|Khách Hàng|
:Chuyển hướng sang Cổng thanh toán VNPay Sandbox;
:Quét mã QR / Nhập thông tin thẻ và Xác nhận;

|Cổng Thanh Toán VNPay|
:Xác thực giao dịch & gửi IPN Webhook về Backend;

|Hệ thống|
:Xác thực chữ ký số & kiểm tra số tiền khớp;
:Kiểm tra thời hạn deadline và cửa sổ ân hạn in-flight (5 phút);
if (Hợp lệ?) then (Hợp lệ)
  :Cập nhật payment_transactions.status = 'SUCCESS';
  :Cập nhật bookings.status = 'PAID';
  :Gửi thông báo thành công;
else (Không hợp lệ hoặc quá hạn)
  :Ghi nhận lỗi RECONCILIATION_REQUIRED;
  stop
endif

|Khách Hàng|
:Trình duyệt nhận phản hồi;
:Xem Lịch sử Đặt sân & Chi tiết Biên lai;
stop
@enduml
```

---

## 2. Luồng Bán Hàng POS tại Quầy (Nhân Viên)
```plantuml
@startuml
title Activity Diagram - Bán Hàng POS tại Quầy (Khách Vãng Lai & Khách Thuê Sân)
skinparam maxMessageSize 150

|Nhân Viên|
start
:Mở màn hình Bán tại quầy (POS);
:Chọn loại khách hàng;

if (Khách thuê sân có sẵn booking?) then (Có - Khách thuê sân)
  :Nhập mã đơn đặt sân (UUID);
  |Hệ thống|
  :Xác thực booking tồn tại và đang ở trạng thái PAID;
  :Tải thông tin tiền sân & ưu đãi hiện tại;
else (Không - Khách vãng lai)
  |Hệ thống|
  :Gán tài khoản khách vãng lai hệ thống (guest@bcm.com);
endif

|Nhân Viên|
:Thêm các mặt hàng / nước uống vào giỏ hàng;
:Nhập mã khuyến mãi sản phẩm (nếu có);

|Hệ thống|
:Tính toán tổng tiền sản phẩm & giảm giá tự động;
:Kiểm tra tồn kho tạm thời;

|Nhân Viên|
if (Chế độ là Khách thuê sân?) then (Khách thuê sân)
  :Bấm "Thêm hàng & xem bill gộp";
  |Hệ thống|
  :Trừ tồn kho sản phẩm GOODS (Optimistic Lock với @Version);
  :Tạo hóa đơn tạm (status: DRAFT, booking_id = ID, court_fee = 0);
  :Lưu line items vào invoice_details;
  |Nhân Viên|
  :Hàng hóa đã được thêm vào hóa đơn nháp của booking;
  stop
else (Khách vãng lai)
  :Chọn phương thức thanh toán (CASH / VNPAY);
  :Thu tiền khách & Tích chọn "Đã nhận tiền";
  :Bấm "Thu tiền hàng";
  |Hệ thống|
  :Trừ tồn kho sản phẩm GOODS (Optimistic Lock với @Version);
  :Tạo Hóa đơn hoàn tất (status: PAID, booking_id = NULL);
  :Tạo giao dịch payment_transactions (status: SUCCESS, source: MANUAL);
  :Lưu line items vào invoice_details;
  |Nhân Viên|
  :Xuất hóa đơn bán lẻ cho khách vãng lai;
  stop
endif
@enduml
```

---

## 3. Luồng Trả Sân & Kết Toán Hóa Đơn Tổng (Checkout Flow)
```plantuml
@startuml
title Activity Diagram - Trả Sân & Kết Toán Hóa Đơn Tổng (Checkout)
skinparam maxMessageSize 150

|Nhân Viên|
start
:Khách kết thúc ca chơi, đến quầy trả sân;
:Mở tab "Trả sân", tìm mã đơn đặt sân;
:Bấm nút "Kết toán";

|Hệ thống|
:Truy vấn thông tin booking và chi tiết lịch chơi;
:Lấy tổng tiền sân đã chốt (court_fee sau giảm);
:Tìm tất cả hóa đơn tạm (status: DRAFT) gắn với booking;
:Tính tổng tiền hàng POS (product_fee) & giảm giá hàng;
:Đối trừ số tiền sân khách đã thanh toán trước (paidCourt);
:Tính số tiền còn phải thu: amountDue = totalAmount - paidCourt;
:Hiển thị Phiếu tạm tính (Checkout Slip);

|Nhân Viên|
if (amountDue > 0?) then (Còn phải thu thêm)
  :Thông báo số tiền cần thanh toán cho khách;
  :Chọn phương thức thu (CASH / VNPAY);
  :Nhập mã tham chiếu (nếu có);
  :Tích chọn "Tôi xác nhận đã nhận tiền còn lại";
  :Bấm "Xác nhận & thu tiền";
else (amountDue == 0 - Không phát sinh hàng)
  :Bấm "Hoàn tất trả sân";
endif

|Hệ thống|
:Bắt đầu Transaction an toàn;
:Khóa booking bằng PESSIMISTIC_WRITE;
:Tạo Hóa đơn chính thức (invoices status: PAID, gộp cả tiền sân và tiền hàng);
:Chuyển toàn bộ invoice_details từ các hóa đơn DRAFT sang hóa đơn mới;
:Cập nhật các hóa đơn DRAFT cũ thành CANCELLED;
if (amountDue > 0) then (Có thu thêm tiền)
  :Tạo payment_transactions (invoice_id = invoice mới, amount = amountDue, status: SUCCESS);
endif
:Cập nhật bookings.status = 'COMPLETED';
:Commit Transaction;

|Nhân Viên|
:Giao diện thông báo kết toán thành công;
:In hóa đơn tổng hợp gửi khách hàng;
stop
@enduml
```

---

## 4. Luồng Nhập Hàng Vào Kho & Duyệt Giá Bán (Admin / Staff)
```plantuml
@startuml
title Activity Diagram - Luồng Nhập Kho & Duyệt Giá Bán Niêm Yết
skinparam maxMessageSize 150

|Nhân Viên / Quản Lý|
start
:Mở màn hình "Nhập kho";
:Nhấn "Tạo phiếu nhập";
:Nhập Nhà cung cấp, Tên sản phẩm, Số lượng và Tổng tiền;
:Bấm "Tạo phiếu nhập";

|Hệ thống|
:Kiểm tra hoặc tạo Nhà cung cấp / Sản phẩm mới nếu chưa có;
:Tạo phiếu nhập (import_orders status: DRAFT);
:Lưu chi tiết giá nhập vào import_order_details;

|Quản Trị Viên (Admin)|
:Kiểm tra thực tế hàng về kho;
:Mở chi tiết phiếu nhập;
:Bấm "Duyệt nhập kho";

|Hệ thống|
:Cập nhật import_orders.status = 'RECEIVED';
:Cộng dồn số lượng nhập vào products.stock_quantity;

|Hệ thống|
if (Sản phẩm thuộc danh mục "Hàng nhập kho" và giá niêm yết <= 0?) then (Chưa duyệt giá)
  :Đánh dấu sản phẩm chưa được phép bán lẻ tại POS;
  |Quản Trị Viên (Admin)|
  :Mở danh sách Sản phẩm;
  :Bấm nút "Duyệt giá niêm yết";
  :Nhập giá bán lẻ niêm yết (> 0);
  :Xác nhận duyệt giá;
  |Hệ thống|
  :Cập nhật products.price = giá mới;
  :Cho phép hiển thị và bán sản phẩm tại quầy POS;
else (Đã có giá niêm yết hợp lệ)
  :Sản phẩm sẵn sàng bán tại POS ngay lập tức;
endif

stop
@enduml
```
