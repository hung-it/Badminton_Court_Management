# Sơ đồ Class (Lớp) - Hệ thống Đặt Sân Cầu Lông

Tài liệu này chứa mã nguồn PlantUML mô phỏng cấu trúc Lớp (Class Diagram). Sơ đồ này cực kỳ hữu ích khi bạn bắt đầu code Backend (Spring Boot JPA Entities) hoặc Frontend (Models/Interfaces trong React/TypeScript). 

Nó mô tả các thuộc tính (Attributes), kiểu dữ liệu (Data Types), phương thức (Methods) và các mối quan hệ (Associations, Aggregations, Compositions) giữa các thực thể.

## Hướng dẫn xem hình
Copy đoạn code bên dưới và dán vào **[PlantText](https://www.planttext.com/)** để render thành ảnh.

---

## Mã nguồn PlantUML
```plantuml
@startuml
title Class Diagram - Hệ thống Đặt Sân Cầu Lông
skinparam classAttributeIconSize 0
skinparam monochrome false
skinparam linetype ortho

class User {
  - UUID id
  - String fullName
  - String phone
  - String email
  - String password
  - LocalDateTime createdAt
  - LocalDateTime updatedAt
  - LocalDateTime deletedAt
  + register(): boolean
  + login(): String
  + updateProfile(): void
  + softDelete(): void
}

class Role {
  - UUID id
  - String roleName
}

class Court {
  - UUID id
  - String name
  - String type
  - String status
  - BigDecimal basePrice
  - LocalDateTime deletedAt
  + updateBasePrice(BigDecimal price): void
  + updateStatus(String status): void
  + markAsDeleted(): void
}

class TimeSlot {
  - UUID id
  - LocalTime startTime
  - LocalTime endTime
}

class Booking {
  - UUID id
  - String status
  - BigDecimal totalAmount
  - LocalDateTime createdAt
  - LocalDateTime updatedAt
  + calculateTotal(): BigDecimal
  + cancelBooking(): void
  + confirmBooking(): void
}

class BookingDetail {
  - UUID id
  - LocalDate bookingDate
  - BigDecimal price
}

class Category {
  - UUID id
  - String categoryName
}

class Product {
  - UUID id
  - String name
  - String type
  - BigDecimal price
  - Integer stockQuantity
  - Integer version
  - LocalDateTime deletedAt
  + reduceStock(Integer quantity): void
  + increaseStock(Integer quantity): void
  + isAvailable(Integer quantity): boolean
}

class Invoice {
  - UUID id
  - String paymentMethod
  - String status
  - BigDecimal totalAmount
  - LocalDateTime createdAt
  + calculateTotal(): BigDecimal
  + markAsPaid(): void
  + cancelInvoice(): void
}

class InvoiceDetail {
  - UUID id
  - Integer quantity
  - BigDecimal unitPrice
  - String note
  + getSubTotal(): BigDecimal
}

' =======================
' Định nghĩa Relationships (Mối quan hệ)
' =======================

' User & Roles (Many-to-Many)
User "1" -- "*" Role : has >

' User & Bookings (One-to-Many)
User "1" -- "*" Booking : makes >

' User & Invoices (Thu ngân tạo hóa đơn, Khách hàng mua lẻ)
User "1" -- "*" Invoice : creates (Staff) >
User "1" -- "0..*" Invoice : buys (Customer) >

' Bookings & BookingDetails (Composition - Xóa Booking thì xóa luôn Details)
Booking "1" *-- "1..*" BookingDetail : contains >

' BookingDetails & Courts/TimeSlots
Court "1" -- "*" BookingDetail : booked in <
TimeSlot "1" -- "*" BookingDetail : scheduled at <

' Invoices & Bookings (Hóa đơn cho 1 lượt đặt sân)
Booking "1" -- "0..1" Invoice : generates >

' Products & Categories
Category "1" -- "*" Product : contains >

' Invoices & InvoiceDetails (Composition)
Invoice "1" *-- "1..*" InvoiceDetail : includes >

' InvoiceDetails & Products
Product "1" -- "*" InvoiceDetail : sold in <

@enduml
```
