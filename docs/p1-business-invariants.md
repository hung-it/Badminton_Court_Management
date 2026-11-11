# P1 business fixes

## Changes

- **#1 Refresh identity:** new refresh tokens include `uid=users.id`. Refresh resolves
  only that active user, even if their email changed. Missing/malformed identity,
  invalid/expired tokens and unavailable users return HTTP 401. Email-only refresh
  tokens must sign in again; existing access tokens retain their original lifetime.
- **#9 POS guest:** `BCM_POS_GUEST_USER_ID` is required. Startup rejects an absent,
  malformed, inactive or privileged guest, or a guest without an active customer
  profile. POS and admin protection use this UUID, never a phone-number heuristic.
  Registration rejects the reserved phone and guest email. Existing accounts with
  the same phone remain intact and can be managed normally if they are not the guest.
- **#3 Court deletion:** lock the Court before checking bookings and soft-deleting.
- **#4 Slot changes:** bookings take ordered shared TimeSlot locks, administration
  takes an exclusive slot lock before checking references. Lock order is existing
  Booking, ordered Courts, ordered TimeSlots. Price/history snapshots remain intact.
- **#8 Voucher reservations:** a PENDING booking with a PENDING payment retains its
  voucher until terminal failure or atomic expiration, including the callback grace
  window. Generic limits and NEWMEMBER use the same pending-reservation predicate.
  Release can conservatively wait for the expiration job after grace; it cannot
  prematurely make a still-payable voucher available to another booking.
- **#2 Branch cards:** group the active catalog by branch, select an existing court
  deterministically, and keep hour selections keyed by branch ID. A missing court 1
  cannot hide courts 2/3; unassigned courts remain separate.

There are no schema changes, new dependencies, new payment methods or automatic
data rewrites. HTTP request/response shapes remain unchanged apart from refresh
authentication failures now consistently returning 401.

## Configure before deployment

Use a read-only query against the intended environment to identify the existing
dedicated guest. Review the customer profile and roles; do not select an arbitrary
account just because its phone is `0000000000` (the original admin seed uses that
phone too).

```sql
SELECT u.id AS guest_user_id, u.email, u.deleted_at,
       c.id AS customer_id, c.deleted_at AS customer_deleted_at,
       array_agg(r.role_name ORDER BY r.role_name) AS roles
FROM users u
JOIN customers c ON c.user_id = u.id
JOIN user_roles ur ON ur.user_id = u.id
JOIN roles r ON r.id = ur.role_id
WHERE u.email = 'guest@bcm.com'
GROUP BY u.id, c.id;
```

Set `BCM_POS_GUEST_USER_ID` to that **user UUID**, not the customer UUID, in the
local environment or Compose `.env`. The configured account needs CUSTOMER and
must have neither ADMIN nor STAFF. Missing or ambiguous historical guest data must
be reviewed before starting the new backend. Do not disable startup validation.

For an empty local database with DataSeeder explicitly enabled, configure a new
UUID first; the seeder creates the guest with that ID. If `guest@bcm.com` already
exists under another UUID, configure its existing ID instead of replacing it.

Back up the deployment database. Stop accepting requests during the coordinated
backend/frontend rollout: old backend instances do not participate in the new
locking protocol. No SQL migration or volume reset is needed. Inform users that
legacy refresh sessions require sign-in after their current access token expires.
Verify startup, sign-in, booking/payment, POS and checkout before reopening.
Do not revert to the vulnerable refresh/locking code while accepting live traffic.

## Verification commands

```powershell
# Disposable PostgreSQL container on 55437; never the project database.
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/verify-p1.ps1

# Focused P1 regression. Lock races and voucher-reservation races each repeat 10 times.
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/verify-p1.ps1 -Tests 'P1MasterDataConcurrencyPostgresTest,BookingPromotionPostgresTest,CustomerAuthPostgresTest,AuthServiceTest,JwtUtilTest,JwtAuthenticationFilterTest,WalkInGuestGuardTest,BookingConcurrencyPostgresTest,CheckoutReceiptPostgresTest,PosPromotionCodePostgresTest'

# Separate disposable acceptance container on 5433; fails if that port is in use.
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/verify-p1-acceptance.ps1

node --test web-app/booking-time.test.mjs web-app/booking-presentation.test.mjs web-app/court-images.test.mjs web-app/court-presentation.test.mjs web-app/court-selection.test.mjs web-app/venue-groups.test.mjs
npm --prefix web-app run build
npm --prefix web-admin run build
```

