-- Canonical additive upgrade: run after migration.sql, before starting the backend.
-- District, booking contact, court metadata, branches and checkout promotions.
-- Existing rows and all historical transaction boundaries are retained.

-- BEGIN SECTION: court-numbers
-- Preserve the globally unique business code and reuse the already persisted local number.
BEGIN;
SET LOCAL lock_timeout = '5s';
DO $court_numbers$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_attribute WHERE attrelid='courts'::regclass AND attname='court_code' AND NOT attisdropped) THEN
        ALTER TABLE courts RENAME COLUMN court_number TO court_code;
    END IF;
    IF EXISTS (SELECT 1 FROM pg_attribute WHERE attrelid='courts'::regclass AND attname='branch_court_number' AND NOT attisdropped) THEN
        IF EXISTS (SELECT 1 FROM pg_attribute WHERE attrelid='courts'::regclass AND attname='court_number' AND NOT attisdropped) THEN
            RAISE EXCEPTION 'BLOCKED: both local court-number columns exist; inspect mapping before migration';
        END IF;
        ALTER TABLE courts RENAME COLUMN branch_court_number TO court_number;
    ELSE
        ALTER TABLE courts ADD COLUMN IF NOT EXISTS court_number integer;
    END IF;
    IF EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid='courts'::regclass AND conname='courts_court_number_key')
       AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid='courts'::regclass AND conname='courts_court_code_key') THEN
        ALTER TABLE courts RENAME CONSTRAINT courts_court_number_key TO courts_court_code_key;
    END IF;
    IF EXISTS (SELECT 1 FROM pg_attribute WHERE attrelid='courts'::regclass AND attname='branch_id' AND NOT attisdropped) THEN
        IF (SELECT count(*) FROM courts)=210 THEN
            IF (SELECT count(DISTINCT branch_id) FROM courts)<>35 OR EXISTS (
                SELECT branch_id FROM courts GROUP BY branch_id
                HAVING branch_id IS NULL OR array_agg(court_number ORDER BY court_number)<>ARRAY[1,2,3,4,5,6]) THEN
                RAISE EXCEPTION 'BLOCKED: expected the verified 210-court mapping, six local numbers per branch';
            END IF;
            IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid='courts'::regclass AND conname='chk_court_number_range') THEN
                ALTER TABLE courts ADD CONSTRAINT chk_court_number_range CHECK (court_number BETWEEN 1 AND 6);
            END IF;
        END IF;
    END IF;
END $court_numbers$;
COMMENT ON COLUMN courts.court_code IS 'Stable globally unique business code; never use as the local court label.';
COMMENT ON COLUMN courts.court_number IS 'Persisted court number within its branch; customer labels format 1..6 as 01..06.';
COMMIT;
-- END SECTION: court-numbers

-- BEGIN SECTION: district
-- Additive Booking Engine upgrade. Apply after migration.sql, including on existing databases.
-- No backfill: a court name is not evidence of its district. Labels are verified master data,
-- not a hardcoded or immutable list of administrative divisions.
BEGIN;
ALTER TABLE courts ADD COLUMN IF NOT EXISTS district VARCHAR(150);
COMMIT;
-- END SECTION: district

-- BEGIN SECTION: contact
-- Checkout contact belongs to this booking, not to the account profile.
-- Apply after migration.sql, before starting the backend with these mappings.
-- Nullable for legacy bookings; reads fall back to their existing customer profile.
-- No email column, no customer_note, no backfill or changes to booking/payment history.
BEGIN;
ALTER TABLE bookings ADD COLUMN IF NOT EXISTS contact_full_name VARCHAR(255);
ALTER TABLE bookings ADD COLUMN IF NOT EXISTS contact_phone VARCHAR(20);
COMMIT;
-- END SECTION: contact

-- BEGIN SECTION: court-metadata
-- Additive upgrade; reuse courts.type as the surface material. No legacy data conversion.
-- Unknown legacy type values stop validation for operator review, never get overwritten.
BEGIN;
ALTER TABLE courts ADD COLUMN IF NOT EXISTS address varchar(500);
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid='courts'::regclass
                   AND conname='courts_surface_type_check') THEN
        ALTER TABLE courts ADD CONSTRAINT courts_surface_type_check
            CHECK (type IN ('STANDARD_MAT','WOODEN_FLOOR')) NOT VALID;
    END IF;
