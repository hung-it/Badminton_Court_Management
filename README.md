# 🏸 Badminton Court Management System

Hệ thống quản lý sân cầu lông toàn diện với booking online, POS, và quản lý kho.

## 📋 Tổng Quan Dự Án

**Mục tiêu**: Xây dựng hệ thống quản lý sân cầu lông với các tính năng:
- 🎯 Đặt sân online với anti-double-booking
- 💰 POS system với 3-tier promotion
- 📦 Quản lý kho hàng và nhà cung cấp
- 👥 Phân quyền (Admin, Staff, Customer)
- 💳 Tích hợp thanh toán VNPay/MoMo

**Team**: 4 thành viên, Sprint-based development (4 tuần)

---

## 🏗️ Kiến Trúc Hệ Thống

```
Badminton_Court_Management/
├── backend/              # Spring Boot REST API
├── web-app/             # React Customer Portal (TODO)
├── web-admin/           # React Admin Dashboard (TODO)
├── docs/                # Documentation & ERD
├── db/                  # Database migration scripts
└── docker-compose.yml   # PostgreSQL container
```

### Tech Stack

| Layer | Technology |
|-------|-----------|
| Backend | Spring Boot 3.2.5 + Java 21 |
| Frontend | React 18 + Vite (TODO) |
| Database | PostgreSQL 15 |
| Security | Spring Security + JWT |
| API Docs | Swagger/OpenAPI 3 |
| Container | Docker + Docker Compose |

---

## 🚀 Quick Start

### 1️⃣ Prerequisites
```bash
# Check installations
java -version    # Java 21.0.12.1
mvn -version     # Maven 3.9+
docker --version # Docker 20+
```

### 2️⃣ Start Database
```bash
docker-compose up -d

# Verify
docker ps
# Expected: Container 'bcm-postgres' running
```

### 3️⃣ Run Backend
```bash
cd backend
mvn spring-boot:run
```

Backend: http://localhost:8080/api

Swagger UI: http://localhost:8080/api/swagger-ui.html

### 4️⃣ Test Health Check
```bash
curl http://localhost:8080/api/health
```

Expected response:
```json
{
  "success": true,
  "message": "System is healthy",
  "data": {
    "status": "UP",
    "service": "Badminton Court Management API",
    "version": "1.0.0"
  }
}
```

---

## 📊 Database Design

### Statistics
- **18 bảng** với quan hệ rõ ràng
- **ERD diagram** trong `docs/database-erd.png`
- **Migration script** trong `db/migration.sql`

### Key Features
✅ **Anti-corruption mechanisms**
- Chống trùng lịch booking (CHECK constraints)
- Chống âm kho (stock >= 0)
- XOR payment constraint (chỉ 1 phương thức thanh toán)

✅ **Snapshot pricing**
- Giữ lịch sử giá khi booking/invoice được tạo
- Đổi giá hiện tại không ảnh hưởng đơn cũ

✅ **3-tier Promotion System**
- PRODUCT: Giảm giá sản phẩm
- INVOICE_TOTAL: Giảm tổng đơn
- VOUCHER: Mã giảm giá cho khách hàng

### Database Schema
Chi tiết: [docs/database.md](docs/database.md)

---

## 👥 Team Structure

| Member | Module | Responsibilities |
|--------|--------|------------------|
| **Thành viên 1** (Leader) | Foundation & Auth | Spring Boot setup, JWT, User/Role, Security |
| **Thành viên 2** | Court & Booking | Court, TimeSlot, Booking, Anti-double-booking |
| **Thành viên 3** | POS & Promotions | Product, Category, Invoice, Promotion logic |
| **Thành viên 4** | Inventory & Suppliers | Supplier, ImportOrder, Stock tracking |

Workload: **7-10 tasks/member** (cân bằng)

---

## 📅 Sprint Planning

### ✅ Sprint 1 - Foundation (Week 1) - **COMPLETED**
- [x] Database design & ERD
- [x] PostgreSQL setup (Docker)
- [x] Spring Boot project structure
- [x] Base infrastructure (BaseEntity, ApiResponse, Exception Handling)
- [x] Swagger/OpenAPI documentation
- [x] CORS & Security skeleton
- [x] Health check endpoints

### ⬜ Sprint 2 - Core Entities (Week 2) - **IN PROGRESS**
- [ ] JWT Authentication implementation
- [ ] All entity classes (18 entities)
- [ ] Repository layer (JPA)
- [ ] Basic CRUD services

