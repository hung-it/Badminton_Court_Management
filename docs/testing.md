# Kiểm thử project

## Backend và HTTP

Tại thư mục gốc, cần Docker Desktop, JDK và Maven:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/verify-p1.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/verify-p1-acceptance.ps1
```

Runner P1 hiện chạy toàn bộ regression bắt buộc, gồm P2 và các bản sửa browser/backend. Regression dùng container `bcm-p1-tests`, port 55437, DB `bcm_p1_work`. Acceptance dùng `bcm-browser-acceptance`, port 55438, DB `bcm_acceptance_test` và role riêng không phải superuser. Không chạy trên DB project.

Maven chạy tuần tự từ `backend` vì các bài SQL dùng `../db`. Full runner clean trước khi chạy; không ghi log ngoài vào `backend/target` lúc clean. Log và Surefire reports ở `backend/target`. Suite backup BANK_TRANSFER legacy là opt-in riêng, ngoài tổng bắt buộc.

Chạy nhóm mục tiêu:

```powershell
powershell -NoProfile -File scripts/verify-p1.ps1 -Tests 'CustomerAuthPostgresTest,P1MasterDataConcurrencyPostgresTest,BookingPromotionPostgresTest'
```

## Frontend

```powershell
node --test web-app/*.test.mjs
npm --prefix web-app run build
npm --prefix web-admin run build
```

Các file `*.e2e.cjs` kiểm tra những luồng khác nhau; giữ để regression. Cần Playwright có Chromium/Edge; `PLAYWRIGHT_MODULE` có thể trỏ module `playwright/test` đã cài.

Bảy suite fixture gần nhất: customer `p1-business`, `p2-business`, `browser-flow-fixes`; admin `booking-fixes`, `pos-customer-mode`, `checkout-list`, `revenue-invoices` (đuôi `.e2e.cjs`). Chạy customer Vite trên 5184, admin trên 5185. Các suite có hỗ trợ biến URL dùng `BCM_WEB_TEST_URL`/`ADMIN_TEST_URL`; booking-fixes cần `ADMIN_TEST_URL=http://127.0.0.1:5185/?tab=checkout`, `CUSTOMER_TEST_URL=http://127.0.0.1:5184/profile`. Xem đầu mỗi script trước khi chạy: các suite cũ có thể dùng port/môi trường khác hoặc API thật.

`scripts/verify-demo-seed.ps1` kiểm tra seed trên DB test riêng. `db/checks/verify-demo-seed.sql` dành cho demo mới; số lượng thay đổi sau giao dịch.

## Kết quả và giới hạn

Mốc `41f8bbc` ngày 12/11/2026: 788 backend + 26 HTTP đạt, không failure/error/skip; 50 Node test, hai React build và 7 suite Edge đạt. Chi tiết: [tổng hợp lỗi đã sửa](tong-hop-loi-da-sua-2026-11-11-den-12.md).

47 ca lỗi baseline đã được khôi phục; bảng đối chiếu từng ca được giữ dưới đây. Không xem chúng là 47 lỗi production riêng biệt. VNPay end-to-end với nhà cung cấp chưa xác nhận do thiếu cấu hình sandbox. Kết quả lịch sử không thay thế chạy lại khi code thay đổi.

## Baseline regression ledger (2026-11-11)

| Case | Resolution |
|---|---|
| `com.bcm.acceptance.FeatureAcceptanceTest.invalidSignatureAmountFailureRetryAndLateCallback` | Signed provider pay date, immutable retry fields; late callback explicitly after bounded grace and released booking. |
| `com.bcm.acceptance.FeatureAcceptanceTest.paidBookingCannotCreateAnotherPayment` | Production payment-initiation response retains bookingStatus. |
| `com.bcm.acceptance.FeatureAcceptanceTest.posCheckoutInvoiceAndRevenue(String)[1]` | Actual staff/admin identities, confirmed totals/idempotency, past playing time and idempotent duplicate checkout. |
| `com.bcm.acceptance.FeatureAcceptanceTest.posCheckoutInvoiceAndRevenue(String)[2]` | Actual staff/admin identities, confirmed totals/idempotency, past playing time and idempotent duplicate checkout. |
| `com.bcm.acceptance.FeatureAcceptanceTest.retailRepeatHasNoIdempotencyKeyInCurrentContract` | Renamed independentRetailSalesUseDifferentKeysAndCreateDistinctInvoices; two distinct confirmed orders remain distinct invoices. |
| `com.bcm.acceptance.FeatureAcceptanceTest.unverifiedOrMismatchedCourtReceiptCannotBeTreatedAsPaid(String)[1]` | Manual CASH confirmer/provenance; past playing time reaches receipt verification; mismatched receipts still rejected. |
| `com.bcm.acceptance.FeatureAcceptanceTest.unverifiedOrMismatchedCourtReceiptCannotBeTreatedAsPaid(String)[2]` | Manual CASH confirmer/provenance; past playing time reaches receipt verification; mismatched receipts still rejected. |
| `com.bcm.acceptance.FeatureAcceptanceTest.vnpayVerifiedSuccessRetryAndDuplicates` | Signed provider pay date, immutable retry fields; late callback explicitly after bounded grace and released booking. |
| `com.bcm.AnalyticsIntegrationTest.statisticsGroupsQuarterBoundariesAndSubtractsImportCosts` | Only RECEIVED imports count as expenses (60000); DRAFT costs excluded, profit expectations corrected. |
| `com.bcm.entity.BookingMappingPostgresTest.roundTripsPaymentStatusesAndInvoiceReference(PaymentStatus)[1]` | Unpaid invoice fixture has no payment method; numeric fee totals match headers; payment mapping/target assertions retained. |
| `com.bcm.entity.BookingMappingPostgresTest.roundTripsPaymentStatusesAndInvoiceReference(PaymentStatus)[2]` | Unpaid invoice fixture has no payment method; numeric fee totals match headers; payment mapping/target assertions retained. |
| `com.bcm.entity.BookingMappingPostgresTest.roundTripsPaymentStatusesAndInvoiceReference(PaymentStatus)[3]` | Unpaid invoice fixture has no payment method; numeric fee totals match headers; payment mapping/target assertions retained. |
| `com.bcm.entity.BookingMappingPostgresTest.roundTripsPaymentStatusesAndInvoiceReference(PaymentStatus)[4]` | Unpaid invoice fixture has no payment method; numeric fee totals match headers; payment mapping/target assertions retained. |
| `com.bcm.entity.BookingMappingPostgresTest.roundTripsPaymentMethodsAndNullableColumns(PaymentMethod)[1]` | CASH success fixture uses MANUAL provenance, transaction date and authenticated confirmer; VNPAY pending remains nullable. |
| `com.bcm.MasterDataIntegrationTest.blocksUnfinishedBookingButPreservesHistory` | Valid active statuses still block mutations; unsupported CHECKED_IN/NO_SHOW are explicitly rejected by the database CHECK. |
| `com.bcm.ProductPromotionIntegrationTest.noStackingFailureRollsBackStockAndInvoice` | PostgreSQL POS with real actor, confirmation and quoted expected amount; concurrent orders use independent idempotency keys. |
| `com.bcm.ProductPromotionIntegrationTest.salePersistsDiscountSnapshotAndLaterEditsDoNotChangeInvoice` | PostgreSQL POS with real actor, confirmation and quoted expected amount; concurrent orders use independent idempotency keys. |
| `com.bcm.ProductPromotionPostgresTest.noStackingFailureRollsBackStockAndInvoice` | PostgreSQL POS with real actor, confirmation and quoted expected amount; concurrent orders use independent idempotency keys. |
| `com.bcm.ProductPromotionPostgresTest.salePersistsDiscountSnapshotAndLaterEditsDoNotChangeInvoice` | PostgreSQL POS with real actor, confirmation and quoted expected amount; concurrent orders use independent idempotency keys. |
| `com.bcm.ProductPromotionPostgresTest.concurrentSalesCannotBothConsumeLastTenDiscountedProducts` | PostgreSQL POS with real actor, confirmation and quoted expected amount; concurrent orders use independent idempotency keys. |
| `com.bcm.RevenueBreakdownIntegrationTest.splitsCourtFeeAcrossBookedCourtsAndKeepsUnbookedFeesSeparate` | PostgreSQL receipt reconciliation; balanced header/line discount snapshots, valid DRAFT/PAID method and staff provenance. |
| `com.bcm.RevenueBreakdownIntegrationTest.aggregatesInvoicesAndProductLinesByQuarterAndYear` | PostgreSQL receipt reconciliation; balanced header/line discount snapshots, valid DRAFT/PAID method and staff provenance. |
| `com.bcm.RevenueBreakdownIntegrationTest.returnsDetailsForTheSelectedQuarterOnly` | PostgreSQL receipt reconciliation; balanced header/line discount snapshots, valid DRAFT/PAID method and staff provenance. |
| `com.bcm.RevenueBreakdownIntegrationTest.splitsInvoiceTotalBetweenProductsAndCourtsSoBothSidesMatch` | PostgreSQL receipt reconciliation; balanced header/line discount snapshots, valid DRAFT/PAID method and staff provenance. |
| `com.bcm.service.BookingHistoryPostgresTest.paymentsUseActualStatusesAndStableOrderingAndExcludeInvoiceTargetEvenWhenInvoiceLinksBooking` | Unpaid invoice fixture has no payment method; numeric fee totals match headers; payment mapping/target assertions retained. |
| `com.bcm.service.BookingModificationPostgresTest.nonPendingBookingsCannotBeEdited(String)[3]` | Valid active statuses still block mutations; unsupported CHECKED_IN/NO_SHOW are explicitly rejected by the database CHECK. |
| `com.bcm.service.BookingModificationPostgresTest.nonPendingBookingsCannotBeEdited(String)[5]` | Valid active statuses still block mutations; unsupported CHECKED_IN/NO_SHOW are explicitly rejected by the database CHECK. |
| `com.bcm.service.BookingModificationPostgresTest.vnpayAfterEditUsesNewAmountWithoutClaimingReceipt` | Production payment-initiation response retains bookingStatus. |
| `com.bcm.service.BookingPaymentLifecyclePostgresTest.rejectsAtAndAfterTenMinutes(long)[1]` | Production checks booking ownership/state/deadline before local gateway configuration; existing expired-booking 400 contract retained. |
| `com.bcm.service.BookingPaymentLifecyclePostgresTest.rejectsAtAndAfterTenMinutes(long)[2]` | Production checks booking ownership/state/deadline before local gateway configuration; existing expired-booking 400 contract retained. |
| `com.bcm.service.BookingPaymentLifecyclePostgresTest.vnpayAndExpirationRaceAtDeadlineCannotConfirmExpiredBooking` | Production checks booking ownership/state/deadline before local gateway configuration; existing expired-booking 400 contract retained. |
| `com.bcm.service.BookingPaymentLifecyclePostgresTest.productOnlyCashSaleStillCommitsInvoiceAndStockOnPostgres` | Real admin actor, explicit confirmation/key/amount and receipt cleanup; invoice/stock commit assertions retained. |
| `com.bcm.service.BookingPaymentLifecyclePostgresTest.staffCheckoutCannotBypassBookingPaymentWithCashOrUnverifiedTransfer` | Authenticated admin before direct checkout; unsupported payment methods and unverified court receipts remain rejected. |
| `com.bcm.service.BookingPaymentLifecyclePostgresTest.declaredTransferBlocksMethodSwitchAndNeverExpires` | Replaced obsolete never-expire expectation with pendingVnpayReuseHasABoundedGraceWindow: reuse before deadline, hold to 5-minute grace, then expire. |
| `com.bcm.service.BookingPaymentLifecyclePostgresTest.vnpayAndExpirationRaceBeforeDeadlinePreservesHeldSlots` | Authenticated admin before direct checkout; unsupported payment methods and unverified court receipts remain rejected. |
| `com.bcm.service.CheckoutHoldPostgresTest.alreadyExpiredBookingCanBeReplacedWithoutChangingLatePaymentHistory` | Clock beyond pending-payment grace before slot replacement; late payment history retained. |
| `com.bcm.service.CheckoutHoldPostgresTest.resumeAtDeadlineExpiresEvenBeforeJobRunsAndCannotPay` | Production checks booking ownership/state/deadline before local gateway configuration; existing expired-booking 400 contract retained. |
| `com.bcm.service.DatabaseConsolidationPostgresTest.consolidatedScriptsMatchPreviousOrderAndPreservePopulatedBookingHistory` | Unpaid invoice fixture has no payment method; numeric fee totals match headers; payment mapping/target assertions retained. |
| `com.bcm.service.ImportOrderTotalPostgresTest.splitPricesPersistExactEnteredTotalAndReceiveExactQuantityOnlyOnce` | Real database-backed admin principal in direct, HTTP and worker-thread calls; valid POS confirmation for the price-approval rejection case. |
| `com.bcm.service.ImportOrderTotalPostgresTest.manualNewNamesCreateReferencesAtomicallyAndReuseThem` | Real database-backed admin principal in direct, HTTP and worker-thread calls; valid POS confirmation for the price-approval rejection case. |
| `com.bcm.service.ImportOrderTotalPostgresTest.manualApiAcceptsTypedNamesAndValidatesRequiredFields` | Real database-backed admin principal in direct, HTTP and worker-thread calls; valid POS confirmation for the price-approval rejection case. |
| `com.bcm.service.ImportOrderTotalPostgresTest.concurrentManualImportsDoNotDuplicateSupplierOrProduct` | Real database-backed admin principal in direct, HTTP and worker-thread calls; valid POS confirmation for the price-approval rejection case. |
| `com.bcm.service.ImportOrderTotalPostgresTest.ambiguousProductRollsBackNewSupplier` | Real database-backed admin principal in direct, HTTP and worker-thread calls; valid POS confirmation for the price-approval rejection case. |
| `com.bcm.service.ImportOrderTotalPostgresTest.importedProductCannotBeQuotedOrSoldBeforePriceApproval` | Real database-backed admin principal in direct, HTTP and worker-thread calls; valid POS confirmation for the price-approval rejection case. |
| `com.bcm.service.ImportOrderTotalPostgresTest.manualNamesReuseExistingReferences` | Real database-backed admin principal in direct, HTTP and worker-thread calls; valid POS confirmation for the price-approval rejection case. |
| `com.bcm.service.PaymentAttemptPostgresTest.openApiDescribesFoundationAndLimitedRequest` | Assert the current callback-verification description instead of obsolete checkoutReady text. |
| `com.bcm.service.RevenueBreakdownPostgresTest.aggregatesWithPostgresNumericAndMatchesInvoiceRevenue` | Balanced numeric fixture (1.11 gross, 0.10 discount, 1.01 net) with matching verified receipt. |
