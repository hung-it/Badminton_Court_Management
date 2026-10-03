# Sơ đồ Sequence (Tuần tự) - Hệ thống Đặt Sân Cầu Lông (Bản Cập Nhật V4)

Sơ đồ Sequence này đã được thiết kế lại hoàn toàn để bám sát với cấu trúc Database V4 mới nhất (Bao gồm: Tách Khách hàng/Nhân viên, Hệ số giờ, Thanh toán 100% Online, Tách bạch tiền POS và Bổ sung luồng Nhập Kho).

## Hướng dẫn xem hình
Copy từng đoạn code bên dưới và dán vào **[PlantText](https://www.planttext.com/)** để render thành ảnh.

---

## 1. Luồng Đăng ký Tài khoản Khách hàng (Registration Flow)

```plantuml
@startuml
title Luồng Đăng ký Tài khoản (Registration Flow)
autonumber
skinparam maxMessageSize 150

actor "Khách hàng" as User
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
    API -> DB : INSERT INTO users (email, password)\nRETURNING id
    DB --> API : user_id
    
    API -> DB : Lấy role_id của 'CUSTOMER'
    DB --> API : role_id
    
    API -> DB : INSERT INTO user_roles (user_id, role_id)
    
    API -> DB : INSERT INTO customers (user_id, full_name, phone)\nRETURNING id as customer_id
    note right of DB: Lưu thông tin Domain\nvào bảng Khách Hàng riêng
    DB --> API : 1 row inserted
    API -> DB : COMMIT
    
    API --> UI : 201 Created\n{message: "Success"}
    UI --> User : Thông báo Đăng ký thành công
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
participant "App / Web UI" as UI
participant "Backend API" as API
database "Database" as DB

User -> UI : Nhập Email & Password -> Đăng nhập
UI -> API : POST /api/v1/auth/login
activate API

API -> DB : SELECT * FROM users WHERE email = ?
activate DB
DB --> API : User record (hash password)

API -> API : Verify Password Hash

alt [Password không khớp]
    API --> UI : 401 Unauthorized
else [Password khớp]
    API -> DB : SELECT * FROM user_roles WHERE user_id = ?
    DB --> API : List of roles (CUSTOMER, STAFF, ADMIN)
    
    alt [Role là CUSTOMER]
        API -> DB : SELECT * FROM customers WHERE user_id = ?
        DB --> API : Customer details
    else [Role là STAFF / ADMIN]
        API -> DB : SELECT * FROM staffs WHERE user_id = ?
        DB --> API : Staff details (position)
    end
    deactivate DB
    
    API -> API : Generate JWT Token (Chứa Role & customer_id/staff_id)
    API --> UI : 200 OK\n{token, domainInfo}
    UI -> UI : Lưu JWT
    UI --> User : Đăng nhập thành công
end
deactivate API
@enduml
```

---

## 3. Luồng Tìm kiếm, Đặt sân & Thanh toán Online (Booking Flow 100% Upfront)

```plantuml
@startuml
title Luồng Tìm kiếm, Đặt sân & Thanh toán Online 100%
autonumber
skinparam maxMessageSize 150

actor "Khách hàng" as User
participant "Mobile App" as UI
participant "Backend API" as API
database "Database" as DB
participant "Cổng TT\n(VNPay/MoMo)" as PaymentGateway

== 1. Xem lịch trống ==
User -> UI : Chọn Ngày & Sân cầu lông
UI -> API : GET /api/v1/courts/{id}/slots?date=YYYY-MM-DD
activate API
API -> DB : SELECT time_slot_id FROM booking_details WHERE court_id = ? AND booking_date = ?
DB --> API : Danh sách ca đã đặt
API --> UI : 200 OK (Danh sách ca trống)
deactivate API

== 2. Tiến hành đặt sân & Tính giá ==
User -> UI : Chọn ca trống & Nhấn "Đặt sân"
UI -> API : POST /api/v1/bookings
activate API

API -> DB : BEGIN TRANSACTION
API -> DB : SELECT base_price FROM courts WHERE id = ?
DB --> API : base_price
API -> DB : SELECT price_multiplier FROM time_slots WHERE id = ?
DB --> API : price_multiplier

API -> API : Tính court_fee = base_price * price_multiplier

API -> DB : INSERT INTO bookings (customer_id, status: PENDING, court_fee)\nRETURNING booking_id
API -> DB : INSERT INTO booking_details (booking_id, court_id, time_slot_id...)
note right of DB: Unique Index chặn trùng lịch
DB --> API : Success

API -> API : Gọi API Cổng Thanh toán tạo Link (truyền court_fee)
API --> UI : 201 Created\n{payment_url}
UI --> User : Hiển thị Webview / Mã QR Thanh toán

== 3. Khách hàng Thanh toán ==
User -> PaymentGateway : Quét mã QR & Trả tiền
PaymentGateway --> API : Webhook (Giao dịch thành công)
API -> DB : INSERT INTO payment_transactions (booking_id, amount, status: SUCCESS)
API -> DB : UPDATE bookings SET status = 'PAID' WHERE id = ?
API -> DB : COMMIT
API --> UI : Bắn Socket (Thanh toán thành công)
UI --> User : "Đặt sân & Thanh toán thành công"
@enduml
```

---

## 4. Luồng Bán hàng POS & Xuất Hóa đơn (Checkout Flow)

```plantuml
@startuml
title Luồng Bán hàng POS & Hóa đơn (Checkout Flow)
autonumber
skinparam maxMessageSize 150

actor "Thu ngân" as Admin
participant "Web Admin" as UI
participant "Backend API" as API
database "Database" as DB

Admin -> UI : Tạo hóa đơn, gán Booking (nếu có), thêm Nước uống
UI -> API : POST /api/v1/invoices\n{booking_id, staff_id, items: [...]}
activate API

API -> DB : BEGIN TRANSACTION

alt [Có truyền booking_id]
    API -> DB : SELECT court_fee FROM bookings WHERE id = ?
    DB --> API : court_fee (Ví dụ: 150k đã trả trước)
else [Khách vãng lai mua lẻ]
    API -> API : court_fee = 0
end

API -> API : Tính toán product_fee từ các items (Ví dụ: Tiền nước 30k)

API -> DB : INSERT INTO invoices (staff_id, court_fee, product_fee, total_amount)\nRETURNING invoice_id
DB --> API : invoice_id

loop [Duyệt qua từng Product Items]
    API -> DB : SELECT stock_quantity, version, type FROM products WHERE id = ?
    DB --> API : Thông tin SP
    
    alt [type == 'GOODS']
        API -> DB : UPDATE products SET stock_quantity = stock - qty, version = version + 1 \nWHERE id = ? AND version = current_version
        note right of DB: Optimistic Locking chặn âm kho
        alt [Update = 0 rows]
            API -> DB : ROLLBACK
            API --> UI : 409 Conflict "Dữ liệu kho thay đổi"
        end
    end
    API -> DB : INSERT INTO invoice_details
end

API -> DB : COMMIT
API --> UI : 201 Created + Invoice Data
UI --> Admin : Hiện Hóa đơn để In (Tổng: 180k, Đã trả trước: 150k, Cần thu: 30k)
deactivate API
@enduml
```

---

## 5. Luồng Quản lý Nhập Kho (Inventory Receiving Flow - MỚI)

```plantuml
@startuml
title Luồng Tạo Phiếu Nhập Kho (Inventory Receiving)
autonumber
skinparam maxMessageSize 150

actor "Quản lý / Thu ngân" as Staff
participant "Web Admin" as UI
participant "Backend API" as API
database "Database" as DB

Staff -> UI : Chọn Nhà Cung Cấp, Chọn Sản phẩm & Nhập số lượng
Staff -> UI : Nhấn "Nhập Kho"
UI -> API : POST /api/v1/inventory/imports\n{supplier_id, staff_id, items: [...]}
activate API

API -> DB : BEGIN TRANSACTION
API -> DB : INSERT INTO import_orders (supplier_id, staff_id, status='RECEIVED', total_amount)\nRETURNING id
DB --> API : import_order_id

loop [Duyệt qua từng item]
    API -> DB : INSERT INTO import_order_details (import_order_id, product_id, quantity, import_price)
    
    API -> DB : UPDATE products \nSET stock_quantity = stock_quantity + quantity \nWHERE id = ?
    note right of DB: Hàng vật lý được\ncộng trực tiếp vào Kho
    DB --> API : 1 row updated
end

API -> DB : COMMIT
API --> UI : 201 Created
UI --> Staff : "Nhập hàng thành công, Tồn kho đã tăng!"
deactivate API
@enduml
```
