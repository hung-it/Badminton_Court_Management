# BCM POS & Analytics Frontend

## Requirements

- Node.js LTS
- Java 17+
- Maven 3.8+
- PostgreSQL 15+ (or the repository Docker Compose setup)

## Run the backend

From the repository root:

```powershell
docker compose up -d postgres
cd backend
mvn spring-boot:run
```

The backend API is available at `http://localhost:8080/api`.

## Run the frontend

Open a second PowerShell window:

```powershell
cd frontend
npm.cmd install
npm.cmd run dev
```

Open the Vite URL shown in the terminal, normally `http://localhost:5173`.

## Connect the frontend to the backend

The UI uses demo data by default. To use the Spring Boot APIs, set local process variables before starting Vite:

```powershell
$env:VITE_USE_API = "true"
$env:VITE_API_URL = "http://localhost:8080/api"
$env:VITE_POS_CUSTOMER_ID = "<existing-customer-uuid>"
$env:VITE_BOOKING_ID = "<existing-booking-uuid>"
npm.cmd run dev
```

Do not commit real credentials, tokens, UUIDs tied to private data, or `.env` files. The POS API requires an existing customer and product records; the checkout API requires an existing booking.

## Production build

```powershell
npm.cmd run build
```
