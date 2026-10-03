# 🏸 Hệ thống Đặt sân và Quản lý Câu lạc bộ Cầu lông (Badminton Court Management)

Đây là mã nguồn đồ án Hệ thống quản lý toàn diện dành cho các Câu lạc bộ Cầu lông, bao gồm **Web Application 2 Portals** (Customer Portal + Admin Portal) cho Khách hàng đặt sân và Quản trị viên điều hành.

Hệ thống được thiết kế chặt chẽ ở cấp độ Cơ sở dữ liệu (PostgreSQL) nhằm giải quyết các bài toán thực tế như: **Chống trùng lịch đặt sân (DB Constraint + Pessimistic Lock)**, **Bảo toàn lịch sử giá (Snapshot Price)**, **Kiểm soát chống âm kho (Optimistic Locking)**, và **Hệ thống Khuyến mãi 3 tầng**.

## 🚀 Công nghệ sử dụng (Tech Stack)

### Backend
- **Framework:** Spring Boot 3.2+ (RESTful API, Spring Security, Spring Data JPA)
- **Java Version:** 17+ (LTS)
- **ORM:** Hibernate 6.x (Jakarta Persistence)
- **Validation:** Jakarta Validation (Bean Validation 3.0)
- **Database:** PostgreSQL 14+ (Unique Constraints, Partial Index, Row-level Locking)
- **Payment Gateway:** VNPay/MoMo (Webhook với signature verification + idempotent handling)

### Frontend
- **Web App:** ReactJS 18+ với Vite (Single Page Application)
  - **Customer Portal** (`/`): Trang công khai cho khách hàng đặt sân, xem lịch sử (responsive mobile-first)
  - **Admin Portal** (`/admin`): Trang quản trị nội bộ cho Staff/Manager (POS, báo cáo, quản lý)
  - **Routing:** React Router v6
  - **State Management:** React Context API / Zustand (lightweight)
  - **UI Components:** Tailwind CSS / shadcn/ui
  - **Real-time updates:** Polling (5s) hoặc WebSocket (STOMP) cho POS

## 📂 Cấu trúc Dự án
Dự án được chia thành 2 phân hệ chính + tài liệu thiết kế:

```text
Badminton_Court_Management/
├── backend/          # Mã nguồn API Server (Spring Boot)
├── web-app/          # Mã nguồn Web Application (ReactJS - 2 Portals)
│   ├── src/
│   │   ├── pages/
│   │   │   ├── customer/       # Customer Portal (/, /booking, /my-bookings)
│   │   │   └── admin/          # Admin Portal (/admin/*)
│   │   ├── components/
│   │   ├── services/           # Axios API calls
│   │   └── routes/             # React Router config
│   └── ...
└── picture/          # Chứa toàn bộ tài liệu thiết kế (UML, DB, ERD)
```

## 📚 Tài liệu Thiết kế Hệ thống

