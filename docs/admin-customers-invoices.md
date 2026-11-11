# CASH / VNPAY cleanup completed — 2026-11-05

**Current contract:** CUSTOMER booking payments use VNPAY only. POS/Checkout use CASH or VNPAY. `PaymentMethod` is exactly `CASH, VNPAY`; no deprecated value or conversion alias remains. Earlier blocked-removal reports below are historical and superseded by the explicit development-data deletion authorization in this task.

## Backup and reviewed data migration

- Verified development target: `bcm-postgres` / `badminton_court_db`, same external volume `badminton_court_management_postgres_data`. No container or volume was deleted.
- Fresh custom archive: `backups/bcm-remove-transfer-2026-11-05T09-39-40-425Z.dump`; SHA256 `fc29699d4333b9bacc8306f405db0a41f6d973478e1b3a2f7a974d7f03fa026c`. `pg_restore --list` succeeded (155 TOC lines); booking and detail COPY data were also read successfully directly from the archive. No restore over live data was performed.
- Audited 9 transfer payment rows: **6 PENDING / 3 SUCCESS**, not the previous 7/2. All are the specifically authorized unfinished development clusters; no other-method payment exists in those 9 bookings.
- Deleted the 9 payment rows, 9 booking headers, 17 booking detail rows, 5 dependent invoices (3 PAID, 1 DRAFT, 1 CANCELLED), and 2 invoice detail rows. No voucher-usage row existed for those invoices. The five invoices stored CASH as the remaining counter collection method but were dependent on these transfer-funded booking fixtures, not independent CASH sales. They were explicitly included in the reviewed deletion scope; no method was relabelled.
- The deleted PAID dependent invoices total **1,094,000 VND**. Valid independent invoices remain unchanged: CASH 630,000 VND and VNPAY 320,000 VND; retained valid PAID revenue is **950,000 VND**. No accounting formula was changed, no stock adjustment/restock was made, and no customer, user, court, slot or product was deleted.
- Executed only the existing `payment-method-compatibility` section of `db/upgrades/booking-ux-upgrade.sql`, with explicit session flag `bcm.remove_development_transfers=on`, reviewed UUID allowlists and FK checks. Before execution, every table fingerprint still matched the fresh backup inventory. Before COMMIT, a SQL snapshot comparison verified all retained rows across 21 tables. Unexpected differences raise an exception and roll back the transaction.
- Main and acceptance DB constraints now read `CHECK (payment_method IN ('CASH','VNPAY'))` on invoices/payment_transactions. Unsupported rows remaining: **0**. No PAID/CHECKED_IN/COMPLETED header lacks a verified full receipt after cleanup (**0**).
- Ordinary bootstrap never enables the cleanup flag and refuses unsupported historical rows. Re-running the upgrade on clean data does not delete anything. `migration.sql` was updated only for future initialization, never re-executed on the existing main database. No new SQL file or previously deleted SQL file was created/restored.

### Exact payment / booking deletion allowlist

| Payment ID | Booking ID | Former status |
| --- | --- | --- |
| 0be54dec-af96-4ee8-8589-a03181aebed0 | bb33e652-8a7a-4907-a639-b3501e5f6b96 | PENDING |
| 2a39e782-beff-4d01-8469-887552ae78c8 | 649d7874-76d7-4819-ae5f-252605fa6d47 | PENDING |
| 42e519bc-0c4c-4d95-ad9b-91b76948de4a | ca147e41-1aae-461f-8b57-3e1c5cbbe8aa | SUCCESS |
| 484b24f7-b166-48bd-b912-ba49da9f4a89 | 5378d15f-c4d5-4749-a567-2209de11848a | PENDING |
| 4ee3e446-96eb-43a0-b822-180c9c7c2ee8 | 868ae90d-f7f9-484a-ae2d-4f498b96439a | PENDING |
| 7c7113a6-955c-4210-af1d-9144dfa77192 | e3ec9f78-8f56-442c-ab94-8e268755a338 | SUCCESS |
| a6e21c5d-ac95-4761-a6cb-5e25d787cfef | ad54a6cc-d66a-4a95-a416-295f2342a077 | PENDING |
| b1306c42-6fed-4bfb-842d-c2e09507c140 | b3f34c1c-0869-4fc9-a2fb-5d70f2ccca5d | SUCCESS |
| dced5a24-640d-4a06-9121-48a2af080580 | e14f66d2-ebb8-40db-a364-80f86cb1a8f9 | PENDING |

Dependent invoice IDs:

- `10f3a738-d6dd-455f-b821-fcf53b253100`: CANCELLED, 30000 VND.
- `7cac4c2e-8001-4d8e-b16c-dd5816843c98`: PAID, 247000 VND.
- `93de3edc-d846-46b9-bd36-89960a450ae0`: PAID, 627000 VND.
- `9c93b405-dd07-46a3-9691-de5b0672ccf4`: PAID, 220000 VND.
- `e9bfdacc-6ce8-40ac-bc79-bdf208c537c9`: DRAFT, 30000 VND.

### Counts and preservation evidence

| Table | Before cleanup | Immediately after cleanup | Final runtime check |
| --- | ---: | ---: | ---: |
| booking_details | 23 | 6 | 4 |
| bookings | 67 | 58 | 58 |
| branches | 35 | 35 | 35 |
| categories | 4 | 4 | 4 |
| courts | 210 | 210 | 210 |
| customer_voucher_usage | 0 | 0 | 0 |
| customers | 4 | 4 | 4 |
| discount_rules | 7 | 7 | 7 |
| import_order_details | 0 | 0 | 0 |
| import_orders | 0 | 0 | 0 |
| invoice_details | 2 | 0 | 0 |
| invoices | 7 | 2 | 2 |
| payment_transactions | 14 | 5 | 5 |
| products | 4 | 4 | 4 |
| promotions | 7 | 7 | 7 |
| roles | 3 | 3 | 3 |
| staffs | 0 | 0 | 0 |
| suppliers | 0 | 0 | 0 |
| time_slots | 16 | 16 | 16 |
| user_roles | 5 | 5 | 5 |
| users | 5 | 5 | 5 |

The final runtime comparison found **one normal automatic expiry**, not an unexplained cleanup difference: booking `5167a9b5-7815-461e-80fc-1bb9c6b9a3f0` changed PENDING → EXPIRED after its original deadline `2026-11-05 09:45:31.898758`; its two held detail rows were released by the existing expiration scheduler after backend restart. Comparing surviving rows against COPY data in the backup verified that other booking fields were unchanged. All Payment/Invoice, financial, stock, account and master-data fingerprints remain identical to the post-cleanup snapshot. Do not describe the entire database as byte-for-byte unchanged across a running expiration scheduler.

Evidence artifacts: `backend/target/remove-transfer-before.json`, `remove-transfer-after.json`, `payment-cleanup-final.json`, `feature-acceptance.log`, `payment-cleanup-related.log`. These contain local audit evidence; the backup stays outside the Docker volume.

## Source cleanup and validation

Before cleanup, references supported a third enum value, nested bank DTO/validation, manual declaration/confirmation, QR alias, checkout checkbox/payload, Web User result branches, configuration and SQL seeds. Final scan of `backend/src/main`, both frontend `src` trees, Compose and active environment defaults has **zero** matches for `BANK_TRANSFER`, `BankTransfer`, `bankTransfer`, `CUSTOMER_DECLARED`, or the old checkbox. Remaining raw method literals are rejection tests, reviewed migration guards or explicitly historical documents, never a runtime payment branch.

