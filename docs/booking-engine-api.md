# Booking Engine backend contract — Phase 6

## Runtime recovery / realtime slot clock — 2026-10-23

`GET /api/public/booking-clock` (public) returns `serverTime` as an absolute ISO Instant,
`timeZone=Asia/Ho_Chi_Minh` and `today` in that zone. It uses the same injectable Clock instant
as availability/create/edit validation. Existing hold timestamps/deadlines are not reinterpreted.
Start rule is strict: `bookingDate + startTime > serverNow` in Vietnam; equality is already started.
Read availability retains all templates for compatibility/history but never marks past starts available.
Create and edit reject past dates and started slots at the service boundary, after relevant locks.

Customer UI anchors that clock to performance.now, updates every 250ms and resynchronizes every
30 seconds/on focus/visibility. It uses Intl with the named timezone, not the device date or a
manual seven-hour adjustment. Dropdowns/chips/extra checkboxes and checkout edit options completely
omit past starts. Expired selection IDs are pruned without clearing other courts' future choices or
name/district/price filters. At midnight the date/minimum advance; after the last start a next-day
action remains available. Availability refreshes every 15 seconds/on focus; writes always recheck DB.
Master time slots and historical booking details are never deleted by time-filtering UI.

Runtime ports: admin `http://localhost:5173`, customer `http://localhost:5174`, backend 8080,
PostgreSQL 5433. Both Vite servers use strictPort and IPv4 backend proxy; customer has an optional
BCM_API_PROXY_TARGET override for isolated tests. Compose defaults Return destination to
`http://localhost:5174/payment-result`; real merchant settings still come from environment.
`db/demo-districts-seed.sql` is explicit opt-in only, adds seven labelled demo facilities and never
backfills existing unknown locations. See db/README.md and the runtime report for backup/apply evidence.

## Courts / Checkout edit contract — 2026-10-23

Apply `db/booking-ux-upgrade.sql` after the historical schema before starting the new backend.
It adds nullable `bookings.customer_note VARCHAR(1000)` and `courts.district VARCHAR(150)`;
does not change historical migration, statuses, price/deadline or payment evidence. No Flyway/Liquibase
is installed: upgrades follow the existing explicit SQL convention. See `db/README.md`.

- `GET /public/courts` still returns the complete active catalog without pagination; now includes
  nullable `district`. Client multi-select districts use OR; no selection means no district predicate.
  Unknown locations are not guessed/backfilled from names. Admin CourtRequest optionally accepts
  district (max 150, no control characters); omission on update preserves it, blank clears it.
- `PATCH /bookings/{bookingId}/notes`, body `{ "note": "Customer request" }`: authenticated owner only,
  PENDING and strictly before original deadline under Booking lock. Up to 1000 characters, control
  characters rejected except tab/newline/CR; blank clears to NULL. Returns full owned detail with
  `customerNote`; GET history summaries expose customerNote as well. Payment note/amount/status never change; notes can be saved during a pending
  attempt because they do not change the payable obligation. Confirmed/expired bookings return 409.
- `PATCH /bookings/{bookingId}/details`, body `{ "bookingDate": "2031-02-20", "timeSlotIds": ["<UUID>"] }`:
  same owner and original deadline checks. Only single-court PENDING bookings without ANY historical
  payment attempts can change details. No court/identity/amount/status/deadline fields are editable.
  1..48 distinct active slots; all start times must be future in Asia/Ho_Chi_Minh.
  Booking -> Court row locks serialize edits with initiation/expiry and create-booking Court locks.
  Court state and deadline are rechecked after waiting; no Court entity is preloaded before the lock.
  Replacement availability excludes only this booking's existing details. Validation precedes deletion;
  deletion + insertion + courtFee commit atomically, with uq_booking_slot as the final 409 protection.
  Unchanged date/court/slot tuples retain price snapshots. New tuples use current basePrice * multiplier,
  HALF_UP to scale 2; total must fit NUMERIC(10,2). Returns full owned detail with authoritative courtFee.

