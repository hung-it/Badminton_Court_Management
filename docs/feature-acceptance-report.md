# Current acceptance — CASH / VNPAY, 2026-11-05

Feature Acceptance: **25 PASS / 0 FAIL / 0 ERROR / 0 SKIPPED** on `bcm_acceptance_test`. Additional targeted Payment/VNPay/Promotion/Checkout run: **110 PASS / 0 FAIL / 0 ERROR / 0 SKIPPED**. No full legacy certification.

New negative tests reject removed-method/QR requests in Customer booking, POS sale/quote and Checkout without changing transactions, invoice, inventory, booking or voucher; enum/database CHECKs accept exactly CASH/VNPAY. Missing/mismatched court receipts cannot be treated as paid. CASH/VNPAY POS/Checkout remain covered, plus the existing signed fake IPN/callback, Customer/Account/Invoice/Analytics tests.

Run acceptance using `powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/verify-feature-acceptance.ps1`. Bootstrap copies individual official SQL files into the container to avoid nested stale directory contents. It refuses the main DB URL and compares main-data fingerprints before/after tests. Migration cleanup runs separately with explicit authorization, not from acceptance tests. All source tests compile; the full legacy suite was not run.

Browser tests executed: `web-admin/checkout-list.e2e.cjs` (2 methods, no checkbox, stored prepayment/due, mobile, controlled checkout payload), `web-app/payment-result-status.e2e.cjs` (5 controlled status cases, refresh/mobile, no writes), and local read-only runtime smoke for Account, Invoice and POS plus Web User VNPay-only request/controlled redirect. The broader old write E2E scripts were migrated to controlled VNPay fixtures and syntax-checked, not all run. Never run them on shared main data; they require an isolated configured test runtime and test signing key. No real Sandbox payment.

Backend compile/startup, both frontend builds, Compose config and diff check passed. Full database cleanup counts, archive/checksum, exact UUID allowlists, finance/stock fingerprint preservation and the separately identified natural expiration event are documented in [admin-customers-invoices.md](admin-customers-invoices.md). Main data was intentionally changed ONLY by the reviewed cleanup and normal existing expiry scheduler; no write test ran against main.

VNPay Sandbox E2E is outside the current acceptance scope. Earlier blocked-removal and three-method reports below are historical, not the deployed contract.

---

## Historical verification

# Feature acceptance report

Latest requested BANK_TRANSFER removal is blocked by existing payment history; no two-method production/acceptance change has been applied. The 16-case results below certify the preceding three-method POS/Checkout contract only. See docs/admin-customers-invoices.md for blocked record IDs. Customer/Invoice UI layout changes were browser/build checked separately without locking or mutating shared customer accounts.

Branch: `feature/invoice-admin`. This is a new PostgreSQL acceptance suite, independent of legacy test fixtures and test inheritance. Legacy regression PASS is **not** claimed.