- Java: removed enum member, nested payer DTO and validation, declaration note/state transition, manual reconciliation at checkout, legacy confirmation response fields and QR alias. `BookingPaymentConfig`/gateway accept VNPAY only. CASH counter receipt remains an independent authenticated STAFF/ADMIN operation; VNPAY receipts are verified via callback/IPN.
- Checkout requires exactly one matching full SUCCESS receipt with transaction ID/time before deducting court prepayment. A PAID header by itself, missing receipt or amount mismatch is rejected without posting an invoice. Preserved saved court/product/discount totals, booking lock, one invoice and COMPLETED transition.
- Frontend: removed transfer option, state, handlers, checkbox, request field and result/history conditional branches. Checkout keeps its two-column layout/CASH/VNPAY picker; POS walk-in now exposes its existing CASH/VNPAY backend contract. Invoice filter has only these two methods. No application CSS or unrelated UI was redesigned.
- API: removed methods and QR return 400; invalid enum JSON is handled by the existing unreadable-body exception handler. Added POS request validation so quote as well as sale rejects unsupported methods before side effects.
- SQL: two-method base/upgrade CHECKs; deleted optional demo invoice `b2000000-0000-4000-8000-000000000103` and its line `...0124` from `revenue-breakdown-sample.sql`, rather than converting them. Optional 2026 sample expected revenue is now 2,090,000 VND. The sample is NOT automatically imported and was not executed against main.
- Fixed a real bootstrap defect: repeated `docker cp db destination` nested the directory, leaving stale top-level migrations. Acceptance script now copies each official SQL to its exact execution path. Re-run seeds insert zero rows.

## File inventory for this task

- Java config/entity: `entity/PaymentMethod.java`, `config/BookingPaymentConfig.java`, `resources/application.yml`.
- Services: `PaymentInitiationService.java`, `PaymentAttemptService.java`, `PaymentGatewayPreparation.java`, `BookingHistoryService.java`, `BookingPromotionService.java`, `PosService.java`, `CheckoutService.java`, `AnalyticsService.java`.
- Controllers: `PaymentAttemptController.java`, `PosController.java`, `CheckoutController.java`.
- DTOs: `CreatePaymentAttemptRequest.java`, `PosSaleRequest.java`, `CheckoutRequest.java`, `PaymentAttemptResponse.java`, `BookingHistoryDetailResponse.java`, `CheckoutResponse.java`.
- React: `web-admin/src/App.jsx`, `RevenueInvoices.jsx`; `web-app/src/CustomerCheckout.jsx`, `CustomerCourts.jsx`, `PaymentResult.jsx`.
- SQL/config/bootstrap: `db/migration.sql`, `booking-ux-upgrade.sql`, `revenue-breakdown-sample.sql`, `docker-compose.yml`, `.env.example`, local enabled-method environment default, `scripts/verify-feature-acceptance.ps1`.
- Tests: `acceptance/FeatureAcceptanceTest.java`; related `PaymentInitiationServiceTest`, `BookingPromotionPostgresTest`, `CheckoutReceiptPostgresTest`, `BookingHistoryPostgresTest`, `BookingModificationPostgresTest`, `BranchBookingPostgresTest`, `BookingPaymentLifecyclePostgresTest`, `BookingUxMigrationPostgresTest`, `CourtNumberMigrationPostgresTest`. Existing unrelated test bodies were not disabled.
- Browser test contracts: Web Admin `checkout-list.e2e.cjs`; Web User `booking-payment`, `checkout-sync`, `checkout-expiration`, `checkout-hold`, `checkout-promotions`, `child-courts`, `court-numbering`, `courts-checkout-ux`, `venue-surfaces`, `payment-result-status` E2E scripts. New shared **test-only** `payment-test-fixture.cjs` intercepts provider navigation and signs controlled local callbacks with an explicitly supplied isolated test key, with a test-database environment guard; never calls the provider or reads real merchant credentials. These old write E2E scripts were syntax-checked, not all executed. They still require their existing dedicated test runtime/catalog and are not a launcher for main data.
- Docs: this report, `docs/feature-acceptance-report.md`, `db/README.md`, `docs/database.md`, `backend/BACKEND_REVIEW.md` (historical review warning), `web-admin/README.md`, `web-app/PLAN.md`.

## Actual verification and limits

- Feature Acceptance: **25 tests / 0 FAIL / 0 ERROR / 0 SKIPPED**, PostgreSQL `bcm_acceptance_test`. Customer VNPAY-only, CASH/removed-method rejection, idempotency/callback/expiry, POS and Checkout both methods, no mutation on rejection, CHECK/JPA enum, Invoice/Analytics, Promotion/inventory and Account JWT/security. The initial run found 2 failures (stale copied SQL and POS quote validation); both causes were fixed without weakening assertions.
- Additional targeted run: **110 tests / 0 FAIL / 0 ERROR / 0 SKIPPED**: Promotion 37, CheckoutReceipt 5, PaymentCallbackController 16, PaymentGatewayPreparation 3, PaymentInitiationService 1, VnPayGateway 14, VnPayIpnVerifier 34.
- Backend Docker clean compile/package/startup PASS; PostgreSQL healthy; Web Admin build PASS, Web User build PASS; Compose config PASS; `git diff --check` PASS. Dockerfile packaging skips tests as before; the separately executed test results above are the test evidence.
- Edge: Bill two-method picker and **zero checkboxes**, correct 200,000 court prepayment + 30,000 products → 30,000 due, bill retry/close, mobile and checkout request VNPAY using API fixtures. Images: `backend/target/checkout-cash-vnpay-desktop.png`, `checkout-cash-vnpay-mobile.png`. No real main-database sale/settlement was created.
- Edge Web User: a controlled checkout fixture exposes only VNPAY, sends exactly `{paymentMethod:'VNPAY'}`, and follows a fake redirect; no provider network request. Payment Result CASH/VNPAY success, VNPay failure/pending/expiry, refresh and mobile PASS. Its pending test now waits for the existing polling interval before asserting the loaded amount.
- Read-only live Admin: POS options CASH/VNPAY, invoice filters CASH/VNPAY, Account/Invoice smoke at 1440/1024/768/375px, zero page JavaScript errors. No live account lock or transaction was performed.
- Full Legacy Regression was NOT run or certified. No new Docker test container. No clean-database bootstrap/restore rehearsal claimed; acceptance upgrade/bootstrap was exercised repeatedly. VNPay Sandbox E2E is outside the current acceptance scope.

Rollback plan: preserve the verified archive; stop application writes before any recovery. Restore into an explicitly approved separate recovery database using `pg_restore --exit-on-error --no-owner`, inspect counts/constraints, then review a selective recovery plan. A rollback restoring old transfer data also requires the corresponding old constraint/source revision; never restore it blindly into the current two-method schema or overwrite newer valid transactions.

Web Admin: `http://127.0.0.1:5173/`. No push/merge.

---

# Historical reports (superseded where the payment contract differs)

# Admin customers and invoices

## Customer/Invoice UI review and requested transfer removal

UI changes are confined to web-admin/src/App.jsx, Customers.jsx, RevenueInvoices.jsx and styles.css. Customer navigation now appears after Promotions in the data-management group, without adding it to masterTabs/API preload. Invoice remains in Operations/POS. Customer breadcrumb is Quản lý dữ liệu / Khách hàng. Existing ADMIN filtering/API policies are unchanged.

Customer and invoice headings/filters now use scoped BCM record layouts, uniform 44px controls, wrapped long customer text, aligned numeric/VND cells, compact table spacing and internal table scrolling. The customer action is labeled Khóa tài khoản, with the requested explanation, Hủy/Khóa tài khoản buttons and success copy. It still calls the same soft-delete endpoint only after confirmation. No unlock behavior was added. Existing forms keep request/validation limits; server field errors render next to inputs. Main–Sub business data/status handling is unchanged; table styling is limited to the list, not nested line tables.

