#!/bin/bash

# Comprehensive JWT Authentication Test Suite
# Tests all authentication scenarios including edge cases

BASE_URL="http://localhost:8080/api"
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

PASS_COUNT=0
FAIL_COUNT=0
TOTAL_TESTS=0

# Helper function to print test result
test_result() {
    TOTAL_TESTS=$((TOTAL_TESTS + 1))
    if [ $1 -eq 0 ]; then
        echo -e "${GREEN}✓ PASS${NC}: $2"
        PASS_COUNT=$((PASS_COUNT + 1))
    else
        echo -e "${RED}✗ FAIL${NC}: $2"
        FAIL_COUNT=$((FAIL_COUNT + 1))
        echo -e "${RED}  Response: $3${NC}"
    fi
}

echo "========================================"
echo "  JWT COMPREHENSIVE TEST SUITE"
echo "========================================"
echo ""

# Generate unique email for this test run
TIMESTAMP=$(date +%s)
TEST_EMAIL="test${TIMESTAMP}@example.com"
TEST_PASSWORD="Test@123456"
TEST_FULLNAME="Test User ${TIMESTAMP: -3}"
TEST_PHONE="0912345${TIMESTAMP: -3}"

echo -e "${BLUE}Test Configuration:${NC}"
echo "  Email: $TEST_EMAIL"
echo "  Full Name: $TEST_FULLNAME"
echo "  Password: $TEST_PASSWORD"
echo "  Phone: $TEST_PHONE"
echo ""

# ============================================================
# 1. HEALTH & CONNECTIVITY TESTS
# ============================================================
echo -e "${YELLOW}[SECTION 1: Health & Connectivity]${NC}"

response=$(curl -s -w "\n%{http_code}" $BASE_URL/health)
http_code=$(echo "$response" | tail -n1)
body=$(echo "$response" | head -n-1)

if [ "$http_code" = "200" ]; then
    test_result 0 "Health endpoint accessible"
else
    test_result 1 "Health endpoint accessible" "$body"
fi

# ============================================================
# 2. REGISTRATION TESTS
# ============================================================
echo ""
echo -e "${YELLOW}[SECTION 2: User Registration]${NC}"

# Test 2.1: Valid registration
response=$(curl -s -w "\n%{http_code}" -X POST $BASE_URL/auth/register \
  -H "Content-Type: application/json" \
  -d "{
    \"email\": \"$TEST_EMAIL\",
    \"password\": \"$TEST_PASSWORD\",
    \"fullName\": \"$TEST_FULLNAME\",
    \"phone\": \"$TEST_PHONE\"
  }")
http_code=$(echo "$response" | tail -n1)
body=$(echo "$response" | head -n-1)

if [ "$http_code" = "201" ]; then
    test_result 0 "Register new user"
else
    test_result 1 "Register new user" "$body"
fi

# Test 2.2: Duplicate email
response=$(curl -s -w "\n%{http_code}" -X POST $BASE_URL/auth/register \
  -H "Content-Type: application/json" \
  -d "{
    \"email\": \"$TEST_EMAIL\",
    \"password\": \"$TEST_PASSWORD\",
    \"fullName\": \"Another User\",
    \"phone\": \"0999999999\"
  }")
http_code=$(echo "$response" | tail -n1)
body=$(echo "$response" | head -n-1)

if [ "$http_code" = "400" ] || [ "$http_code" = "409" ] || [ "$http_code" = "500" ]; then
    test_result 0 "Reject duplicate email"
else
    test_result 1 "Reject duplicate email" "$body"
fi

# Test 2.3: Invalid email format
response=$(curl -s -w "\n%{http_code}" -X POST $BASE_URL/auth/register \
  -H "Content-Type: application/json" \
  -d "{
    \"email\": \"invalid-email\",
    \"password\": \"$TEST_PASSWORD\",
    \"fullName\": \"Invalid User\",
    \"phone\": \"0988888888\"
  }")
http_code=$(echo "$response" | tail -n1)

if [ "$http_code" = "400" ]; then
    test_result 0 "Reject invalid email format"
else
    test_result 1 "Reject invalid email format" "HTTP $http_code"
fi

# Test 2.4: Weak password
response=$(curl -s -w "\n%{http_code}" -X POST $BASE_URL/auth/register \
  -H "Content-Type: application/json" \
  -d "{
    \"email\": \"test2${TIMESTAMP}@example.com\",
    \"password\": \"123\",
    \"fullName\": \"Weak Pass User\",
    \"phone\": \"0977777777\"
  }")
http_code=$(echo "$response" | tail -n1)

if [ "$http_code" = "400" ]; then
    test_result 0 "Reject weak password"
else
    test_result 1 "Reject weak password" "HTTP $http_code"
fi

# ============================================================
# 3. LOGIN TESTS
# ============================================================
echo ""
echo -e "${YELLOW}[SECTION 3: User Login]${NC}"

# Test 3.1: Valid login
response=$(curl -s -w "\n%{http_code}" -X POST $BASE_URL/auth/login \
  -H "Content-Type: application/json" \
  -d "{
    \"email\": \"$TEST_EMAIL\",
    \"password\": \"$TEST_PASSWORD\"
  }")
http_code=$(echo "$response" | tail -n1)
body=$(echo "$response" | head -n-1)

if [ "$http_code" = "200" ]; then
    USER_TOKEN=$(echo "$body" | grep -o '"accessToken":"[^"]*' | cut -d'"' -f4)
    test_result 0 "Login with valid credentials"