Toàn bộ tài liệu phân tích thiết kế được viết dưới định dạng chuẩn UML (PlantUML/Mermaid) để dễ dàng theo dõi và bảo trì. Bạn có thể xem trực tiếp các file trên GitHub hoặc copy code dán vào [PlantText](https://www.planttext.com/):

### 📋 Core Documentation (6 files - BẮT BUỘC ĐỌC)
1. **[⭐ Upgrade Summary](docs/UPGRADE-SUMMARY.md)**: 🎉 **BẮT ĐẦU TỪ ĐÂY** - Tóm tắt nâng cấp database, checklist hoàn thành, next steps.
2. **[📝 CHANGELOG](docs/CHANGELOG.md)**: 🆕 **v1.1.0** - Các thay đổi mới nhất (fixes từ team review).
3. **[Database & ERD](docs/database.md)**: Chi tiết cấu trúc 18 bảng, ERD, constraints, indexes.
4. **[🗄️ Database Migration SQL](docs/database-migration.sql)**: Script DDL hoàn chỉnh để tạo database.
5. **[Promotion System](docs/promotion-flow-advanced.md)**: 🎁 Hệ thống khuyến mãi 3 tầng (PRODUCT/INVOICE_TOTAL/VOUCHER).
6. **[Payment & Invoice Flow](docs/payment-invoice-flow.md)**: Luồng thanh toán (Online booking, Walk-in, NO_SHOW).

### 🔍 Technical Deep Dive (2 files)
7. **[Design Review](docs/design-review-final.md)**: 📋 Review tổng thể, quyết định thiết kế, tradeoffs.
8. **[Technical Considerations](docs/technical-considerations.md)**: ⚠️ Locking strategies, Payment webhook, Jakarta EE migration.

### 📐 UML Diagrams (3 files)
9. **[Use Case](docs/usecase.md)**: Phân quyền 3 Actor (Customer, Staff, Admin).
10. **[Sequence](docs/sequence.md)**: 5 luồng nghiệp vụ cốt lõi.
11. **[Activity](docs/activity.md)**: Điều kiện rẽ nhánh logic.

## 👥 Tổ chức Team & Workflow (Cắt dọc tính năng)
Dự án áp dụng mô hình làm việc **Vertical Slicing** (Mỗi người ôm Full-stack 1 tính năng) và sử dụng **Git Flow rút gọn**:
- Tuyệt đối không commit trực tiếp vào `main`.
- Code được gom về nhánh `develop`.
- Mỗi thành viên tự tạo nhánh tính năng: `feature/<tên-module>`.

| Thành viên | Phụ trách Module Chính | Nhánh (Branch) | Workload |
| :--- | :--- | :--- | :---: |
| **Thành viên 1 (Leader)** | Foundation + Auth Core (JWT, Spring Security) | `feature/foundation-and-auth` | 7/10 |
| **Thành viên 2** | Master Data + **Hệ thống Khuyến mãi 3 tầng** | `feature/master-data-and-promotions` | 7/10 |
| **Thành viên 3** | Booking Engine + User Management | `feature/booking-and-users` | 7/10 |
| **Thành viên 4** | POS + Nhập kho + Báo cáo Doanh thu | `feature/pos-and-analytics` | 7/10 |

📄 **Chi tiết phân công:** Xem file [team_task_allocation.md](../team_task_allocation.md)

## 🛠 Hướng dẫn Cài đặt & Khởi chạy (Local)

### Yêu cầu Hệ thống
- **Java:** JDK 17+ (LTS)
- **Node.js:** v18+ (LTS)
- **PostgreSQL:** 14+
- **Maven:** 3.8+
- **Git:** 2.30+

### Bước 1: Clone Repository
```bash
git clone https://github.com/hung-it/Badminton_Court_Management.git
cd Badminton_Court_Management
```

### Bước 2: Cài đặt Database
```bash
# Tạo database
psql -U postgres
CREATE DATABASE badminton_court_db;

# Import schema migration
\c badminton_court_db
\i docs/database-migration.sql
```

### Bước 3: Khởi chạy Backend
```bash
cd backend

# Cấu hình database (application.properties hoặc application.yml)
# spring.datasource.url=jdbc:postgresql://localhost:5432/badminton_court_db
# spring.datasource.username=postgres
# spring.datasource.password=your_password

# Build và chạy
mvn clean install
mvn spring-boot:run

# Backend chạy tại: http://localhost:8080
```

### Bước 4: Khởi chạy Web App
```bash
cd web-app
npm install
npm run dev

# Web App chạy tại: http://localhost:5173 (Vite)
# Customer Portal: http://localhost:5173/
# Admin Portal: http://localhost:5173/admin
```

---

## ⚠️ Lưu Ý Kỹ Thuật Quan Trọng

### 1. Chống Trùng Lịch Đặt Sân
**Không phải** Optimistic Locking - Sử dụng **DB Constraint + Pessimistic Lock**:
```sql
-- Unique constraint ở DB level
ALTER TABLE booking_details
ADD CONSTRAINT uq_booking_slot 
UNIQUE (booking_date, court_id, time_slot_id);
```

```java
// Pessimistic lock trong JPA
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT c FROM Court c WHERE c.id = :id")
Optional<Court> findByIdForUpdate(@Param("id") UUID id);
```

**Lý do:** Booking conflict cần fail fast ở DB level, không thể retry như inventory.

### 2. Chống Âm Kho (Products)
Sử dụng **Optimistic Locking** với `@Version`:
```java
@Entity
@Table(name = "products")
public class Product {
    @Version
    private Integer version;  // JPA tự động tăng
    private Integer stockQuantity;
}
```

**Lý do:** Conflict ít xảy ra, có thể retry khi bán hàng.

### 3. Spring Boot 3.x - Jakarta EE
⚠️ **Bắt buộc** dùng `jakarta.*` thay vì `javax.*`:
```java
// ✅ ĐÚNG
import jakarta.persistence.*;
import jakarta.validation.constraints.*;

// ❌ SAI (Spring Boot 2.x)
import javax.persistence.*;
```

### 4. Payment Webhook Security
3 tầng bảo vệ bắt buộc:
- ✅ Verify signature từ VNPay/MoMo
- ✅ Idempotent (check duplicate transaction)
- ✅ Timeout handling (cron job expire pending bookings sau 15 phút)

Chi tiết: Xem [technical-considerations.md](picture/technical-considerations.md)

### 5. Real-time Sync cho POS
**Đề xuất cho đồ án:** Polling 5 giây (đơn giản, đủ dùng)
```javascript
// Web App - Admin Portal - CourtAvailability component
setInterval(async () => {
  const res = await fetch('/api/courts/availability');
  setCourts(await res.json());
}, 5000);
```

**Nâng cao:** WebSocket (STOMP) nếu muốn latency < 100ms.

### 6. Hệ thống Khuyến mãi 3 tầng
**3 loại Discount Rules:**
- **PRODUCT:** Giảm giá sản phẩm cụ thể (ví dụ: Nước Aquafina giảm 15%)
- **INVOICE_TOTAL:** Giảm theo tổng hóa đơn (ví dụ: Hóa đơn ≥ 500k giảm 50k)
- **VOUCHER:** Mã giảm giá giới hạn số lần dùng (ví dụ: VIP20 giảm 20%, mỗi khách dùng 1 lần)

Chi tiết: Xem [promotion-flow-advanced.md](picture/promotion-flow-advanced.md)

---

## 🌐 Kiến trúc Web App (2 Portals)

```
┌────────────────────────────────────────────┐
│   Web App (ReactJS + Vite + React Router)  │
├────────────────────────────────────────────┤
│  📱 CUSTOMER PORTAL (/)                    │
│  ├─ /                 Trang chủ            │
│  ├─ /courts           Danh sách sân        │
│  ├─ /booking          Đặt sân (Time-slot)  │
│  ├─ /login            Đăng nhập            │
│  ├─ /register         Đăng ký              │
│  ├─ /my-bookings      Vé của tôi           │
│  └─ /profile          Profile cá nhân      │
├────────────────────────────────────────────┤
│  🖥️ ADMIN PORTAL (/admin)                  │
│  ├─ /admin/login      Đăng nhập staff      │
│  ├─ /admin/dashboard  Tổng quan            │
│  ├─ /admin/courts     Quản lý sân          │
│  ├─ /admin/products   Quản lý sản phẩm     │
│  ├─ /admin/promotions Quản lý khuyến mãi   │
│  ├─ /admin/staff      Quản lý nhân viên    │
│  ├─ /admin/bookings   Calendar view        │
│  ├─ /admin/pos        POS bán hàng         │
│  ├─ /admin/checkout   Check-out trả sân    │
│  └─ /admin/reports    Báo cáo doanh thu    │
└────────────────────────────────────────────┘
         ↓ Axios (REST API)
┌────────────────────────────────────────────┐
│  Backend (Spring Boot 3.2 + PostgreSQL)    │
│  http://localhost:8080/api                 │
└────────────────────────────────────────────┘
```

---

## 📖 Tài Liệu Tham Khảo

### 🎯 Bắt đầu từ đây
- **[⭐ UPGRADE-SUMMARY.md](picture/UPGRADE-SUMMARY.md)**: Tổng quan nâng cấp, checklist, next steps

### 📋 Core Documentation (BẮT BUỘC ĐỌC)
- **[Database Schema](picture/database.md)**: ERD với 18 bảng, constraints, indexes
- **[Promotion System](picture/promotion-flow-advanced.md)**: Hệ thống khuyến mãi 3 tầng với discount rules
- **[Payment & Invoice Flow](picture/payment-invoice-flow.md)**: Luồng nghiệp vụ chi tiết (Online booking → Check-in → Mua hàng)

### 🔍 Technical Deep Dive
- **[Design Review](picture/design-review-final.md)**: Review các quyết định thiết kế và tradeoffs
- **[Technical Considerations](picture/technical-considerations.md)**: ⚠️ **Đọc trước khi code** - Locking strategies, Payment webhook, Jakarta EE

### 📐 UML Diagrams
- **[Use Case](picture/usecase.md)**: Phân quyền 3 Actor (Customer, Staff, Admin)
- **[Sequence](picture/sequence.md)**: 5 luồng nghiệp vụ cốt lõi
- **[Activity](picture/activity.md)**: Điều kiện rẽ nhánh logic

### 👥 Team Management
- **[Team Task Allocation](../team_task_allocation.md)**: Phân công chi tiết 4 thành viên, lộ trình 4 sprints

---

*Đồ án Môn học - Hệ thống Quản lý Sân Cầu Lông (2026).*