Web Admin build and git diff --check PASS. Read-only Edge checks passed at 1440, 1024, 768 and 375px: navigation/breadcrumb, search/sort, add/edit/detail modal opening and cancellation, lock wording/cancellation, invoice Main–Sub and no page overflow. No real account was locked and no create/update form was submitted to the shared DB. Invoice UUID/method/date filters and first/last-page controls also passed. Full CRUD write behavior was not retested on main.

### BANK_TRANSFER removal is blocked — no destructive migration performed

New pre-review backup: backups/bcm-before-method-review-20261105.dump, SHA256 37EF434636A077396441B44A0676C704B2F4496A8838FAC48277B39DD561AA6D; archive TOC parsed successfully. Inventory is in backend/target/method-review.log.

There are 9 BANK_TRANSFER payment rows: 7 PENDING totaling 2840000, all linked to PAID bookings with CUSTOMER_DECLARED/unverified receipt evidence; 2 SUCCESS totaling 844000, linked to COMPLETED bookings after STAFF_RECEIVED reconciliation. No BANK_TRANSFER invoice row was found. The rows are not identified as disposable demo fixtures.

| Payment ID | Status | Amount | Booking ID |
| --- | --- | ---: | --- |
| a6e21c5d-ac95-4761-a6cb-5e25d787cfef | PENDING | 1050000 | ad54a6cc-d66a-4a95-a416-295f2342a077 |
| dced5a24-640d-4a06-9121-48a2af080580 | PENDING | 410000 | e14f66d2-ebb8-40db-a364-80f86cb1a8f9 |
| 0be54dec-af96-4ee8-8589-a03181aebed0 | PENDING | 220000 | bb33e652-8a7a-4907-a639-b3501e5f6b96 |
| 484b24f7-b166-48bd-b912-ba49da9f4a89 | PENDING | 500000 | 5378d15f-c4d5-4749-a567-2209de11848a |
| 2a39e782-beff-4d01-8469-887552ae78c8 | PENDING | 220000 | 649d7874-76d7-4819-ae5f-252605fa6d47 |
| 4ee3e446-96eb-43a0-b822-180c9c7c2ee8 | PENDING | 220000 | 868ae90d-f7f9-484a-ae2d-4f498b96439a |
| b1306c42-6fed-4bfb-842d-c2e09507c140 | PENDING | 220000 | b3f34c1c-0869-4fc9-a2fb-5d70f2ccca5d |
| 42e519bc-0c4c-4d95-ad9b-91b76948de4a | SUCCESS | 627000 | ca147e41-1aae-461f-8b57-3e1c5cbbe8aa |
| 7c7113a6-955c-4210-af1d-9144dfa77192 | SUCCESS | 217000 | e3ec9f78-8f56-442c-ab94-8e268755a338 |

Deleting the unverified declarations could also make Checkout lose the evidence that receipt confirmation is required for an already PAID booking. Deleting reconciled successes would erase legitimate payment history. Per the user's prerequisite, stopped before changing enum, schema, transfer-specific services, POS/Checkout options or acceptance contract. Deleted/converted records: ZERO. No method was relabeled CASH/VNPAY. The removal task is NOT complete; a legitimate disposition of these exact records is required first. An archive alone does not authorize erasing reconciled history or altering booking state.

The two-method contract has not been tested or deployed. Existing three-method behavior remains until the data blocker is resolved. No SQL/business logic changes, no push/merge in this continuation.

## Team catalog synchronization into shared PostgreSQL

Fetched origin and inspected codex/catalog-seed, feature/courts-and-catalog, feature/pos-and-analytics, codex/product-promotions, codex/unified-promotions and develop. No branch was merged/cherry-picked. The old member demo payload on member branches repeats the same three basic catalog items and also contains obsolete court fixtures; it was read for comparison, not restored or executed. Product/court promotion SQL from the promotion branches is already represented by the current files, and main already has the required campaigns. The only missing basic catalog source was origin/codex/catalog-seed:db/catalog-seed.sql.

Inventory of remaining SQL: migration.sql is historical base schema/roles/slots/courts; booking-ux-upgrade.sql holds compatibility/schema and NEWMEMBER/KMKH10; branches-seed.sql is the existing 35-branch/210-court project catalog; product-promotions-upgrade.sql holds product discounts and now the shared catalog section; court-promotions-upgrade.sql holds campaign limits/COURT-BILL seeds; revenue-breakdown-sample.sql is optional report demo data. Java DataSeeder creates ordinary default roles/admin/POS guest; it is not a goods or revenue seed. Acceptance UUID fixtures remain test-only. No deleted SQL file was restored and no new SQL file was created.

### Backup, actual imports and before/after

Verified bcm-postgres healthy, bcm-backend running against the existing shared development database. Created NEW backup backups/bcm-before-team-seed-20261105.dump; pg_restore TOC and full extraction succeeded; SHA256 A70E994A0F11C9462DCF61E433CC0ABCF9D79FD3B63EAA888426578C6C544396. Application-table fingerprints include active and soft-deleted rows.

Before: one active category Nước, one active product Coca, 10000/cái, stock 10, version 0; no soft-deleted catalog rows. None of the three incoming category/product identities existed. Imported exactly three categories and three products through the existing product-promotions-upgrade.sql, transactionally with table locking. The inserted catalog uses generated UUIDs, not branch-specific hard-coded UUIDs; unexpected constraint conflicts roll back instead of updating rows. Case and all whitespace are normalized; existing soft-deleted identities suppress insertion and are not restored. Existing category ambiguity aborts rather than choosing a category arbitrarily.

| New category | New product | Type | Price / unit | Initial stock |
| --- | --- | --- | --- | ---: |
| Nước giải khát | Nước suối 500ml | GOODS | 10000 / chai | 50 |
| Phụ kiện | Cầu lông tiêu chuẩn | GOODS | 25000 / quả | 30 |
| Thuê vợt | Thuê vợt theo lượt | SERVICE | 20000 / lượt | 0 |

Immediate before/after seed: categories 1→4, products 1→4. Users 5, customers 4, staffs 0, courts 210, slots 16, bookings/details 66/21, payments 13, invoices/details 2/0, promotions/rules 7/7 all stayed unchanged with identical full-row fingerprints. The complete hashes of the original category and Coca were separately verified against the pre-seed hashes, including price/stock/version/timestamps. Re-executing the final seed inserted 0 categories and 0 products. No old category_id, price or inventory was overwritten.

Important concurrent activity: the user explicitly confirmed performing counter operations during this task. Later snapshots/logs show successful POST /checkout calls at server times 06:14:17 and 06:19:50, additional invoices and booking/payment changes; a later snapshot has 5 invoices/1 detail, and Coca stock became 7/version 1. Those are live application operations, not seed DML. They were not undone. Therefore the final live snapshot is NOT claimed to match every pre-seed fingerprint. The immediate seed comparison shows only category/product additions; the independent acceptance run also reported unchanged main fingerprints during its own window.

### Revenue demo decision

Did NOT import revenue-breakdown-sample.sql. The complete script contains ON CONFLICT DO UPDATE for profiles, prices, stock, invoices and invoice details. It uses a1000000/b2000000 UUID namespaces (zero existing rows in these namespaces across relevant main tables at audit). Absence of UUID collisions alone is insufficient: rows lack an unambiguous visible DEMO label in Analytics/Main–Sub, and the first 2025 invoice has product_fee 60000 but detail gross amounts 70000. Import would misrepresent demo revenue and introduce inconsistent historical subtotals. These are concrete blockers; no fabricated customer/staff/invoice was created to decorate charts. The file remains opt-in, outside Compose.

At the SQL/API comparison snapshot, valid PAID revenue totals matched exactly: court_fee 1870000, product_fee 30000, discount_amount 76000, total_amount 1824000. These are live operational invoices, not imported report samples; subsequent user counter operations can change them. No fixed total is promised while the shared application is actively used.

