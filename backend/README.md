# 🏸 Badminton Court Management - Backend

**Spring Boot REST API with JWT Authentication**

[![Java](https://img.shields.io/badge/Java-17-orange.svg)](https://openjdk.org/projects/jdk/17/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2.0-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-15-blue.svg)](https://www.postgresql.org/)
[![JWT](https://img.shields.io/badge/JWT-HS512-red.svg)](https://jwt.io/)
[![Tests](https://img.shields.io/badge/Tests-14%2F14%20PASSED-success.svg)](./test-jwt-comprehensive.sh)

---

## 📋 Mục lục

- [Tổng quan](#-tổng-quan)
- [Tech Stack](#-tech-stack)
- [Quick Start](#-quick-start)
- [API Documentation](#-api-documentation)
- [Authentication](#-authentication)
- [Testing](#-testing)
- [Project Structure](#-project-structure)
- [Team Guide](#-team-guide)

---

## 🎯 Tổng quan

Backend API cho hệ thống quản lý sân cầu lông với các tính năng:

- ✅ **JWT Authentication** - Xác thực người dùng an toàn
- ✅ **Role-Based Access** - 3 roles: ADMIN, STAFF, CUSTOMER
- ✅ **RESTful APIs** - Chuẩn REST với HTTP methods
- ✅ **Database Integration** - PostgreSQL với JPA/Hibernate
- ✅ **Input Validation** - Bean Validation cho tất cả requests
- ✅ **Error Handling** - Global exception handler
- ✅ **API Documentation** - Swagger/OpenAPI
- ✅ **CORS Support** - Ready for frontend integration

---

## 🛠️ Tech Stack

| Technology | Version | Purpose |
|------------|---------|---------|
| Java | 17+ | Programming language |
| Spring Boot | 3.2.0 | Application framework |
| Spring Security | 6.x | Authentication & Authorization |
| Spring Data JPA | 3.2.0 | Database ORM |
| PostgreSQL | 15+ | Database |
| JWT | HS512 | Token-based auth |
| Maven | 3.8+ | Build tool |
| Lombok | Latest | Reduce boilerplate |
| Swagger | 3.x | API documentation |

---

## 🚀 Quick Start

### Prerequisites

```bash
# Check Java version (need 17+)
java -version

# Check Maven version
mvn -version

# PostgreSQL must be running
# Default: localhost:5433
# Database: badminton_court_db
# User: bcm_admin / Password: 12345
```

### Installation

```bash
# 1. Clone repository
git clone <repository-url>
cd Badminton_Court_Management/backend

# 2. Configure database (if needed)
# Edit: src/main/resources/application.yml
# Change: spring.datasource.url, username, password

# 3. Install dependencies
mvn clean install

# 4. Run application
mvn spring-boot:run

# 5. Verify startup
curl http://localhost:8080/api/health
```

### Expected Output

```
========================================
  Badminton Court Management API
  Status: RUNNING
  Port: 8080
  Context Path: /api
  Swagger UI: http://localhost:8080/api/swagger-ui.html
========================================
```

---

## 📚 API Documentation

### Swagger UI

**Interactive API documentation:**
```
http://localhost:8080/api/swagger-ui.html
```

### Base URL

```
http://localhost:8080/api
```

### Quick Test

```bash
# Health check
curl http://localhost:8080/api/health

# Register user
curl -X POST http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{
    "email": "test@example.com",
    "password": "Test@123",
    "fullName": "Test User",
    "phone": "0912345678"
  }'

# Login
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{
    "email": "test@example.com",
    "password": "Test@123"
  }'

# Get current user (need JWT token from login)
curl http://localhost:8080/api/users/me \
  -H "Authorization: Bearer <YOUR_JWT_TOKEN>"
```

---

## 🔐 Authentication

### JWT Flow

```
┌─────────┐                                    ┌─────────┐
│ Client  │                                    │ Backend │
└────┬────┘                                    └────┬────┘
     │                                              │
     │  POST /api/auth/register                    │
     │────────────────────────────────────────────>│
     │                                              │
     │  201 Created                                 │
     │<────────────────────────────────────────────│
     │                                              │
     │  POST /api/auth/login                        │
     │  {email, password}                           │
     │────────────────────────────────────────────>│
     │                                              │
     │  200 OK                                      │
     │  {accessToken, user}                         │
     │<────────────────────────────────────────────│
     │                                              │
     │  GET /api/users/me                           │
     │  Authorization: Bearer <token>               │
     │────────────────────────────────────────────>│
     │                                              │
     │  200 OK                                      │
     │  {user data}                                 │
     │<────────────────────────────────────────────│
     │                                              │
```

### Roles & Permissions

| Endpoint | Public | Customer | Staff | Admin |
|----------|--------|----------|-------|-------|
| `POST /auth/register` | ✅ | ✅ | ✅ | ✅ |
| `POST /auth/login` | ✅ | ✅ | ✅ | ✅ |
| `GET /users/me` | ❌ | ✅ | ✅ | ✅ |
| `GET /courts` | ✅ | ✅ | ✅ | ✅ |
| `POST /courts` | ❌ | ❌ | ❌ | ✅ |
| `POST /bookings` | ❌ | ✅ | ❌ | ✅ |
| `GET /bookings` | ❌ | Own | ✅ | ✅ |

### Default Accounts

```
Admin Account:
  Email:    admin@bcm.com
  Password: admin123
  Role:     ADMIN

⚠️ IMPORTANT: Change password in production!
```

---

## 🧪 Testing

### Automated Tests

```bash
# Basic test (6 scenarios)
bash test-jwt-auth.sh

# Comprehensive test (14 scenarios)
bash test-jwt-comprehensive.sh

# Interactive demo
bash demo-jwt-interactive.sh
```

### Test Results

```
✓ ALL TESTS PASSED! (14/14)

[SECTION 1: Health & Connectivity]
✓ PASS: Health endpoint accessible

[SECTION 2: User Registration]
✓ PASS: Register new user
✓ PASS: Reject duplicate email
✓ PASS: Reject invalid email format
✓ PASS: Reject weak password

[SECTION 3: User Login]
✓ PASS: Login with valid credentials
✓ PASS: Reject wrong password
✓ PASS: Reject non-existent user

[SECTION 4: Protected Endpoints]
✓ PASS: Deny access without token
✓ PASS: Access protected endpoint with valid token
✓ PASS: Deny access with invalid token
✓ PASS: Deny access with malformed header

[SECTION 5: Admin Access]
✓ PASS: Admin login successful
✓ PASS: Admin has ADMIN role
```

### Postman Collection

Import `api-collection.json` for manual testing:

1. Open Postman
2. Import → File → `api-collection.json`
3. Test endpoints with pre-configured requests
4. JWT tokens auto-saved after login

---

## 📁 Project Structure

```
backend/
├── src/main/java/com/bcm/
│   ├── config/                    # Configuration classes
│   │   ├── CorsConfig.java        # CORS policy
│   │   ├── DataSeeder.java        # Database seeding
│   │   ├── SecurityConfig.java    # Spring Security
│   │   └── SwaggerConfig.java     # API docs
│   │
│   ├── controller/                # REST Controllers
│   │   ├── AuthController.java    # /auth/register, /auth/login
│   │   ├── HealthController.java  # /health
│   │   └── UserController.java    # /users/me
│   │
│   ├── dto/                       # Data Transfer Objects
│   │   ├── request/               # Request DTOs
│   │   └── response/              # Response DTOs
│   │
│   ├── entity/                    # JPA Entities
│   │   ├── BaseEntity.java        # Abstract base
│   │   ├── Role.java              # Role entity
│   │   └── User.java              # User entity
│   │
│   ├── exception/                 # Exception handling
│   │   ├── GlobalExceptionHandler.java
│   │   └── Custom exceptions...
│   │
│   ├── repository/                # JPA Repositories
│   │   ├── RoleRepository.java
│   │   └── UserRepository.java
│   │
│   ├── security/                  # Security components
│   │   ├── JwtAuthenticationFilter.java
│   │   ├── JwtUtil.java
│   │   └── UserPrincipal.java
│   │
│   └── service/                   # Business logic
│       ├── AuthService.java
│       └── UserService.java
│
├── src/main/resources/
│   ├── application.yml            # Application config
│   └── application-dev.yml        # Dev profile (optional)
│
├── pom.xml                        # Maven dependencies
│
├── api-collection.json            # Postman collection
├── test-jwt-auth.sh               # Basic tests
├── test-jwt-comprehensive.sh      # Full tests
└── demo-jwt-interactive.sh        # Interactive demo
```

---

## 👥 Team Guide

### For New Team Members

**📖 Start here:**
1. Read this README - Complete backend documentation
2. Run `demo-jwt-interactive.sh` - Interactive demo
3. Import `api-collection.json` to Postman
4. Check Swagger UI for live API docs

### Creating Your Module

**Example: Adding Court Management**

```java
// 1. Create Entity (extend BaseEntity)
@Entity
@Table(name = "courts")
public class Court extends BaseEntity {
    private String courtName;
    private String status;
    // BaseEntity provides: id, createdAt, updatedAt
}

// 2. Create Repository
public interface CourtRepository extends JpaRepository<Court, UUID> {
    List<Court> findByStatus(String status);
}

// 3. Create Service
@Service
public class CourtService {
    // Business logic here
}

// 4. Create Controller with Auth
@RestController
@RequestMapping("/courts")
public class CourtController {
    
    // Public endpoint
    @GetMapping
    public ResponseEntity<ApiResponse<List<Court>>> getAll() {
        return ResponseEntity.ok(ApiResponse.success(courts));
    }
    
    // Admin-only endpoint
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Court>> create(
            @RequestBody @Valid CourtRequest request,
            @AuthenticationPrincipal UserPrincipal admin
    ) {
        // admin.getId() - get current user
        return ResponseEntity.ok(ApiResponse.success(court));
    }
}
```

### Key Patterns

**✅ Always use:**
- `extends BaseEntity` for entities
- `ApiResponse<T>` for responses
- `@Valid` for request validation
- `@AuthenticationPrincipal UserPrincipal` to get current user
- `@PreAuthorize("hasRole('X')")` for role-based access

**❌ Never:**
- Return raw objects (always wrap in ApiResponse)
- Hardcode IDs (use UUID)
- Skip validation
- Expose stack traces in responses

---

## 🔧 Configuration

### Database

Edit `src/main/resources/application.yml`:

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5433/badminton_court_db
    username: bcm_admin
    password: 12345
```

### JWT

```yaml
jwt:
  secret: your-256-bit-secret-key-change-in-production
  expiration: 86400000  # 24 hours in milliseconds
```

### CORS

Edit `src/main/java/com/bcm/config/CorsConfig.java`:

```java
.allowedOrigins(
    "http://localhost:3000",    // React dev
    "http://localhost:5173"     // Vite dev
)
```

---

## 🐛 Troubleshooting

### Port 8080 already in use

```bash
# Windows
netstat -ano | findstr :8080
taskkill /F /PID <PID>

# Linux/Mac
lsof -ti:8080 | xargs kill -9
```

### Database connection failed

```bash
# Check PostgreSQL is running
pg_isready -h localhost -p 5433

# Check database exists
psql -h localhost -p 5433 -U bcm_admin -d badminton_court_db -c "\dt"
```

### JWT token invalid

- Token expired (24h lifetime) → Login again
- Wrong secret key → Check application.yml
- Malformed header → Use `Authorization: Bearer <token>`

---

## 🎯 Development Status

### ✅ Completed (Sprint 1, Week 1)

- [x] Project setup & dependencies
- [x] JWT authentication system
- [x] User registration & login
- [x] Role-based access control
- [x] Database integration & seeding
- [x] Global error handling
- [x] CORS configuration
- [x] Swagger documentation
- [x] Comprehensive testing (14/14 pass)

### 🚧 In Progress (Sprint 1, Week 2)

- [ ] Court management (Team Member 2)
- [ ] TimeSlot management (Team Member 2)
- [ ] Booking system (Team Member 3)
- [ ] Payment & Product (Team Member 4)

### 📅 Planned (Sprint 2+)

- [ ] Invoice & promotion system
- [ ] Notification system
- [ ] Report & statistics
- [ ] Admin dashboard APIs

---

## 🤝 Contributing

### Git Workflow

```bash
# Create feature branch
git checkout -b feature/your-feature-name

# Commit changes
git add .
git commit -m "feat: add court management

- Add Court entity and repository
- Implement CRUD operations
- Add tests"
# Push to remote
git push origin feature/your-feature-name

# Create Pull Request to develop
```

### Code Style

- Follow existing code patterns
- Use Lombok annotations
- Write JavaDoc for complex logic
- Test before committing

---

## 📞 Support

**Issues:**
- Report bugs in GitHub Issues
- Ask questions in team chat

**Contacts:**
- Thành viên 1 (Leader) - JWT Authentication
- Thành viên 2 - Court & TimeSlot
- Thành viên 3 - Booking
- Thành viên 4 - Payment & Product

---

## 📄 License

Internal project for educational purposes.

---

**Generated:** 2026-10-05  
**Version:** 1.0.0  
**Status:** ✅ Production Ready

🏸 **Happy Coding!** 🏸