END $$;
ALTER TABLE courts VALIDATE CONSTRAINT courts_surface_type_check;
COMMIT;
-- END SECTION: court-metadata

-- BEGIN SECTION: branches
-- Additive branch support. Apply after the existing court metadata upgrades.
BEGIN;
CREATE TABLE IF NOT EXISTS branches (
    id uuid PRIMARY KEY DEFAULT uuid_generate_v4(),
    code varchar(50) NOT NULL UNIQUE,
    name varchar(255) NOT NULL,
    district varchar(150) NOT NULL,
    address varchar(500) NOT NULL,
    active boolean NOT NULL DEFAULT true,
    fixture_source varchar(150),
    created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at timestamp
);
ALTER TABLE courts ADD COLUMN IF NOT EXISTS branch_id uuid;
ALTER TABLE courts ADD COLUMN IF NOT EXISTS court_number integer;
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid='courts'::regclass AND conname='fk_courts_branch') THEN
        ALTER TABLE courts ADD CONSTRAINT fk_courts_branch FOREIGN KEY(branch_id) REFERENCES branches(id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid='courts'::regclass AND conname='chk_court_branch_number') THEN
        ALTER TABLE courts ADD CONSTRAINT chk_court_branch_number CHECK (
            (branch_id IS NULL AND court_number IS NULL) OR (branch_id IS NOT NULL AND court_number IS NOT NULL AND court_number > 0));
    END IF;
END $$;
CREATE UNIQUE INDEX IF NOT EXISTS uq_court_branch_number ON courts(branch_id,court_number);
COMMENT ON COLUMN courts.court_number IS 'Display number within a branch; court_code and all existing UUIDs remain unchanged.';
COMMIT;
-- END SECTION: branches

-- BEGIN SECTION: promotions
-- Additive, idempotent checkout promotion snapshots. Legacy bookings retain zero discount.
BEGIN;
ALTER TABLE promotions ALTER COLUMN valid_to DROP NOT NULL;
ALTER TABLE bookings ADD COLUMN IF NOT EXISTS discount_amount NUMERIC(10,2) NOT NULL DEFAULT 0 CHECK (discount_amount >= 0 AND discount_amount <= court_fee);
ALTER TABLE bookings ADD COLUMN IF NOT EXISTS promotion_code VARCHAR(50);
ALTER TABLE bookings ADD COLUMN IF NOT EXISTS requested_voucher VARCHAR(50);
ALTER TABLE bookings ADD COLUMN IF NOT EXISTS final_amount NUMERIC(10,2) GENERATED ALWAYS AS (court_fee - discount_amount) STORED;
INSERT INTO promotions(code,name,valid_from,valid_to,is_active)
VALUES ('NEWMEMBER','Khách hàng mới · 5%',DATE '2020-01-01',NULL,true),
       ('KMKH10','Khách hàng thân thiết · 10%',DATE '2020-01-01',NULL,true)
ON CONFLICT (code) DO NOTHING;
INSERT INTO discount_rules(promotion_id,rule_type,voucher_code,max_usage_per_customer,discount_type,discount_value)
SELECT p.id,'VOUCHER',p.code,CASE WHEN p.code='NEWMEMBER' THEN 1 ELSE NULL END,'PERCENT',
       CASE WHEN p.code='NEWMEMBER' THEN 5 ELSE 10 END
FROM promotions p WHERE p.code IN ('NEWMEMBER','KMKH10')
AND NOT EXISTS (SELECT 1 FROM discount_rules d WHERE d.promotion_id=p.id AND d.rule_type='VOUCHER' AND d.voucher_code=p.code);
CREATE INDEX IF NOT EXISTS idx_booking_newmember ON bookings(customer_id) WHERE promotion_code='NEWMEMBER';
COMMIT;
-- END SECTION: promotions

-- BEGIN SECTION: checkout-release
BEGIN;
SET LOCAL lock_timeout = '5s';
ALTER TABLE bookings ADD COLUMN IF NOT EXISTS checkout_release_at TIMESTAMP;
COMMIT;
-- END SECTION: checkout-release
