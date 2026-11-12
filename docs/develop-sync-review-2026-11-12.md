# Develop synchronization review — 2026-11-12

Integrated `origin/develop` at `6c68077` into `fix/browser-flow-regressions`
(previous HEAD `38cc4ab`). The four incoming commits are `273af4e`, `49147b3`,
`26387b6`, and `6c68077` (PR #20, customer management and access control).
No remote push was performed.

## Incoming behavior

- Administrators can create customers, inspect active/locked accounts and their
  booking/invoice history, and lock/unlock CUSTOMER-only accounts. Staff/admin
  accounts and the dedicated POS guest remain protected from this operation.
- Lock/unlock synchronizes the user/customer deletion state and advances a durable
  `users.token_version` only when state actually changes. Previously issued access
  and refresh tokens remain revoked after unlocking; a fresh login works.
- Cashiers can read invoice lists/details. Invoice mutations, account management,
  statistics and revenue reporting remain unavailable to cashiers.
- The admin UI exposes the appropriate navigation permissions, customer account
  lock/unlock confirmations, and role labels “Quản lý” / “Thu ngân”.

## Integration findings and resolutions

1. **Refresh identity:** the incoming refresh validation compared the email subject
   with the current email, conflicting with the existing immutable-identity fix.
   Refresh continues to resolve `users.id`, checks the token revocation epoch and
   active account, and permits an email change for that same identity. Reusing the
   old email for another user cannot transfer the session. Access tokens still
   check their email subject and immutable identity.
2. **Protected guest:** incoming phone-based protection would hide ordinary users
   sharing the guest phone. Account/customer operations retain the configured POS
   guest UUID instead. Existing guest spoofing and identity regression tests pass.
3. **Authentication failure contract:** revoked/unavailable refresh identities
   return HTTP 401, consistently with the existing customer session handling.
4. **Regression fixtures:** updated the old cashier invoice-denial expectation to
   read-only access, kept mutation-denial checks, supplied the paged POS draft
   response in UI fixtures, and allowed test URLs on isolated Vite ports. Quoted
   history field aliases stabilize the JSON contract across H2 and PostgreSQL.
   UI tests intercept external font requests and wait for rendered navigation.

The local credential policy, normalized email uniqueness, booking/court/slot
concurrency fixes, net court fees, contact snapshots, persistent POS drafts,
booking resume and configurable payment-hold display remain present.

## Verification

- Full backend regression: **788 tests, 0 failures/errors/skips**.
- Separate HTTP acceptance database: **26 tests, 0 failures/errors/skips**.
- Frontend Node tests: **50 passed**; both React production builds passed.
- Seven Edge UI suites passed: customer P1/P2, browser flow regressions, admin
  booking fixes, POS customer mode, checkout list, and account/invoice permissions.
  These suites intercept backend requests and do not write to the project DB.
- Read-only smoke against the rebuilt Docker applications passed: admin accounts,
  cashier invoice access and blocked account navigation, the 410,000 VND net court
  fee and 06:00–08:00 schedule, draft persistence after reload, and customer history.
  No browser page errors and no business-data writes in this smoke check.
- `git diff --check` passed.

Evidence: `.tools/p1-results/develop-sync-{regression,all}.json`, the matching
`develop-sync-*.log` files, and `.tools/develop-sync-20261112/screenshots/`.
The regression runner excludes the separate acceptance suite and the existing
opt-in legacy-payment compatibility suite; no tests were newly skipped/disabled.
VNPay provider end-to-end payment was not exercised.

## Local runtime and migration

The running backend still selects **`bcm_manual_p2_20261111`**. The original
`badminton_court_db` was not migrated or selected. No volume was replaced.

Before adding the column, a complete custom-format backup was restored
successfully into `bcm_develop_sync_restore_20261112` in the isolated test container.
Only the `account-token-version` section of `booking-ux-upgrade.sql` was applied,
twice on the restored copy and once on the manual DB. Hashes of existing users
(excluding the new column), customers, bookings, invoices and payment transactions
matched before/after. All 16 restored users received epoch zero.

Backup: `.tools/develop-sync-20261112/manual-before-develop.dump`.
Migration evidence: `.tools/develop-sync-20261112/migration-evidence.json`.
Docker images were assembled offline from the existing runtime images plus the
locally verified JAR/React build artifacts; a fresh Docker Hub source build was
not used. The manual Compose override remains necessary when starting the stack.

Admin: <http://localhost:5173>; customer web: <http://localhost:5174>.
Existing sessions without the new token epoch must sign in again once.
