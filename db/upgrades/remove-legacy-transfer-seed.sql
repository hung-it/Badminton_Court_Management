-- Opt-in cleanup of one verified fixture from 3b57b62:db/revenue-breakdown-sample.sql.
-- Run through scripts/remove-legacy-transfer-seed.ps1 to create a full backup first.
BEGIN;
SET LOCAL lock_timeout='5s';
LOCK TABLE invoices,invoice_details IN SHARE ROW EXCLUSIVE MODE;
DO $cleanup$
DECLARE
 target uuid:='b2000000-0000-4000-8000-000000000103';
 actual jsonb; detail jsonb; dependency record; referenced boolean;
 expected jsonb:='{"id":"b2000000-0000-4000-8000-000000000103","booking_id":null,"customer_id":"b2000000-0000-4000-8000-000000000033","promotion_id":null,"payment_method":"BANK_TRANSFER","status":"PAID","court_fee":0.00,"product_fee":60000.00,"discount_amount":0.00,"total_amount":60000.00,"created_by":null,"updated_by":null,"created_at":"2026-03-14T08:45:00","updated_at":"2026-03-14T08:45:00","deleted_at":null}';
 expected_detail jsonb:='{"id":"b2000000-0000-4000-8000-000000000124","invoice_id":"b2000000-0000-4000-8000-000000000103","product_id":"b2000000-0000-4000-8000-000000000044","quantity":6,"unit_price":10000.00,"note":null,"created_at":"2026-03-14T08:45:00","discount_amount":0.00,"promotion_id":null,"promotion_name":null,"discount_percent":0.00}';
BEGIN
 SELECT to_jsonb(i) INTO actual FROM invoices i WHERE id=target FOR UPDATE;
 IF actual IS NULL THEN
  RAISE NOTICE 'Known legacy fixture is absent; nothing removed';
  RETURN;
 END IF;
 IF actual IS DISTINCT FROM expected THEN
  RAISE EXCEPTION 'BLOCKED: legacy invoice differs from the original seed; preserve and review';
 END IF;
 IF (SELECT count(*) FROM invoice_details WHERE invoice_id=target)<>1 THEN
  RAISE EXCEPTION 'BLOCKED: unexpected legacy fixture lines';
 END IF;
 SELECT to_jsonb(d) INTO detail FROM invoice_details d WHERE invoice_id=target;
 IF detail IS DISTINCT FROM expected_detail THEN
  RAISE EXCEPTION 'BLOCKED: legacy invoice line differs from the original seed';
 END IF;
 -- Any receipt, voucher usage or child invoice makes the fixture ineligible.
 FOR dependency IN
  SELECT fk.conrelid::regclass AS relation,a.attname AS column_name
  FROM pg_constraint fk JOIN pg_attribute a ON a.attrelid=fk.conrelid AND a.attnum=fk.conkey[1]
  WHERE fk.contype='f' AND fk.confrelid='invoices'::regclass AND fk.conrelid<>'invoice_details'::regclass
 LOOP
  EXECUTE format('SELECT EXISTS(SELECT 1 FROM %s WHERE %I=$1)',dependency.relation,dependency.column_name) INTO referenced USING target;
  IF referenced THEN RAISE EXCEPTION 'BLOCKED: fixture has dependent records in %',dependency.relation; END IF;
 END LOOP;
 DELETE FROM invoice_details WHERE invoice_id=target;
 DELETE FROM invoices WHERE id=target;
 RAISE NOTICE 'Removed exactly one verified legacy seed invoice and its seed line';
END $cleanup$;
COMMIT;
