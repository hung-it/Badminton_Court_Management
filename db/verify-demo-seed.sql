-- Read-only assertions for a FRESH demo database, never for an operational DB.
BEGIN READ ONLY;
DO $verify$
BEGIN
    IF (SELECT count(*) FROM users)<>15 OR (SELECT count(*) FROM user_roles)<>15
       OR (SELECT count(*) FROM staffs)<>5 OR (SELECT count(*) FROM customers)<>10 THEN
        RAISE EXCEPTION 'Expected 15 accounts, 5 staff profiles (including ADMIN), 10 customers';
    END IF;
    IF EXISTS (SELECT r.role_name FROM roles r LEFT JOIN user_roles ur ON ur.role_id=r.id
        GROUP BY r.role_name HAVING count(ur.user_id)<>CASE r.role_name WHEN 'ADMIN' THEN 1 WHEN 'STAFF' THEN 4 ELSE 10 END) THEN
        RAISE EXCEPTION 'Unexpected role distribution';
    END IF;
    IF (SELECT count(*) FROM products)<>11 OR (SELECT count(*) FROM suppliers)<>3
       OR (SELECT count(*) FROM import_orders WHERE status='RECEIVED')<>12
       OR (SELECT count(*) FROM import_orders WHERE status='DRAFT')<>3
       OR (SELECT count(*) FROM import_orders)<>15
       OR (SELECT count(*) FROM bookings WHERE status='COMPLETED')<>100
       OR (SELECT count(*) FROM invoices WHERE status='PAID')<>150
       OR (SELECT count(*) FROM promotions WHERE code LIKE 'DEMO-OFF-%' AND NOT is_active)<>3 THEN
        RAISE EXCEPTION 'Demo dataset counts do not match';
    END IF;
    IF (SELECT count(DISTINCT date_trunc('month',created_at)) FROM invoices)<>12 THEN
        RAISE EXCEPTION 'Expected invoices across twelve calendar months';
    END IF;
    IF EXISTS(SELECT 1 FROM invoices i WHERE i.product_fee<>(SELECT sum(d.quantity*d.unit_price) FROM invoice_details d WHERE d.invoice_id=i.id)
        OR i.total_amount<>i.court_fee+i.product_fee-i.discount_amount
        OR i.total_amount<>(SELECT coalesce(sum(p.amount),0) FROM payment_transactions p WHERE p.status='SUCCESS'
            AND (p.invoice_id=i.id OR p.booking_id=i.booking_id))) THEN
        RAISE EXCEPTION 'Invoice lines/totals/receipts do not reconcile';
    END IF;
    IF EXISTS(SELECT 1 FROM bookings b WHERE b.court_fee<>(SELECT sum(d.price) FROM booking_details d WHERE d.booking_id=b.id)
        OR b.final_amount<>(SELECT sum(p.amount) FROM payment_transactions p WHERE p.booking_id=b.id AND p.status='SUCCESS')) THEN
        RAISE EXCEPTION 'Court fee snapshot or booking receipt mismatch';
    END IF;
    IF EXISTS(SELECT 1 FROM import_orders o WHERE o.total_amount<>(SELECT sum(d.quantity*d.import_price) FROM import_order_details d WHERE d.import_order_id=o.id)) THEN
        RAISE EXCEPTION 'Import totals do not reconcile';
    END IF;
    IF EXISTS(SELECT 1 FROM products p WHERE p.stock_quantity<0 OR (p.type='SERVICE' AND p.stock_quantity<>0)
        OR (p.type='GOODS' AND p.stock_quantity<>
            coalesce((SELECT sum(d.quantity) FROM import_order_details d JOIN import_orders o ON o.id=d.import_order_id WHERE d.product_id=p.id AND o.status='RECEIVED'),0)
            -coalesce((SELECT sum(d.quantity) FROM invoice_details d WHERE d.product_id=p.id AND d.stock_deducted),0))) THEN
        RAISE EXCEPTION 'Inventory does not reconcile with receipts and sales';
    END IF;
    IF EXISTS(WITH movements AS (
        SELECT d.product_id,d.created_at,d.quantity AS delta FROM import_order_details d
        JOIN import_orders o ON o.id=d.import_order_id WHERE o.status='RECEIVED'
        UNION ALL SELECT product_id,created_at,-quantity FROM invoice_details WHERE stock_deducted
    ), balances AS (
        SELECT sum(delta) OVER(PARTITION BY product_id ORDER BY created_at ROWS UNBOUNDED PRECEDING) AS balance FROM movements
    ) SELECT 1 FROM balances WHERE balance<0) THEN
        RAISE EXCEPTION 'Historical sales precede available inventory';
    END IF;
    IF EXISTS(SELECT 1 FROM payment_transactions WHERE source<>'MANUAL' OR payment_method<>'CASH'
        OR confirmed_by_user IS NULL OR note NOT LIKE '[DEMO]%') THEN
        RAISE EXCEPTION 'Demo receipt must be explicitly simulated and audited';
    END IF;
    IF EXISTS(SELECT 1 FROM booking_details GROUP BY booking_date,court_id,time_slot_id HAVING count(*)>1) THEN
        RAISE EXCEPTION 'Duplicate court slot';
    END IF;
    RAISE NOTICE 'Demo counts, calendar coverage, snapshots, inventory, receipts and audits verified';
END $verify$;
ROLLBACK;