## Run

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/verify-feature-acceptance.ps1
```

Requires the existing development PostgreSQL instance (`bcm-postgres`, localhost:5433), Docker access, and the workspace Java 17/Maven tools under backend/target/phase1-tools. No new Docker container is created. The runner uses only `-Dtest=com.bcm.acceptance.*Test` and explicitly disables JUnit parallel execution. Maven compiles the test source set but executes only the new acceptance class. It does not run legacy tests or use skipTests.

## Database isolation and bootstrap

- Dedicated database: `bcm_acceptance_test`; dedicated owner/login: `bcm_acceptance_owner`, with no superuser, CREATEDB, CREATEROLE or replication privilege. Its password is a public, local acceptance-only fixture credential, never a real gateway or application credential.
- The script refuses an externally supplied non-acceptance URL. Verified with badminton_court_db: exit 1 before database access. Java validates the exact acceptance URL before Spring creates its datasource; each fixture also asserts current_database().
- Role privileges were checked on the main DB: zero public tables writable by the acceptance role.
- Bootstrap uses repository SQL: migration.sql only for an empty acceptance schema, then booking-ux-upgrade.sql, booking-ux-upgrade.sql, booking-ux-upgrade.sql, product-promotions-upgrade.sql and court-promotions-upgrade.sql. Branch catalog/demo reporting seed is unnecessary for these minimal unbranched fixtures and is not loaded. The application creates its ordinary synthetic admin/guest identities in the acceptance database; no real data is copied.
- First bootstrap succeeded on a new database. Repeated bootstrap succeeded; each of COURT-BILL-300, COURT-BILL-500, NEWMEMBER and KMKH10 has one promotion row. Active seed slots are reused without modifying them; all constraints stay enabled.
- Tests intentionally commit for concurrency/callback assertions. Each case creates UUID-scoped users/customers/courts/products and deletes only its own dependent rows afterward. Explicit post-run checks found zero leftover acceptance users, courts or categories. Seed/admin/guest rows remain.
- Main database is accessed only in READ ONLY transactions to fingerprint all application tables (including payments, invoices, stock, roles, catalog and import tables). Before/after fingerprints matched. No main database schema/data, migration history, container or volume was modified by the suite.

## Cases and coverage

`backend/src/test/java/com/bcm/acceptance/FeatureAcceptanceTest.java` contains 16 executed cases, counting parameterized invocations:

| Case | Count | Contract checked |
| --- | ---: | --- |
| customerCrudHistoryAndHashedAccount | 1 | ADMIN create/list/search/page/update/soft-delete, duplicate email, encoded password, synchronized profile, retained booking/invoice history; real login and JWT history request |
| managementAuthorization | 2 | STAFF/CUSTOMER forbidden from customer admin and invoice analytics |
| bookingHoldExpiryAndPastSlot | 1 | PENDING hold, exact court/slot, duplicate 409, past date 400, expired initiation rejected without payment, expiration retains header and deletes slot details |
| customerOnlyVnpayNoMutations | 2 | CASH/BANK_TRANSFER HTTP 400; no transaction, unchanged booking/voucher snapshots |
| vnpayVerifiedSuccessRetryAndDuplicates | 1 | Same-attempt retry; initiation leaves PENDING; signed successful IPN confirms SUCCESS/PAID; repeated IPN cannot add payments |
| invalidSignatureAmountFailureRetryAndLateCallback | 1 | Invalid signature 97, amount mismatch 04, verified failure, separate retry, late callback cannot mark booking PAID |
| foreignBookingCannotPay | 1 | Foreign owner 403, no payment |
| posCheckoutInvoiceAndRevenue | 3 | CASH/BANK_TRANSFER/VNPAY POS and Checkout; totals, oversell rollback, stock once, DRAFT detail attachment, cancelled draft exclusion, one settlement, COMPLETED booking, prepaid court/due amounts, saved prices despite master price change, retail bookingStatus null, independent invoice/booking statuses, UUID/customer search, method filter, pagination, soft-deleted/DRAFT/CANCELLED exclusion, date-filtered revenue and read-only invoice header |
| promotionBestOfferIsNotStackedAndLocksAfterAttempt | 1 | Seeded automatic 30000 discount beats NEWMEMBER 5% on 400000, no stacking, persisted payment amount 370000; promotion immutable after attempt |
| paidBookingCannotCreateAnotherPayment | 1 | Settled booking rejects another initiation; one payment retained |
| retailRepeatHasNoIdempotencyKeyInCurrentContract | 1 | Documents current POS repeat behavior: two retail requests create distinct sales and decrement stock per accepted sale |
| controlledConcurrentBookingHasOneWinner | 1 | Two independent requests released by latches; exactly 201/409, one booking and one detail, no partial loser rows |

MockMvc executes the actual controllers/security/service/database stack. Most role identities use Spring Security test request principals; the customer CRUD case additionally exercises real password authentication and the JWT filter. Callback fields use controlled local HMAC signatures with test-only merchant configuration and real verification/transaction services. Gateway initiation builds its URL locally; no network request or real VNPay Sandbox payment is sent.

## Results

- Initial 13-case run: 12 passed / 1 failed / 0 errors / 0 skipped. The new test incorrectly assumed expired initiation returns 409; source `PaymentAttemptService.requireEligible` explicitly returns BadRequestException/400. Corrected the new test to that existing contract, without changing production behavior. Rerun: 13/13 PASS.
- Expanded suite: **16 PASS, 0 FAIL, 0 ERROR, 0 SKIPPED** on PostgreSQL. See backend/target/feature-acceptance.log and TEST-com.bcm.acceptance.FeatureAcceptanceTest.xml.
- Final confirmation after strengthening concurrency/callback row-count assertions and the historical invoice fixture: **16/16 PASS** again; main fingerprint unchanged. Final run finished 2026-11-05 10:59:48 local time.
- Web Admin `npm.cmd run build`: PASS. Web User `npm.cmd run build`: PASS.
- `git diff --check`: PASS.
- Runner failure path was exercised by the initial failed assertion: nonzero exit and main fingerprint comparison in finally. Wrong-main-URL refusal was also exercised. Before/after evidence: backend/target/acceptance-main-before.log and acceptance-main-after.log.

## Limits and observed contract gap

POS retail `PosSaleRequest` has no idempotency key. Identical retail requests are separate accepted sales under the current implementation; the suite explicitly records this rather than claiming duplicate-submit protection. If network-retry deduplication is a product requirement, it needs an agreed idempotency contract and controlled production change. No such change is included here. Booking attempts, callbacks and checkout duplicate protection are separately tested.

No new production defect requiring a business logic change was established. Validation covers the cases above, not every legacy scenario or high-load schedule. No legacy tests were edited, removed, disabled or executed for this task; pre-existing working-tree edits from prior tasks remain untouched. No full legacy regression PASS, real Sandbox E2E or new browser E2E is claimed. VNPay Sandbox E2E is outside acceptance scope.

New task files: FeatureAcceptanceTest.java, scripts/verify-feature-acceptance.ps1 and this report. docs/admin-customers-invoices.md links to this report. No production code, UI/CSS, historical SQL, main data or Docker topology change; no push/merge.
# Bổ sung nghiệm thu Tài khoản và quyền báo cáo — 2026-11-05

Kết quả mới nhất: **22 PASS / 0 FAIL / 0 ERROR / 0 SKIPPED**, gồm 16 test `FeatureAcceptanceTest` giữ nguyên và 6 test mới `AccountSecurityAcceptanceTest`. Hai lượt chạy sau khi bổ sung concurrency đều thành công. Chạy lại bằng `powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/verify-feature-acceptance.ps1`.

Test mới sử dụng database `bcm_acceptance_test` và tài khoản UUID riêng, BCrypt password, gọi `/auth/login` để lấy JWT thật; dọn đúng fixtures sau mỗi test. Không mock quyền backend.

| Test | Contract đã kiểm chứng |
| --- | --- |
| uniqueUsersRoleOrderProfilesAndPagination | CUSTOMER/STAFF/ADMIN, ADMIN không có Staff, nhiều Role không lặp, thứ tự Role, chức vụ, search/filter/page và không lộ hash/token |
| realJwtRolesGuardAllReportsAndOperations | ADMIN 200, STAFF/CUSTOMER 403, anonymous 401; STAFF POS/Checkout vẫn 200 |
| updateSynchronizesProfilesButNeverPasswordOrRoles | User/Customer/Staff đồng bộ, trùng email 409, password/Role không bị đổi qua profile DTO |
| locksRejectLoginAndPreviouslyIssuedAccessAndRefreshTokens | Chặn tự khóa, soft lock Staff/Customer, access token cũ 401, login 401, refresh bị từ chối không phát token |
| staleElevatedClaimsDoNotBypassCurrentDatabaseRoles | JWT đã phát hành với ADMIN không vượt quyền sau khi role trong DB bị gỡ; POS quyền STAFF vẫn dùng được |
| concurrentAdminCrossLocksLeaveAnActiveAdmin | Hai request độc lập cùng khởi chạy; chỉ một khóa thành công, một bị từ chối, vẫn còn ADMIN hoạt động; chỉ thay đổi fixtures trong DB nghiệm thu |

Fingerprint 20 bảng database phát triển trước/sau trùng nhau. Không có thao tác ghi test lên `badminton_court_db`. Không thay đổi schema hay SQL.

Web Admin và Web User build PASS. Edge headless xác minh menu, breadcrumb, tìm kiếm/lọc Role, modal thêm/sửa/chi tiết/khóa (chỉ mở và Hủy), Invoice vẫn mở, không pageerror; 1440/1024/768/375px không overflow cấp trang, modal trong viewport. Browser STAFF route guard dùng session role có kiểm soát, backend STAFF dùng JWT thật như bảng trên. Không tạo STAFF hay khóa tài khoản thật trong database phát triển.

Chi tiết API, file thay đổi và phần code tài chính giữ lại do dùng chung nằm trong [admin-customers-invoices.md](admin-customers-invoices.md). Trang Doanh thu cũ bị bỏ; contract tổng hợp doanh thu vẫn giữ để không phá các nghiệp vụ và kiểm tra đối soát dùng chung.

**Giới hạn:** không chạy full legacy suite; chưa thử ghi giao dịch trên database phát triển; không thử cấp quyền ADMIN/STAFF (không cung cấp chức năng này). VNPay Sandbox E2E không thuộc phạm vi nghiệm thu theo yêu cầu hiện tại. Acceptance PASS không đồng nghĩa Full Legacy Regression PASS.

