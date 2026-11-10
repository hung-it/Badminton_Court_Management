# PostgreSQL setup and upgrades

## Current payment contract — 2026-11-05

New payments accept only **CASH / VNPAY**. The Java enum also maps BANK_TRANSFER solely for explicitly preserved historical records; it is rejected for all new payments. CUSTOMER booking payments accept only VNPAY; POS/Checkout accept CASH or VNPAY. Unsupported raw payment-method requests return 400. There is no manual self-declared receipt flow, alias, bank form or reconciliation checkbox.

After explicit authorization to remove unfinished development data, a fresh custom backup was verified: `backups/bcm-remove-transfer-2026-11-05T09-39-40-425Z.dump`, SHA256 `fc29699d4333b9bacc8306f405db0a41f6d973478e1b3a2f7a974d7f03fa026c`, 155 TOC lines. The reviewed cluster contained 9 transfer payments (6 PENDING, 3 SUCCESS), 9 bookings and 5 dependent counter-settlement invoices. They were deleted atomically, not relabelled. Retained data across all 21 tables was fingerprint-checked before COMMIT, including products/stock and independent CASH/VNPAY history. Full counts and UUIDs: `docs/admin-customers-invoices.md`.

`migration.sql` creates two-method CHECKs for fresh databases. The existing `payment-method-compatibility` section in `booking-ux-upgrade.sql` upgrades existing CHECKs, aborting on unsupported data by default. Its explicitly reviewed development-only UUID cleanup is **off by default**; `SET LOCAL bcm.remove_development_transfers='on'` is only for the documented snapshot after a fresh verified backup. It refuses another database or changed cluster. It does not delete arbitrary future legacy rows. Normal Compose/bootstrap never enables this flag; no SQL file was added or restored.

To upgrade an existing database, inspect/backup first and apply only the compatibility section; never rerun the base migration. Repeating the section on an already clean database is a no-op for application data. Historical records in the older audit sections below describe previous states and do not override this contract.

The optional revenue sample removed its obsolete invoice `b2000000-0000-4000-8000-000000000103` and child line `...0124` from the source, rather than converting their method. The sample was NOT imported into shared data and remains opt-in.

## Shared catalog seed

The shared catalog from origin/codex/catalog-seed is integrated once in the `shared-catalog-seed` section of product-promotions-upgrade.sql. No standalone catalog/demo SQL was recreated. Compose, shared startup and acceptance bootstrap already execute that file.

The section inserts missing categories Nước giải khát, Phụ kiện, Thuê vợt and products Nước suối 500ml (10000/chai, initial stock 50), Cầu lông tiêu chuẩn (25000/quả, initial stock 30), Thuê vợt theo lượt (SERVICE, 20000/lượt, stock 0). Existing prices, category relationships, stock, versions and timestamps are never updated. Existence checks include soft-deleted identities, normalize case/whitespace and never resurrect deleted rows. Ambiguous existing category identities abort; table locks serialize startup seeds. Repeated execution must insert zero rows.

This is the team's basic operational demonstration catalog, not a transaction/revenue seed. revenue-breakdown-sample.sql remains opt-in and was NOT imported: it overwrites historical rows/stock on conflict, lacks a visible DEMO discriminator in reports, and includes an inconsistent 2025 product subtotal/detail example. Do not run it against shared data until those issues and a demo-data policy are resolved. Never restore a whole pre-seed backup just to remove catalog rows when newer real transactions exist; selective rollback requires checking references to each inserted UUID first.

## Verified shared-volume rebuild

On 2026-11-05 a new backup was captured before the main database constraint correction and container rebuild: backups/bcm-pre-rebuild-20261105-1300.dump, SHA256 345B4B9F7D90D438214F41ECABCB688CC012E650D0440C88F18A7F2D20FC1B4A. Archive listing and full SQL extraction succeeded. Only the catalog-integrity section of booking-ux-upgrade.sql was applied on the existing main DB; master_court_values now checks court_code rather than local court_number. All application data fingerprints were unchanged.

All nine old BCM containers were removed without deleting any volume. The two Compose services were recreated after a no-cache backend build using the same external PostgreSQL volume. Hibernate validation/startup and the separate 16-case acceptance suite passed. This does not certify full legacy regression, empty-database bootstrap or a restore rehearsal. See docs/admin-customers-invoices.md for the full evidence and container inventory. Never run migration.sql again on the retained data directory.

Use docker-compose.yml as the bootstrap source of truth. The existing development service is bcm-postgres / badminton_court_db, exposed on localhost:5433. Resolve connection identity from the container environment; do not copy credentials into ad hoc scripts.

## Preserve reviewed legacy transfers — 2026-11-10