### UI/API/tests and remaining limits

- Admin Category/Product APIs return 200. Actual Edge browser shows 4 categories and 4 products, including all three new catalog rows with the correct category/price/unit/stock; inspected screenshot backend/target/team-catalog-ui.png. Product search for 500ml returns one row. POS displays all four products; no agent sale was performed on main. Existing POS/Checkout method handling is unchanged.
- Analytics API totals matched persisted PAID sums at comparison time. Browser month/range controls worked; screenshot team-analytics-ui.png. Invoice list/detail APIs returned 200; Main–Sub opened. Existing history was not reconstructed from new master prices. New stored detail attachment/totals/status independence are covered by acceptance tests; loading a catalog does not invent missing historical invoice lines.
- Final-version feature acceptance: **16 PASS / 0 FAIL / 0 ERROR / 0 SKIPPED**, bcm_acceptance_test only. Main fingerprint unchanged during the suite. Repeated bootstrap created no duplicate catalog rows.
- Both frontend builds PASS; Compose config PASS; git diff --check PASS. PostgreSQL healthy and backend restart count 0. No Java/React/CSS change, no container/volume reset, no full legacy regression or Sandbox transaction, no push/merge.

Task-local source files changed: db/upgrades/product-promotions-upgrade.sql (one insert-only catalog section), db/README.md and this report. Evidence: team-seed-before.log, team-seed-after.log, team-seed-final.log, team-seed-live-final.log, team-catalog-before.log, team-catalog-after.log, team-seed-final-audit.log, team-seed-repeat-final.log, team-seed-backup-toc.log, feature-acceptance.log under backend/target.

Rollback plan: retain the verified backup for disaster recovery. For catalog-only rollback, first verify that the exact new product/category UUIDs from team-catalog-after.log have no transaction references; only those unreferenced new rows could be removed in a separately reviewed transaction. Do not restore the entire old database over newer user settlements, reset stock, or delete referenced catalog/history. Remaining limitation: safe revenue DEMO import needs corrected fixtures plus an agreed way to distinguish DEMO in reports; it was intentionally not executed.

## Clean rebuild and main-database constraint synchronization — 2026-11-05

This section supersedes earlier reports that the shared database still had the local-number constraint or Docker was unavailable.

### New backup and safe schema correction

Verified Docker Engine 29.8.1, Compose config, bcm-postgres/badminton_court_db and the external volume badminton_court_management_postgres_data before changes. Created a NEW full pg_dump -Fc archive outside Docker: backups/bcm-pre-rebuild-20261105-1300.dump (81378 bytes; SHA256 345B4B9F7D90D438214F41ECABCB688CC012E650D0440C88F18A7F2D20FC1B4A). pg_restore --list parsed 143 entries and full SQL extraction succeeded. No restore over existing data was attempted. Previous backups were not substituted for this backup or overwritten.

Captured schema, pg_get_constraintdef, indexes and fingerprints of all application tables. Confirmed master_court_values checked court_number. Applied ONLY the catalog-integrity section from the existing booking-ux-upgrade.sql, with its transaction, lock timeout and invalid-data precheck; no base migration, branch seed or promotion seed was rerun on main. The erroneous constraint was replaced atomically and now checks court_code > 0. Correct existing catalog constraints/indexes were retained; comparison of the pre/post audit showed only this constraint definition changed (apart from output formatting).

Initial shell quoting/line-ending attempts stopped without applying SQL; the section was subsequently executed successfully and committed. No destructive container step proceeded until the corrected constraint and identical data fingerprints were confirmed. No SQL file was added to the repository, no removed SQL file was restored and no business source/UI/CSS change was required.

### Containers and clean build

Removed all nine verified BCM containers, retaining ALL volumes:

- bcm-postgres and bcm-backend via docker compose down --remove-orphans (without -v).
- bcm-availability-sync-audit, bcm-availability-sync-e2e, bcm-checkout-release-e2e, bcm-checkout-release-audit and bcm-metadata-audit: old BCM backend images/project labels, explicitly removed after Compose left them behind.
- bcm-checkout-snapshot-20261025 and bcm-checkout-tests-20261025: verified old PostgreSQL test/snapshot containers with separate mounts, removed without volume deletion.

Ran docker compose build --no-cache backend, then docker compose up -d --force-recreate. Recreated only the two actual Compose services bcm-postgres and bcm-backend; no test container or frontend Docker service was added. PostgreSQL image was not removed. The external volume has the same name and mountpoint before/after. Its existing data directory was reused, not initialized from migration.sql. The Compose network was recreated; no volume prune/system prune was used.

Backend clean Docker build PASS (existing Dockerfile packaging skips tests; acceptance is run separately). Hibernate initialized JPA with ddl-auto validate and backend started successfully. Final PostgreSQL status healthy, backend running, restart count 0, no startup/schema-validation error. Docker Compose config validates. Web Admin is available at http://127.0.0.1:5173/; its existing host Vite service was retained when the port was already in use. Web User was started from source at http://127.0.0.1:5174/. Both URLs returned 200.

### Data preservation and verification

Complete-row fingerprints match before schema correction, after correction, after container rebuild AND after acceptance. Main data stayed: users 5, customers 4, bookings/details 66/21, payments 13, invoices/details 2/0, products 1 (stock unchanged), courts/slots 210/16, promotions/rules 7/7. Additional role, staff, branch, category, voucher and import tables were fingerprinted as well. No UUID, historical payment, invoice total, promotion row, price, stock, slot or booking status changed.

- scripts/verify-feature-acceptance.ps1: **16 PASS / 0 FAIL / 0 ERROR / 0 SKIPPED**, bcm_acceptance_test on the existing PostgreSQL volume. POS→Invoice and Checkout→Invoice checked for all three payment methods. No main integration writes, no legacy test execution, no Sandbox transaction.
- Main read APIs: health, customers list/detail, invoices list/detail, POS products/customers, Checkout active bookings, Analytics revenue and public branches: HTTP 200. Existing admin login succeeded.
- Real Edge browser invoice check: UUID search, CASH/VNPAY/BANK_TRANSFER filters, empty date range, first/last page disabled controls, Main–Sub ID/status/saved amount, and no document overflow at 375px: PASS. Main has only two existing invoices, so multi-page UI navigation with >20 invoices is not claimed; pagination/data/detail attachment is covered in the separate acceptance database. No real POS sale/settlement was created on main.
- Web Admin build PASS; Web User build PASS; git diff --check PASS. Full Legacy Regression and VNPay Sandbox E2E were not requested or run.

Evidence: backend/target/pre-rebuild-backup-toc.log, pre-rebuild-schema.log, post-constraint-schema.log, post-rebuild-schema.log, pre-rebuild-fingerprint.log, post-rebuild-fingerprint.log, acceptance-main-after.log, clean-rebuild-docker.log, feature-acceptance.log, invoice-main-sub-desktop.png. The schema/SQL is no longer known to differ on master_court_values. Fresh-empty-database bootstrap and backup restore rehearsal were not performed in this task. No remaining startup or acceptance failure observed; the previously documented retail POS idempotency-key limitation remains unchanged.

Task-local tracked source changes: this report and db/README.md only. No push/merge. Manual invoice review: sign in at Web Admin using existing ADMIN account admin@bcm.com and its current password (not disclosed), then Vận hành & POS → Hóa đơn → search/filter → Chi tiết.

## Invoice acceptance after Docker restart and SQL cleanup

Docker Engine 29.8.1 is available; Compose config validates. Initially bcm-postgres was stopped and backend was restarting because PostgreSQL was unavailable. Recreated only the existing postgres service using the cleaned Compose mounts and the same external volume. No init SQL was reapplied to main data. PostgreSQL is healthy; backend is running, its restart counter stabilized at 32 after recovery. Web Admin is running at http://127.0.0.1:5173/ and was opened in the user's browser.

