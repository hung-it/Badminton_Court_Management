# Shared Docker / local development

## New machine

Run docker compose up -d --build from the repository root. The Compose project is badminton_court_management. Frontends use nginx on ports 5173/5174, backend 8080, PostgreSQL host port 5433. A fresh volume loads schema and demo data; see [demo data](DEMO-DATA.md) and [DB upgrades](../db/README.md).

Configure .env.example values for the intended environment, including BCM_POS_GUEST_USER_ID. Both web apps must use the same backend/database.

## Existing development database

scripts/start-shared-docker.ps1 starts the default shared DB, captures a backup in backups/, applies the retained upgrade scripts, builds the backend and launches native Vite frontends. It checks port ownership and keeps existing volumes. It targets the container POSTGRES_DB, not the manual-test override database. Review existing financial history before using it: unsupported legacy transfer data or duplicate emails must stop migration for operator review.

The retained upgrades are booking-ux-upgrade.sql, product-promotions-upgrade.sql, court-promotions-upgrade.sql and email-identity-upgrade.sql. SQL init mounts only execute on fresh volumes. Do not reset a volume to repair an upgrade failure.

## Current manual-test environment

The local stack uses bcm_manual_p2_20261111 through .tools/shared-docker/manual-p2-compose.yml. Start it with:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .tools/shared-docker/start-manual-p2.ps1
```

The helper/override is machine-local. Keep it when testing this database; default Compose selects badminton_court_db instead. No cleanup operation migrates either database.

## Native tools

- scripts/start-backend.ps1: Maven with the local Spring profile; configure its datasource first.
- scripts/start-frontend.ps1: native web-admin Vite.
- scripts/start-local-db.ps1 / stop-local-db.ps1: optional portable PostgreSQL in .tools, separate from Docker.
- Regression / acceptance / demo-seed runners: [testing](testing.md).

Check http://localhost:5173/api/health and http://localhost:5174/api/health and verify both apps show the intended data. Build commands and native prerequisites: [project README](../README.md).
