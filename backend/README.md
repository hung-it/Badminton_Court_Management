# Backend - Badminton Court Management System

Spring Boot REST API cho hệ thống quản lý sân cầu lông.

## 🚀 Quick Start

### Prerequisites
- Java 21 (JDK 21.0.12.1 hoặc cao hơn)
- Maven 3.9+
- Docker Desktop (cho PostgreSQL)

### 1. Start Database
```bash
cd ..
docker-compose up -d
```

Verify database:
```bash
docker exec bcm-postgres psql -U bcm_admin -d badminton_court_db -c "\dt"
# Expected: 20 tables
```

### 2. Set JAVA_HOME (Windows)
```bash
# Bash
export JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot"
export PATH="$JAVA_HOME/bin:$PATH"

# Verify
java -version  # Should show: 21.0.12.1
mvn -version   # Should show Java 21
```

### 3. Run Backend
```bash
mvn spring-boot:run
```

Backend sẽ chạy tại: **http://localhost:8080/api**

---

## 📚 API Documentation

### Swagger UI (Interactive)
http://localhost:8080/api/swagger-ui.html

### OpenAPI JSON
http://localhost:8080/api/v3/api-docs

### Health Check
```bash
curl http://localhost:8080/api/health
curl http://localhost:8080/api/health/ping
```

---

## 🏗️ Project Structure

```
backend/
├── src/main/java/com/bcm/
│   ├── BadmintonCourtManagementApplication.java  # Main entry point
│   ├── config/
│   │   ├── CorsConfig.java          # CORS configuration
│   │   ├── SecurityConfig.java      # Spring Security config
│   │   └── SwaggerConfig.java       # API documentation config
│   ├── controller/
│   │   └── HealthController.java    # Health check endpoints
│   ├── dto/
│   │   └── ApiResponse.java         # Standardized API response
│   ├── entity/
│   │   └── BaseEntity.java          # Base class for all entities
│   └── exception/
│       ├── GlobalExceptionHandler.java  # Centralized exception handling
│       ├── ResourceNotFoundException.java
│       ├── BadRequestException.java
│       └── ConflictException.java
│
├── src/main/resources/
│   └── application.yml              # Application configuration
│
├── pom.xml                          # Maven dependencies
└── README.md
```

---

## 🔧 Technology Stack

| Component | Technology | Version |
|-----------|-----------|---------|
| Framework | Spring Boot | 3.2.5 |
| Language | Java | 21 |
| Database | PostgreSQL | 15 |
| ORM | Hibernate/JPA | 6.4.4 |
| Security | Spring Security + JWT | 6.2.4 |
| API Docs | Springdoc OpenAPI | 2.5.0 |
| Build Tool | Maven | 3.9+ |
| Validation | Hibernate Validator | 8.0.1 |
| Database Driver | PostgreSQL JDBC | 42.7.3 |

---

## 📦 Maven Dependencies

### Core Dependencies
- `spring-boot-starter-web` - REST API
- `spring-boot-starter-data-jpa` - Database ORM
- `spring-boot-starter-validation` - Input validation
- `spring-boot-starter-security` - Authentication & Authorization

### Database
- `postgresql` - PostgreSQL JDBC driver

### Documentation
- `springdoc-openapi-starter-webmvc-ui` - Swagger UI + OpenAPI 3

### Development Tools
- `spring-boot-devtools` - Hot reload
- `lombok` - Reduce boilerplate code

---

## ⚙️ Configuration

### Database Connection
`src/main/resources/application.yml`:
```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/badminton_court_db
    username: bcm_admin
    password: 12345
```

### Server Configuration
```yaml
server:
  port: 8080
  servlet:
    context-path: /api
```

### CORS Configuration
Allowed origins: `http://localhost:5173` (Frontend dev server)

---

## 🛡️ Security

### Current Status (Development Mode)
- ✅ Spring Security enabled
- ✅ CORS configured
- ✅ Public endpoints: Swagger UI, Health check
- ⚠️ **Tất cả endpoints đang public** (`permitAll()`)

