-- Additive Booking Engine upgrade. Apply after migration.sql, including on existing databases.
-- No backfill: a court name is not evidence of its district. Labels are verified master data,
-- not a hardcoded or immutable list of administrative divisions.
BEGIN;
ALTER TABLE bookings ADD COLUMN IF NOT EXISTS customer_note VARCHAR(1000);
ALTER TABLE courts ADD COLUMN IF NOT EXISTS district VARCHAR(150);
COMMIT;
