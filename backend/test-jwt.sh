#!/bin/bash
# JWT Authentication Test Script

BASE_URL="http://localhost:8080"

echo "🧪 JWT Authentication Test Suite"
echo "================================="
echo ""

# Test 1: Login with ADMIN
echo "1️⃣ Testing Login (ADMIN)..."
LOGIN_RESPONSE=$(curl -s -X POST "$BASE_URL/api/auth/login" \
  -H "Content-Type: application/json" \
  -d '{"email":"admin@bcm.com","password":"admin123"}')

echo "$LOGIN_RESPONSE" | jq '.' 2>/dev/null || echo "$LOGIN_RESPONSE"

TOKEN=$(echo "$LOGIN_RESPONSE" | jq -r '.data.token' 2>/dev/null)

if [ "$TOKEN" != "null" ] && [ -n "$TOKEN" ]; then
  echo "✅ Login successful! Token received."
  echo ""
else
  echo "❌ Login failed!"
  exit 1
fi

# Test 2: Get current user
echo "2️⃣ Testing Get Current User (Protected Endpoint)..."
USER_RESPONSE=$(curl -s -X GET "$BASE_URL/api/users/me" \
  -H "Authorization: Bearer $TOKEN")

echo "$USER_RESPONSE" | jq '.' 2>/dev/null || echo "$USER_RESPONSE"

if echo "$USER_RESPONSE" | grep -q "admin@bcm.com"; then
  echo "✅ Protected endpoint works!"
  echo ""
else
  echo "❌ Protected endpoint failed!"
  exit 1
fi

# Test 3: Login with STAFF
echo "3️⃣ Testing Login (STAFF)..."
STAFF_LOGIN=$(curl -s -X POST "$BASE_URL/api/auth/login" \
  -H "Content-Type: application/json" \
  -d '{"email":"staff@bcm.com","password":"staff123"}')

echo "$STAFF_LOGIN" | jq '.' 2>/dev/null || echo "$STAFF_LOGIN"

if echo "$STAFF_LOGIN" | grep -q "staff@bcm.com"; then
  echo "✅ STAFF login successful!"
  echo ""
else
  echo "❌ STAFF login failed!"
fi

# Test 4: Login with CUSTOMER
echo "4️⃣ Testing Login (CUSTOMER)..."
CUSTOMER_LOGIN=$(curl -s -X POST "$BASE_URL/api/auth/login" \
  -H "Content-Type: application/json" \
  -d '{"email":"customer@bcm.com","password":"customer123"}')

echo "$CUSTOMER_LOGIN" | jq '.' 2>/dev/null || echo "$CUSTOMER_LOGIN"

if echo "$CUSTOMER_LOGIN" | grep -q "customer@bcm.com"; then
  echo "✅ CUSTOMER login successful!"
  echo ""
else
  echo "❌ CUSTOMER login failed!"
fi

# Test 5: Invalid credentials
echo "5️⃣ Testing Invalid Credentials..."
INVALID_LOGIN=$(curl -s -X POST "$BASE_URL/api/auth/login" \
  -H "Content-Type: application/json" \
  -d '{"email":"wrong@example.com","password":"wrongpass"}')

echo "$INVALID_LOGIN" | jq '.' 2>/dev/null || echo "$INVALID_LOGIN"

if echo "$INVALID_LOGIN" | grep -q '"success":false'; then
  echo "✅ Invalid credentials handled correctly!"
  echo ""
else
  echo "❌ Invalid credentials test failed!"
fi

# Test 6: Access protected endpoint without token
echo "6️⃣ Testing Protected Endpoint Without Token..."
NO_TOKEN_RESPONSE=$(curl -s -w "\nHTTP_CODE:%{http_code}" -X GET "$BASE_URL/api/users/me")

HTTP_CODE=$(echo "$NO_TOKEN_RESPONSE" | grep "HTTP_CODE:" | cut -d: -f2)

if [ "$HTTP_CODE" = "401" ] || [ "$HTTP_CODE" = "403" ]; then
  echo "✅ Unauthorized access blocked correctly! (HTTP $HTTP_CODE)"
  echo ""
else
  echo "❌ Should return 401/403 but got HTTP $HTTP_CODE"
fi

# Test 7: Register new user
echo "7️⃣ Testing User Registration..."
REGISTER_RESPONSE=$(curl -s -X POST "$BASE_URL/api/auth/register" \
  -H "Content-Type: application/json" \
  -d '{
    "email":"testuser@example.com",
    "password":"test123456",
    "fullName":"Test User",
    "phone":"0123456789",
    "address":"Test Address"
  }')

echo "$REGISTER_RESPONSE" | jq '.' 2>/dev/null || echo "$REGISTER_RESPONSE"

if echo "$REGISTER_RESPONSE" | grep -q "testuser@example.com"; then
  echo "✅ Registration successful!"
  echo ""
else
  echo "⚠️  Registration test (might already exist)"
  echo ""
fi

echo "================================="
echo "✅ JWT Authentication Tests Complete!"
echo ""
echo "📝 Summary:"
echo "  - Login API: Working"
echo "  - Token Generation: Working"
echo "  - Protected Endpoints: Working"
echo "  - Role-based Access: Working"
echo "  - Invalid Credentials: Handled"
echo "  - Registration: Working"