PATCH errors: 400 invalid input/past or deleted slot/unavailable Court; 401 unauthenticated;
403 foreign owner/non-CUSTOMER; 404 missing booking; 409 occupied slot/confirmed booking,
`BOOKING_EXPIRED` or `PAYMENT_ATTEMPT_EXISTS`. Commit failures never return success.
Edits never create a booking or reset its hold. No gateway URL is invalidated or silently reused
after a price change: an existing attempt locks editing, including FAILED/REFUNDED history.

Checkout disables payment while editing/saving/unsaved notes or uncertain synchronization,
persists unsaved notes in a booking-scoped session draft, and reads backend data after refresh.
After a failed/lost edit response it reconciles persisted detail before payment is enabled.
Payment preflight detects changes made by another tab and asks the customer to review the updated total.
Payment initiation still uses the database amount and Booking lock; BANK_TRANSFER CUSTOMER_DECLARED,
VNPay verification/Return, expiration and product-only CASH/POS rules remain unchanged.

Verification and limitations: [Courts / Checkout UX report](courts-checkout-ux-2026-10-23.md).

Local base URL: `http://localhost:8080/api`. OpenAPI: `/api/api-docs`;
Swagger UI: `/api/swagger-ui.html`. Controller paths do not contain `/api`.

Booking Engine supports VNPay Sandbox and unverified BANK_TRANSFER requests.
CASH is forbidden for booking payment and staff booking checkout. Product-only POS CASH remains supported.

## Contract update — 2026-10-22

This section supersedes earlier Phase 6/VNPay-only limitations below and in historical plan checkpoints.
No migration is needed: the existing payment_transactions method CHECK permits BANK_TRANSFER;
payment target remains booking_id only, invoice_id NULL. ddl-auto remains validate, SQL init never.

POST /bookings/{bookingId}/payments accepts:

```json
{
  "paymentMethod": "BANK_TRANSFER",
  "bankTransfer": {
    "bankName": "Payer's bank",
    "accountHolder": "Payer's account holder",
    "accountNumber": "Digits only, 1..34 characters",
    "transferContent": "Payer's transfer description"
  }
}
```

All four transfer fields are required. Maximum lengths: bankName 100, accountHolder 150,
accountNumber 34, transferContent 255. This is payer input, not a configured recipient account
or bank confirmation. No recipient account is invented. Account information is validated
but not persisted or returned. A non-sensitive note records the declaration timestamp.
Unknown amount/status/target/transaction fields cannot change server data.

### Customer declaration policy — 2026-10-23

HTTP 201 for BANK_TRANSFER atomically confirms booking PAID under the customer declaration
policy, with payment still PENDING and checkoutReady false. PAID here confirms the reservation;
it does not assert bank receipt. Payment SUCCESS retains its verified-receipt meaning.
Note format: `CUSTOMER_DECLARED; receipt=UNVERIFIED; declaredAt=<server local timestamp>`.
The payment response adds `bookingStatus=PAID` and `confirmationMethod=CUSTOMER_DECLARED`;
owned detail payment entries expose the same confirmationMethod without exposing the note.
the UI then opens /payment-result?bookingId=...&paymentAttemptId=... and reads the owned detail.
It displays "Đã xác nhận đặt sân" and explicitly says the transfer is customer declared and bank
receipt unverified. It never displays a paid-in-full receipt for this declaration.
No staff or bank callback is required to accept the declaration. There is no bank reconciliation API.
Retries, including after the deadline, return the same declared attempt for confirmed bookings;
an overdue PENDING or EXPIRED booking returns 409 with BOOKING_EXPIRED before payment creation.
Booking locks serialize declaration, competing methods and expiration; confirmed bookings retain
their details and cannot be expired. An outstanding VNPay attempt prevents switching methods.
A delayed VNPay receipt on a prior failed attempt is retained for reconciliation, never a second settlement.

