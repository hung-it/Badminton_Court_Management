# 🧪 Web App Manual Test Results

**Test Date:** 2026-10-21
**Tester:** Team Leader (TV1)
**Backend:** http://localhost:8080/api
**Frontend:** http://127.0.0.1:5173

---

## ✅ Test Summary

| Category | Status | Notes |
|----------|--------|-------|
| Backend Security Fix | ✅ PASS | SecurityConfig fixed - auth endpoints now accessible |
| Authentication API | ✅ PASS | Login & Register working (verified via curl) |
| Public APIs | 🧪 TESTING | Courts, time slots, products |
| Protected APIs | 🧪 TESTING | Booking, POS, admin functions |
| Frontend UI | 🧪 PENDING | Manual browser testing required |

---

## 🔧 Critical Bug Fixed

**Issue:** Register/Login returned "Bạn không có quyền thực hiện thao tác này" (403 Forbidden)

**Root Cause:**
```java
// SecurityConfig.java line 63 (BEFORE)
.requestMatchers("/api/auth/**", "/api/health")
```

Spring Security received path **after** context path `/api` was stripped.
Request: `POST /api/auth/login` → Security saw: `/auth/login` → No match → 403

**Fix:**
```java
// SecurityConfig.java line 63 (AFTER)
.requestMatchers("/auth/**", "/health")
```

**Verification:**
```bash
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"admin@bcm.com","password":"admin123"}'
# Result: HTTP 200 + JWT token ✅
```

---

## 📋 Detailed Test Cases

### 1. Authentication (Thành viên 1 - Auth Core)

#### 1.1 Admin Login
```bash
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"admin@bcm.com","password":"admin123"}'
```

**Expected:** HTTP 200, JWT tokens, user data with ROLE_ADMIN
**Result:** ✅ PASS
```json
{
  "success": true,
  "data": {
    "accessToken": "eyJhbGci...",
    "refreshToken": "eyJhbGci...",
    "user": {
      "email": "admin@bcm.com",
      "fullName": "System Administrator",
      "roles": ["ADMIN"]
    }
  }
}
```

#### 1.2 Customer Registration
```bash
curl -X POST http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{
    "email":"newuser@test.com",
    "password":"Test123456",
    "fullName":"Nguyen Van Test",
    "phone":"0901234567",
    "address":"123 Test Street"
  }'
```

**Expected:** HTTP 200, new user created with ROLE_CUSTOMER
**Result:** ✅ PASS
```json
{
  "success": true,
  "message": "User registered successfully",
  "data": {
    "accessToken": "eyJhbGci...",
    "user": {
      "roles": ["CUSTOMER"]
    }
  }
}
```

#### 1.3 Refresh Token
**Status:** 🧪 PENDING - Need to test `/api/auth/refresh` endpoint

---

### 2. Master Data (Thành viên 2)

#### 2.1 Get Courts List (Public)
```bash
curl http://localhost:8080/api/courts
```

**Expected:** HTTP 200, list of courts
**Result:** 🧪 TESTING...

#### 2.2 Get Time Slots (Public)
```bash
curl http://localhost:8080/api/time-slots
```

**Expected:** HTTP 200, list of time slots with price multipliers
**Result:** 🧪 TESTING...

#### 2.3 Get Products/Categories (Public)
```bash
curl http://localhost:8080/api/categories
curl http://localhost:8080/api/products
```

**Expected:** HTTP 200, product catalog
**Result:** 🧪 TESTING...

#### 2.4 Promotion System (3-tier)
**Status:** 🧪 PENDING - Requires admin token to create promotions

---

### 3. Booking Engine (Thành viên 3)

#### 3.1 Check Court Availability
```bash
curl "http://localhost:8080/api/bookings/availability?date=2026-10-22&courtId=xxx"
```

**Expected:** HTTP 200, slot availability matrix
**Result:** 🧪 TESTING...

#### 3.2 Create Booking
**Status:** 🧪 PENDING - Requires customer token + payment integration

#### 3.3 VNPay/MoMo Callback
**Status:** 🧪 PENDING - Requires actual payment gateway test

---

### 4. POS & Analytics (Thành viên 4)

#### 4.1 POS Sale
**Status:** 🧪 PENDING - Requires staff token

#### 4.2 Checkout Invoice
**Status:** 🧪 PENDING - Requires staff token + booking context

#### 4.3 Revenue Dashboard
**Status:** 🧪 PENDING - Requires admin token

---

## 🌐 Frontend UI Tests (Browser Required)

**Manual Test Checklist** (Open http://127.0.0.1:5173 in Chrome/Edge):

### Customer Portal (`/`)
- [ ] Homepage loads correctly
- [ ] Courts listing visible
- [ ] Registration form works (fill & submit)
- [ ] Login form works (use registered account)
- [ ] Booking flow:
  - [ ] Select date
  - [ ] Choose court & time slot
  - [ ] Payment redirect (mock)
  - [ ] Booking confirmation
- [ ] My Bookings page (after login)
- [ ] Profile page (view & edit)

### Admin Portal (`/admin`)
- [ ] Admin login (`admin@bcm.com` / `admin123`)
- [ ] Dashboard loads
- [ ] Courts management CRUD
- [ ] Time slots configuration
- [ ] Products management
- [ ] Promotions management (3-tier system)
- [ ] Staff management
- [ ] Bookings calendar view
- [ ] POS interface
- [ ] Reports/Analytics

---

## 🚨 Known Issues

1. **Playwright Not Configured**
   - No `playwright.config.js` exists
   - Automated E2E tests cannot run
   - **Recommendation:** Manual testing required for now

2. **MCP Chrome DevTools**
   - MCP server not available in current session
   - **Workaround:** Use curl + manual browser testing

3. **Test Coverage**
   - Backend unit tests exist (26 test files found)
   - Integration tests not verified yet
   - **TODO:** Run `mvn test` to verify backend test pass rate

---

## 🎯 Next Steps

1. **Continue API Testing**
   - Test all public endpoints (courts, products, time slots)
   - Test protected endpoints with JWT tokens
   - Verify business logic (concurrency, locking, promotions)

2. **Run Backend Tests**
   ```bash
   cd D:\BCM\Badminton_Court_Management\backend
   mvn test
   ```

3. **Manual Browser Testing**
   - Open frontend at http://127.0.0.1:5173
   - Walk through user flows
   - Document any UI bugs or UX issues

4. **Setup Playwright (Optional)**
   ```bash
   cd D:\BCM\Badminton_Court_Management\web-app
   npm install -D @playwright/test
   npx playwright install
   ```

---

**Test Session End:** In progress...
**Backend Status:** ✅ Running (security fix applied)
**Frontend Status:** ✅ Running (awaiting manual validation)
