# Sơ đồ Use Case - Hệ thống Quản lý Sân Cầu Lông (Bản Cập Nhật V4)

Sơ đồ này đã phân chia rõ 3 nhóm người dùng (Actors) theo đúng thiết kế CSDL mới: Khách Hàng (Customers), Nhân Viên (Staffs) và Chủ Sân (Admins).

> **Lưu ý nghiệp vụ:** Hệ thống **không cho phép khách hàng tự hủy đặt sân (No Cancellation)** sau khi đã thanh toán để đảm bảo doanh thu và lịch vận hành của chủ sân (theo đúng quy tắc không có trạng thái `CANCELLED` trong CSDL).

## Mã nguồn PlantUML

```plantuml
@startuml
title Sơ đồ Use Case - Quản lý Sân Cầu Lông (V4)
left to right direction
skinparam packageStyle rectangle
skinparam actorStyle awesome

actor "Khách Hàng\n(Customer)" as Customer
actor "Nhân Viên\n(Staff)" as Staff
actor "Chủ Sân\n(Admin)" as Admin

' Admin kế thừa quyền của Staff
Admin -|> Staff

rectangle "Hệ thống Quản lý Sân Cầu Lông (BCM)" {
    
    ' Tính năng của Khách hàng
    usecase "Đăng ký / Đăng nhập" as UC1
    usecase "Tìm kiếm Sân trống" as UC2
    usecase "Đặt sân & Thanh toán Online (VNPay)" as UC3
    usecase "Xem lịch sử đặt sân & biên lai" as UC4
    usecase "Xem thông tin cá nhân" as UC5
    
    ' Tính năng của Nhân viên
    usecase "Check-in Sân cho khách" as UC6
    usecase "Bán hàng POS (Nước, Dịch vụ)" as UC7
    usecase "Tạo Hóa đơn (Invoices)" as UC8
    usecase "Tạo Phiếu Nhập Kho" as UC9
    usecase "Quản lý Đơn hàng nhập" as UC10
    
    ' Tính năng của Admin / Chủ sân
    usecase "Quản lý Sân & Giá gốc" as UC11
    usecase "Cấu hình Khung giờ & Hệ số" as UC12
    usecase "Quản lý Hàng hóa & Nhà CC" as UC13
    usecase "Quản lý Nhân sự & Phân quyền" as UC14
    usecase "Xem Báo cáo (Tiền sân & POS)" as UC15
}

' Liên kết Khách Hàng
Customer --> UC1
Customer --> UC2
Customer --> UC3
Customer --> UC4
Customer --> UC5

' Liên kết Nhân Viên
Staff --> UC1
Staff --> UC6
Staff --> UC7
Staff --> UC8
Staff --> UC9
Staff --> UC10

' Liên kết Admin
Admin --> UC11
Admin --> UC12
Admin --> UC13
Admin --> UC14
Admin --> UC15

' Ràng buộc luồng (Includes / Extends)
UC3 .> UC2 : <<include>>
UC7 .> UC8 : <<include>>
UC9 .> UC13 : <<extend>>
@enduml
```
