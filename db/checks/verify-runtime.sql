-- Operational checks: no fixed demo counts and no writes.
BEGIN READ ONLY;
DO $verify$
BEGIN
 -- Checkout moves draft lines to the paid parent and retains the cancelled draft header.
 IF EXISTS(SELECT 1 FROM invoices i WHERE i.parent_invoice_id IS NOT NULL AND
  (i.status IS DISTINCT FROM 'CANCELLED' OR i.booking_id IS NULL OR i.payment_method IS NOT NULL
   OR NOT EXISTS(SELECT 1 FROM invoices p WHERE p.id=i.parent_invoice_id AND p.status='PAID'
    AND p.parent_invoice_id IS NULL AND p.booking_id=i.booking_id)
   OR EXISTS(SELECT 1 FROM invoice_details d WHERE d.invoice_id=i.id)
   OR EXISTS(SELECT 1 FROM payment_transactions t WHERE t.invoice_id=i.id))) THEN
  RAISE EXCEPTION 'Invalid merged draft or paid parent link';
 END IF;
 IF EXISTS(SELECT 1 FROM invoices i WHERE
  (i.parent_invoice_id IS NULL AND i.product_fee IS DISTINCT FROM
   (SELECT coalesce(sum(d.quantity*d.unit_price),0) FROM invoice_details d WHERE d.invoice_id=i.id))
  OR i.total_amount IS DISTINCT FROM i.court_fee+i.product_fee-i.discount_amount
  OR (i.status='PAID' AND i.total_amount IS DISTINCT FROM
   (SELECT coalesce(sum(p.amount),0) FROM payment_transactions p WHERE p.status='SUCCESS' AND (p.invoice_id=i.id OR p.booking_id=i.booking_id)))) THEN
  RAISE EXCEPTION 'Invoice snapshots or successful receipts do not reconcile';
 END IF;
 IF EXISTS(SELECT 1 FROM bookings b WHERE b.status IN ('PAID','COMPLETED') AND
  (b.court_fee IS DISTINCT FROM (SELECT coalesce(sum(d.price),0) FROM booking_details d WHERE d.booking_id=b.id)
   OR b.final_amount IS DISTINCT FROM (SELECT coalesce(sum(p.amount),0) FROM payment_transactions p WHERE p.booking_id=b.id AND p.status='SUCCESS'))) THEN
  RAISE EXCEPTION 'Paid booking snapshots or receipts do not reconcile';
 END IF;
 IF EXISTS(SELECT 1 FROM import_orders o WHERE o.total_amount IS DISTINCT FROM
  (SELECT coalesce(sum(d.quantity*d.import_price),0) FROM import_order_details d WHERE d.import_order_id=o.id)) THEN
  RAISE EXCEPTION 'Import totals do not reconcile';
 END IF;
 IF EXISTS(SELECT 1 FROM products WHERE stock_quantity<0 OR (type='SERVICE' AND stock_quantity<>0)) THEN
  RAISE EXCEPTION 'Invalid inventory';
 END IF;
 IF EXISTS(SELECT 1 FROM payment_transactions WHERE payment_method NOT IN ('CASH','VNPAY') OR
  source NOT IN ('MANUAL','GATEWAY') OR (source='MANUAL' AND confirmed_by_user IS NULL)) THEN
  RAISE EXCEPTION 'Unsupported payment method or missing receipt actor';
 END IF;
 IF EXISTS(SELECT 1 FROM invoices WHERE payment_method NOT IN ('CASH','VNPAY')) OR
  EXISTS(SELECT 1 FROM invoices WHERE status='DRAFT' AND payment_method IS NOT NULL) OR
  EXISTS(SELECT 1 FROM courts WHERE court_code<=0 OR base_price<=0) THEN
  RAISE EXCEPTION 'Unsupported invoice or invalid court price/code';
 END IF;
 RAISE NOTICE 'Runtime invoice, booking, import, stock and receipt invariants passed';
END $verify$;
ROLLBACK;
