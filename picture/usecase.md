# Sơ đồ Use Case - Hệ thống Đặt Sân Cầu Lông

Dựa trên yêu cầu và hình mẫu của bạn, mình đã thiết kế sơ đồ Use Case bằng ngôn ngữ **PlantUML**. Sơ đồ phân tách rõ 3 Actor (Người dùng) và nhóm các chức năng (Functions) vào từng block (rectangle) giống hệt hình bạn gửi.

## Hướng dẫn xem hình
Vì PlantUML là dạng code sinh ra hình ảnh, bạn hãy copy toàn bộ đoạn code trong khung bên dưới và dán vào 1 trong các trang web sau để xem và tải ảnh PNG về nhé:
1. **[PlantText](https://www.planttext.com/)** (Khuyên dùng - giao diện đẹp)
2. **[PlantUML Web Server](http://www.plantuml.com/plantuml/uml/)**

---

## Mã nguồn PlantUML (Copy đoạn này)

```plantuml
@startuml
left to right direction
skinparam packageStyle rectangle
skinparam monochrome false

actor "Khách hàng\n(User)" as User
note bottom of User
  Sử dụng Mobile App
  Tìm kiếm sân, đặt lịch
  Quản lý lịch sử cá nhân
end note

actor "Admin" as Admin
note bottom of Admin
  Quản lý Web Admin
  Toàn quyền hệ thống
  Quản lý tài khoản, phân quyền
end note

actor "Chủ sân\n(Owner)" as Owner
note bottom of Owner
  Quản lý Web Admin
  Quản lý sân bãi của mình
  Bán hàng POS, xem báo cáo
end note

rectangle "User Functions" {
  usecase "Đăng nhập / Đăng ký" as UC_Login
  usecase "Tìm kiếm sân trống" as UC_SearchCourt
  usecase "Đặt lịch sân" as UC_BookCourt
  usecase "Hủy lịch đặt" as UC_CancelCourt
  usecase "Xem lịch sử đặt sân" as UC_History
  
  UC_BookCourt .> UC_Login : <<includes>>
  UC_BookCourt .> UC_SearchCourt : <<includes>>
  UC_CancelCourt .> UC_BookCourt : <<extends>>
}

rectangle "Admin Functions" {
  usecase "Quản lý Người dùng (Users)" as UC_ManageUsers
  usecase "Phân quyền Hệ thống (Roles)" as UC_ManageRoles
  usecase "Cấu hình chung (System Configs)" as UC_SysConfig
  usecase "Xem Báo cáo Toàn hệ thống" as UC_AdminAnalytics
}

rectangle "Owner Functions (Chủ Sân)" {
  usecase "Quản lý Danh sách Sân" as UC_ManageCourts
  usecase "Thiết lập Giá & Khung giờ" as UC_SetPrice
  usecase "Quản lý Hàng hóa / Dịch vụ" as UC_ManageProducts
  usecase "Theo dõi Lưới lịch (Time-grid)" as UC_ViewGrid
  usecase "Thanh toán POS & Hóa đơn" as UC_ManageInvoices
  usecase "Xem Báo cáo Doanh thu" as UC_OwnerAnalytics
}

' Kết nối Khách hàng
User ---> UC_Login
User ---> UC_SearchCourt
User ---> UC_BookCourt
User ---> UC_CancelCourt
User ---> UC_History

' Kết nối Admin
Admin ---> UC_ManageUsers
Admin ---> UC_ManageRoles
Admin ---> UC_SysConfig
Admin ---> UC_AdminAnalytics

' Kết nối Chủ sân
Owner ---> UC_ManageCourts
Owner ---> UC_SetPrice
Owner ---> UC_ManageProducts
Owner ---> UC_ViewGrid
Owner ---> UC_ManageInvoices
Owner ---> UC_OwnerAnalytics

' Các mối quan hệ chéo (Giống hình mẫu)
UC_AdminAnalytics .> UC_OwnerAnalytics : <<extends>>
UC_ManageInvoices .> UC_BookCourt : <<extends>>

@enduml
```
