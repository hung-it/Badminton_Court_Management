# Booking Engine backend contract — Phase 6

Local base URL: `http://localhost:8080/api`. OpenAPI: `/api/api-docs`;
Swagger UI: `/api/swagger-ui.html`. Controller paths do not contain `/api`.

Booking Engine currently supports VNPay Sandbox as its only online payment gateway.
The current project decision supersedes the original multi-provider assignment.

## Auth boundary

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
contract before persistence. Enum, request schema and database CHECKs allow VNPAY only.
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

## VNPay-only schema decision

PaymentMethod and payment-method CHECKs on invoices/payment_transactions allow VNPAY only.
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
