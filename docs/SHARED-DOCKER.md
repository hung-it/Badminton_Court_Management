# Shared Docker / local development

## New machine

Run docker compose up -d --build from the repository root. The Compose project is badminton_court_management. Frontends use nginx on ports 5173/5174, backend 8080, PostgreSQL host port 5433. A fresh volume loads schema and demo data; see [demo data](DEMO-DATA.md) and [DB upgrades](../db/README.md).

Configure .env.example values for the intended environment. Both web apps use the same backend/database; POS walk-in profiles do not require a guest login UUID.

## Existing development database

`scripts/start-shared-docker.ps1` starts PostgreSQL, captures a backup in `backups/`, applies the retained upgrades and runs the read-only runtime invariant checks. It builds the backend and both nginx frontends in Docker and checks API access through ports 5173/5174. It preserves the existing PostgreSQL volume and targets `badminton_court_db`. Unsupported payment history, duplicate emails or invalid financial snapshots stop startup for review.

The retained upgrades are booking-ux-upgrade.sql, product-promotions-upgrade.sql, court-promotions-upgrade.sql and email-identity-upgrade.sql. SQL init mounts only execute on fresh volumes. Do not reset a volume to repair an upgrade failure.

## Current local environment

The complete manual-test database was backed up, restored, upgraded and promoted to `badminton_court_db` on 13/11/2026. It preserves the existing accounts, 210 courts, 106 bookings and 155 invoices at promotion. The previous original DB and retired test databases have backups in `backups/`.

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/start-shared-docker.ps1
```

The manual override has been retired. Use only the base `docker-compose.yml`; the runtime database is `badminton_court_db` on `bcm-postgres`, host port 5433. Regression runners deliberately recreate disposable databases when invoked; they are not part of normal project startup.

## Native tools

- scripts/start-backend.ps1: Maven with the local Spring profile; configure its datasource first.
- scripts/start-frontend.ps1: native web-admin Vite.
- scripts/start-local-db.ps1 / stop-local-db.ps1: optional portable PostgreSQL in .tools, separate from Docker.
- Regression / acceptance / demo-seed runners: [testing](testing.md).

Check http://localhost:5173/api/health and http://localhost:5174/api/health and verify both apps show the intended data. Build commands and native prerequisites: [project README](../README.md).