Re-executed scripts/verify-feature-acceptance.ps1 with only the remaining SQL files: **16 PASS, 0 FAIL, 0 ERROR, 0 SKIPPED**, on bcm_acceptance_test. This includes POS and Checkout → invoice for CASH/BANK_TRANSFER/VNPAY, stock/prepaid amounts, attached detail rows, one checkout, independent invoice/booking states and Analytics. The main-database before/after fingerprints are identical. No new SQL file or test container was created, and no deleted upgrade file was restored. Full legacy regression and Sandbox E2E were not run.

Important schema distinction: the merged SQL executed successfully on the acceptance database and repaired master_court_values to court_code > 0. The shared main database still has the pre-existing court_number > 0 version. Slot exclusion and both three-method payment CHECKs remain present in main. Per the instruction to keep main data/schema unchanged, no upgrade was applied there. Therefore full schema convergence of the shared database is NOT claimed. Fresh-empty-schema bootstrap was not re-executed in this check.

Actual main-data read APIs: health, customer list/detail, invoice list/detail, POS products/customers, checkout active bookings, Analytics revenue and public branches all returned HTTP 200 after recovery. Existing admin login succeeded. Existing ADMIN account for manual review: admin@bcm.com; use its existing password (not included in this report).

Read-only Edge browser check on Web Admin verified invoice UUID search, CASH/VNPAY/BANK_TRANSFER filters, empty date range, first/last pagination disabled states, detail dialog, persisted ID/status/amount and mobile document overflow at 375px. Both real invoices were available. The current main dataset contains only two invoices, so navigation across multiple populated pages and product lines in existing real invoice data were not demonstrated by UI; pagination/detail rows are covered on the acceptance DB. Screenshot: backend/target/invoice-main-sub-desktop.png. No main POS/Checkout sale/settlement or other business write was performed.

Manual review: open Web Admin, sign in as admin@bcm.com, select Hóa đơn under Vận hành & POS, search an invoice UUID/customer/booking, choose payment method and both date boundaries, then click Chi tiết. Check independent Invoice/Booking status and saved totals. Remove filters to return to both historical PAID invoices. Use the existing administrator password; no password reset or disclosure was performed.

Both frontend builds and git diff --check PASS. Task-local source change: this report only; runtime browser/check scripts and logs are local backend/target artifacts. No production business logic/UI/CSS change, no push/merge.

## SQL consolidation (current)

Final cleanup checks: all three obsolete SQL paths are absent. No executable references remain under scripts, Compose, backend source/tests or remaining SQL. PowerShell syntax parsing, both frontend builds, docker compose config --quiet and git diff --check succeeded. Docker Engine endpoint dockerDesktopLinuxEngine is unavailable and localhost:8080 refuses connection in this session; PostgreSQL health, backend startup/API reads and clean-database bootstrap were therefore NOT validated after consolidation. No database command applied the consolidated SQL; shared data was not modified. These environmental blockers mean schema/bootstrap compatibility is source-reviewed, not a claimed PostgreSQL execution PASS.

Current fresh order: migration.sql, booking-ux-upgrade.sql, branches-seed.sql (project catalog), product-promotions-upgrade.sql, court-promotions-upgrade.sql. Report sample SQL stays opt-in. Existing-volume scripts omit base migration and invoke the consolidated upgrade followed by the two promotion upgrades.

Deleted member2-upgrade.sql, member2-payment-method-upgrade.sql and runtime-promotion-schema-upgrade.sql. No new SQL file created. Catalog columns/indexes/constraints and guarded legacy payment compatibility now live in booking-ux-upgrade.sql. Promotion recovery columns already exist in product-promotions-upgrade.sql and court-promotions-upgrade.sql and were not duplicated. Compose and PowerShell callers use only remaining files. Earlier historical statements below do not describe the current file inventory. No SQL was applied to the development database during consolidation. Fresh bootstrap and live backend/API checks require verification; do not infer PASS from source cleanup.

## New isolated feature acceptance suite

See [feature acceptance report](feature-acceptance-report.md) for the new PostgreSQL acceptance suite, dedicated database/role, guarded rerun script, test results and main-database fingerprint evidence. Acceptance results do not replace or certify the unresolved full legacy regression. VNPay Sandbox E2E remains outside scope. The report explicitly documents the current absence of a retail POS idempotency-key contract.

## Requested SQL cleanup

Removed db/member2-demo.sql because its optional court fixtures use the former numbering schema. Removed the Demo switch/call from scripts/start-local-db.ps1 and updated documentation. Kept both member2 upgrade SQL files because Docker Compose and startup scripts still use them. No database or historical migration changes. PowerShell syntax parse and git diff --check passed.

## Rà soát cuối theo phạm vi nghiệm thu hiện tại — 2026-11-05

**VNPay Sandbox E2E không thuộc phạm vi nghiệm thu theo yêu cầu hiện tại.** Không cần tạo giao dịch Sandbox, container PostgreSQL test mới hoặc chạy integration test ghi/xóa fixture trên database phát triển dùng chung. Các kết quả lịch sử phía dưới được giữ làm bằng chứng, không phải tuyên bố full regression đã thành công.

### Kết quả kiểm tra cuối

| Kiểm tra | Kết quả thực tế |
| --- | --- |
| API chỉ đọc | Admin login, health, danh sách/chi tiết khách hàng, danh sách/chi tiết hóa đơn, POS products/customers, Checkout active bookings, Analytics revenue, public branches: HTTP 200 |
| Quyền CUSTOMER | Login/history 200; truy cập admin customers 403 |
| VNPay-only | CUSTOMER gửi CASH/BANK_TRANSFER tới API thanh toán: 400, đúng thông báo `Customer booking payments only support VNPAY` |
| Không phát sinh dữ liệu | Dùng UUID booking không tồn tại để kiểm tra chốt từ chối trước lookup/persistence. Fingerprint toàn bộ bản ghi vẫn khớp mốc sau đồng bộ; không tạo payment, booking hoặc thay đổi voucher |
| POS/Checkout và lịch sử | Source vẫn chấp nhận CASH, BANK_TRANSFER, VNPAY; enum và CHECK database giữ đủ ba phương thức. PaymentResult vẫn xử lý BANK_TRANSFER cũ. Runtime giữ 9 payment BANK_TRANSFER PENDING và hai invoice PAID (CASH/VNPAY) |
| Seed và constraint | COURT-BILL-300, COURT-BILL-500, NEWMEMBER, KMKH10: mỗi code có đúng 1 promotion/1 rule. Không có slot hoạt động chồng lấn hoặc booking có nhiều invoice PAID chưa xóa. CHECK payment target/method, expiry, giảm giá, exclusion slot và unique index booking slot/transaction ID đều hiện diện |
| UI chỉ đọc | Edge mở được Customer, Invoice, POS, Checkout; mở modal Invoice Main–Sub. Chặn mọi API không phải GET trong browser smoke. Viewport 375px không tràn ngang; không thay CSS |
| Build Web Admin | `npm.cmd run build` trong web-admin: PASS |
| Build Web User | `npm.cmd run build` trong web-app: PASS |
| Unit tests độc lập | PaymentInitiationServiceTest, PaymentGatewayPreparationTest, VnPayGatewayTest, VnPayIpnVerifierTest: **53 PASS, 0 FAIL, 0 ERROR, 0 SKIPPED** |
| Git/Compose | `git diff --check`: PASS; `docker compose config --quiet`: PASS |

Chốt VNPay-only được thực thi tại PaymentInitiationService trước khi gọi PaymentAttemptService; CustomerCheckout chỉ có radio VNPay và gửi literal `paymentMethod: 'VNPAY'`, không gửi bankTransfer. Kiểm tra HTTP cuối xác nhận chốt từ chối trên backend đang chạy, không chỉ dựa vào giao diện. Rà soát endpoint controller không thấy đường HTTP khởi tạo booking payment khác gọi trực tiếp shared transfer service.

