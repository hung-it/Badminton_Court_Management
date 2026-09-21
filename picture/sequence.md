# Sơ đồ Sequence (Tuần tự) - Hệ thống Đặt Sân Cầu Lông (Bản Đầy Đủ)

Dựa trên yêu cầu của bạn, mình đã mở rộng sơ đồ Sequence để bao phủ toàn bộ vòng đời của hệ thống. Dưới đây là **7 luồng nghiệp vụ cốt lõi**, trải dài từ lúc Khách hàng đăng ký cho đến khi Chủ sân xem báo cáo cuối tháng.

## Hướng dẫn xem hình
Copy từng đoạn code bên dưới và dán vào **[PlantText](https://www.planttext.com/)** để render thành ảnh.

---

## 1. Luồng Đăng ký Tài khoản (Registration Flow)

```plantuml
@startuml
title Luồng Đăng ký Tài khoản (Registration Flow)
autonumber
skinparam maxMessageSize 150

actor "Người dùng" as User
participant "Mobile App" as UI
participant "Backend API" as API
database "Database" as DB

User -> UI : Nhập Thông tin (Tên, Email, Mật khẩu, SĐT)
UI -> API : POST /api/v1/auth/register
activate API

API -> DB : SELECT id FROM users WHERE email = ?
activate DB
DB --> API : Result (Có tồn tại hay không)
deactivate DB

alt [Email đã tồn tại]
    API --> UI : 409 Conflict\n{error: "Email đã được sử dụng"}
    UI --> User : Hiển thị lỗi báo trùng Email
else [Email hợp lệ]
    API -> API : Hash Password (Bcrypt)
    
    API -> DB : BEGIN TRANSACTION
    API -> DB : INSERT INTO users (email, password, full_name, phone)\nRETURNING id
    DB --> API : user_id
    
    API -> DB : Lấy role_id của 'CUSTOMER'\nSELECT id FROM roles WHERE role_name = 'CUSTOMER'
    DB --> API : role_id
    
    API -> DB : INSERT INTO user_roles (user_id, role_id)
    DB --> API : 1 row inserted
    API -> DB : COMMIT
    
    API --> UI : 201 Created\n{message: "Success"}
    UI --> User : Thông báo Đăng ký thành công,\nChuyển sang màn hình Đăng nhập
end
@enduml
```

---

## 2. Luồng Đăng nhập & Xác thực (Login Flow)

```plantuml
@startuml
title Luồng Đăng nhập & Xác thực (Login Flow)
autonumber
skinparam maxMessageSize 150

actor "Người dùng" as User
participant "Mobile App / Web UI" as UI
participant "Backend API\n(Spring Boot)" as API
database "Database\n(PostgreSQL)" as DB

User -> UI : Nhập Email & Password
User -> UI : Nhấn "Đăng nhập"

UI -> API : POST /api/v1/auth/login\n{email, password}
activate API

API -> DB : SELECT * FROM users\nWHERE email = ? AND deleted_at IS NULL
activate DB
DB --> API : User record (hash password)
deactivate DB

API -> API : Verify Password Hash

alt [Password không khớp hoặc Không tìm thấy User]
    API --> UI : 401 Unauthorized\n{error: "Invalid credentials"}
    UI --> User : Hiển thị lỗi "Sai email hoặc mật khẩu"
else [Password khớp]
    API -> API : Generate JWT Token
    API --> UI : 200 OK\n{token, userId, role}
    UI -> UI : Lưu JWT vào Secure Storage
    UI --> User : Đăng nhập thành công, chuyển hướng trang chủ
end

deactivate API
@enduml
```

---

## 3. Luồng Quản lý Danh mục Sân (Manage Courts Flow - Chủ sân)

```plantuml
@startuml
title Luồng Quản lý Sân (Admin/Owner Manage Courts)
autonumber
skinparam maxMessageSize 150

actor "Chủ sân" as Admin
participant "Web Admin" as UI
participant "Backend API" as API
database "Database" as DB

== Thêm sân mới ==
Admin -> UI : Điền thông tin sân (Tên, Loại, Giá)\nNhấn "Thêm mới"
UI -> API : POST /api/v1/courts\n{name, type, base_price}
activate API

API -> DB : INSERT INTO courts (name, type, base_price)\nRETURNING id
activate DB
DB --> API : court_id
deactivate DB

API --> UI : 201 Created + Data
UI --> Admin : Hiển thị Sân mới lên danh sách

== Chỉnh sửa giá sân ==
Admin -> UI : Chọn Sân -> Đổi giá -> Nhấn "Lưu"
UI -> API : PUT /api/v1/courts/{court_id}\n{base_price}
activate API

API -> DB : UPDATE courts SET base_price = ?\nWHERE id = ?
activate DB
DB --> API : Updated 1 row
deactivate DB

API --> UI : 200 OK
UI --> Admin : Cập nhật UI báo thành công

== Xóa sân (Soft Delete) ==
Admin -> UI : Chọn Sân -> Nhấn "Xóa"
UI -> API : DELETE /api/v1/courts/{court_id}
activate API

API -> DB : UPDATE courts SET deleted_at = NOW()\nWHERE id = ?
activate DB
DB --> API : Updated 1 row
deactivate DB

API --> UI : 204 No Content
UI --> Admin : Ẩn sân khỏi danh sách hiện tại
@enduml
```

---

## 4. Luồng Tìm kiếm & Đặt sân (Booking Flow - Chống trùng lịch)

```plantuml
@startuml
title Luồng Tìm kiếm & Đặt sân (Booking Flow)
autonumber
skinparam maxMessageSize 150

actor "Khách hàng" as User
participant "Mobile App" as UI
participant "Backend API\n(Spring Boot)" as API
database "Database\n(PostgreSQL)" as DB

== 1. Xem lịch trống ==
User -> UI : Chọn Ngày & Sân cầu lông
UI -> API : GET /api/v1/courts/{id}/slots?date=YYYY-MM-DD
activate API

API -> DB : SELECT time_slot_id FROM booking_details\nWHERE court_id = ? AND booking_date = ?
activate DB
DB --> API : Danh sách các ca đã có người đặt
deactivate DB

API -> API : Tính toán các ca còn trống
API --> UI : 200 OK + Danh sách ca trống
deactivate API

UI --> User : Hiển thị lưới lịch (Time-grid)

== 2. Tiến hành đặt sân ==
User -> UI : Chọn ca trống & Nhấn "Đặt sân"
UI -> API : POST /api/v1/bookings\n{courtId, timeSlotId, bookingDate}
activate API

API -> DB : BEGIN TRANSACTION
activate DB

API -> DB : SELECT base_price FROM courts WHERE id = ?
DB --> API : base_price

API -> DB : INSERT INTO bookings (user_id, status)\nRETURNING id
DB --> API : booking_id

API -> DB : INSERT INTO booking_details\n(booking_id, court_id, time_slot_id, booking_date, price)
note right of DB
  Trigger Unique Index DB:
  (court_id, time_slot_id, booking_date)
end note

alt [Xảy ra trùng lịch (Khách khác đã nhanh tay đặt trước)]
    DB --> API : Unique Constraint Violation Exception
    API -> DB : ROLLBACK TRANSACTION
    API --> UI : 409 Conflict\n{error: "Ca này đã có người đặt, vui lòng thử lại"}
    UI --> User : Thông báo lỗi "Trùng lịch"
else [Thành công]
    DB --> API : 1 row inserted
    API -> DB : COMMIT TRANSACTION
    deactivate DB
    API --> UI : 201 Created\n{booking_id, status: PENDING}
    UI --> User : Thông báo "Đặt sân thành công"
end

deactivate API
@enduml
```

---

## 5. Luồng Hủy lịch Đặt Sân (Cancel Booking Flow)

Giới thiệu kĩ thuật xóa Detail để giải phóng Slot cho người khác đặt.

```plantuml
@startuml
title Luồng Hủy lịch Đặt Sân (Cancel Booking Flow)
autonumber
skinparam maxMessageSize 150

actor "Khách hàng" as User
participant "Mobile App" as UI
participant "Backend API" as API
database "Database" as DB

User -> UI : Chọn Booking -> Nhấn "Hủy lịch"
UI -> API : POST /api/v1/bookings/{booking_id}/cancel
activate API

API -> DB : SELECT status FROM bookings WHERE id = ?
activate DB
DB --> API : status = 'PENDING'
deactivate DB

alt [Trạng thái không phải PENDING (Đã check-in/thanh toán)]
    API --> UI : 400 Bad Request\n{error: "Chỉ được hủy lịch chưa sử dụng"}
else [Trạng thái hợp lệ]
    API -> DB : BEGIN TRANSACTION
    activate DB
    
    API -> DB : UPDATE bookings SET status = 'CANCELLED' WHERE id = ?
    DB --> API : Updated
    
    API -> DB : DELETE FROM booking_details WHERE booking_id = ?
    note right of DB: QUAN TRỌNG: Phải xóa detail \nđể nhả lại slot (Giải phóng Unique Index)\ncho khách khác đặt.
    DB --> API : Deleted
    
    API -> DB : COMMIT
    deactivate DB
    
    API --> UI : 200 OK
    UI --> User : Cập nhật UI thành "Đã hủy"
end
@enduml
```

---

## 6. Luồng Bán hàng & Dịch vụ POS (Checkout & Optimistic Locking)

```plantuml
@startuml
title Luồng Bán hàng POS & Hàng hóa (Checkout Flow)
autonumber
skinparam maxMessageSize 150

actor "Thu ngân" as Admin
participant "Web Admin" as UI
participant "Backend API\n(Spring Boot)" as API
database "Database\n(PostgreSQL)" as DB

Admin -> UI : Tạo hóa đơn (Thêm Nước suối, Đan lưới...)
Admin -> UI : Nhấn "Thanh toán"
UI -> API : POST /api/v1/invoices\n{booking_id, staff_id, items: [...]}
activate API

API -> DB : BEGIN TRANSACTION
activate DB

API -> DB : INSERT INTO invoices (...)\nRETURNING id
DB --> API : invoice_id

loop [Lặp qua từng item trong items]
    API -> DB : SELECT price, stock_quantity, version, type\nFROM products WHERE id = ?
    DB --> API : Thông tin Product
    
    alt [type == 'GOODS']
        alt [stock_quantity < quantity]
            API -> DB : ROLLBACK TRANSACTION
            API --> UI : 400 Bad Request\n{error: "Sản phẩm không đủ tồn kho"}
        else [Đủ tồn kho]
            API -> DB : UPDATE products \nSET stock_quantity = stock - qty, version = version + 1 \nWHERE id = ? AND version = current_version
            note right of DB: Cơ chế Optimistic Locking\nChặn 2 thu ngân bán cùng lúc
            
            alt [Update Affected Rows == 0 (Vừa có thu ngân khác bán trước)]
                API -> DB : ROLLBACK TRANSACTION
                API --> UI : 409 Conflict\n{error: "Dữ liệu kho thay đổi, vui lòng thử lại"}
            else [Cập nhật kho thành công]
                DB --> API : 1 row updated
            end
        end
    end
    
    API -> DB : INSERT INTO invoice_details\n(invoice_id, product_id, quantity, unit_price, note)
    DB --> API : 1 row inserted
end

API -> DB : COMMIT TRANSACTION
deactivate DB

API --> UI : 201 Created + Invoice Data
UI --> Admin : Hiện hóa đơn để in
deactivate API
@enduml
```

---

## 7. Luồng Báo cáo Doanh thu (Analytics Flow)

```plantuml
@startuml
title Luồng Báo cáo Doanh thu (Analytics Flow)
autonumber
skinparam maxMessageSize 150

actor "Chủ sân" as Admin
participant "Web Admin" as UI
participant "Backend API" as API
database "Database" as DB

Admin -> UI : Truy cập mục Báo cáo\nChọn khoảng ngày (vd: Tháng này)
UI -> API : GET /api/v1/analytics/revenue?start=...&end=...
activate API

API -> DB : SELECT DATE(created_at) as date, \nSUM(total_amount) as revenue\nFROM invoices \nWHERE status = 'PAID' \n  AND created_at BETWEEN ? AND ?\nGROUP BY DATE(created_at)
activate DB
DB --> API : Danh sách (Ngày, Tổng tiền)
deactivate DB

API -> DB : Truy vấn top sản phẩm/dịch vụ bán chạy nhất
activate DB
DB --> API : Danh sách (Tên SP, Số lượng bán)
deactivate DB

API -> API : Format & Cấu trúc dữ liệu để vẽ Chart
API --> UI : 200 OK\n{revenueByDay: [...], topProducts: [...]}
deactivate API

UI -> UI : Render biểu đồ bằng thư viện (vd: Recharts/ChartJS)
UI --> Admin : Hiển thị Dashboard Doanh thu
@enduml
```
