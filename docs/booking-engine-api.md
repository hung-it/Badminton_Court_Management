# Booking Engine backend contract — Phase 6

Local base URL: `http://localhost:8080/api`. OpenAPI: `/api/api-docs`;
Swagger UI: `/api/swagger-ui.html`. Controller paths do not contain `/api`.

## Auth boundary

The owner JWT filter authenticates a DB-backed UserPrincipal. CurrentCustomerService
uses its trusted users.id to query customers.user_id and returns the distinct
customers.id. Only authenticated CUSTOMER authorities may use history/detail;
ADMIN/STAFF have no invented unrestricted Booking history policy.

GET /bookings uses the trusted customer when customerId is absent. A matching
customerId remains accepted; a foreign one returns 403. Detail checks ownership
before reading details/payments and returns 403 for foreign bookings, 404 for
missing bookings. Missing or soft-deleted Customer profiles return 403 and are
never automatically created by reads. Pagination, status filtering and ordering
remain DB-side; GETs remain read-only.

Registration already accepts validated fullName, phone and optional address.
It now commits User, CUSTOMER role association and Customer profile in one
transaction. Customer insert failure rolls everything back; duplicate email is
409. No ADMIN/STAFF registration endpoint was added.

POST /bookings and payment initiation still accept domain IDs without customer
ownership checks; this remains a separate authorization limitation. Access/refresh
token purpose separation is also unchanged and remains an Auth limitation.

## Web API

Normal APIs return `ApiResponse<T>` with success/message/data/timestamp; errors
use the existing ApiResponse error envelope. Missing/invalid UUID/status/numeric
query parameters return 400. Booking status values are case-sensitive schema enums.

| Method and path (relative to base) | Inputs | Responses |
| --- | --- | --- |
| GET `/availability` | Required date YYYY-MM-DD; optional courtId | 200 grid; 400 invalid/missing input; 404 filtered court unknown/ineligible |
| POST `/bookings` | customerId; nonempty details with bookingDate/courtId/timeSlotId | 201 booking; 400 invalid/ineligible/overflow; 404 reference missing; 409 occupied slot/constraint |
| GET `/bookings` | Optional own customerId; page=0; size=20 (1..100); optional status | 200 owned paginated headers; 400 validation; 403 identity/mismatch |
| GET `/bookings/{bookingId}` | Booking UUID | 200 owned header/details/payments; 400 UUID; 403 foreign/identity; 404 missing header |
| POST `/bookings/{bookingId}/payments` | paymentMethod VNPAY or MOMO | 201 attempt/initiation; 400 ineligible/disabled/input; 404 booking; 409 method conflict; 502 provider failure; 503 config/contract unavailable |
| GET `/payments/vnpay/return` | Signed VNPay return query fields | 200 verified display information; 400 unverifiable; 503 config unavailable |

Availability is based on persisted booking_details, regardless of header status
or deadline. Only AVAILABLE, non-deleted courts appear. Creation locks Court rows
in stable UUID order, snapshots `base_price * price_multiplier` using BigDecimal
HALF_UP scale 2 per detail and sums persisted snapshots. Price/status/expiry are
server-controlled; unknown JSON input fields cannot override them. Header/details
commit atomically. PENDING hold duration is configurable; past dates remain allowed.

### History and payment display

`GET /bookings` returns `data: {content, page, size, totalElements, totalPages}`.
Headers contain bookingId/customerId/status/courtFee/expiresAt/createdAt/updatedAt.
Customer filtering and pagination happen in SQL. Ordering is createdAt DESC,
bookingId DESC; optional status is exact. An out-of-range page returns an empty content array. Foreign customer filters
return 403. List responses do not load details/payments.

Detail includes the same header fields and:

- `details`: detailId/bookingDate/courtId/timeSlotId/price/courtNumber/courtName/startTime/endTime.
  Sorted by bookingDate, courtId, timeSlotId, detailId ascending. Prices remain
  snapshots; names/numbers/times use current owner master data, not historic copies.
- `payments`: paymentAttemptId/paymentMethod/paymentStatus/amount/transactionId/transactionDate/createdAt.
  Sorted by createdAt DESC then paymentAttemptId DESC. Exposes every booking-target
  row, including CASH/BANK_TRANSFER history if present, without adding those payment
  initiation flows. Invoice-target rows are excluded even if their invoice refers to
  this booking. Payment status is read directly, never inferred from booking status.
  Nullable transaction ID/date are preserved; MoMo date remains null.

Existing booking without payments returns `payments: []`, not 404. Expiration
preserves header/courtFee/payment history and deletes details atomically; history
still shows EXPIRED with `details: []`. Deleted details are never reconstructed.
An overdue PENDING booking stays PENDING on GET until the expiration process runs.

