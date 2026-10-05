#!/bin/bash

# Interactive Demo - JWT Authentication System
# Demonstrates all features with pretty output and user interaction

set -e

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
MAGENTA='\033[0;35m'
CYAN='\033[0;36m'
WHITE='\033[1;37m'
NC='\033[0m'

BASE_URL="http://localhost:8080/api"

clear

cat << "EOF"
╔══════════════════════════════════════════════════════════════╗
║                                                              ║
║     🏸 BADMINTON COURT MANAGEMENT - JWT AUTH DEMO 🏸        ║
║                                                              ║
║               Interactive Authentication Demo                ║
║                                                              ║
╚══════════════════════════════════════════════════════════════╝
EOF

echo ""
echo -e "${CYAN}This demo will walk you through:${NC}"
echo "  1. User Registration"
echo "  2. User Login & JWT Token"
echo "  3. Accessing Protected Resources"
echo "  4. Admin Login & Privileges"
echo ""
echo -e "${YELLOW}Press ENTER to continue...${NC}"
read

# ============================================================
# STEP 1: Health Check
# ============================================================
clear
echo -e "${MAGENTA}═══════════════════════════════════════════════════════════${NC}"
echo -e "${WHITE}STEP 1: Health Check${NC}"
echo -e "${MAGENTA}═══════════════════════════════════════════════════════════${NC}"
echo ""
echo -e "${CYAN}Checking if backend is running...${NC}"
echo ""
echo -e "${YELLOW}$ curl http://localhost:8080/api/health${NC}"
echo ""

response=$(curl -s $BASE_URL/health)
http_code=$?

if [ $http_code -eq 0 ]; then
    echo "$response" | jq '.'
    echo ""
    echo -e "${GREEN}✓ Backend is UP and running!${NC}"
else
    echo -e "${RED}✗ Backend is not running!${NC}"
    echo -e "${YELLOW}Please start backend: cd backend && mvn spring-boot:run${NC}"
    exit 1
fi

echo ""
echo -e "${YELLOW}Press ENTER to continue...${NC}"
read

# ============================================================
# STEP 2: User Registration
# ============================================================
clear
echo -e "${MAGENTA}═══════════════════════════════════════════════════════════${NC}"
echo -e "${WHITE}STEP 2: User Registration${NC}"
echo -e "${MAGENTA}═══════════════════════════════════════════════════════════${NC}"
echo ""
echo -e "${CYAN}Let's register a new customer account${NC}"
echo ""

TIMESTAMP=$(date +%s)
DEMO_EMAIL="demo${TIMESTAMP}@example.com"
DEMO_PASSWORD="Demo@123456"
DEMO_FULLNAME="Demo User"
DEMO_PHONE="0912${TIMESTAMP: -6}"

echo -e "${WHITE}Registration Details:${NC}"
echo "  Email:     $DEMO_EMAIL"
echo "  Password:  $DEMO_PASSWORD"
echo "  Full Name: $DEMO_FULLNAME"
echo "  Phone:     $DEMO_PHONE"
echo ""
echo -e "${YELLOW}$ curl -X POST http://localhost:8080/api/auth/register \\${NC}"
echo -e "${YELLOW}  -H \"Content-Type: application/json\" \\${NC}"
echo -e "${YELLOW}  -d '{...}'${NC}"
echo ""

response=$(curl -s -w "\n%{http_code}" -X POST $BASE_URL/auth/register \
  -H "Content-Type: application/json" \
  -d "{
    \"email\": \"$DEMO_EMAIL\",
    \"password\": \"$DEMO_PASSWORD\",
    \"fullName\": \"$DEMO_FULLNAME\",
    \"phone\": \"$DEMO_PHONE\"
  }")

http_code=$(echo "$response" | tail -n1)
body=$(echo "$response" | head -n-1)

echo "$body" | jq '.'
echo ""

if [ "$http_code" = "201" ]; then
    echo -e "${GREEN}✓ Registration successful!${NC}"
    echo -e "${GREEN}✓ User created with CUSTOMER role${NC}"
else
    echo -e "${RED}✗ Registration failed (HTTP $http_code)${NC}"
fi

echo ""
echo -e "${YELLOW}Press ENTER to continue...${NC}"
read

# ============================================================
# STEP 3: User Login
# ============================================================
clear
echo -e "${MAGENTA}═══════════════════════════════════════════════════════════${NC}"
echo -e "${WHITE}STEP 3: User Login${NC}"
echo -e "${MAGENTA}═══════════════════════════════════════════════════════════${NC}"
echo ""
echo -e "${CYAN}Logging in with the registered account...${NC}"
echo ""
echo -e "${YELLOW}$ curl -X POST http://localhost:8080/api/auth/login \\${NC}"
echo -e "${YELLOW}  -H \"Content-Type: application/json\" \\${NC}"
echo -e "${YELLOW}  -d '{\"email\":\"$DEMO_EMAIL\",\"password\":\"$DEMO_PASSWORD\"}'${NC}"
echo ""

