#!/bin/bash

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

BASE_URL="http://localhost:8080/api"

echo "================================"
echo "JWT Authentication Test Suite"
echo "================================"
echo ""

# Test 1: Health Check
echo -e "${YELLOW}[1/7] Testing Health Endpoint...${NC}"
HEALTH=$(curl -s -w "\n%{http_code}" "$BASE_URL/health")
HTTP_CODE=$(echo "$HEALTH" | tail -n1)
RESPONSE=$(echo "$HEALTH" | sed '$d')

if [ "$HTTP_CODE" = "200" ]; then
    echo -e "${GREEN}✓ Health check passed${NC}"
else
    echo -e "${RED}✗ Health check failed (HTTP $HTTP_CODE)${NC}"
    echo "Response: $RESPONSE"
    exit 1
fi
echo ""

# Test 2: Register User
echo -e "${YELLOW}[2/7] Testing User Registration...${NC}"
TIMESTAMP=$(date +%s)
TEST_EMAIL="testuser${TIMESTAMP}@example.com"

REGISTER=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/auth/register" \
  -H "Content-Type: application/json" \
  -d "{
    \"email\": \"$TEST_EMAIL\",
    \"password\": \"Test123@456\",
    \"fullName\": \"Test User\",
    \"phone\": \"0901234567\"
  }")

HTTP_CODE=$(echo "$REGISTER" | tail -n1)
RESPONSE=$(echo "$REGISTER" | sed '$d')

if [ "$HTTP_CODE" = "200" ] || [ "$HTTP_CODE" = "201" ]; then
    echo -e "${GREEN}✓ Registration successful${NC}"
    echo "Email: $TEST_EMAIL"
else
    echo -e "${RED}✗ Registration failed (HTTP $HTTP_CODE)${NC}"
    echo "Response: $RESPONSE"
    exit 1
fi
echo ""

# Test 3: Login
echo -e "${YELLOW}[3/7] Testing User Login...${NC}"
LOGIN=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/auth/login" \
  -H "Content-Type: application/json" \
  -d "{
    \"email\": \"$TEST_EMAIL\",
    \"password\": \"Test123@456\"
  }")

HTTP_CODE=$(echo "$LOGIN" | tail -n1)
RESPONSE=$(echo "$LOGIN" | sed '$d')

if [ "$HTTP_CODE" = "200" ]; then
    echo -e "${GREEN}✓ Login successful${NC}"
    TOKEN=$(echo "$RESPONSE" | grep -o '"accessToken":"[^"]*' | cut -d'"' -f4)

    if [ -z "$TOKEN" ]; then
        echo -e "${RED}✗ Failed to extract JWT token${NC}"
        echo "Response: $RESPONSE"
        exit 1
    fi

    echo "JWT Token: ${TOKEN:0:80}..."
else
    echo -e "${RED}✗ Login failed (HTTP $HTTP_CODE)${NC}"
    echo "Response: $RESPONSE"
    exit 1
fi
echo ""

# Test 4: Access protected endpoint without token
echo -e "${YELLOW}[4/7] Testing Protected Endpoint Without Token...${NC}"
NO_TOKEN=$(curl -s -w "\n%{http_code}" "$BASE_URL/users/me")
HTTP_CODE=$(echo "$NO_TOKEN" | tail -n1)

if [ "$HTTP_CODE" = "401" ] || [ "$HTTP_CODE" = "403" ]; then
    echo -e "${GREEN}✓ Correctly denied access without token (HTTP $HTTP_CODE)${NC}"
else
    echo -e "${RED}✗ Should have denied access (HTTP $HTTP_CODE)${NC}"
fi
echo ""

# Test 5: Access protected endpoint with valid token
echo -e "${YELLOW}[5/7] Testing Protected Endpoint With Valid Token...${NC}"
WITH_TOKEN=$(curl -s -w "\n%{http_code}" "$BASE_URL/users/me" \
  -H "Authorization: Bearer $TOKEN")

HTTP_CODE=$(echo "$WITH_TOKEN" | tail -n1)
RESPONSE=$(echo "$WITH_TOKEN" | sed '$d')

if [ "$HTTP_CODE" = "200" ]; then
    echo -e "${GREEN}✓ Successfully accessed protected endpoint${NC}"
    echo "User Profile: $(echo "$RESPONSE" | grep -o '"email":"[^"]*' | cut -d'"' -f4)"
else
    echo -e "${RED}✗ Failed to access protected endpoint (HTTP $HTTP_CODE)${NC}"
    echo "Response: $RESPONSE"
fi
echo ""

# Test 6: Login as Admin
echo -e "${YELLOW}[6/7] Testing Admin Login...${NC}"
ADMIN_LOGIN=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/auth/login" \
  -H "Content-Type: application/json" \
  -d '{
    "email": "admin@bcm.com",
    "password": "admin123"
  }')

HTTP_CODE=$(echo "$ADMIN_LOGIN" | tail -n1)
RESPONSE=$(echo "$ADMIN_LOGIN" | sed '$d')

if [ "$HTTP_CODE" = "200" ]; then
    echo -e "${GREEN}✓ Admin login successful${NC}"
    ADMIN_TOKEN=$(echo "$RESPONSE" | grep -o '"accessToken":"[^"]*' | cut -d'"' -f4)
    echo "Admin Token: ${ADMIN_TOKEN:0:80}..."
else
    echo -e "${RED}✗ Admin login failed (HTTP $HTTP_CODE)${NC}"
    echo "Response: $RESPONSE"
fi
echo ""

# Test 7: Refresh Token
echo -e "${YELLOW}[7/7] Testing Token Refresh...${NC}"
REFRESH_TOKEN=$(echo "$RESPONSE" | grep -o '"refreshToken":"[^"]*' | cut -d'"' -f4)

if [ -n "$REFRESH_TOKEN" ]; then
    REFRESH=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/auth/refresh" \
      -H "Content-Type: application/json" \
      -d "{
        \"refreshToken\": \"$REFRESH_TOKEN\"
      }")

    HTTP_CODE=$(echo "$REFRESH" | tail -n1)
    RESPONSE=$(echo "$REFRESH" | sed '$d')

    if [ "$HTTP_CODE" = "200" ]; then
        echo -e "${GREEN}✓ Token refresh successful${NC}"
        NEW_TOKEN=$(echo "$RESPONSE" | grep -o '"accessToken":"[^"]*' | cut -d'"' -f4)
        echo "New Token: ${NEW_TOKEN:0:80}..."
    else
        echo -e "${RED}✗ Token refresh failed (HTTP $HTTP_CODE)${NC}"
        echo "Response: $RESPONSE"
    fi
else
    echo -e "${YELLOW}⚠ Skipped (no refresh token from previous step)${NC}"
fi
echo ""

echo "================================"
echo "Test Summary"
echo "================================"
echo -e "${GREEN}JWT Authentication tests completed!${NC}"
echo ""
echo "Next steps:"
echo "1. Test with Postman/Insomnia for detailed inspection"
echo "2. Check Swagger UI: http://localhost:8080/api/swagger-ui.html"
echo "3. Check application logs: tail -f /tmp/backend-jwt.log"
echo "4. Verify database records in PostgreSQL"
