# P2 fixes and regression recovery

Branch: `fix/p2-and-regression`, based on P1 commit `3dd9211`.

## Behavior

| Issue | Result |
|---|---|
| #5 | Editing a court retains its existing type; both STANDARD_MAT and WOODEN_FLOOR are selectable and displayed correctly. |
| #6 | Counter booking lists prefer the booking's contact name and phone, falling back separately to the customer profile for missing fields. |
| #7 | History summaries expose `discountAmount` and `finalAmount` from the booking snapshot. The UI shows the net court fee and expands gross/discount/net details. |
| #10 | Product edits acquire the same row lock as POS inventory operations. Any DRAFT invoice line blocks a type change. Cancelling an inconsistent legacy SERVICE draft returns 409 atomically instead of restoring service stock. |
| #11 | New passwords require at least 8 characters and at most 72 UTF-8 bytes. Existing current passwords up to 100 characters can still be checked and changed; existing hashes are preserved. |
| #12 | Registration, login and administrator email edits use lowercase normalized email. A database unique index protects concurrent registrations and edits, including soft-deleted identities. |
| #13 | New/edited court prices must be positive. Legacy zero-price courts remain visible to administrators but are excluded from public booking, availability and quotes. Historical booking prices remain snapshots. |

Regression recovery also fixes two production defects: payment initiation now
preserves booking status in its response, and checks ownership/state/deadline
before gateway configuration so a configuration error cannot hide an expired
booking. Existing 400/409 semantics remain in place.

The original 47 failing cases are tracked individually in
[p2-regression-ledger.md](p2-regression-ledger.md). PostgreSQL-dependent POS and
revenue suites now use PostgreSQL rather than H2: advisory locks and receipt
reconciliation are part of the behavior under test. Test receipts use actual
database-backed staff/admin identities, valid payment provenance, and explicit
idempotency/amount confirmation. No test is removed or disabled to obtain green.

## Database rollout

Apply `db/upgrades/email-identity-upgrade.sql` to an existing database before
starting the updated application. Compose initialization includes it for fresh
volumes; initialization mounts do not upgrade an existing volume. The shared
Docker startup script takes a backup before running upgrades. No main database
was changed during this implementation.

Use a maintenance window and a verified backup. This read-only preflight includes
soft-deleted accounts:

```sql
SELECT lower(btrim(email)) AS normalized_email,
       array_agg(id ORDER BY id) AS user_ids
FROM users
GROUP BY lower(btrim(email))
HAVING count(*) > 1;
```

If it returns rows, resolve ownership with the operator first. The migration
itself locks writes, repeats the collision check, and fails before changing any
account on a collision. It never merges/deletes accounts. On a clean database it
normalizes email, creates `uq_users_email_lower`, and can be rerun. Passwords,
user IDs, roles and booking/invoice history are preserved.

Inspect these legacy records without repairing them automatically:

```sql
SELECT id, court_code, base_price FROM courts
WHERE deleted_at IS NULL AND base_price <= 0;

SELECT DISTINCT p.id, p.name, i.id AS invoice_id
FROM products p JOIN invoice_details d ON d.product_id=p.id
JOIN invoices i ON i.id=d.invoice_id
WHERE p.type='SERVICE' AND d.stock_deducted=true AND i.status='DRAFT';
```

Set a valid court price through the administrator UI to return a zero-price court
to the catalog. An inconsistent legacy service draft requires an operator to
reconcile its product type and inventory provenance before cancellation; do not
invent a stock adjustment. Keep the unique index if application code is rolled
back; dropping it would reintroduce email identity races.

## Verification

Backend and HTTP acceptance use separate disposable PostgreSQL containers and
mandatory environment variables. The opt-in historical BANK_TRANSFER backup
compatibility suite remains outside the mandatory suite, as in the P1 runner.
There are no skipped tests inside the mandatory selected suites.

```powershell
powershell -NoProfile -File scripts/verify-p1.ps1
powershell -NoProfile -File scripts/verify-p1-acceptance.ps1
```

Frontend checks include both production builds, 48 existing Node cases,
`web-app/p1-business.e2e.cjs`, `web-app/p2-business.e2e.cjs`, and
`web-admin/booking-fixes.e2e.cjs`. Browser checks intercept every API request and
do not contact any project database. Set `PLAYWRIGHT_MODULE` to an installed
`playwright/test` module; start customer Vite on 5184 and admin Vite on 5185 for
the P2 browser suite.

Two clean `mvn verify` regression runs passed on the same disposable database:
741 tests each, zero failures, errors or skips (2026-11-11). This includes 31 new
cases: 25 P2 PostgreSQL cases, 4 focused regression cases and 2 email migration
rehearsals. The PostgreSQL P2 suite repeats each inventory race ten times and
observes an actual database lock wait before allowing the first transaction to
commit. Committed promotion-concurrency fixtures now clean their own receipts,
invoices, promotion, product and category, so later revenue suites see no leaked
sales. Build directories are cleaned before each full run; Maven runs are
sequential to avoid concurrent compilation replacing loaded test classes.

Both React production builds and all 48 Node cases passed. All three browser
suites above passed in headless Microsoft Edge, including mobile password forms,
the P1 branch/refresh checks, net history amounts, wooden-court editing, positive
court prices, and 7-character/72-byte/75-byte registration boundaries.

The final HTTP acceptance run passed 25/25 cases, zero failures, errors or skips.
Combined final reports contain 766 passing cases. All 47 original failed cases
were matched by suite and case name and verified passing; the two renamed cases
are explained in the regression ledger. `docker compose config --quiet` and
`git diff --check` also passed. All application changes are committed on the
branch above; no merge, push or deployment was performed.
