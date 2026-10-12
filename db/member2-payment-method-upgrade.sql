-- Align existing databases with the team's supported payment methods.
-- Run after migration.sql and member2-upgrade.sql. Safe to run again.
BEGIN;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM invoices WHERE payment_method = 'MOMO')
       OR EXISTS (SELECT 1 FROM payment_transactions WHERE payment_method = 'MOMO') THEN
        RAISE EXCEPTION 'Existing MOMO payments require a team-approved data migration before applying this constraint.';
    END IF;
END $$;

ALTER TABLE invoices
    DROP CONSTRAINT IF EXISTS invoices_payment_method_check;
ALTER TABLE invoices
    ADD CONSTRAINT invoices_payment_method_check
    CHECK (payment_method IN ('CASH', 'BANK_TRANSFER', 'VNPAY'));

ALTER TABLE payment_transactions
    DROP CONSTRAINT IF EXISTS payment_transactions_payment_method_check;
ALTER TABLE payment_transactions
    ADD CONSTRAINT payment_transactions_payment_method_check
    CHECK (payment_method IN ('CASH', 'BANK_TRANSFER', 'VNPAY'));

COMMIT;