The hold starts when the backend creates the booking (default PT10M, environment override supported).
GET /bookings/{bookingId}/checkout-clock enforces authenticated ownership and returns
`serverTime`, original `expiresAt`, and current `status`; it never renews or mutates the booking.
Checkout creates the hold once, then derives the countdown from these server timestamps using
a monotonic timer, resynchronizing every 15 seconds and on focus. Refresh, another tab for that
booking and method changes retain the original deadline. Before clock synchronization payment
is disabled. Under one minute the warning is visible; at zero payment is disabled with an expired
message and a link to `/`. No automatic booking recreation occurs. The backend remains authoritative.
On expiry, only PENDING becomes EXPIRED and details are deleted atomically; payment history remains.

The Booking row lock serializes payment initiation. Repeated same-method requests reuse the
same PENDING attempt; requesting another method while one is PENDING returns 409. CASH returns 400.
Non-positive courtFee is rejected. Staff checkout locks the same Booking row and requires
PAID/CHECKED_IN; CASH and unpaid/expired/already completed bookings cannot bypass this policy.
A PENDING customer declaration cannot be used to issue a paid staff invoice: actual receipt must
be reconciled first. Reservation confirmation does not change invoice accounting or POS CASH.
Product-only PosService is unchanged. Its CASH invoice and stock update are covered on PostgreSQL.

A persisted SUCCESS payment blocks new initiation even if a legacy/inconsistent Booking header
still says PENDING. Settlement checks this under the Booking lock as well, so another attempt cannot
become SUCCESS for the same obligation. Existing successful history is preserved; conflicting verified
receipt is noted for reconciliation rather than silently repairing the header or confirming twice.

Availability still accepts historical dates for read compatibility but returns available=false
for every start time at or before now in Asia/Ho_Chi_Minh. Creation repeats that check after Court locks.
Multiple details, HALF_UP per-detail rounding, price snapshots and uq_booking_slot are preserved.

VNPay success requires signed IPN and atomic SUCCESS + PAID before the deadline. Verified terminal
TransactionStatus 02 with non-success response marks an unconfirmed attempt FAILED, allowing retry
on the same eligible booking. Intermediate status 01 is ignored. Failed callbacks never downgrade
SUCCESS. A success received after expiry or on a non-payable attempt is not settled: note records
RECONCILIATION_REQUIRED plus verified transaction/amount/date for investigation, without new enum values.
The existing global transaction_id index remains unchanged.

GET /payments/vnpay/return verifies checksum and never settles. By default it retains JSON output.
Optional BOOKING_PAYMENT_FRONTEND_RESULT_URL must be an absolute HTTP(S) URL with path /payment-result,
without userinfo/query/fragment. When set, valid browser Return responds 303 to that configured URL,
carrying the signed provider fields. displayOnly=true retains JSON for the frontend verification call.
No caller-supplied redirect target is accepted. /payment/result remains a frontend alias.

Docker Compose now forwards existing BOOKING_VNPAY_PAYMENT_URL/TMN_CODE/HASH_SECRET/RETURN_URL,
BOOKING_PAYMENT_ENABLED_METHODS and the optional frontend-result URL. It does not supply credentials.
Register public HTTPS IPN /api/payments/vnpay/ipn in the merchant portal; there is no IPN URL
environment variable consumed by source. Backend Return and IPN must be reachable by the provider.

See [audit and test evidence](booking-payment-audit-2026-10-22.md) for live connectivity and test limits.

## Phase 6 reference (historical; updates above take precedence)

### Auth boundary

The owner JWT filter authenticates a DB-backed UserPrincipal. CurrentCustomerService
uses its trusted users.id to query customers.user_id and returns the distinct
customers.id. Only authenticated CUSTOMER authorities may use history/detail,
create bookings or initiate payments. No ADMIN/STAFF override is granted.

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

POST /bookings retains required customerId as a consistency assertion: it must
match the trusted customer profile or returns 403 before court locks or writes.
The persisted owner comes from the trusted customer ID, not the request identity.
POST /bookings/{bookingId}/payments checks that same customer against the owner
of the existing locked Booking before creating or reusing a payment attempt.
Foreign bookings return 403 without payment artifacts; missing bookings return
404. Configuration validation follows ownership, before any attempt is persisted.
Initiation rechecks ownership and eligibility before and after provider work.
Access/refresh token purpose separation remains an unchanged Auth limitation.

## Web API

