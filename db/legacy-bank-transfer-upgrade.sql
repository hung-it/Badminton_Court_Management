-- Explicit opt-in preservation of reviewed BANK_TRANSFER history (2026-11-10).
-- Run only after a verified backup, before booking-ux-upgrade.sql.
-- No amount, method, status, transaction reference, timestamp or old actor is rewritten.
BEGIN;
SET LOCAL lock_timeout='5s';
LOCK TABLE invoices,payment_transactions IN SHARE ROW EXCLUSIVE MODE;
DO $$ BEGIN
 IF EXISTS(SELECT 1 FROM payment_transactions WHERE payment_method NOT IN ('CASH','VNPAY','BANK_TRANSFER'))
    OR EXISTS(SELECT 1 FROM invoices WHERE payment_method NOT IN ('CASH','VNPAY','BANK_TRANSFER')) THEN
  RAISE EXCEPTION 'Unknown historical payment methods; explicit review required';
 END IF;
 IF EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='payment_transactions' AND column_name='source') THEN
  IF EXISTS(SELECT 1 FROM payment_transactions WHERE payment_method='BANK_TRANSFER' AND source IS DISTINCT FROM 'LEGACY') THEN
   RAISE EXCEPTION 'Existing bank receipt source metadata requires separate review';
  END IF;
 ELSE
  ALTER TABLE payment_transactions ADD COLUMN source VARCHAR(20) NOT NULL DEFAULT 'GATEWAY';
  UPDATE payment_transactions SET source='LEGACY' WHERE payment_method='BANK_TRANSFER';
 END IF;
END $$;

CREATE OR REPLACE FUNCTION bcm_guard_legacy_bank_transfer() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='INSERT' THEN
  IF NEW.payment_method='BANK_TRANSFER' THEN
   RAISE EXCEPTION 'BANK_TRANSFER is read-only historical data' USING ERRCODE='23514';
  END IF;
 ELSE
  IF OLD.payment_method='BANK_TRANSFER' THEN
   IF TG_OP='DELETE' THEN
    RAISE EXCEPTION 'Cannot delete historical BANK_TRANSFER record' USING ERRCODE='23514';
   ELSIF NEW IS DISTINCT FROM OLD THEN
    RAISE EXCEPTION 'Cannot modify historical BANK_TRANSFER record' USING ERRCODE='23514';
   END IF;
  ELSIF TG_OP='UPDATE' AND NEW.payment_method='BANK_TRANSFER' THEN
   RAISE EXCEPTION 'Cannot create BANK_TRANSFER via update' USING ERRCODE='23514';
  END IF;
 END IF;
 IF TG_OP='DELETE' THEN RETURN OLD; END IF;
 RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS guard_legacy_bank_transfer ON payment_transactions;
CREATE TRIGGER guard_legacy_bank_transfer BEFORE INSERT OR UPDATE OR DELETE ON payment_transactions
 FOR EACH ROW EXECUTE FUNCTION bcm_guard_legacy_bank_transfer();
DROP TRIGGER IF EXISTS guard_legacy_bank_transfer ON invoices;
CREATE TRIGGER guard_legacy_bank_transfer BEFORE INSERT OR UPDATE OR DELETE ON invoices
 FOR EACH ROW EXECUTE FUNCTION bcm_guard_legacy_bank_transfer();
COMMIT;