### ⬜ Sprint 3 - Business Logic (Week 3)
- [ ] Anti-double-booking logic
- [ ] 3-tier promotion calculation
- [ ] Stock tracking & alerts
- [ ] Payment webhooks (VNPay/MoMo)

### ⬜ Sprint 4 - Integration & Testing (Week 4)
- [ ] Frontend-backend integration
- [ ] End-to-end testing
- [ ] Performance optimization
- [ ] Deployment preparation

---

## 🔧 Development Workflow

### Git Branches
```
main              # Production-ready code
└── develop       # Integration branch
    ├── feature/foundation-and-auth      (TV1)
    ├── feature/court-and-booking        (TV2)
    ├── feature/pos-and-promotions       (TV3)
    └── feature/inventory-and-suppliers  (TV4)
```

### Work Process
1. Checkout feature branch từ `develop`
2. Code & test locally
3. Commit với message rõ ràng
4. Push lên remote
5. Create PR về `develop`
6. Review & merge

---

## 📚 Documentation

| Document | Purpose | Location |
|----------|---------|----------|
| Database Schema | Thiết kế CSDL | `docs/database.md` |
| ERD Diagram | Sơ đồ quan hệ | `docs/database-erd.png` |
| Migration Script | SQL setup | `db/migration.sql` |
| Works To Do | Task breakdown | `docs/Works to do.md` |
| Backend README | Backend setup | `backend/README.md` |
| API Changelog | Version history | `docs/CHANGELOG.md` |

---

## 🛠️ Backend Infrastructure (Current)

### ✅ Core Components

**1. BaseEntity** - Audit fields cho tất cả entities
```java
@MappedSuperclass
public abstract class BaseEntity {
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
```

**2. ApiResponse<T>** - Standardized API response
```json
{
  "success": true,
  "message": "...",
  "data": { ... },
  "timestamp": "2026-10-03T..."
}
```

**3. GlobalExceptionHandler** - Centralized error handling
- `ResourceNotFoundException` → 404
- `BadRequestException` → 400
- `ConflictException` → 409
- Generic exceptions → 500

**4. Security Configuration**
- CORS: Allow `localhost:5173` (frontend dev server)
- Public endpoints: Swagger, Health check
- Protected endpoints: Business APIs (TODO: JWT)

**5. Swagger/OpenAPI**
- Interactive API testing
- JWT Bearer token support (TODO)
- Request/Response examples

---

## 🧪 Testing

### Manual Testing
```bash
# Health check
curl http://localhost:8080/api/health

# Swagger UI
open http://localhost:8080/api/swagger-ui.html
```

### Automated Testing (TODO)
- [ ] Unit tests (JUnit 5 + Mockito)
- [ ] Integration tests (TestContainers)
- [ ] API tests (REST Assured)

---

## 🐛 Common Issues & Solutions

### 1. Port 8080 already in use
```bash
# Windows
netstat -ano | findstr :8080
taskkill /PID <PID> /F
```

### 2. Database connection failed
```bash
docker-compose restart
docker logs bcm-postgres
```

### 3. Maven compile error
```bash
# Check Java version
java -version  # Must be 21

# Clean rebuild
mvn clean compile
```

### 4. Lombok not working
- IntelliJ: Install "Lombok" plugin + Enable annotation processing
- Eclipse: Install from https://projectlombok.org/

---

## 📞 Resources

- **Swagger UI**: http://localhost:8080/api/swagger-ui.html
- **Database**: `localhost:5432` (user: `bcm_admin`, pass: `12345`)
- **Team Leader**: Thành viên 1
- **Documentation**: `docs/` folder

---

## 🎯 Next Steps

### For Team Lead (Thành viên 1)
1. ✅ Setup backend skeleton - **DONE**
2. ⬜ Push to `develop` branch
3. ⬜ Create feature branch `feature/foundation-and-auth`
4. ⬜ Implement JWT authentication
5. ⬜ Create User, Role entities
6. ⬜ Notify team to start their modules

### For Other Members
1. ⬜ Pull latest `develop` branch
2. ⬜ Create your feature branch
3. ⬜ Start implementing entities using BaseEntity
4. ⬜ Follow ApiResponse format for all endpoints

---

## ✅ Current Status

**Foundation Layer**: 🟢 COMPLETE
- Spring Boot project structure
- Database running (PostgreSQL)
- Base infrastructure classes
- API documentation (Swagger)
- Development-ready environment

**Next Priority**: JWT Authentication & Entity Implementation

---

## 📄 License
MIT License - BCM Development Team © 2026
