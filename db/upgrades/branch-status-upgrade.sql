-- Idempotent branch and court status separation upgrade
BEGIN;
-- Ensure branches has active boolean column
ALTER TABLE branches ADD COLUMN IF NOT EXISTS active boolean NOT NULL DEFAULT true;

-- Migrate any legacy CLOSED court to MAINTENANCE since CLOSED now belongs exclusively to branch level
UPDATE courts SET status = 'MAINTENANCE' WHERE status = 'CLOSED';

-- Validate runtime invariant
DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM courts WHERE status NOT IN ('AVAILABLE', 'MAINTENANCE')) THEN
        RAISE EXCEPTION 'Courts must only be AVAILABLE or MAINTENANCE';
    END IF;
END $$;
COMMIT;