### Database, Docker và cleanup

bcm-postgres và bcm-backend đều running, restart count 0. Hai container bcm-invoice-admin-tests-20261105 và bcm-invoice-regression-tests-v2 vẫn không còn. Không khởi động lại container test, không chạy lại migration/seed, không thay đổi volume hoặc cấu hình runtime trong lượt rà soát này.

Fingerprint cuối khớp mốc sau đồng bộ: users 5, customers 4, bookings 66, booking_details 21, payments 13, invoices 2, invoice_details 0, products 1, courts 210, time_slots 16, promotions/rules 7/7. Không thay đổi dữ liệu hiện có.

Bootstrap SQL được đối chiếu với Compose và các script: dependency/đường dẫn có đủ; seed doanh thu mẫu vẫn ngoài bootstrap bắt buộc. Không xóa migration lịch sử hoặc script test chỉ vì hiện không chạy; chúng còn được tài liệu tham chiếu và có giá trị cho regression/recovery sau này. Không có file đã được xác minh là thừa để xóa thêm.

File sửa riêng trong lượt rà soát cuối: tài liệu này và db/README.md (sửa dấu phân cách bị thành ký tự `?` ở danh sách SQL). Không sửa source nghiệp vụ, tests, UI/CSS, migration hay dữ liệu. Các thay đổi feature đã có trước vẫn giữ nguyên; không push/merge.

### Giới hạn nghiệm thu

- Full backend regression vẫn **chưa PASS**; không chạy lại full `mvn verify` trong lượt này. Các lỗi và kết quả từng nhóm trước đó không được thay thế bằng 53 unit tests.
- Chưa kiểm chứng ghi thực tế: thêm/sửa/ngừng sử dụng khách, tạo booking, POS bán hàng, trừ kho, Checkout kết toán, payment retry/callback hoặc cập nhật doanh thu qua giao dịch mới. Không thực hiện các thao tác này trên dữ liệu dùng chung.
- Browser smoke xác nhận màn hình/modal chỉ đọc, không phải CRUD/settlement E2E. Checkout Web User được rà soát source; không tạo booking mới để kiểm tra redirect thanh toán.
- Chưa diễn tập restore hoặc bootstrap database sạch trong lượt này. Backup và kết quả đồng bộ đã xác nhận trước được giữ nguyên.
- **VNPay Sandbox E2E không thuộc phạm vi nghiệm thu theo yêu cầu hiện tại.** Không coi việc chưa chạy Sandbox là điều kiện chặn của phạm vi mới.

Bằng chứng cuối: backend/target/final-review-unit-tests.log, final-review-audit.log, final-review-fingerprint.log và runtime-admin-mobile.png. Đây là artifact kiểm tra cục bộ; không đưa token/credential vào báo cáo.

## Shared development database synchronization — 2026-11-05

This section supersedes the earlier test-only runtime status below. User explicitly confirmed bcm-postgres is the shared development database, not production. Verified Compose project/working directory, database badminton_court_db, main external volume badminton_court_management_postgres_data and backend datasource targeting the Compose postgres service. Connection identity was read from container POSTGRES_USER/POSTGRES_DB; secrets were not copied into new scripts.

### Backup and database changes

Full custom archive: backups/bcm-before-sync-20261105.dump. SHA256: 2EB0CBB71C62290015BB1CF38BCAD3AE3D5EFD249E59AC930C968D58A9D7E2F9. pg_restore --list parsed 141 TOC entries; complete extraction to SQL succeeded (132143 bytes). A destructive restore/rehearsal was not attempted on the shared database. Recovery procedure is in db/README.md.

Read-only audit captured columns, pg_get_constraintdef, indexes, seed, paid totals and complete-row hashes before changes. Existing promotion snapshot columns already matched JPA mappings, but catalog/product/campaign validation upgrades and the two automatic court promotion seeds were missing. Applied existing SQL, without editing historical migrations:

1. booking-ux-upgrade.sql.
2. product-promotions-upgrade.sql.
3. court-promotions-upgrade.sql.

All succeeded. Repeated execution succeeded with zero promotion/rule INSERTs, confirming no duplicate seed. No new SQL file was necessary. Existing schema and seed differences are represented by these retained repository scripts.

| Data | Before | After |
| --- | ---: | ---: |
| Users | 5 | 5 |
| Customers | 4 | 4 |
| Bookings / details | 66 / 21 | 66 / 21 |
| Payments | 13 | 13 |
| Invoices / details | 2 / 0 | 2 / 0 |
| Products | 1 | 1 |
| Courts / slots | 210 / 16 | 210 / 16 |
| Promotions / rules | 5 / 5 | 7 / 7 |

Complete-row hashes for all protected data above remained identical, including after backend restart and API checks. Only COURT-BILL-300 and COURT-BILL-500 plus their two rules were inserted. Existing paid invoice totals remained CASH 630000 and VNPAY 320000. No historical transaction, stock quantity, deadline or status was rewritten. Audit evidence: backend/target/runtime-before-sync.log, runtime-after-sync.log, runtime-fingerprint-before.log, runtime-fingerprint-after.log and runtime-fingerprint-final.log.

### SQL and Docker cleanup

- docker-compose.yml: removed automatic revenue-breakdown-sample.sql initialization. It creates report invoices and must remain opt-in. Kept the SQL file and existing data.
- db/README.md: replaced stale paths with the actual ordered bootstrap and SQL inventory, existing-volume upgrade rules, optional demo classification and restore plan.
- scripts/start-shared-docker.ps1: now preserves a host backup before upgrades, adds the consolidated booking-ux upgrade to the existing-volume sequence, and resolves psql identity from container environment.
- Kept all historical migration files. the obsolete member2-demo.sql was subsequently removed during requested cleanup; product-promotions-upgrade.sql / court-promotions-upgrade.sql remains documented recovery-only, outside canonical bootstrap. No SQL file was deleted merely because its columns overlap another upgrade.
- Removed verified test containers bcm-invoice-admin-tests-20261105 and bcm-invoice-regression-tests-v2. Their mounts were separate anonymous volumes and their network was Docker's shared bridge. Kept volumes and bridge; removed no global network or main volume. Older stopped audit containers were retained because their lifecycle/dependencies were not revalidated in this task.
- Rebuilt/recreated only bcm-backend. It runs against the existing database with schema validation, restart count 0 at verification. bcm-postgres remained healthy. Web Admin/User Vite servers were started on 5173/5174. No new test container was created.

### Actual checks and limits

- Admin login 200; GET customers list/detail, invoices list/detail, POS products/customers, checkout active bookings, Analytics revenue, public branches and health: all 200.
- Existing seeded CUSTOMER login/history: 200. CUSTOMER access to admin customers: 403.
- CUSTOMER CASH and BANK_TRANSFER payment POSTs: 400 with VNPay-only message. Used non-existing UUIDs to verify rejection before database lookup/mutation without initiating any payment. Complete-row hashes remained unchanged. This does not claim successful paid-booking E2E.
- Source inspection confirms PosService and CheckoutService retain CASH, BANK_TRANSFER and VNPAY. No enum/formula/callback/shared transfer logic changed in this task. Historical invoices remain readable through Main–Sub.
- Read-only browser smoke through installed Edge: Customer, Invoice, POS and Checkout panels opened; saved invoice Main–Sub modal opened. At 375px the document had no horizontal overflow; screenshot backend/target/runtime-admin-mobile.png was inspected. API writes were blocked during this browser check. This was not a CRUD or settlement E2E test.
- Web Admin build PASS; Web User build PASS. Independent payment/gateway/signature unit tests: 53 PASS, zero failures/errors/skips (runtime-unit-tests.log). git diff --check PASS. Docker packaging uses the existing Dockerfile, which skips tests; packaging is not reported as regression verification.
- Runtime VNPay configuration is present (presence only checked, no secrets printed). No real Sandbox redirect/payment/IPN was performed. No booking creation, POS sale, stock mutation, customer mutation or checkout settlement was executed on the shared dataset.
- No full integration suite was run against bcm-postgres. Earlier full regression remains unresolved; no full-regression PASS is claimed. Clean-database bootstrap order was reviewed against Compose, but a new clean database was not created in this task. Backup was parsed fully, not restore-rehearsed.

