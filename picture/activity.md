# Sơ đồ Activity (Hoạt động) - Hệ thống Đặt Sân Cầu Lông

Tài liệu này chứa mã nguồn PlantUML để vẽ 7 luồng Hoạt động (Activity Diagrams) tương ứng với 7 luồng Sequence Diagram. Sơ đồ Activity giúp mô tả rõ ràng luồng chạy của thuật toán, các rẽ nhánh (if/else), vòng lặp và điểm kết thúc của từng chức năng.

## Hướng dẫn xem hình
Copy từng đoạn code bên dưới và dán vào **[PlantText](https://www.planttext.com/)** để render thành ảnh.

---

## 1. Luồng Đăng ký Tài khoản
```plantuml
@startuml
title Activity Diagram - Đăng ký tài khoản
skinparam maxMessageSize 150

|Khách hàng|
start
:Nhập thông tin (Tên, Email, Pass, SĐT);
:Nhấn Đăng ký;

|Hệ thống|
:Kiểm tra định dạng dữ liệu;
if (Dữ liệu hợp lệ?) then (Không)
  :Báo lỗi định dạng;
  |Khách hàng|
  stop
else (Có)
  |Hệ thống|
  :Truy vấn DB kiểm tra Email;
  if (Email đã tồn tại?) then (Có)
    :Báo lỗi "Email đã sử dụng";
    |Khách hàng|
    stop
  else (Không)
    |Hệ thống|
    :Mã hóa Mật khẩu (Hash);
    :Lưu user mới vào DB;
    :Gán Role 'CUSTOMER';
    :Báo Đăng ký thành công;
    |Khách hàng|
    stop
  endif
endif
@enduml
```

---

## 2. Luồng Đăng nhập & Xác thực
```plantuml
@startuml
title Activity Diagram - Đăng nhập
skinparam maxMessageSize 150

|Người dùng|
start
:Nhập Email & Mật khẩu;
:Nhấn Đăng nhập;

|Hệ thống|
:Truy vấn User theo Email;
if (Tìm thấy User & Chưa bị xóa?) then (Không)
  :Báo lỗi "Sai Email hoặc Mật khẩu";
  |Người dùng|
  stop
else (Có)
  |Hệ thống|
  :So sánh Mật khẩu (Hash);
  if (Mật khẩu khớp?) then (Không)
    :Báo lỗi "Sai Email hoặc Mật khẩu";
    |Người dùng|
    stop
  else (Có)
    |Hệ thống|
    :Tạo JWT Token;
    :Trả về Token & Thông tin User;
    |Người dùng|
    :Lưu Token vào thiết bị;
    :Chuyển hướng vào Màn hình chính;
    stop
  endif
endif
@enduml
```

---

## 3. Luồng Quản lý Danh mục Sân
```plantuml
@startuml
title Activity Diagram - Quản lý Danh mục Sân
skinparam maxMessageSize 150

|Chủ Sân|
start
:Truy cập trang Quản lý Sân;
:Chọn hành động;
fork
  :Thêm Sân mới;
  :Nhập Tên, Loại, Giá;
  |Hệ thống|
  :Lưu Sân mới vào DB (INSERT);
fork again
  |Chủ Sân|
  :Sửa Sân;
  :Cập nhật Tên, Loại, Giá;
  |Hệ thống|
  :Cập nhật DB (UPDATE);
fork again
  |Chủ Sân|
  :Xóa Sân;
  :Xác nhận Xóa;
  |Hệ thống|
  :Đánh dấu xóa trong DB\n(UPDATE deleted_at);
end merge

|Hệ thống|
:Truy vấn lại danh sách sân mới nhất;
:Cập nhật danh sách trên UI;
:Hiển thị thông báo thành công;
|Chủ Sân|
stop
@enduml
```

---

## 4. Luồng Tìm kiếm & Đặt sân
```plantuml
@startuml
title Activity Diagram - Tìm kiếm & Đặt sân
skinparam maxMessageSize 150

|Khách hàng|
start
:Chọn Ngày & Sân muốn đặt;
|Hệ thống|
:Truy vấn các Slot đã có người đặt trong DB;
:Tính toán & Hiển thị các Slot còn trống;
|Khách hàng|
:Chọn Slot trống trên lưới (Time-grid);
:Nhấn "Đặt Sân";
|Hệ thống|
:Bắt đầu DB Transaction;
:Lưu thông tin Booking;
:Lưu thông tin Booking Details\n(Kích hoạt Unique Index trên DB);
if (Bị trùng lịch do người khác nhanh tay hơn?) then (Có)
  :Rollback Transaction;
  :Trả về mã 409 Conflict;
  |Khách hàng|
  :Nhận thông báo lỗi Trùng lịch;
  stop
else (Không)
  |Hệ thống|
  :Commit Transaction;
  :Trả về mã 201 Created;
  |Khách hàng|
  :Nhận thông báo Đặt sân thành công;
  stop
endif
@enduml
```

---

## 5. Luồng Hủy lịch Đặt Sân
```plantuml
@startuml
title Activity Diagram - Hủy lịch Đặt sân
skinparam maxMessageSize 150

|Khách hàng|
start
:Vào Lịch sử Đặt sân;
:Chọn một lịch đang chờ (PENDING);
:Nhấn nút "Hủy lịch";
|Hệ thống|
:Truy vấn DB kiểm tra trạng thái Booking;
if (Trạng thái == PENDING?) then (Không)
  :Báo lỗi "Chỉ được hủy lịch chưa xác nhận/chưa chơi";
  |Khách hàng|
  stop
else (Có)
  |Hệ thống|
  :Bắt đầu DB Transaction;
  :Cập nhật trạng thái Booking thành CANCELLED;
  :Xóa (DELETE) record trong Booking Details\nđể giải phóng Slot cho người khác;
  :Commit Transaction;
  :Trả kết quả thành công;
  |Khách hàng|
  :Nhận thông báo Hủy thành công;
  :UI chuyển sang trạng thái "Đã hủy";
  stop
endif
@enduml
```

---

## 6. Luồng Bán hàng POS & Tồn kho
```plantuml
@startuml
title Activity Diagram - Bán hàng POS (Chống âm kho)
skinparam maxMessageSize 150

|Thu Ngân|
start
:Tạo Hóa đơn mới (Thêm Hàng hóa/Dịch vụ);
:Nhấn nút "Thanh toán";
|Hệ thống|
:Bắt đầu DB Transaction;
:Lưu Hóa đơn (Invoices);
:Bắt đầu vòng lặp qua từng mặt hàng;
while (Còn mặt hàng trong giỏ?) is (Có)
  :Lấy thông tin Tồn kho & Version hiện tại;
  if (Là Hàng hóa (GOODS)?) then (Có)
    if (Tồn kho >= Số lượng bán?) then (Không)
      :Rollback Transaction;
      :Báo lỗi "Không đủ tồn kho";
      |Thu Ngân|
      stop
    else (Có)
      |Hệ thống|
      :Trừ tồn kho & Tăng Version lên 1\n(Mệnh đề WHERE id=? AND version=?);
      if (Cập nhật thất bại do sai Version?) then (Có)
        :Rollback Transaction;
        :Báo lỗi "Dữ liệu kho thay đổi,\nvui lòng thử lại";
        |Thu Ngân|
        stop
      else (Không)
      endif
    endif
  else (Là Dịch vụ (SERVICE))
    :Không cần xử lý tồn kho;
  endif
  |Hệ thống|
  :Lưu Chi tiết hóa đơn (Invoice Details);
endwhile (Hết)
:Commit Transaction;
:Trả kết quả thành công;
|Thu Ngân|
:Hiển thị UI xuất/in hóa đơn cho khách;
stop
@enduml
```

---

## 7. Luồng Báo cáo Doanh thu
```plantuml
@startuml
title Activity Diagram - Xem Báo cáo Doanh thu
skinparam maxMessageSize 150

|Chủ Sân|
start
:Truy cập module Báo cáo;
:Chọn khoảng thời gian (Từ ngày - Đến ngày);
|Hệ thống|
:Truy vấn Invoices (Trạng thái PAID)\ntrong khoảng thời gian đã chọn;
:Gom nhóm (GROUP BY) tổng doanh thu theo từng ngày;
:Truy vấn Top các Sản phẩm/Dịch vụ bán chạy nhất;
:Chuẩn bị cấu trúc JSON trả về;
:Gửi dữ liệu cho Web Admin;
|Chủ Sân|
:Nhận dữ liệu API;
:Render biểu đồ (Line/Bar Chart) lên giao diện;
stop
@enduml
```
