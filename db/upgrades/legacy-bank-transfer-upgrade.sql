-- Retired compatibility entry point: strict validation only, never rewrites history.
BEGIN;
LOCK TABLE invoices,payment_transactions IN SHARE ROW EXCLUSIVE MODE;
DO $$ BEGIN
 IF EXISTS(SELECT 1 FROM invoices WHERE payment_method NOT IN ('CASH','VNPAY'))
 OR EXISTS(SELECT 1 FROM payment_transactions WHERE payment_method NOT IN ('CASH','VNPAY')) THEN
 RAISE EXCEPTION 'Unsupported payment history; manual review required';
 END IF;
END $$;
COMMIT;