The user explicitly approved retaining the main database's 5 successful BANK_TRANSFER
receipts (1,260,000 VND) and 3 BANK_TRANSFER invoices (780,000 VND). After a verified
backup, run `legacy-bank-transfer-upgrade.sql` before `booking-ux-upgrade.sql` on
that existing database. This opt-in script is not in fresh Compose bootstrap.
It only adds `source=LEGACY` metadata, preserves all original receipt/invoice fields,
and installs triggers rejecting any new BANK_TRANSFER row, relabelling into it, or
updates/deletes of protected historical rows. It never invents staff confirmation.
The ordinary upgrade still rejects unreviewed unsupported history; fresh databases
retain two-method constraints. Restored-backup tests cover both read compatibility
and forbidden writes. Existing legacy invoices remain included in revenue and use
an explicit historical label in the UI.

The 2026-11-10 deployment skipped the shared catalog seed: only schema sections of
product/court upgrades were needed. Before/after hashes matched all original fields
and row counts of 11 business tables; no historical money/status/date/method changed.
See `docs/booking-checkout-account-fixes.md` for deployment evidence and backups.

## Fresh database

Init scripts execute only when the PostgreSQL data directory is empty. Never delete/reset the existing external volume to rerun them. Compose applies:

1. migration.sql - historical base schema and initial roles, slots and eight courts.
2. booking-ux-upgrade.sql - consolidated court numbering, contact, branches schema, metadata, booking promotion snapshots and release deadline. Includes NEWMEMBER/KMKH10 seed.
3. branches-seed.sql - project branch/court catalog fixtures, retained because the current project bootstrap uses this catalog. This is demo/catalog data, not verified real venue information.
4. product-promotions-upgrade.sql - product discount snapshots and validation.
5. court-promotions-upgrade.sql - campaign limits and COURT-BILL-300/500 seeds.

Catalog integrity and legacy payment-method compatibility now execute within booking-ux-upgrade.sql, after court numbering. The three former standalone upgrade/recovery files have been deleted; do not invoke them. Existing correct constraints are retained. Only a proven legacy master_court_values binding to local court_number is repaired, after validation, to bind court_code instead. Unsupported historical payments stop the ordinary compatibility upgrade; no payments are converted. Only the explicitly enabled, reviewed development cleanup documented above can delete its fixed UUID allowlist.

The same sequence must be used for manual fresh initialization. New instances require the external volume named in Compose to be created explicitly. Existing volumes are never reinitialized automatically.

## Existing development database

Confirm container/database/environment first. Capture pg_dump -Fc, read its TOC with pg_restore --list, and preserve the archive outside backend/target. Record row counts and hashes before upgrading. Inspect columns, indexes and pg_get_constraintdef; apply only missing upgrades. Use psql -X -v ON_ERROR_STOP=1; each retained upgrade provides its transaction boundary. Lock/validation failures must stop the upgrade; do not remove constraints to proceed.

scripts/start-shared-docker.ps1 now backs up before its additive upgrade sequence and resolves database identity through POSTGRES_USER/POSTGRES_DB. It retains the archive under backups/. It does not reset volumes. Use it only for the shared development stack, not production.

There is no Flyway/Liquibase ledger. Applied state is inferred from the actual schema/constraints/seed, not file timestamps. Do not rerun migration.sql on an existing schema. Hibernate remains validate and SQL init remains never.

## SQL inventory

- All five scripts above: retained, referenced by Compose or supported upgrade/bootstrap paths; the current two-method schema/compatibility rules are documented above.
- Removed obsolete member2-demo.sql and the local bootstrap Demo switch: its court fixtures use the former numbering schema. No existing demo records were deleted.
- revenue-breakdown-sample.sql: optional report fixtures that create invoices and affect revenue. Removed from automatic Compose initialization. Run only on a controlled demo dataset after explicit authorization; existing sample rows are not deleted.
- scripts/verify-backend-postgres.ps1 and PostgreSQL test source: retained opt-in test infrastructure. Do not run against bcm-postgres; no full integration suite is part of shared-database synchronization.

The three superseded standalone upgrade files were deleted after consolidation; the base migration defines the current CASH/VNPAY contract for new databases and must not be re-executed on an existing schema. Old reports may reference former per-feature SQL names; the consolidated booking-ux-upgrade.sql is the current entry point.

## Backup restore plan

Archive backups/bcm-before-sync-20261105.dump was captured from the verified development database. Its TOC and complete SQL extraction were validated. A restore rehearsal was not performed against the shared database. To recover, stop application writes, choose an approved separate recovery database, and use pg_restore --exit-on-error --no-owner into it; validate counts/constraints before switching application connectivity. Do not restore over live data or use --clean without a separately reviewed recovery action.