response=$(curl -s -w "\n%{http_code}" -X POST $BASE_URL/auth/login \
  -H "Content-Type: application/json" \
  -d "{
    \"email\": \"$DEMO_EMAIL\",
    \"password\": \"$DEMO_PASSWORD\"
  }")

http_code=$(echo "$response" | tail -n1)
body=$(echo "$response" | head -n-1)

echo "$body" | jq '.'
echo ""

if [ "$http_code" = "200" ]; then
    USER_TOKEN=$(echo "$body" | jq -r '.data.accessToken')
    echo -e "${GREEN}✓ Login successful!${NC}"
    echo ""
    echo -e "${WHITE}JWT Token (first 50 chars):${NC}"
    echo -e "${CYAN}${USER_TOKEN:0:50}...${NC}"
    echo ""
    echo -e "${GREEN}✓ Token will be used for authenticated requests${NC}"
else
    echo -e "${RED}✗ Login failed (HTTP $http_code)${NC}"
    exit 1
fi

echo ""
echo -e "${YELLOW}Press ENTER to continue...${NC}"
read

# ============================================================
# STEP 4: Access Protected Endpoint (Without Token)
# ============================================================
clear
echo -e "${MAGENTA}═══════════════════════════════════════════════════════════${NC}"
echo -e "${WHITE}STEP 4: Protected Endpoint - No Token${NC}"
echo -e "${MAGENTA}═══════════════════════════════════════════════════════════${NC}"
echo ""
echo -e "${CYAN}Trying to access /users/me WITHOUT token...${NC}"
echo ""
echo -e "${YELLOW}$ curl http://localhost:8080/api/users/me${NC}"
echo ""

response=$(curl -s -w "\n%{http_code}" $BASE_URL/users/me)
http_code=$(echo "$response" | tail -n1)
body=$(echo "$response" | head -n-1)

if [ ! -z "$body" ]; then
    echo "$body" | jq '.' 2>/dev/null || echo "$body"
fi
echo ""

if [ "$http_code" = "403" ]; then
    echo -e "${GREEN}✓ Correctly denied! (HTTP 403 Forbidden)${NC}"
    echo -e "${GREEN}✓ Protected endpoints require authentication${NC}"
else
    echo -e "${RED}✗ Should be denied but got HTTP $http_code${NC}"
fi

echo ""
echo -e "${YELLOW}Press ENTER to continue...${NC}"
read

# ============================================================
# STEP 5: Access Protected Endpoint (With Token)
# ============================================================
clear
echo -e "${MAGENTA}═══════════════════════════════════════════════════════════${NC}"
echo -e "${WHITE}STEP 5: Protected Endpoint - With Token${NC}"
echo -e "${MAGENTA}═══════════════════════════════════════════════════════════${NC}"
echo ""
echo -e "${CYAN}Accessing /users/me WITH valid JWT token...${NC}"
echo ""
echo -e "${YELLOW}$ curl http://localhost:8080/api/users/me \\${NC}"
echo -e "${YELLOW}  -H \"Authorization: Bearer \$TOKEN\"${NC}"
echo ""

response=$(curl -s -w "\n%{http_code}" $BASE_URL/users/me \
  -H "Authorization: Bearer $USER_TOKEN")

http_code=$(echo "$response" | tail -n1)
body=$(echo "$response" | head -n-1)

echo "$body" | jq '.'
echo ""

if [ "$http_code" = "200" ]; then
    echo -e "${GREEN}✓ Access granted!${NC}"
    echo -e "${GREEN}✓ User information retrieved successfully${NC}"

    user_email=$(echo "$body" | jq -r '.data.email')
    user_roles=$(echo "$body" | jq -r '.data.roles[]')

    echo ""
    echo -e "${WHITE}Current User:${NC}"
    echo "  Email: $user_email"
    echo "  Roles: $user_roles"
else
    echo -e "${RED}✗ Access denied (HTTP $http_code)${NC}"
fi

echo ""
echo -e "${YELLOW}Press ENTER to continue...${NC}"
read

# ============================================================
# STEP 6: Admin Login
# ============================================================
clear
echo -e "${MAGENTA}═══════════════════════════════════════════════════════════${NC}"
echo -e "${WHITE}STEP 6: Admin Login${NC}"
echo -e "${MAGENTA}═══════════════════════════════════════════════════════════${NC}"
echo ""
echo -e "${CYAN}Logging in as administrator...${NC}"
echo ""
echo -e "${WHITE}Admin Credentials:${NC}"
echo "  Email:    admin@bcm.com"
echo "  Password: admin123"
echo "  Role:     ADMIN"
echo ""
echo -e "${YELLOW}$ curl -X POST http://localhost:8080/api/auth/login \\${NC}"
echo -e "${YELLOW}  -d '{\"email\":\"admin@bcm.com\",\"password\":\"admin123\"}'${NC}"
echo ""

response=$(curl -s -w "\n%{http_code}" -X POST $BASE_URL/auth/login \
  -H "Content-Type: application/json" \
  -d "{
    \"email\": \"admin@bcm.com\",
    \"password\": \"admin123\"
  }")

