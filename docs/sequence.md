# Sơ đồ Sequence (Tuần tự) - Hệ thống Đặt Sân Cầu Lông (Bản Đồng Bộ V5)

Sơ đồ Sequence mô phỏng chi tiết tương tác tuần tự giữa Người dùng (Actors), Giao diện (Web App / Web Admin), Backend API (Spring Boot) và Cơ sở dữ liệu (PostgreSQL) cùng Cổng thanh toán (VNPay Sandbox).

> **Hướng dẫn xem hình:** Copy từng đoạn mã PlantUML bên dưới và dán vào công cụ xem trực quan như **[PlantText](https://www.planttext.com/)** hoặc **[Mermaid Live Editor](https://mermaid.live/)**.

---

## 1. Luồng Đăng Ký Tài Khoản Khách Hàng (Registration Flow)

```plantuml
@startuml
title Luồng Đăng ký Tài khoản (Registration Flow)
autonumber
skinparam maxMessageSize 150

actor "Khách hàng" as User
participant "Web App" as UI
participant "Backend API" as API
database "PostgreSQL" as DB

User -> UI : Nhập thông tin (Tên, Email, Mật khẩu, SĐT)
UI -> API : POST /api/auth/register\n{fullName, email, password, phone}
activate API

API -> DB : SELECT id FROM users WHERE lower(email) = lower(?)
activate DB
DB --> API : Result (Kiểm tra trùng email)
deactivate DB

alt [Email đã tồn tại]
    API --> UI : 409 Conflict\n{message: "Email đã được sử dụng"}
    UI --> User : Báo lỗi email đã tồn tại
else [Email hợp lệ]
    API -> API : Mã hóa mật khẩu (BCrypt PasswordEncoder)
    
    API -> DB : BEGIN TRANSACTION
    API -> DB : INSERT INTO users (id, email, password_hash, full_name, phone)\nRETURNING id as user_id
    API -> DB : SELECT id FROM roles WHERE role_name = 'CUSTOMER'
    API -> DB : INSERT INTO user_roles (user_id, role_id)
    API -> DB : INSERT INTO customers (id, user_id, full_name, phone)
    API -> DB : COMMIT
    
    API -> API : Sinh Access Token & Refresh Token (JWT)
    API --> UI : 201 Created\n{accessToken, refreshToken, userProfile}
    UI --> User : Thông báo Đăng ký thành công & Tự động đăng nhập
end
deactivate API
@enduml
```

---

## 2. Luồng Đăng Nhập & Xác Thực (Login Flow)

```plantuml
@startuml
title Luồng Đăng nhập & Cấp phát JWT (Login Flow)
autonumber
skinparam maxMessageSize 150

actor "Người dùng\n(Customer/Staff/Admin)" as User
participant "Web App / Admin" as UI
participant "Backend API" as API
database "PostgreSQL" as DB

User -> UI : Nhập Email & Mật khẩu
UI -> API : POST /api/auth/login\n{email, password}
activate API

API -> DB : SELECT u.*, r.role_name FROM users u \nJOIN user_roles ur ON ur.user_id = u.id \nJOIN roles r ON r.id = ur.role_id \nWHERE lower(u.email) = lower(?) AND u.deleted_at IS NULL
activate DB
DB --> API : Thông tin User & Danh sách Roles
deactivate DB

alt [Tài khoản không tồn tại hoặc bị khóa]
    API --> UI : 401 Unauthorized\n{message: "Tài khoản hoặc mật khẩu không chính xác"}
else [Tài khoản hợp lệ]
    API -> API : Đối chiếu mật khẩu (BCrypt.matches)
    alt [Mật khẩu sai]
        API --> UI : 401 Unauthorized
    else [Mật khẩu chính xác]
        API -> API : Tạo cặp JWT (Access Token 15p, Refresh Token 7 ngày)
        API --> UI : 200 OK\n{accessToken, refreshToken, roles, fullName}
        UI -> UI : Lưu token vào Session/Local Storage
        UI --> User : Chuyển hướng vào màn hình làm việc
    end
end
deactivate API
@enduml
```

---

## 3. Luồng Tra Cứu, Đặt Sân & Thanh Toán Online (Booking & VNPay Flow)

```plantuml
@startuml
title Luồng Tra cứu, Đặt sân & Thanh toán Online 100%
autonumber
skinparam maxMessageSize 150

actor "Khách hàng" as User
participant "Web App" as UI
participant "Backend API" as API
database "PostgreSQL" as DB
participant "Cổng TT\n(VNPay Sandbox)" as PaymentGateway

== 1. Xem lịch sân trống ==
User -> UI : Chọn Ngày & Chi nhánh
UI -> API : GET /api/public/availability?date=YYYY-MM-DD
activate API
API -> DB : SELECT c.* FROM courts c WHERE c.status = 'AVAILABLE' AND c.deleted_at IS NULL
API -> DB : SELECT * FROM time_slots WHERE deleted_at IS NULL ORDER BY start_time
API -> DB : SELECT * FROM booking_details WHERE booking_date = ?
DB --> API : Danh sách sân & ca đã được đặt (Occupied Slots)
API --> UI : 200 OK (Ma trận trạng thái: AVAILABLE, HELD, BOOKED, PAST)
deactivate API

== 2. Giữ chỗ & Khởi tạo Đơn đặt sân ==
User -> UI : Chọn ca trống & Nhập liên hệ -> Bấm "Đặt sân"
UI -> API : POST /api/bookings\n{customerId, details: [{courtId, timeSlotId, bookingDate}]}
activate API

API -> DB : BEGIN TRANSACTION
API -> DB : SELECT * FROM courts WHERE id IN (...) FOR UPDATE
note right of DB: Khóa PESSIMISTIC_WRITE các sân\ntheo thứ tự UUID để chống Deadlock
API -> DB : Kiểm tra occupied slots trong booking_details
alt [Có ca đã bị đặt trước]
    API -> DB : ROLLBACK
    API --> UI : 409 Conflict "Khung giờ này đã có người đặt"
else [Tất cả ca đều trống]
    API -> API : Tính court_fee = SUM(base_price * price_multiplier)
    API -> API : Áp dụng tự động giảm giá hóa đơn (BookingPromotionService)
    API -> DB : INSERT INTO bookings (id, customer_id, status='PENDING', court_fee, expires_at=now+10m)
    API -> DB : INSERT INTO booking_details (booking_id, court_id, time_slot_id, price)
    API -> DB : COMMIT (Kiểm tra ràng buộc duy nhất uq_booking_slot)
    API --> UI : 201 Created\n{bookingId, status: 'PENDING', totalAmount, expiresAt}
end
deactivate API

== 3. Khởi tạo Giao dịch Thanh toán VNPay ==
User -> UI : Chọn thanh toán qua VNPay
UI -> API : POST /api/bookings/{id}/payments\n{paymentMethod: 'VNPAY'}
activate API
API -> DB : Khóa booking & Kiểm tra deadline
API -> DB : INSERT INTO payment_transactions (booking_id, payment_method='VNPAY', status='PENDING', amount=finalAmount)
API -> API : Tạo chữ ký HMAC SHA-512 & sinh Payment URL
API --> UI : 201 Created\n{paymentUrl, attemptId}
deactivate API

UI -> PaymentGateway : Chuyển hướng sang VNPay Sandbox
User -> PaymentGateway : Quét mã QR / Nhập thẻ Test & Xác nhận

== 4. Xử lý IPN Webhook từ VNPay ==
PaymentGateway -> API : GET /api/payments/vnpay/ipn?vnp_ResponseCode=00&vnp_TxnRef=...&vnp_SecureHash=...
activate API
API -> API : Kiểm tra chữ ký số (VnPayIpnVerifier)
API -> DB : Khóa booking & Khóa payment_transaction
API -> API : Kiểm tra deadline và cửa sổ ân hạn in-flight (5 phút)
API -> DB : UPDATE payment_transactions SET status='SUCCESS', transaction_id=..., transaction_date=now
API -> DB : UPDATE bookings SET status='PAID'
API -> DB : COMMIT
API --> PaymentGateway : 200 OK\n{"RspCode": "00", "Message": "Confirm Success"}
deactivate API

PaymentGateway --> UI : Trình duyệt chuyển về /payment-result
UI -> API : GET /api/bookings/{id}
API --> UI : 200 OK (status: 'PAID')
UI --> User : "Đặt sân & Thanh toán thành công!"
@enduml
```

---

## 4. Luồng Bán Hàng POS và Kết Toán Trả Sân (Checkout Flow)

```plantuml
@startuml
title Luồng Bán Hàng POS và Kết Toán Trả Sân (Checkout)
autonumber
skinparam maxMessageSize 150

actor "Nhân viên Thu ngân" as Staff
participant "Web Admin" as UI
participant "Backend API" as API
database "PostgreSQL" as DB

== 1. Bán thêm nước uống trong ca chơi ==
Staff -> UI : Nhập mã bookingId & Chọn 2 chai nước
UI -> API : POST /api/pos/sales\n{bookingId, items: [{productId, quantity: 2}], idempotencyKey}
activate API
API -> DB : BEGIN TRANSACTION
API -> DB : SELECT stock_quantity, version, type FROM products WHERE id = ?
alt [type == 'GOODS' và đủ hàng]
    API -> DB : UPDATE products SET stock_quantity = stock - 2, version = version + 1 \nWHERE id = ? AND version = current_version
    note right of DB: Optimistic Locking chống âm kho
    API -> DB : INSERT INTO invoices (booking_id, status='DRAFT', court_fee=0, product_fee=20000)
    API -> DB : INSERT INTO invoice_details (invoice_id, product_id, quantity, unit_price)
    API -> DB : COMMIT
    API --> UI : 200 OK (Hóa đơn tạm DRAFT đã gắn vào booking)
else [Không đủ hàng tồn]
    API -> DB : ROLLBACK
    API --> UI : 400 Bad Request "Kho không đủ số lượng"
end
deactivate API

== 2. Ca chơi kết thúc - Trả sân & Kết toán ==
Staff -> UI : Mở mục "Trả sân", chọn đơn -> Bấm "Kết toán"
UI -> API : GET /api/checkout/{id}/preview
activate API
API -> DB : SELECT b.*, paid_amount FROM bookings b...
API -> DB : SELECT SUM(product_fee) FROM invoices WHERE booking_id = ? AND status='DRAFT'
API --> UI : 200 OK\n{courtFee, productFee, paidCourtAmount, amountDue: 20000}
deactivate API

Staff -> UI : Khách thanh toán 20.000đ tiền mặt\nTích chọn xác nhận đã nhận tiền -> Bấm "Xác nhận & thu tiền"
UI -> API : POST /api/checkout/{id}\n{paymentMethod: 'CASH', paymentConfirmed: true, expectedAmountDue: 20000}
activate API

API -> DB : BEGIN TRANSACTION
API -> DB : Khóa booking PESSIMISTIC_WRITE
API -> DB : INSERT INTO invoices (booking_id, status='PAID', court_fee=b.court_fee, product_fee=20000, total_amount=total)
API -> DB : Chuyển các invoice_details từ các DRAFT cũ sang Invoice mới
API -> DB : UPDATE invoices (các DRAFT cũ) SET status='CANCELLED'
API -> DB : INSERT INTO payment_transactions (invoice_id, amount=20000, payment_method='CASH', status='SUCCESS', source='MANUAL')
API -> DB : UPDATE bookings SET status='COMPLETED'
API -> DB : COMMIT

API --> UI : 200 OK\n{invoiceId, bookingId, status: 'COMPLETED'}
deactivate API
UI --> Staff : Báo kết toán thành công & In hóa đơn tổng hợp
@enduml
```

---

## 5. Luồng Quản Lý Nhập Kho & Duyệt Giá Bán (Inventory Flow)

```plantuml
@startuml
title Luồng Tạo Phiếu Nhập Kho, Duyệt Nhập & Duyệt Giá Niêm Yết
autonumber
skinparam maxMessageSize 150

actor "Nhân viên / Admin" as Staff
participant "Web Admin" as UI
participant "Backend API" as API
database "PostgreSQL" as DB

== 1. Tạo phiếu nhập hàng ==
Staff -> UI : Chọn Nhà cung cấp, Tên SP, Số lượng, Tổng tiền -> Bấm "Tạo phiếu nhập"
UI -> API : POST /api/import-orders\n{supplierId, importDate, details: [{productId, quantity, importPrice}]}
activate API
API -> DB : INSERT INTO import_orders (status='DRAFT', supplier_id, total_amount)\nRETURNING id as import_order_id
API -> DB : INSERT INTO import_order_details (import_order_id, product_id, quantity, import_price)
API --> UI : 201 Created (Phiếu nhập DRAFT)
deactivate API

== 2. Phê duyệt nhập kho (Admin) ==
Staff -> UI : Mở chi tiết phiếu nhập -> Bấm "Duyệt nhập kho"
UI -> API : POST /api/import-orders/{id}/approve
activate API
API -> DB : BEGIN TRANSACTION
API -> DB : SELECT * FROM import_order_details WHERE import_order_id = ?
loop Từng sản phẩm nhập
    API -> DB : UPDATE products SET stock_quantity = stock_quantity + quantity \nWHERE id = ? AND type = 'GOODS'
    note right of DB: Tồn kho thực tế được cộng dồn
end
API -> DB : UPDATE import_orders SET status = 'RECEIVED' WHERE id = ?
API -> DB : COMMIT
API --> UI : 200 OK (Nhập kho thành công)
deactivate API

== 3. Duyệt giá niêm yết bán lẻ cho hàng mới ==
note over Staff,UI: Sản phẩm thuộc danh mục "Hàng nhập kho" có giá bán = 0\nchưa thể bán tại POS cho đến khi được duyệt giá.
Staff -> UI : Vào tab "Hàng hóa", chọn sản phẩm -> Bấm "Duyệt giá niêm yết"
UI -> API : POST /api/admin/products/{id}/approve-price\n{price: 15000, version: currentVersion}
activate API
API -> DB : UPDATE products SET price = 15000, version = version + 1 \nWHERE id = ? AND version = currentVersion
API --> UI : 200 OK (Đã duyệt giá niêm yết)
deactivate API
UI --> Staff : "Sản phẩm đã được duyệt giá và sẵn sàng bán tại POS"
@enduml
```
