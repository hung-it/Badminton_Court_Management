# Sơ đồ Activity (Hoạt động) - Hệ thống Quản lý Sân Cầu Lông (Bản Cập Nhật V4)

Các sơ đồ Hoạt động mô phỏng lại luồng đi của người dùng và hệ thống, bao gồm các điều kiện logic (if/else), phù hợp với Database V4 (Thanh toán online 100%, Hệ số giờ, Nhập kho).

## Hướng dẫn xem hình
Copy từng đoạn code bên dưới và dán vào **[PlantText](https://www.planttext.com/)**.

---

## 1. Luồng Tìm kiếm & Đặt Sân (Khách Hàng)
```plantuml
@startuml
title Activity Diagram - Khách Hàng Đặt Sân & Thanh Toán Online
skinparam maxMessageSize 150

|Khách Hàng|
start
:Mở ứng dụng / Web;
:Chọn Ngày và Sân cầu lông;

|Hệ thống|
:Truy vấn `booking_details` tìm các ca đã đặt;
:Hiển thị danh sách các ca trống;

|Khách Hàng|
:Chọn ca trống;
:Nhấn nút "Đặt sân";

|Hệ thống|
:Tính tiền: `base_price` * `price_multiplier`;
:Ghi nhận vào `bookings` (status: PENDING);
:Tạo URL Cổng thanh toán (VNPay/MoMo);

|Khách Hàng|
:Mở App Ngân hàng / MoMo quét mã QR;
:Thanh toán thành công;

|Cổng Thanh Toán|
:Gửi Webhook xác nhận giao dịch;

|Hệ thống|
:Lưu vào `payment_transactions`;
:Cập nhật `bookings.status` = 'PAID';
:Gửi thông báo thành công cho Khách;

|Khách Hàng|
:Nhận thông báo & Xem biên lai;
stop
@enduml
```

---

## 2. Luồng Bán hàng POS (Nhân Viên)
```plantuml
@startuml
title Activity Diagram - Bán Hàng & Xuất Hóa Đơn (POS)
skinparam maxMessageSize 150

|Nhân Viên|
start
:Khách đến quầy thanh toán (Mua thêm nước);
:Mở màn hình POS;
if (Khách có lịch đặt sân?) then (Có)
  :Gán `booking_id` vào Hóa đơn;
  |Hệ thống|
  :Lấy `court_fee` từ bảng `bookings`;
else (Không (Khách vãng lai))
  |Hệ thống|
  :Set `court_fee` = 0;
endif

|Nhân Viên|
:Thêm Hàng hóa / Dịch vụ vào giỏ;
:Nhấn "Tạo Hóa đơn";

|Hệ thống|
:Tính toán `product_fee`;
:Tạo Hóa đơn (Invoices);
while (Còn mặt hàng trong giỏ?) is (Còn)
  if (Loại hàng là GOODS?) then (Đúng)
    :Kiểm tra Tồn kho (`stock_quantity`);
    if (Đủ hàng?) then (Đủ)
      :Trừ tồn kho & Cộng `version` (Optimistic Locking);
    else (Thiếu hàng)
      :Báo lỗi "Không đủ hàng";
      stop
    endif
  else (Là SERVICE)
    :Bỏ qua kiểm tra tồn kho;
  endif
  :Lưu vào `invoice_details`;
endwhile (Hết)

:Cập nhật Tổng tiền (`total_amount`);
:Lưu Hóa đơn thành công;

|Nhân Viên|
:Thu tiền khách & Đóng hóa đơn;
stop
@enduml
```

---

## 3. Luồng Nhập Hàng Vào Kho (Nhân Viên / Quản Lý)
```plantuml
@startuml
title Activity Diagram - Luồng Tạo Phiếu Nhập Kho
skinparam maxMessageSize 150

|Nhân Viên|
start
:Vào màn hình Quản lý Nhập Kho;
:Nhấn "Tạo Phiếu Nhập";
:Chọn Nhà Cung Cấp (Suppliers);
:Thêm các Sản phẩm & Số lượng cần nhập;
:Ghi nhận Giá nhập kho;
:Nhấn "Lưu & Xác nhận Nhập";

|Hệ thống|
:Tạo bản ghi `import_orders` (status: RECEIVED);

while (Duyệt từng sản phẩm nhập?) is (Còn)
  :Lưu vào `import_order_details`;
  :Cập nhật Bảng `products`:
  Cộng dồn số lượng vào `stock_quantity`;
endwhile (Hết)

:Hoàn tất giao dịch DB (Commit);
:Hiển thị thông báo thành công;

|Nhân Viên|
:Kho hàng đã được cập nhật số lượng thực tế;
stop
@enduml
```