Task-local source changes: docker-compose.yml, db/README.md, scripts/start-shared-docker.ps1 and this report. Earlier feature/test edits remain in the working tree. Production business source, UI/CSS and historical migration files were not changed in this task. No push/merge performed.

## Customer booking VNPay restriction

CustomerCheckout now renders only VNPay and posts the literal paymentMethod VNPAY without bankTransfer. PaymentInitiationService rejects every other method before gateway preparation or PaymentAttemptService persistence. The booking payment controller is the only controller calling this initiation service. Shared transfer processing, PaymentMethod enum, POS, Checkout, historical payment rendering and database schema are unchanged. Existing conflicting pending transfer attempts remain protected by PaymentAttemptService's method conflict check; no automatic conversion or deletion is introduced.

Validation for this change: web-app build PASS; PaymentInitiationServiceTest, PaymentGatewayPreparationTest, VnPayGatewayTest and VnPayIpnVerifierTest: 53 tests PASS, 0 failures/errors/skipped (backend/target/vnpay-only-tests.log). Browser and full payment integration verification remain outstanding. Full backend regression is still incomplete; the earlier full-suite failure below is not superseded by these targeted results.

Working branch: `feature/invoice-admin`, based on develop `c1188ce`.

## API

All endpoints below require ADMIN through the existing security configuration and method security.

- `GET /api/admin/customers?search=&page=0&sort=created`: 20 customers per page; `sort=name` sorts by name. Booking count includes all saved bookings. Spending includes only undeleted PAID invoices.
- `POST /api/admin/customers`: fullName, email, phone, address, password. Password is required for creation. Creates an actual User with CUSTOMER role and Customer in one transaction.
- `PUT /api/admin/customers/{id}`: updates profile fields on both entities. Does not change passwords.
- `DELETE /api/admin/customers/{id}`: soft deletes Customer and User; preserves bookings and invoices. Staff/admin identities and the existing POS guest phone are protected.
- `GET /api/admin/customers/{id}`: booking history and paid invoice history.
- Existing `GET /api/analytics/invoices`: preserves period/date/from/to/page; adds search and method. Search matches invoice UUID, booking UUID and customer name before pagination.
- Existing `GET /api/analytics/invoices/{id}`: Summary now also contains invoiceStatus and bookingStatus, read independently from persisted entities. Retail bookingStatus is null.

No invoice mutation API is added. POS and Checkout remain the only invoice creation paths. No price, stock, promotion, revenue or settlement formula is changed.

## UI

App adds ADMIN-only customer and invoice entries outside masterTabs. Customers.jsx uses existing table, form and dialog classes. RevenueInvoices.jsx is reused for the invoice page and viewing customer invoices. Successful ADMIN retail sales and checkout open the returned invoice ID. STAFF keeps existing POS/Checkout access.

## Limits

- Product/court names use the existing current-name fallback; no historical name snapshot exists in this schema.
- The invoice detail retains saved header and line amounts. It does not infer an original advance-payment breakdown from current product prices or invent a settlement snapshot.
- Customer detail currently loads all saved booking/paid-invoice history for that customer.
- Existing JWTs contain stateless identity claims. Soft deletion prevents new login and active-customer resolution; this change does not add global token revocation.
- Browser visual/responsive and end-to-end interaction checks still need execution; production readiness must not be inferred from builds alone.

## Validation

### Continued regression work (2026-11-05)

- Added SlotFixtureIsolation test listener for suites that build their own active slot catalog. It temporarily retires the pre-existing templates outside the test transaction and restores those exact rows after test cleanup/rollback. Exclusion constraints remain active for all fixture writes. Applied to BookingModification, BookingPaymentLifecycle, BranchBooking, CheckoutHold and BookingHistory.
- Corrected negative court codes in BookingPostgresTest, BookingExpirationPostgresTest, BranchBookingPostgresTest and CheckoutHoldPostgresTest. Previously verified Mapping/Availability/Concurrency fixtures are unchanged in this continuation.
- Added scripts/verify-backend-postgres.ps1: fixed isolated container bcm-invoice-regression-tests-v2, port 55437, database bcm_regression_test; migration followed by member2, product-promotions, booking-ux and court-promotions upgrades. Fresh bootstrap succeeded. The script defaults to two verify runs, stopping on Maven failure. It never targets bcm-postgres or removes volumes. Local Java/Maven locations match the workspace tools used in prior runs.
- Targeted BookingHistory/BookingModification/BranchBooking/CheckoutHold run: 101 tests, 4 failures, 0 errors, 0 skipped (97 passed). No remaining slot insertion errors in this run. Failures: obsolete payment Swagger expectation and three tests using BANK_TRANSFER through the now VNPay-only customer endpoint. Updated the Swagger expectation and changed the expired-booking test to use the supported VNPAY method; those edits have not yet been rerun.
- Added two HTTP rejection cases to BookingPaymentLifecyclePostgresTest checking no payment insertion and unchanged booking/voucher fields for CASH/BANK_TRANSFER. These new cases have not yet been executed.
- Full verify attempt was interrupted by the bootstrap runner treating a harmless JVM stderr warning as a PowerShell terminating error. Adjusted runner to use Maven exit status; two successful full runs are still outstanding. Pool exhaustion has not yet been ruled out by a complete run.
- Web Admin build PASS; Web User build PASS; git diff --check PASS for this continuation. Browser E2E, responsive interaction and real VNPay Sandbox interaction have not been executed. No real Sandbox credentials were used, so no Sandbox E2E success is claimed.
- Existing transfer-success API tests in BookingPaymentLifecycle, BookingModification and BranchBooking still need deliberate separation of shared transfer-service compatibility coverage from CUSTOMER VNPay-only HTTP coverage. Full regression is NOT complete.

No production business logic, CSS/UI or main database data was changed during this continuation. All new fixture/bootstrap code is test infrastructure. No push/merge performed.

Frontend: `npm.cmd run build` in web-admin. Backend: Java 17, Maven 3.9.9, `mvn -f backend/pom.xml verify` with a workspace-local Maven repository.

PostgreSQL testing uses isolated `bcm-invoice-admin-tests-20261105` on port 55436, initialized with migration.sql, booking-ux-upgrade.sql, product-promotions-upgrade.sql, booking-ux-upgrade.sql and the pre-existing product-promotions-upgrade.sql / court-promotions-upgrade.sql. No main database or volume reset is performed.

Final results:

- web-admin `npm.cmd run build`: PASS.
- `git diff --check`: PASS.
- AdminCustomerIntegrationTest: 5 PASS; AdminCustomerPostgresTest: 5 PASS, including synchronized account/profile, password hashing, duplicate rejection, list/detail, soft deletion and role checks.
- CustomerAuthPostgresTest: 13 PASS; CheckoutReceiptPostgresTest: 5 PASS; CheckoutReleaseMigrationPostgresTest: 1 PASS; AnalyticsIntegrationTest: 7 PASS.
- Full PostgreSQL-enabled Maven verify: FAILURE, 558 tests, 12 failures, 240 errors, 11 skipped. See backend/target/invoice-admin-verify-final.log. Failures include legacy negative court-number fixtures conflicting with master_court_values from member2-upgrade, promotion fixture expectations, and exhaustion of PostgreSQL connections across cached Spring contexts (`too many clients already`). These have not been repaired or bypassed. Full regression acceptance remains incomplete.
# Quản lý Tài khoản và phân quyền báo cáo — 2026-11-05

