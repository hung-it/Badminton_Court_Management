BEGIN;
DO $sample_invoices_and_payments$
DECLARE
    ns uuid := 'bcd00000-0000-4000-8000-000000000001'::uuid;
    cust_draft uuid; cust_cancel uuid; cust_failed uuid; cust_pending uuid;
    staff_actor uuid; staff_user uuid;
    water_prod uuid; water_price numeric;
    shuttle_prod uuid; shuttle_price numeric;
    draft_inv uuid; draft_line uuid;
    cancel_inv uuid; cancel_line uuid;
    failed_booking uuid; failed_txn uuid;
    pending_booking uuid; pending_txn uuid;
    stamp_base timestamp := current_date - interval '2 days' + interval '14 hours';
BEGIN
    SELECT id INTO cust_draft FROM customers WHERE id = uuid_generate_v5(ns, 'customer-6');
    SELECT id INTO cust_cancel FROM customers WHERE id = uuid_generate_v5(ns, 'customer-7');
    SELECT id INTO cust_failed FROM customers WHERE id = uuid_generate_v5(ns, 'customer-8');
    SELECT id INTO cust_pending FROM customers WHERE id = uuid_generate_v5(ns, 'customer-9');
    SELECT s.id, s.user_id INTO staff_actor, staff_user FROM staffs s WHERE s.id = uuid_generate_v5(ns, 'staff-2');

    SELECT id, price INTO water_prod, water_price FROM products WHERE name = 'Nước suối 500ml' AND deleted_at IS NULL LIMIT 1;
    SELECT id, price INTO shuttle_prod, shuttle_price FROM products WHERE name = 'Cầu lông tiêu chuẩn' AND deleted_at IS NULL LIMIT 1;

    -- 1. Hóa đơn DRAFT có sản phẩm, chưa thanh toán
    draft_inv := uuid_generate_v5(ns, 'sample-draft-invoice-1');
    IF NOT EXISTS (SELECT 1 FROM invoices WHERE id = draft_inv) THEN
        INSERT INTO invoices(id, customer_id, buyer_phone, payment_method, status, court_fee, product_fee, discount_amount, total_amount, created_by, updated_by, created_by_user, created_at, updated_at)
        VALUES (draft_inv, cust_draft, '0901234566', NULL, 'DRAFT', 0, 2 * COALESCE(water_price, 10000), 0, 2 * COALESCE(water_price, 10000), staff_actor, staff_actor, staff_user, stamp_base, stamp_base);

        draft_line := uuid_generate_v5(ns, 'sample-draft-line-1');
        INSERT INTO invoice_details(id, invoice_id, product_id, product_name, product_type, quantity, unit_price, discount_amount, added_by, added_by_user, stock_deducted, note, created_at)
        VALUES (draft_line, draft_inv, water_prod, 'Nước suối 500ml', 'GOODS', 2, COALESCE(water_price, 10000), 0, staff_actor, staff_user, false, 'MẪU DRAFT: Đơn hàng POS nháp chờ kết toán', stamp_base);
    END IF;

    -- 2. Hóa đơn CANCELLED có lịch sử hủy hợp lệ
    cancel_inv := uuid_generate_v5(ns, 'sample-cancelled-invoice-1');
    IF NOT EXISTS (SELECT 1 FROM invoices WHERE id = cancel_inv) THEN
        INSERT INTO invoices(id, customer_id, buyer_phone, payment_method, status, court_fee, product_fee, discount_amount, total_amount, created_by, updated_by, created_by_user, created_at, updated_at)
        VALUES (cancel_inv, cust_cancel, '0901234567', 'CASH', 'CANCELLED', 0, COALESCE(shuttle_price, 20000), 0, COALESCE(shuttle_price, 20000), staff_actor, staff_actor, staff_user, stamp_base + interval '1 hour', stamp_base + interval '1 hour 15 minutes');

        cancel_line := uuid_generate_v5(ns, 'sample-cancelled-line-1');
        INSERT INTO invoice_details(id, invoice_id, product_id, product_name, product_type, quantity, unit_price, discount_amount, added_by, added_by_user, stock_deducted, note, created_at)
        VALUES (cancel_line, cancel_inv, shuttle_prod, 'Cầu lông tiêu chuẩn', 'GOODS', 1, COALESCE(shuttle_price, 20000), 0, staff_actor, staff_user, false, 'MẪU CANCELLED: Khách đổi ý hủy đơn mua tại quầy', stamp_base + interval '1 hour');
    END IF;

    -- 3. Giao dịch VNPay FAILED gắn với booking/payment attempt phù hợp
    failed_booking := uuid_generate_v5(ns, 'sample-failed-booking-1');
    failed_txn := uuid_generate_v5(ns, 'sample-failed-txn-1');
    IF NOT EXISTS (SELECT 1 FROM bookings WHERE id = failed_booking) THEN
        INSERT INTO bookings(id, customer_id, status, court_fee, expires_at, created_at, updated_at, contact_full_name, contact_phone)
        SELECT failed_booking, cust_failed, 'EXPIRED', 180000, stamp_base + interval '2 hours 15 minutes', stamp_base + interval '2 hours', stamp_base + interval '2 hours 15 minutes', c.full_name, c.phone
        FROM customers c WHERE c.id = cust_failed;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM payment_transactions WHERE id = failed_txn) THEN
        INSERT INTO payment_transactions(id, booking_id, invoice_id, payment_method, transaction_id, status, amount, transaction_date, created_at, source, note, reference)
        VALUES (failed_txn, failed_booking, NULL, 'VNPAY', 'BCM-DEMO-VNPAY-FAILED-1', 'FAILED', 180000, stamp_base + interval '2 hours 5 minutes', stamp_base + interval '2 hours 5 minutes', 'GATEWAY', 'MẪU FAILED: Giao dịch VNPay bị từ chối/hủy bởi người dùng (vnp_ResponseCode=24)', 'BCM-DEMO-VNPAY-FAILED-1');
    END IF;

    -- 4. Giao dịch VNPay PENDING chưa có kết quả xác nhận
    pending_booking := uuid_generate_v5(ns, 'sample-pending-booking-1');
    pending_txn := uuid_generate_v5(ns, 'sample-pending-txn-1');
    IF NOT EXISTS (SELECT 1 FROM bookings WHERE id = pending_booking) THEN
        INSERT INTO bookings(id, customer_id, status, court_fee, expires_at, created_at, updated_at, contact_full_name, contact_phone)
        SELECT pending_booking, cust_pending, 'PENDING', 200000, current_timestamp + interval '15 minutes', current_timestamp - interval '2 minutes', current_timestamp - interval '2 minutes', c.full_name, c.phone
        FROM customers c WHERE c.id = cust_pending;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM payment_transactions WHERE id = pending_txn) THEN
        INSERT INTO payment_transactions(id, booking_id, invoice_id, payment_method, transaction_id, status, amount, transaction_date, created_at, source, note, reference)
        VALUES (pending_txn, pending_booking, NULL, 'VNPAY', 'BCM-DEMO-VNPAY-PENDING-1', 'PENDING', 200000, current_timestamp - interval '2 minutes', current_timestamp - interval '2 minutes', 'GATEWAY', 'MẪU PENDING: Giao dịch VNPay đang chờ khách hàng quét mã/xác thực OTP', 'BCM-DEMO-VNPAY-PENDING-1');
    END IF;
END $sample_invoices_and_payments$;
ROLLBACK;
