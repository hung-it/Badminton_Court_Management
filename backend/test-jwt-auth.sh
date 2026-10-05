#!/bin/bash

# JWT Authentication Test Script
# Tests the complete JWT flow: register, login, access protected endpoints

BASE_URL="http://localhost:8080/api"
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m'

echo "================================"
echo "JWT Authentication Test Suite"
echo "================================"
echo ""

# Test 1: Health Check
echo -e "${YELLOW}[1/6] Testing Health Endpoint...${NC}"
HEALTH=$(curl -s -w "\n%{http_code}" "$BASE_URL/health")
HTTP_CODE=$(echo "$HEALTH" | tail -n1)
RESPONSE=$(echo "$HEALTH" | sed '$d')

if [ "$HTTP_CODE" = "200" ]; then
    echo -e "${GREEN}✓ Health check passed${NC}"
    echo "Response: $RESPONSE"
else
    echo -e "${RED}✗ Health check failed (HTTP $HTTP_CODE)${NC}"
fi
echo ""

# Test 2: Register New User
echo -e "${YELLOW}[2/6] Testing User Registration...${NC}"
REGISTER=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/auth/register" \
  -H "Content-Type: application/json" \
  -d '{
    "email": "testuser@example.com",
    "password": "Test@123456",
    "fullName": "Test User",
    "phone": "0123456789",
    "address": "123 Test Street"
  }')

HTTP_CODE=$(echo "$REGISTER" | tail -n1)
RESPONSE=$(echo "$REGISTER" | sed '$d')

if [ "$HTTP_CODE" = "200" ] || [ "$HTTP_CODE" = "201" ]; then
    echo -e "${GREEN}✓ Registration successful${NC}"
    echo "Response: $RESPONSE"
else
    echo -e "${RED}✗ Registration failed (HTTP $HTTP_CODE)${NC}"
    echo "Response: $RESPONSE"
fi
echo ""

# Test 3: Login
echo -e "${YELLOW}[3/6] Testing User Login...${NC}"
LOGIN=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/auth/login" \
  -H "Content-Type: application/json" \
  -d '{
    "email": "testuser@example.com",
    "password": "Test@123456"
  }')

HTTP_CODE=$(echo "$LOGIN" | tail -n1)
RESPONSE=$(echo "$LOGIN" | sed '$d')

if [ "$HTTP_CODE" = "200" ]; then
    echo -e "${GREEN}✓ Login successful${NC}"
    TOKEN=$(echo "$RESPONSE" | grep -o '"accessToken":"[^"]*' | cut -d'"' -f4)
    echo "JWT Token: ${TOKEN:0:50}..."
else
    echo -e "${RED}✗ Login failed (HTTP $HTTP_CODE)${NC}"
    echo "Response: $RESPONSE"
    exit 1
fi
echo ""

# Test 4: Access Protected Endpoint WITHOUT Token
echo -e "${YELLOW}[4/6] Testing Protected Endpoint Without Token...${NC}"
NO_AUTH=$(curl -s -w "\n%{http_code}" -X GET "$BASE_URL/users/me")
HTTP_CODE=$(echo "$NO_AUTH" | tail -n1)

if [ "$HTTP_CODE" = "401" ] || [ "$HTTP_CODE" = "403" ]; then
    echo -e "${GREEN}✓ Correctly denied access without token (HTTP $HTTP_CODE)${NC}"
else
    echo -e "${RED}✗ Should have denied access (HTTP $HTTP_CODE)${NC}"
fi
echo ""

# Test 5: Access Protected Endpoint WITH Valid Token
echo -e "${YELLOW}[5/6] Testing Protected Endpoint With Valid Token...${NC}"
WITH_AUTH=$(curl -s -w "\n%{http_code}" -X GET "$BASE_URL/users/me" \
  -H "Authorization: Bearer $TOKEN")

HTTP_CODE=$(echo "$WITH_AUTH" | tail -n1)
RESPONSE=$(echo "$WITH_AUTH" | sed '$d')

if [ "$HTTP_CODE" = "200" ]; then
    echo -e "${GREEN}✓ Successfully accessed protected endpoint${NC}"
    echo "Response: $RESPONSE"
else
    echo -e "${RED}✗ Failed to access protected endpoint (HTTP $HTTP_CODE)${NC}"
    echo "Response: $RESPONSE"
fi
echo ""

# Test 6: Login as Admin
echo -e "${YELLOW}[6/6] Testing Admin Login...${NC}"
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
    echo "Admin Token: ${ADMIN_TOKEN:0:50}..."
else
    echo -e "${RED}✗ Admin login failed (HTTP $HTTP_CODE)${NC}"
    echo "Response: $RESPONSE"
fi
echo ""

echo "================================"
echo "Test Summary"
echo "================================"
echo -e "${GREEN}JWT Authentication tests completed!${NC}"
echo ""
echo "Next steps:"
echo "1. Test with Postman/Insomnia for detailed inspection"
echo "2. Check application logs: tail -f /tmp/backend-jwt.log"
echo "3. Verify database records"