Phần này thay thế mô tả menu Khách hàng/Doanh thu cũ ở các biên bản phía dưới.

- Nhánh thực hiện: `feature/invoice-admin`. Không push/merge, không sửa SQL/schema hoặc dữ liệu phát triển trong đợt này.
- Sidebar Quản lý dữ liệu: **Tài khoản**, Sân cầu, Khung giờ & giá, Danh mục, Hàng hóa & dịch vụ, Khuyến mãi. Vận hành & POS: Hóa đơn, Bán tại quầy, Trả sân, Nhập kho, Thống kê, Doanh thu theo đối tượng. Hai báo cáo chỉ ADMIN; quyền vận hành và quyền xem khuyến mãi của STAFF giữ nguyên.
- Trang Doanh thu cũ đã bỏ khỏi menu, lựa chọn URL, JSX, state/effect và request riêng trong `App.jsx`. URL `?tab=analytics` không còn mở trang cũ. Giữ `AnalyticsService`, API tổng hợp doanh thu, DTO và repository vì đây là contract tính doanh thu dùng chung, được dùng để đối chiếu Checkout/Statistics/RevenueBreakdown; giữ `RevenueInvoices`, `PeriodFilter` và `.analytics-panel` vì các màn hình còn lại vẫn dùng. Không xóa dữ liệu doanh thu.

## Schema và API Tài khoản

Đối chiếu PostgreSQL bằng transaction READ ONLY: `users.id`, `roles.id`; `user_roles` có PK kép `(user_id, role_id)` và hai FK; `customers.user_id`, `staffs.user_id` có UNIQUE/FK. Vì vậy một User có nhiều Role, tối đa một profile mỗi loại. Database phát triển hiện có 1 ADMIN, 4 CUSTOMER và không có STAFF; ADMIN hiện không có Staff profile. Không suy luận vai trò từ profile hoặc email.

- API ADMIN mới: `GET /api/admin/accounts?search=&role=&sort=created|name&page=0`, `GET /api/admin/accounts/{userId}`, `PUT /api/admin/accounts/{userId}`, `DELETE /api/admin/accounts/{userId}`.
- Danh sách phân trang 20 User duy nhất, tổng hợp vai trò theo ADMIN → STAFF → CUSTOMER trong một truy vấn; tìm tên/email/SĐT, lọc Role bằng EXISTS. Không query từng role/profile riêng cho mỗi dòng, không trả hash/token. Bao gồm tài khoản đã khóa; tài khoản POS vãng lai được bảo vệ khỏi sửa/khóa.
- Chi tiết Customer giữ địa chỉ, số lượt đặt, chi tiêu PAID, Booking và Invoice; Staff có chức vụ thật nếu tồn tại profile; ADMIN không bắt buộc có profile. Địa chỉ Customer dùng fallback từ users khi profile không có địa chỉ.
- PUT chỉ nhận họ tên, email, SĐT, địa chỉ; đồng bộ User/Customer/Staff trong transaction, không nhận password, Role hoặc ID. Kiểm tra email trùng cả tài khoản đã khóa.
- Tạo mới vẫn gọi `POST /api/admin/customers`: nút **Thêm khách hàng**, chỉ tạo CUSTOMER cùng User và mật khẩu hash; không cấp STAFF/ADMIN.
- Khóa là soft delete User/profile, không xóa lịch sử. Khóa row User; tuần tự hóa thao tác khóa ADMIN bằng row Role hiện có, kiểm tra lại ADMIN đang thực hiện, chặn tự khóa và ADMIN cuối cùng. Không có mở khóa giả.
- JWT filter tải trạng thái tài khoản và quyền hiện tại từ database trên mỗi request. Token đã phát hành không vượt qua được khóa tài khoản hoặc việc gỡ quyền ADMIN. Login/refresh của tài khoản khóa cũng bị từ chối theo AuthService hiện có.
- `AnalyticsController` đã có `hasRole('ADMIN')` ở cấp class; giữ nguyên bảo vệ `/analytics/statistics`, `/analytics/revenue-breakdown` (cả detail), invoice và tổng hợp doanh thu. Không có export endpoint riêng trong controller này. Không đổi quyền POS/Checkout.

## File của đợt này

- Mới: `AdminAccountController.java`, `AdminAccountService.java`, `AdminAccountUpdateRequest.java`, `AccountSecurityAcceptanceTest.java`, `web-admin/src/Accounts.jsx`.
- Cập nhật: `JwtAuthenticationFilter.java`, `web-admin/src/App.jsx`, `web-admin/src/styles.css`, tài liệu này và `docs/feature-acceptance-report.md`.
- Component Customers của working tree trước đây được thay bằng Accounts; không còn import/render trang Khách hàng riêng. Trang Doanh thu trước đây nằm trực tiếp trong App, không có file component riêng để xóa. Các diff từ những task trước không bị hoàn tác.

## Kiểm chứng

- `scripts/verify-feature-acceptance.ps1`: **22 tests, 0 failures, 0 errors, 0 skipped**, chạy lại cùng kết quả. Gồm 16 nghiệm thu hiện có và 6 test mới dùng PostgreSQL `bcm_acceptance_test`, login/JWT thật cho ADMIN/STAFF/CUSTOMER, nhiều Role, sửa profile, chống nâng quyền, email trùng, khóa và thu hồi phiên, hai ADMIN khóa chéo đồng thời. Fingerprint toàn bộ 20 bảng phát triển trước/sau không đổi.
- Web Admin và Web User: `npm run build` PASS. Backend Docker compile/package và khởi động thành công, PostgreSQL healthy. Dockerfile hiện có bỏ chạy test khi package; kết quả test ở trên được chạy riêng, không lấy package làm bằng chứng test PASS.
- Edge headless, dữ liệu phát triển chỉ đọc, chặn mọi API ghi sau login: đúng toàn bộ thứ tự menu/breadcrumb; lọc ADMIN, tìm email, mở Chi tiết/Thêm/Sửa/xác nhận Khóa rồi Hủy; Hóa đơn vẫn mở được. 1440/1024/768/375px không tràn ngang cấp trang, modal trong viewport, không có pageerror JavaScript.
- Frontend STAFF direct URL `accounts`, `statistics`, `revenue-breakdown` chuyển POS và không gửi API báo cáo; quyền xem Khuyến mãi vẫn hoạt động. Browser dùng session role STAFF có kiểm soát để kiểm tra route, **không phải đăng nhập STAFF thật trên database phát triển** (database này không có STAFF). Kiểm tra API với JWT STAFF thật đã chạy trên database nghiệm thu.
- Ảnh/log cục bộ: `backend/target/accounts-{1440,1024,768,375}.png`, `feature-acceptance.log`, `acceptance-main-before.log`, `acceptance-main-after.log`.
- Không khóa/sửa tài khoản thật và không tạo giao dịch trên database phát triển. Không sửa công thức tiền, tồn kho, Booking/Payment/Invoice, CSS của POS/Checkout hoặc quy trình VNPay.
- Không chạy Full Legacy Regression; không tuyên bố Full Legacy Regression PASS. VNPay Sandbox E2E không thuộc phạm vi nghiệm thu hiện tại.

Mở Web Admin tại `http://127.0.0.1:5173/?tab=accounts`, dùng ADMIN hiện có `admin@bcm.com` với mật khẩu do nhóm đang quản lý. Kết quả nghiệm thu chi tiết: [feature-acceptance-report.md](feature-acceptance-report.md).