### TODO (Production)
- [ ] Implement JWT authentication
- [ ] Add role-based authorization (ADMIN, STAFF, CUSTOMER)
- [ ] Secure all business endpoints
- [ ] Add refresh token mechanism
- [ ] Implement rate limiting

---

## 🧪 Testing Endpoints

### Using cURL
```bash
# Health check
curl http://localhost:8080/api/health

# Ping
curl http://localhost:8080/api/health/ping
```

### Using Swagger UI
1. Open http://localhost:8080/api/swagger-ui.html
2. Click "Health Check" section
3. Try out "GET /health" or "GET /health/ping"

---

## 🐛 Troubleshooting

### 1. Port 8080 already in use
```bash
# Windows: Find process using port 8080
netstat -ano | findstr :8080

# Kill the process
taskkill /PID <PID> /F
```

### 2. Database connection failed
```bash
# Check if PostgreSQL container is running
docker ps

# Restart database
docker-compose restart

# Check logs
docker logs bcm-postgres
```

### 3. Maven compile error "Cannot find symbol"
```bash
# Clean and rebuild
mvn clean compile

# If still fails, check Java version
java -version  # Must be Java 21
```

### 4. Lombok not working in IDE
- **IntelliJ IDEA**: Install "Lombok" plugin, Enable annotation processing
- **Eclipse**: Install Lombok from https://projectlombok.org/

---

## 📝 Code Conventions

### Entity Classes
- Extend `BaseEntity` for audit fields (`createdAt`, `updatedAt`)
- Use `@Entity`, `@Table(name = "...")` annotations
- Use Lombok `@Data`, `@NoArgsConstructor`, `@AllArgsConstructor`

### API Response Format
All endpoints return `ApiResponse<T>`:
```json
{
  "success": true,
  "message": "Operation successful",
  "data": { ... },
  "timestamp": "2026-10-03T14:30:00"
}
```

### Exception Handling
- Use custom exceptions: `ResourceNotFoundException`, `BadRequestException`, `ConflictException`
- GlobalExceptionHandler converts to standardized error response

---

## 🚧 Next Steps (Team Development)

### Member 1 (Leader) - Authentication & Authorization
- [ ] Implement JWT token generation
- [ ] Create AuthController (login, register, refresh token)
- [ ] Add UserDetailsService
- [ ] Configure SecurityFilterChain with JWT filter

### Member 2 - Court & Booking
- [ ] Create Court, TimeSlot entities
- [ ] Implement anti-double-booking logic
- [ ] Add Booking, BookingDetail entities
- [ ] Build CRUD APIs

### Member 3 - POS & Promotions
- [ ] Create Product, Category entities
- [ ] Implement 3-tier promotion system
- [ ] Add Invoice, InvoiceDetail entities
- [ ] Build POS APIs

### Member 4 - Inventory & Suppliers
- [ ] Create Supplier, ImportOrder entities
- [ ] Implement stock tracking
- [ ] Add ImportOrderDetail entity
- [ ] Build inventory APIs

---

## 📞 Support

- **Team Lead**: Thành viên 1
- **Documentation**: `docs/` folder
- **Database Schema**: `docs/database.md`
- **Migration Script**: `db/migration.sql`

---

## ✅ Current Status

**Sprint 1 - Foundation (COMPLETED)**
- ✅ Database setup (PostgreSQL + Docker)
- ✅ Spring Boot project structure
- ✅ Base infrastructure classes
- ✅ Swagger/OpenAPI documentation
- ✅ CORS configuration
- ✅ Security skeleton (dev mode)
- ✅ Health check endpoints

**Next Sprint**
- ⬜ JWT Authentication implementation
- ⬜ User/Role entities
- ⬜ Entity classes for all modules
- ⬜ CRUD APIs

---

## 📄 License
MIT License - BCM Development Team