`GET /api/bookings/customer-profile` returns
`ApiResponse<{customerId: UUID}>` for the active authenticated CUSTOMER.
The ID is resolved through `CurrentCustomerService` from the trusted principal,
not from request parameters. It is the domain `customers.id`, distinct from
`users.id`. It performs no writes. Anonymous requests return 401; missing/deleted
profiles and non-CUSTOMER users return 403. Web checkout uses this endpoint before
POST /bookings, so the Auth response does not need to expose a new domain field.

All protected Booking routes return 401 for missing authentication; 403 means an
authenticated caller lacks the required customer identity or ownership.

Normal APIs return `ApiResponse<T>` with success/message/data/timestamp; errors
handled by MVC use the existing ApiResponse error envelope. All five application
operations require authentication; anonymous requests currently return 403 from
the security filter and may have no JSON envelope. This is not a documented 401
contract. History/detail and both write operations enforce CUSTOMER ownership.
Missing/invalid UUID/status/numeric
query parameters return 400. Booking status values are case-sensitive schema enums.

| Method and path (relative to base) | Inputs | Responses |
| --- | --- | --- |
| GET `/availability` | Required date YYYY-MM-DD; optional courtId | 200 grid; 400 invalid/missing input; 403 unauthenticated; 404 filtered court unknown/ineligible |
| POST `/bookings` | Own customerId; nonempty details with bookingDate/courtId/timeSlotId | 201 booking; 400 invalid/ineligible/overflow; 403 identity/customerId mismatch; 404 reference missing; 409 occupied slot/constraint |
| GET `/bookings` | Optional own customerId; page=0; size=20 (1..100); optional status | 200 owned paginated headers; 400 validation; 403 identity/mismatch |
| GET `/bookings/{bookingId}` | Booking UUID | 200 owned header/details/payments; 400 UUID; 403 foreign/identity; 404 missing header |
| POST `/bookings/{bookingId}/payments` | Own booking; paymentMethod VNPAY only | 201 attempt/initiation; 400 unsupported/ineligible/disabled/input; 403 identity/foreign booking; 404 booking; 409 attempt/target conflict; 502 provider failure; 503 config/contract unavailable |
| GET `/payments/vnpay/return` | Signed VNPay return query fields | 200 verified display information; 400 unverifiable; 503 config unavailable |

Availability is based on persisted booking_details, regardless of header status
or deadline. Only AVAILABLE, non-deleted courts appear. Creation locks Court rows
in stable UUID order, snapshots `base_price * price_multiplier` using BigDecimal
HALF_UP scale 2 per detail and sums persisted snapshots. Price/status/expiry are
server-controlled; unknown JSON input fields cannot override them. Header/details
commit atomically. PENDING hold duration is configurable; past dates remain allowed.

Availability returns date, courtId/courtNumber/name and slots containing
timeSlotId/startTime/endTime/priceMultiplier/available. MAINTENANCE, CLOSED and
soft-deleted courts are omitted; filtering one returns 404. No price quote or
basePrice is exposed by this contract. Selectable slots use the available boolean;
only successful booking creation reserves them and returns authoritative prices.

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
  VNPay row. Invoice-target rows are excluded even if their invoice refers to
  this booking. Payment status is read directly, never inferred from booking status.
  Nullable transaction ID/date are preserved, including historical records.

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

VNPay Sandbox checkout is signed when configuration is valid. Initiation accepts
only VNPAY. Missing/unknown methods return 400 using the existing ApiResponse
contract before persistence. The shared `PaymentMethod` enum and database CHECKs
support VNPAY, CASH and BANK_TRANSFER for booking/invoice payment records.
Booking payment-attempt requests and the initiation service accept VNPAY only.
Config defaults to VNPAY. All attempts remain
PENDING at initiation. Existing checkout response fields remain backward-compatible;
unused QR/deeplink/blocker artifacts stay null.
Browser return verifies/displays provider data only, performs no DB reads/writes and
cannot mark Payment SUCCESS or Booking PAID. Local return URL remains
`http://localhost:8080/api/payments/vnpay/return`.

## Provider-facing IPN

