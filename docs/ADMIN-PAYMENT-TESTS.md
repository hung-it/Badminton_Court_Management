# Admin payment regression checks

Tests use a separate PostgreSQL database initialized from `db/migration.sql` and
its upgrades. Never point test environment variables at the application database.

## Backend

Set `BCM_INVOICE_DEV_DB_URL`, `BCM_INVOICE_DEV_DB_USERNAME`,
`BCM_INVOICE_DEV_DB_PASSWORD`, and the corresponding `BCM_TEST_DB_*` variables to
the dedicated test database, then run:

```powershell
mvn -f backend/pom.xml '-Dtest=PosVoucherUsagePostgresTest,BookingPromotionPostgresTest,PaymentCallbackPostgresTest,PaymentInitiationPostgresTest,BookingConcurrencyPostgresTest' test
```

Coverage includes real booking creation, voucher application, CASH/VNPAY counter
receipts, merchandise drafts, mixed-method checkout, stale amounts, idempotent
retries, concurrent reservations, and signed simulated VNPay callbacks. Gateway
tests use test credentials and do not contact VNPay or move real money.

## Admin browser

Build and serve the admin application on port 5183. Install Playwright separately
or set `PLAYWRIGHT_MODULE` to its `index.mjs`; Chrome must be available. Run:

```powershell
npm --prefix web-admin run build
npm --prefix web-admin run preview -- --host 127.0.0.1 --port 5183
# In another terminal:
node scripts/verify-admin-payment-ui.mjs
```

`BCM_ADMIN_UI_TEST_URL` optionally selects another local preview URL. The browser
test mocks every API request: it never creates real invoices. It verifies buyer
phone/profile requoting, voucher versus automatic promotion labels, refreshing a
conflicting checkout bill, requiring new confirmation, and blocking payment if
refresh fails.

Application behavior: an unknown new buyer cannot qualify for KMKH10. A bill
changed by another staff member is refreshed after HTTP 409; the operator must
review the new amount and confirm again. A manual **Cập nhật bill** button is
also available. Request and response formats remain unchanged.
