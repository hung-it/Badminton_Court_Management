# Badminton Court Management

Java 17 / Spring Boot 3.2.5, PostgreSQL 15, React 18 / Vite. Booking, payments, POS, checkout, inventory, promotions and reporting; ADMIN / STAFF / CUSTOMER roles.

## Run on a new machine

Requires Docker Desktop and Compose. From the repository root:

```powershell
docker compose up -d --build
```

- Admin: http://localhost:5173
- Customer: http://localhost:5174
- Backend: http://localhost:8080/api/health
- Docker PostgreSQL host port: 5433.

Fresh volumes load schema and demo data. Accounts and existing-data precautions: [Demo data](docs/DEMO-DATA.md). Configure environment using .env.example; BCM_POS_GUEST_USER_ID must identify the dedicated guest user UUID.

**Current local manual-test environment:** database bcm_manual_p2_20261111. Use its existing local override instead of the default Compose configuration:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .tools/shared-docker/start-manual-p2.ps1
```

The .tools helper is machine-local. Other machines must configure their own database using the [Docker guide](docs/SHARED-DOCKER.md). Never rerun the base schema on an existing database.

## Native development

Requires JDK 17+, Maven and an upgraded PostgreSQL schema. Configure datasource and guest environment first:

```powershell
mvn -f backend/pom.xml spring-boot:run
npm --prefix web-admin ci
npm --prefix web-app ci
```

Run each frontend in a separate terminal with npm --prefix web-admin run dev and npm --prefix web-app run dev. Both proxy /api to backend 8080; BCM_API_PROXY_TARGET overrides it.

## Documentation

| Topic | Source |
|---|---|
| Database setup / upgrades | [DB guide](db/README.md), [database design](docs/database.md) |
| Docker / demo | [Shared Docker](docs/SHARED-DOCKER.md), [demo data](docs/DEMO-DATA.md) |
| Payments / invoices | [Payment flow](docs/payment-invoice-flow.md) |
| Promotions | [Promotions](docs/promotions.md) |
| Suppliers / inventory | [Import logic](docs/SUPPLIER_IMPORT_LOGIC.md) |
| Business diagrams | [Use cases](docs/usecase.md), [activities](docs/activity.md), [sequences](docs/sequence.md) |
| Verification commands | [Testing](docs/testing.md) |
| Fixed P1/P2/frontend issues and remaining work | [Fix summary, 11-12 November 2026](docs/tong-hop-loi-da-sua-2026-11-11-den-12.md) |

Source code and SQL schema take precedence over historical design descriptions. Do not commit secrets, database dumps or test logs.