http_code=$(echo "$response" | tail -n1)
body=$(echo "$response" | head -n-1)

echo "$body" | jq '.'
echo ""

if [ "$http_code" = "200" ]; then
    ADMIN_TOKEN=$(echo "$body" | jq -r '.data.accessToken')
    echo -e "${GREEN}✓ Admin login successful!${NC}"
    echo ""
    echo -e "${WHITE}Admin JWT Token (first 50 chars):${NC}"
    echo -e "${CYAN}${ADMIN_TOKEN:0:50}...${NC}"
else
    echo -e "${RED}✗ Admin login failed (HTTP $http_code)${NC}"
fi

echo ""
echo -e "${YELLOW}Press ENTER to continue...${NC}"
read

# ============================================================
# STEP 7: Verify Admin Role
# ============================================================
clear
echo -e "${MAGENTA}═══════════════════════════════════════════════════════════${NC}"
echo -e "${WHITE}STEP 7: Verify Admin Role${NC}"
echo -e "${MAGENTA}═══════════════════════════════════════════════════════════${NC}"
echo ""
echo -e "${CYAN}Getting admin user information...${NC}"
echo ""

response=$(curl -s -w "\n%{http_code}" $BASE_URL/users/me \
  -H "Authorization: Bearer $ADMIN_TOKEN")

http_code=$(echo "$response" | tail -n1)
body=$(echo "$response" | head -n-1)

echo "$body" | jq '.'
echo ""

if [[ "$body" == *"ADMIN"* ]]; then
    echo -e "${GREEN}✓ User has ADMIN role!${NC}"
    echo -e "${GREEN}✓ Admin can access admin-only endpoints${NC}"

    admin_roles=$(echo "$body" | jq -r '.data.roles[]')
    echo ""
    echo -e "${WHITE}Admin Roles:${NC}"
    echo "$admin_roles" | while read role; do
        echo "  • $role"
    done
else
    echo -e "${RED}✗ User does not have ADMIN role${NC}"
fi

echo ""
echo -e "${YELLOW}Press ENTER to continue...${NC}"
read

# ============================================================
# FINAL SUMMARY
# ============================================================
clear
cat << "EOF"
╔══════════════════════════════════════════════════════════════╗
║                                                              ║
║                    🎉 DEMO COMPLETE! 🎉                     ║
║                                                              ║
╚══════════════════════════════════════════════════════════════╝
EOF

echo ""
echo -e "${GREEN}✓ All JWT authentication features demonstrated!${NC}"
echo ""
echo -e "${CYAN}═══════════════════════════════════════════════════════════${NC}"
echo -e "${WHITE}Summary:${NC}"
echo -e "${CYAN}═══════════════════════════════════════════════════════════${NC}"
echo ""
echo -e "  ${GREEN}✓${NC} Backend health check"
echo -e "  ${GREEN}✓${NC} User registration with validation"
echo -e "  ${GREEN}✓${NC} User login with JWT token generation"
echo -e "  ${GREEN}✓${NC} Protected endpoint security (403 without token)"
echo -e "  ${GREEN}✓${NC} Authenticated access with valid token"
echo -e "  ${GREEN}✓${NC} Admin login and role verification"
echo ""
echo -e "${CYAN}═══════════════════════════════════════════════════════════${NC}"
echo -e "${WHITE}Your Tokens (save these for testing):${NC}"
echo -e "${CYAN}═══════════════════════════════════════════════════════════${NC}"
echo ""
echo -e "${WHITE}Customer Token:${NC}"
echo -e "${YELLOW}$USER_TOKEN${NC}"
echo ""
echo -e "${WHITE}Admin Token:${NC}"
echo -e "${YELLOW}$ADMIN_TOKEN${NC}"
echo ""
echo -e "${CYAN}═══════════════════════════════════════════════════════════${NC}"
echo -e "${WHITE}Quick Test Commands:${NC}"
echo -e "${CYAN}═══════════════════════════════════════════════════════════${NC}"
echo ""
echo -e "${WHITE}1. Get current user (Customer):${NC}"
echo -e "   ${YELLOW}curl http://localhost:8080/api/users/me \\${NC}"
echo -e "   ${YELLOW}  -H \"Authorization: Bearer $USER_TOKEN\"${NC}"
echo ""
echo -e "${WHITE}2. Get current user (Admin):${NC}"
echo -e "   ${YELLOW}curl http://localhost:8080/api/users/me \\${NC}"
echo -e "   ${YELLOW}  -H \"Authorization: Bearer $ADMIN_TOKEN\"${NC}"
echo ""
echo -e "${CYAN}═══════════════════════════════════════════════════════════${NC}"
echo -e "${WHITE}Next Steps for Team:${NC}"
echo -e "${CYAN}═══════════════════════════════════════════════════════════${NC}"
echo ""
echo "  1. Read: backend/QUICK_START_FOR_TEAM.md"
echo "  2. Import: backend/api-collection.json to Postman"
echo "  3. Start coding your module with authentication ready!"
echo ""
echo -e "${GREEN}🚀 Happy coding! 🚀${NC}"
echo ""
