-- Migration: Drop payment_transactions.reference safely and idempotently
ALTER TABLE payment_transactions DROP COLUMN IF EXISTS reference;