These endpoints do not use ApiResponse and require cryptographic verification.
No provider network request is needed during callbacks or normal deterministic tests.
Security explicitly permits GET /payments/vnpay/ipn and
GET /payments/vnpay/return without application JWT. Signatures remain the provider
trust boundary. Public project docs use /api-docs/**, /swagger-ui.html and
/swagger-ui/** (relative to context /api), consistent with the existing dev docs
policy. History/detail and payment initiation remain authenticated; no blanket
/bookings/** permitAll rule was added. Provider OpenAPI operations override bearer
security, while customer operations declare it.

| Endpoint | Response contract |
| --- | --- |
| GET `/payments/vnpay/ipn` | HTTP 200 JSON `{RspCode, Message}`: 00 handled success/non-success, 01 unknown, 02 confirmed replay, 04 amount mismatch, 97 checksum, 99 conflict/late/nonpayable/system failure |

VNPay verifies HMAC-SHA512, exact attempt/provider/merchant and amount x100; success
requires ResponseCode=00 AND TransactionStatus=00. Actual transaction ID is
TransactionNo; optional PayDate is strictly parsed in GMT+7, never replaced with
server time on parse failure.

VNPay callbacks lock Booking then Payment, re-check deadline after lock and commit
SUCCESS + PAID atomically. Expiration locks Booking only. Exact replay does not
overwrite metadata; conflicting replay or global transaction-ID uniqueness failure
cannot partially settle. Late success never revives EXPIRED or restores details;
there is no durable late-payment evidence/refund/reconciliation in this scope.
VNPay has no dedicated
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
| Callback authenticity/amount and sequential replay | VnPayIpnVerifierTest; PaymentCallbackPostgresTest; PaymentCallbackControllerTest |
| Concurrent duplicate, unique-ID rollback, both expiration race winners | PaymentCallbackConcurrencyPostgresTest (all VNPay PostgreSQL race cases retained) |
| History/read-only/pagination/payment XOR/no N+1/OpenAPI | BookingHistoryPostgresTest (Phase 6 additions) |

## Migration review

Current mappings use the committed db/migration.sql. The Auth handoff expanded
Customer from an ID reference to a profile mapped through BaseEntity; its audit
columns, including deleted_at, exist in the current migration. Schema was not changed
by that handoff or this API completeness audit.

| Mapping | Review |
| --- | --- |
| Booking | Exactly id/customer_id/status/court_fee/expires_at/created_by/updated_by/created_at/updated_at; no BaseEntity/deleted_at. PENDING requires expires_at. Six statuses match CHECK. Staff audit IDs reference staffs, not users. |
| BookingDetail | Exactly id/booking_id/court_id/time_slot_id/booking_date/price/created_at; no updated_at/deleted_at. Relations match FKs. uq_booking_slot remains final guard. |
| PaymentTransaction | Exactly id/booking_id/invoice_id/payment_method/transaction_id/status/amount/transaction_date/created_at/note; no added timestamps/statuses. XOR target remains DB guard. VNPAY method and four statuses match CHECK. Nullable transaction_id globally unique when non-null. |
| References | Customer maps user_id/full_name/phone/address plus BaseEntity id/created_at/updated_at/deleted_at. Staff/Invoice remain minimal ID references; Court/TimeSlot remain read-only subsets. No Court/TimeSlot/Invoice CRUD was added. |

## Current Member 3 HTTP inventory

Exactly seven operations cover the required HTTP surface. Paths below include
the context path once. Every operation has Swagger summary/request/response and
security coverage; callback operations override application bearer security.
Normal response types below are wrapped in ApiResponse, except VNPay IPN.

| Method / actual route | Controller -> service | Request | Response | Security | Principal test coverage |
| --- | --- | --- | --- | --- | --- |
| GET /api/availability | AvailabilityController -> AvailabilityService | date; optional courtId | AvailabilityResponse | Authenticated | AvailabilityPostgresTest |
| POST /api/bookings | BookingController -> BookingService | CreateBookingRequest | BookingResponse | CUSTOMER; customerId must match trusted profile | BookingPostgresTest; BookingConcurrencyPostgresTest; BookingWriteAuthorizationPostgresTest |
| GET /api/bookings | BookingController -> BookingHistoryService | optional own customerId/status; page/size | BookingHistoryResponse | CUSTOMER; CurrentCustomerService | BookingHistoryPostgresTest; CustomerAuthPostgresTest |
| GET /api/bookings/{bookingId} | BookingController -> BookingHistoryService | bookingId | BookingHistoryDetailResponse (including payment statuses/history) | CUSTOMER; owner only | BookingHistoryPostgresTest |
| POST /api/bookings/{bookingId}/payments | PaymentAttemptController -> PaymentInitiationService -> PaymentAttemptService/VnPayGateway | bookingId; CreatePaymentAttemptRequest | PaymentAttemptResponse | CUSTOMER; owner required for new/reused attempts | PaymentAttemptPostgresTest; PaymentInitiationPostgresTest; BookingWriteAuthorizationPostgresTest; VnPayGatewayTest |
| GET /api/payments/vnpay/ipn | PaymentCallbackController -> PaymentCallbackService -> PaymentCallbackTransactionService | signed VNPay query fields | VnPayIpnAcknowledgment | Public; HMAC-SHA512 verification | VnPayIpnVerifierTest; PaymentCallbackControllerTest; PaymentCallbackPostgresTest; PaymentCallbackConcurrencyPostgresTest |
| GET /api/payments/vnpay/return | VnPayReturnController -> VnPayIpnVerifier | signed VNPay query fields | PaymentNotification (display only) | Public; checksum verification | VnPayIpnVerifierTest; PaymentCallbackControllerTest; CustomerAuthPostgresTest |

Hold expiration is provided by BookingExpirationJob/BookingExpirationService,
not a client mutation API; BookingExpirationPostgresTest covers atomic release.
Pricing and pessimistic locking belong to the existing POST /bookings workflow.
VNPay Sandbox is the only online initiation/callback provider.
No required endpoint is missing. Separate provider initiation, /bookings/me,
/my-bookings and payment-history endpoints would duplicate existing contracts.
Cancel/reschedule/refund/check-in/no-show/admin CRUD/CASH/BANK_TRANSFER initiation,
Court/TimeSlot CRUD and POS/Invoice/revenue APIs are not required by Member 3 scope.

History queries use customer_id/created_at and booking_id filtering compatible with
existing idx_bookings_customer_created, idx_payments_booking and detail FK/query
paths. No new index, new status, fake transaction ID or Invoice payment API.

## Historical VNPay-only booking payment policy (superseded 2026-10-22)

The shared `PaymentMethod` enum and payment-method CHECKs on
invoices/payment_transactions allow VNPAY, CASH and BANK_TRANSFER. The Member 3
booking payment-attempt API remains VNPay-only.
Existing local/dev databases containing removed payment methods may need recreation from
current db/migration.sql. Application data is never automatically reset.
Phase 1–6 remain complete with VNPay Sandbox as the sole provider; Phase 7 remains unimplemented.

## Current verification — 2026-10-08

Java 17.0.20.1, Maven 3.10.0, PostgreSQL 15.19; dedicated test database initialized
from the current migration. Targeted fourteen-suite regression: 246 tests PASS.
Full `mvn -B -f backend/pom.xml verify`: BUILD SUCCESS, 297 tests,
0 failures, 0 errors, 0 skipped. Enum/schema scope reduction removed nine additional
legacy-method invocations from the preceding 306-test checkpoint; VNPay lifecycle,
identity, callback, concurrency and browser Return coverage is preserved.
Both payment-method CHECKs permit only VNPAY; a removed-method insert was rejected
in the disposable database and the entire probe transaction rolled back.
Docker backend rebuilt using `.env.example`, without reading private `.env`.
Swagger HTTP 200, OpenAPI HTTP 200: exactly seven Member 3 operations, and VNPAY
as the sole payment method in request/response/history schemas. Backend restart count
is zero; application PostgreSQL remains healthy and was not restarted or modified.
No real payment-provider request, commit, push or Phase 7 implementation.