The full runner cleans stale classes and executes Maven from `backend`, because
historical SQL tests resolve `../db`. Do not redirect its outer output into
`backend/target` while clean is running. Logs and Surefire reports are written there
after clean completes. All three PostgreSQL environment-variable families are enabled
(`BCM_TEST_DB`, `BCM_INVOICE_DEV_DB`, `BCM_PROMOTION_TEST_DB`). The promotion test
adapter now accepts test credentials instead of requiring passwordless `postgres`.
The opt-in legacy BANK_TRANSFER backup/rehearsal suite is separate and requires its
reviewed historical backup; it is not replaced with fabricated legacy receipts.

For browser verification, run the customer Vite app on `127.0.0.1:5184` and execute
`web-app/p1-business.e2e.cjs` with an installed `playwright/test` module (or set
`PLAYWRIGHT_MODULE` to its path). This test uses explicit API fixtures and never
contacts a database. It verifies branch-without-court-1 rendering, selection and
booking payload, empty catalog and sign-in redirection after a legacy refresh 401.
Actual transaction/identity/payment behavior is covered separately by PostgreSQL
integration tests and HTTP acceptance tests.

## Results

Verified on 2026-11-11, against PostgreSQL 15 and a clean Maven build:

| Check | Result |
|---|---|
| Focused backend regression, consolidated by suite | **224 passed, 0 failed/errors/skipped** |
| Direct P1 cases within those runs | 170 passed: 80 court/slot races including parallel multi-slot bookings, 59 promotion cases, 17 customer-auth cases, 14 token/guard cases |
| Additional booking concurrency / checkout receipts / POS code cases | 9 / 40 / 5 passed |
| Account-security HTTP acceptance | 6 passed |
| Node behavioral tests | 48 passed |
| Browser API-fixture integration | Passed: missing court 1, booking remaining court, empty catalog, refresh 401 redirect, no page errors |
| Customer and admin production builds | Both passed |
| Consolidated broad regression + supplemental PG/HTTP suites | 735 executed cases: 688 passed, 29 assertion failures, 18 errors, 0 skipped |

Broad-suite counts are consolidated by suite, without counting repeated executions
of the same suite in separate commands twice. The opt-in reviewed legacy-backup
suite is intentionally outside these totals. The full project gate is **not green**.

Every one of the 47 remaining failed cases was reproduced on the **unmodified
baseline commit `f7d48ea`**, in a separate checkout and test database. Comparison
uses individual failed test-case names, not just suite totals: **zero new failed
cases**. The supplemental product PG suite and HTTP feature suite fail identically
on baseline. Local evidence is retained under `.tools/p1-results`, including
`final-verification.json` and original/clean-run logs.

| Existing failing suite | Assertion failures | Errors |
|---|---:|---:|
| AnalyticsIntegrationTest | 1 | 0 |
| BookingMappingPostgresTest | 0 | 5 |
| MasterDataIntegrationTest | 0 | 1 |
| ProductPromotionIntegrationTest | 2 | 0 |
| ProductPromotionPostgresTest | 3 | 0 |
| RevenueBreakdownIntegrationTest | 1 | 3 |
| BookingHistoryPostgresTest | 0 | 1 |
| BookingModificationPostgresTest | 1 | 2 |
| BookingPaymentLifecyclePostgresTest | 6 | 1 |
| CheckoutHoldPostgresTest | 2 | 0 |
| DatabaseConsolidationPostgresTest | 1 | 0 |
| ImportOrderTotalPostgresTest | 3 | 4 |
| PaymentAttemptPostgresTest | 1 | 0 |
| RevenueBreakdownPostgresTest | 0 | 1 |
| FeatureAcceptanceTest | 8 | 0 |

Examples include fixtures using removed booking states, receipts incompatible with
current DB constraints, HTTP IPN fixtures missing the mandatory provider pay date,
and old staff fixtures without a valid staff identity. These were not bypassed or
fixed by changing production rules. Existing promotion-suite fixtures needed for
P1 verification were updated to use valid gateway receipts, supported minimum
discount thresholds, confirmed staff receipts and current idempotency contracts.

No production database or application deployment was performed. P1 implementation
and focused regression are complete; project-wide acceptance still requires the
baseline failures to be resolved separately before release.