Read services use readOnly REPEATABLE_READ for a consistent header/details/payment
snapshot during concurrent settlement/expiration. They never mutate audit/lifecycle
fields, acquire pessimistic locks, create attempts or call providers. List uses at
most three SQL statements (identity/content/count); detail uses four regardless of collection
size, with Court/TimeSlot fetched in the detail query. No global LAZY mappings changed.

### Initiation and browser return limitations

VNPay sandbox checkout is signed when configuration is valid. MoMo request/client
foundation exists but Create Payment RESPONSE signature ambiguity remains unresolved;
checkoutReady=false, artifacts withheld. All attempts remain PENDING at initiation.
Browser return verifies/displays provider data only, performs no DB reads/writes and
cannot mark Payment SUCCESS or Booking PAID. Local return URL remains
`http://localhost:8080/api/payments/vnpay/return`.

## Provider-facing IPN

These endpoints do not use ApiResponse and require cryptographic verification.
No provider network request is needed during callbacks or normal deterministic tests.
Security explicitly permits GET /payments/vnpay/ipn, POST /payments/momo/ipn and
GET /payments/vnpay/return without application JWT. Signatures remain the provider
trust boundary. Public project docs use /api-docs/**, /swagger-ui.html and
/swagger-ui/** (relative to context /api), consistent with the existing dev docs
policy. History/detail and payment initiation remain authenticated; no blanket
/bookings/** permitAll rule was added. Provider OpenAPI operations override bearer
security, while customer operations declare it.

| Endpoint | Response contract |
| --- | --- |
| GET `/payments/vnpay/ipn` | HTTP 200 JSON `{RspCode, Message}`: 00 handled success/non-success, 01 unknown, 02 confirmed replay, 04 amount mismatch, 97 checksum, 99 conflict/late/nonpayable/system failure |
| POST `/payments/momo/ipn` | HTTP 204, empty body for safely handled valid notifications including duplicate/late/conflict; defensive invalid data/signature/amount 400, unknown target/provider 404, config 503, unhandled system failure 500, all empty |

VNPay verifies HMAC-SHA512, exact attempt/provider/merchant and amount x100; success
requires ResponseCode=00 AND TransactionStatus=00. Actual transaction ID is
TransactionNo; optional PayDate is strictly parsed in GMT+7, never replaced with
server time on parse failure. MoMo verifies HMAC-SHA256 with explicit signed order:
accessKey (config), amount, extraData, message, orderId, orderInfo, orderType,
partnerCode, payType, requestId, responseTime, resultCode, transId. Payload includes
signature; numeric fields must be JSON integers, orderType=momo_wallet. Exact
orderId=requestId=attempt UUID; resultCode=0 is success, transId is preserved exactly.
responseTime is not mapped to a payment timestamp.

Both providers lock Booking then Payment, re-check deadline after lock and commit
SUCCESS + PAID atomically. Expiration locks Booking only. Exact replay does not
overwrite metadata; conflicting replay or global transaction-ID uniqueness failure
cannot partially settle. Late success never revives EXPIRED or restores details;
there is no durable late-payment evidence/refund/reconciliation in this scope.
MoMo 204 acknowledges transport handling, not settlement. VNPay has no dedicated
late code: application mapping uses 99 with a domain reason, which can trigger retries.

## Existing coverage inventory (reused, not duplicated)

| Acceptance criterion | Existing executable coverage |
| --- | --- |
| BigDecimal per-detail HALF_UP, summed snapshots | BookingPostgresTest.roundsEachSnapshotHalfUpBeforeSummingAndCommitsAllDetails; snapshotRemainsUnchangedAfterMasterPricesChange |
| Header/detail persistence rollback | BookingPostgresTest.databaseFailureRollsBackHeaderAndOtherDetail |
| Two same-slot HTTP requests: one 201, one 409, no orphan | BookingConcurrencyPostgresTest.sameSlotHasExactlyOneCreatedOneConflictAndNoOrphan |
| Exact deadline, PAID preserved | BookingExpirationPostgresTest.expiresBeforeAndExactlyAtDeadline; neverChangesNonPendingBookingsOrTheirDetails |
| EXPIRED + delete atomic, header/payment/fee preserved | BookingExpirationPostgresTest.deletesEveryDetailButPreservesHeaderAmountsAuditAndPaymentHistoryAndRerunIsSafe; deleteFailureRollsBackAlreadyExecutedStatusUpdate |
| Release/availability/rebook same tuple | BookingExpirationPostgresTest.releaseChangesAvailabilityAndAllowsPostBookingOnSameTuple |
| Callback authenticity/amount and sequential replay | VnPayIpnVerifierTest; MoMoIpnVerifierTest; PaymentCallbackPostgresTest; PaymentCallbackControllerTest |
| Concurrent duplicate, unique-ID rollback, both expiration race winners | PaymentCallbackConcurrencyPostgresTest (26 real PostgreSQL cases) |
| History/read-only/pagination/payment XOR/no N+1/OpenAPI | BookingHistoryPostgresTest (Phase 6 additions) |

## Migration review

Java maps db/migration.sql; no migration or mapped entity field was added in Phase 6.

| Mapping | Review |
| --- | --- |
| Booking | Exactly id/customer_id/status/court_fee/expires_at/created_by/updated_by/created_at/updated_at; no BaseEntity/deleted_at. PENDING requires expires_at. Six statuses match CHECK. Staff audit IDs reference staffs, not users. |
| BookingDetail | Exactly id/booking_id/court_id/time_slot_id/booking_date/price/created_at; no updated_at/deleted_at. Relations match FKs. uq_booking_slot remains final guard. |
| PaymentTransaction | Exactly id/booking_id/invoice_id/payment_method/transaction_id/status/amount/transaction_date/created_at/note; no added timestamps/statuses. XOR target remains DB guard. Four methods/statuses match CHECK. Nullable transaction_id globally unique when non-null. |
| References | Reused minimal Customer/Staff/Invoice identity mappings and existing read-only Court/TimeSlot fields. No owner entity/CRUD redesign. |

History queries use customer_id/created_at and booking_id filtering compatible with
existing idx_bookings_customer_created, idx_payments_booking and detail FK/query
paths. No new index, new status, fake transaction ID or Invoice payment API.

## Pre-pull verification checkpoint

`mvn -f backend/pom.xml verify`: BUILD SUCCESS, **400 tests, 0 failures, 0 errors,
0 skipped** on PostgreSQL **15.19**, JDK 17.0.20.1 and Maven 3.10.0. Includes the
375 existing tests plus 25 BookingHistoryPostgresTest cases. Targeted history/OpenAPI
suite also passed. Dedicated PostgreSQL test database uses the original migration;
test container is stopped after verification. No real sandbox request or merchant
credential is required. Ownership is not included in the PASS claim.

## Historical post-pull reconciliation verification

Baseline source was recovered from inspected WIP stash commit `a5d7e8a`, parent
`14bff6f`, and compared method-by-method with the current merge `8de264c`.
Only Booking-specific diffs were applied: two history GET handlers, paginated
header projection, detail fetch query, booking-target payment query, callback
PESSIMISTIC_WRITE query and lost API error schemas. No stash pop, wholesale revert,
Auth/schema restoration, customer resolver or ownership check was performed.

Tests now insert the current User schema's password_hash/full_name/phone columns
and use authenticated UserPrincipal request fixtures through the existing owner
filter chain. Two additional compatibility tests prove anonymous history/detail
requests are rejected and the actual owner JWT filter authenticates a database
user. They do not prove customer ownership. No filters are disabled or routes opened.

Compile `mvn -f backend/pom.xml -DskipTests compile`: BUILD SUCCESS.
Targeted suites in requested order: **387 PASS**. Full
`mvn -f backend/pom.xml verify`: BUILD SUCCESS, **402 tests, 0 failures, 0 errors,
0 skipped**. Difference from the 400-test checkpoint is the two compatibility tests.
PostgreSQL **15.19**, separate `bcm_reconciliation_test` database initialized from
the current unchanged migration; the old test database was preserved. Includes
all 400 previous Booking checks, with fixtures/OpenAPI assertions adapted to the
current Auth policy. Test container stopped after verification.

Auth-owned source, SecurityConfig, JWT code, owner entities, pom.xml, application.yml,
migration, plans.md and prompts.md remain untouched. Phase 6 history ownership
remains `[ ]`, pending trusted users.id -> customers.id resolution from the owner.
Provider IPN/Return and configured API docs public-route coordination is still an
external Security integration blocker; successful handler regression tests do not
claim that unauthenticated real provider requests can reach the handlers today.

## Phase 6 Auth handoff - final verification

Phase 6 ownership is now complete through CurrentCustomerService and the existing
owner JWT principal, as described in the current Auth boundary section above.
Historical initial/reconciliation sections record the earlier blocked checkpoints.

Compile and full `mvn -f backend/pom.xml verify`: **BUILD SUCCESS**, **423 tests,
0 failures, 0 errors, 0 skipped**, PostgreSQL **15.19**. Baseline 402 increased by
4 history ownership/JWT cases, 12 Auth registration/security PostgreSQL cases and
5 resolver unit cases. Targeted ownership/OpenAPI/gateway: 98 PASS; targeted
Booking/payment/callback regressions: 310 PASS. Real Customer constraint failure
proves registration rollback; provider routes reach signature verification without
application JWT, and Booking routes remain protected. Query bounds including
customer identity are 3 for a history page and 4 for detail. Schema is unchanged.

No real sandbox requests were run; MoMo initiation ambiguity, late-payment
reconciliation/refund and global transaction_id uniqueness remain limitations.
POST booking and payment initiation ownership and token-purpose separation remain
outside this completion. Test container stopped; no automatic staging/commit/push.