else
    test_result 1 "Login with valid credentials" "$body"
    exit 1
fi

# Test 3.2: Invalid password
response=$(curl -s -w "\n%{http_code}" -X POST $BASE_URL/auth/login \
  -H "Content-Type: application/json" \
  -d "{
    \"email\": \"$TEST_EMAIL\",
    \"password\": \"WrongPassword@123\"
  }")
http_code=$(echo "$response" | tail -n1)

if [ "$http_code" = "401" ]; then
    test_result 0 "Reject wrong password"
else
    test_result 1 "Reject wrong password" "HTTP $http_code"
fi

# Test 3.3: Non-existent user
response=$(curl -s -w "\n%{http_code}" -X POST $BASE_URL/auth/login \
  -H "Content-Type: application/json" \
  -d "{
    \"email\": \"nonexistent@example.com\",
    \"password\": \"$TEST_PASSWORD\"
  }")
http_code=$(echo "$response" | tail -n1)

if [ "$http_code" = "401" ]; then
    test_result 0 "Reject non-existent user"
else
    test_result 1 "Reject non-existent user" "HTTP $http_code"
fi

# ============================================================
# 4. PROTECTED ENDPOINT TESTS
# ============================================================
echo ""
echo -e "${YELLOW}[SECTION 4: Protected Endpoints]${NC}"

# Test 4.1: Access without token
response=$(curl -s -w "\n%{http_code}" $BASE_URL/users/me)
http_code=$(echo "$response" | tail -n1)

if [ "$http_code" = "403" ]; then
    test_result 0 "Deny access without token"
else
    test_result 1 "Deny access without token" "HTTP $http_code"
fi

# Test 4.2: Access with valid token
response=$(curl -s -w "\n%{http_code}" $BASE_URL/users/me \
  -H "Authorization: Bearer $USER_TOKEN")
http_code=$(echo "$response" | tail -n1)
body=$(echo "$response" | head -n-1)

if [ "$http_code" = "200" ]; then
    returned_email=$(echo "$body" | grep -o '"email":"[^"]*' | cut -d'"' -f4)
    if [ "$returned_email" = "$TEST_EMAIL" ]; then
        test_result 0 "Access protected endpoint with valid token"
    else
        test_result 1 "Access protected endpoint with valid token" "Email mismatch"
    fi
else
    test_result 1 "Access protected endpoint with valid token" "$body"
fi

# Test 4.3: Access with invalid token
response=$(curl -s -w "\n%{http_code}" $BASE_URL/users/me \
  -H "Authorization: Bearer invalid.token.here")
http_code=$(echo "$response" | tail -n1)

if [ "$http_code" = "403" ]; then
    test_result 0 "Deny access with invalid token"
else
    test_result 1 "Deny access with invalid token" "HTTP $http_code"
fi

# Test 4.4: Access with malformed Authorization header
response=$(curl -s -w "\n%{http_code}" $BASE_URL/users/me \
  -H "Authorization: $USER_TOKEN")
http_code=$(echo "$response" | tail -n1)

if [ "$http_code" = "403" ]; then
    test_result 0 "Deny access with malformed header"
else
    test_result 1 "Deny access with malformed header" "HTTP $http_code"
fi

# ============================================================
# 5. ADMIN ACCESS TESTS
# ============================================================
echo ""
echo -e "${YELLOW}[SECTION 5: Admin Access]${NC}"

# Test 5.1: Admin login
response=$(curl -s -w "\n%{http_code}" -X POST $BASE_URL/auth/login \
  -H "Content-Type: application/json" \
  -d "{
    \"email\": \"admin@bcm.com\",
    \"password\": \"admin123\"
  }")
http_code=$(echo "$response" | tail -n1)
body=$(echo "$response" | head -n-1)

if [ "$http_code" = "200" ]; then
    ADMIN_TOKEN=$(echo "$body" | grep -o '"accessToken":"[^"]*' | cut -d'"' -f4)
    test_result 0 "Admin login successful"
else
    test_result 1 "Admin login successful" "$body"
fi

# Test 5.2: Verify admin role
if [ ! -z "$ADMIN_TOKEN" ]; then
    response=$(curl -s -w "\n%{http_code}" $BASE_URL/users/me \
      -H "Authorization: Bearer $ADMIN_TOKEN")
    http_code=$(echo "$response" | tail -n1)
    body=$(echo "$response" | head -n-1)

    if [[ "$body" == *"ADMIN"* ]]; then
        test_result 0 "Admin has ADMIN role"
    else
        test_result 1 "Admin has ADMIN role" "$body"
    fi
fi

# ============================================================
# SUMMARY
# ============================================================
echo ""
echo "========================================"
echo "  TEST SUMMARY"
echo "========================================"
echo -e "Total Tests: ${BLUE}$TOTAL_TESTS${NC}"
echo -e "Passed: ${GREEN}$PASS_COUNT${NC}"
echo -e "Failed: ${RED}$FAIL_COUNT${NC}"
echo ""

if [ $FAIL_COUNT -eq 0 ]; then
    echo -e "${GREEN}✓ ALL TESTS PASSED!${NC}"
    echo ""
    echo "JWT Authentication system is working perfectly!"
    exit 0
else
    echo -e "${RED}✗ SOME TESTS FAILED${NC}"
    echo ""
    echo "Please review the failed tests above."
    exit 1
fi
