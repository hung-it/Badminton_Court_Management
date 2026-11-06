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

-- BEGIN SECTION: catalog-integrity
-- Member 2: additive upgrade, run after migration.sql (also on existing databases).
BEGIN;
SET LOCAL lock_timeout = '5s';
ALTER TABLE products ADD COLUMN IF NOT EXISTS unit VARCHAR(50) NOT NULL DEFAULT 'cái';
ALTER TABLE products ADD COLUMN IF NOT EXISTS image_url VARCHAR(2048);
ALTER TABLE time_slots ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMP;
CREATE UNIQUE INDEX IF NOT EXISTS uq_categories_active_name ON categories(lower(btrim(category_name))) WHERE deleted_at IS NULL;
DO $$ BEGIN
IF EXISTS (SELECT 1 FROM pg_constraint c JOIN pg_attribute a ON a.attrelid=c.conrelid AND a.attnum=ANY(c.conkey) WHERE c.conrelid='courts'::regclass AND c.conname='master_court_values' AND a.attname='court_number') THEN
 IF EXISTS(SELECT 1 FROM courts WHERE btrim(name)='' OR base_price<0 OR court_code<=0) THEN RAISE EXCEPTION 'Invalid court data; global code constraint repair refused'; END IF;
 ALTER TABLE courts DROP CONSTRAINT master_court_values;
END IF;
IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid='courts'::regclass AND conname='master_court_values') THEN
ALTER TABLE courts ADD CONSTRAINT master_court_values CHECK (btrim(name)<>'' AND base_price>=0 AND court_code>0);
END IF;
IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid='categories'::regclass AND conname='master_category_name') THEN
ALTER TABLE categories ADD CONSTRAINT master_category_name CHECK (btrim(category_name)<>'');
END IF;
IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid='products'::regclass AND conname='master_product_values') THEN
ALTER TABLE products ADD CONSTRAINT master_product_values CHECK (btrim(name)<>'' AND btrim(unit)<>'' AND price>=0 AND stock_quantity>=0 AND (type<>'SERVICE' OR stock_quantity=0));
END IF;
IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid='time_slots'::regclass AND conname='master_slot_multiplier') THEN
ALTER TABLE time_slots ADD CONSTRAINT master_slot_multiplier CHECK (price_multiplier>0);
END IF;
IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid='time_slots'::regclass AND conname='master_slot_no_overlap') THEN
ALTER TABLE time_slots ADD CONSTRAINT master_slot_no_overlap EXCLUDE USING gist
(int4range(extract(epoch from start_time)::integer,extract(epoch from end_time)::integer,'[)') WITH &&) WHERE (deleted_at IS NULL);
END IF;
END $$;
COMMIT;

-- END SECTION: catalog-integrity

-- BEGIN SECTION: payment-method-compatibility
BEGIN;
SET LOCAL lock_timeout = '5s';
DO $methods$
DECLARE t text; n text; definition text;
 booking_ids uuid[] := ARRAY[
 '5378d15f-c4d5-4749-a567-2209de11848a','649d7874-76d7-4819-ae5f-252605fa6d47',
 '868ae90d-f7f9-484a-ae2d-4f498b96439a','ad54a6cc-d66a-4a95-a416-295f2342a077',
 'b3f34c1c-0869-4fc9-a2fb-5d70f2ccca5d','bb33e652-8a7a-4907-a639-b3501e5f6b96',
 'ca147e41-1aae-461f-8b57-3e1c5cbbe8aa','e14f66d2-ebb8-40db-a364-80f86cb1a8f9',
 'e3ec9f78-8f56-442c-ab94-8e268755a338']::uuid[];
 payment_ids uuid[] := ARRAY[
 '0be54dec-af96-4ee8-8589-a03181aebed0','2a39e782-beff-4d01-8469-887552ae78c8',
 '42e519bc-0c4c-4d95-ad9b-91b76948de4a','484b24f7-b166-48bd-b912-ba49da9f4a89',
 '4ee3e446-96eb-43a0-b822-180c9c7c2ee8','7c7113a6-955c-4210-af1d-9144dfa77192',
 'a6e21c5d-ac95-4761-a6cb-5e25d787cfef','b1306c42-6fed-4bfb-842d-c2e09507c140',
 'dced5a24-640d-4a06-9121-48a2af080580']::uuid[];
 invoice_ids uuid[] := ARRAY[
 '10f3a738-d6dd-455f-b821-fcf53b253100','7cac4c2e-8001-4d8e-b16c-dd5816843c98',
 '93de3edc-d846-46b9-bd36-89960a450ae0','9c93b405-dd07-46a3-9691-de5b0672ccf4',
 'e9bfdacc-6ce8-40ac-bc79-bdf208c537c9']::uuid[];
BEGIN
 -- Reviewed development-only cleanup, explicitly authorized 2026-11-05.
 -- Never runs during ordinary bootstrap/upgrades. Requires a fresh verified backup.
 -- These invoices are dependent counter-settlement fixtures of the listed transfer bookings,
 -- not independent CASH sales. No payment is relabelled and no stock is changed.
 IF current_setting('bcm.remove_development_transfers',true)='on'
    AND EXISTS(SELECT 1 FROM payment_transactions WHERE payment_method='BANK_TRANSFER') THEN
  IF current_database()<>'badminton_court_db' THEN RAISE EXCEPTION 'Cleanup approved only for reviewed development database'; END IF;
  LOCK TABLE bookings,payment_transactions,invoices,invoice_details,booking_details,customer_voucher_usage IN SHARE ROW EXCLUSIVE MODE;
  IF (SELECT count(*) FROM payment_transactions WHERE payment_method='BANK_TRANSFER')<>9
     OR (SELECT count(*) FROM payment_transactions WHERE id=ANY(payment_ids) AND booking_id=ANY(booking_ids) AND payment_method='BANK_TRANSFER' AND invoice_id IS NULL)<>9
     OR (SELECT count(*) FROM payment_transactions WHERE id=ANY(payment_ids) AND status='PENDING' AND note LIKE 'CUSTOMER_DECLARED;%')<>6
     OR (SELECT count(*) FROM payment_transactions WHERE id=ANY(payment_ids) AND status='SUCCESS' AND transaction_id='RECONCILED-'||id::text AND note LIKE 'STAFF_RECEIVED;%CUSTOMER_DECLARED;%')<>3
     OR EXISTS(SELECT 1 FROM payment_transactions WHERE (booking_id=ANY(booking_ids) OR invoice_id=ANY(invoice_ids)) AND NOT(id=ANY(payment_ids)))
     OR (SELECT count(*) FROM bookings WHERE id=ANY(booking_ids))<>9
     OR (SELECT count(*) FROM invoices WHERE id=ANY(invoice_ids) AND booking_id=ANY(booking_ids) AND payment_method='CASH')<>5
     OR EXISTS(SELECT 1 FROM invoices WHERE booking_id=ANY(booking_ids) AND NOT(id=ANY(invoice_ids))) THEN
   RAISE EXCEPTION 'Reviewed development cluster has changed; rollback and audit again';
  END IF;
  DELETE FROM customer_voucher_usage WHERE invoice_id=ANY(invoice_ids);
  DELETE FROM invoice_details WHERE invoice_id=ANY(invoice_ids);
  DELETE FROM payment_transactions WHERE id=ANY(payment_ids);
  DELETE FROM invoices WHERE id=ANY(invoice_ids);
  DELETE FROM booking_details WHERE booking_id=ANY(booking_ids);
  DELETE FROM bookings WHERE id=ANY(booking_ids);
 END IF;
 IF EXISTS(SELECT 1 FROM invoices WHERE payment_method NOT IN ('CASH','VNPAY','MIXED')) OR EXISTS(SELECT 1 FROM payment_transactions WHERE payment_method NOT IN ('CASH','VNPAY')) THEN
  RAISE EXCEPTION 'Unsupported historical payments require a reviewed data migration; no automatic conversion is permitted';
 END IF;
 FOREACH t IN ARRAY ARRAY['invoices','payment_transactions'] LOOP
  n=t||'_payment_method_check';
  SELECT pg_get_constraintdef(oid) INTO definition FROM pg_constraint WHERE conrelid=t::regclass AND conname=n;
  IF definition IS NOT NULL AND (position('MOMO' in definition)>0 OR position('BANK_TRANSFER' in definition)>0) THEN
   EXECUTE format('ALTER TABLE %I DROP CONSTRAINT %I',t,n); definition=NULL;
  ELSIF definition IS NOT NULL AND NOT (position('CASH' in definition)>0 AND position('VNPAY' in definition)>0) THEN
   RAISE EXCEPTION 'Unrecognized payment constraint on %, manual review required',t;
  END IF;
  IF definition IS NULL THEN EXECUTE format('ALTER TABLE %I ADD CONSTRAINT %I CHECK(payment_method IN (''CASH'',''VNPAY''))',t,n); END IF;
 END LOOP;
END $methods$;
COMMIT;
-- END SECTION: payment-method-compatibility

-- BEGIN SECTION: invoice-receipts
-- No seed or historical financial mutation. Stop and report incompatible records.
BEGIN;
SET LOCAL lock_timeout = '5s';
LOCK TABLE invoices,payment_transactions IN SHARE ROW EXCLUSIVE MODE;
DO $$ DECLARE blocked text; BEGIN
 SELECT string_agg(id::text, ', ') INTO blocked FROM (
 SELECT id FROM invoices WHERE payment_method NOT IN ('CASH','VNPAY','MIXED') OR (status='DRAFT' AND payment_method IS NOT NULL)
 UNION ALL SELECT id FROM payment_transactions WHERE payment_method NOT IN ('CASH','VNPAY')) x;
 IF blocked IS NOT NULL THEN RAISE EXCEPTION 'Payment migration blocked by historical records requiring review: %', blocked; END IF;
END $$;
ALTER TABLE invoices ALTER COLUMN payment_method DROP NOT NULL;
ALTER TABLE invoices ADD COLUMN IF NOT EXISTS pos_request_key UUID;
ALTER TABLE invoices ADD COLUMN IF NOT EXISTS request_fingerprint VARCHAR(64);
ALTER TABLE invoices ADD COLUMN IF NOT EXISTS created_by_user UUID REFERENCES users(id);
ALTER TABLE invoices ADD COLUMN IF NOT EXISTS parent_invoice_id UUID REFERENCES invoices(id);
CREATE UNIQUE INDEX IF NOT EXISTS uq_pos_request_key ON invoices(pos_request_key) WHERE pos_request_key IS NOT NULL;
ALTER TABLE invoice_details ADD COLUMN IF NOT EXISTS product_name VARCHAR(255);
ALTER TABLE invoice_details ADD COLUMN IF NOT EXISTS added_by UUID REFERENCES staffs(id);
ALTER TABLE invoice_details ADD COLUMN IF NOT EXISTS added_by_user UUID REFERENCES users(id);
-- NULL on legacy rows: inventory provenance must be reviewed before cancellation.
ALTER TABLE invoice_details ADD COLUMN IF NOT EXISTS stock_deducted BOOLEAN;
DO $$ DECLARE blocked text; BEGIN
 IF NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_name='payment_transactions' AND column_name='source') THEN
 SELECT string_agg(id::text,', ') INTO blocked FROM payment_transactions WHERE payment_method='CASH' OR note LIKE 'STAFF_RECEIVED;%';
 IF blocked IS NOT NULL THEN RAISE EXCEPTION 'Legacy CASH receipt actors must be reviewed: %',blocked; END IF;
 END IF;
END $$;
ALTER TABLE payment_transactions ADD COLUMN IF NOT EXISTS source VARCHAR(20) NOT NULL DEFAULT 'GATEWAY';
ALTER TABLE payment_transactions ADD COLUMN IF NOT EXISTS confirmed_by UUID REFERENCES staffs(id);
ALTER TABLE payment_transactions ADD COLUMN IF NOT EXISTS confirmed_by_user UUID REFERENCES users(id);
ALTER TABLE payment_transactions ADD COLUMN IF NOT EXISTS reference VARCHAR(255);
-- Convert only a reconciled multi-method summary, never its underlying receipts.
DO $$ DECLARE blocked text; BEGIN
 SELECT string_agg(i.id::text,', ') INTO blocked FROM invoices i WHERE i.payment_method='MIXED' AND
 (SELECT count(DISTINCT p.payment_method)<2 OR coalesce(sum(p.amount),0)<>i.total_amount FROM payment_transactions p
 WHERE p.status='SUCCESS' AND (p.invoice_id=i.id OR p.booking_id=i.booking_id));
 IF blocked IS NOT NULL THEN RAISE EXCEPTION 'Unreconciled multi-method invoices: %',blocked; END IF;
END $$;
ALTER TABLE invoices DROP CONSTRAINT IF EXISTS invoices_payment_method_check;
UPDATE invoices SET payment_method=NULL WHERE payment_method='MIXED';
ALTER TABLE invoices ADD CONSTRAINT invoices_payment_method_check CHECK (
 (status='DRAFT' AND payment_method IS NULL) OR
 (status='PAID' AND (payment_method IS NULL OR payment_method IN ('CASH','VNPAY'))) OR
 (status IN ('CANCELLED','REFUNDED') AND (payment_method IS NULL OR payment_method IN ('CASH','VNPAY'))));
ALTER TABLE payment_transactions DROP CONSTRAINT IF EXISTS payment_transactions_payment_method_check;
ALTER TABLE payment_transactions ADD CONSTRAINT payment_transactions_payment_method_check CHECK(payment_method IN ('CASH','VNPAY'));
DO $$ BEGIN
 IF NOT EXISTS(SELECT 1 FROM pg_constraint WHERE conrelid='payment_transactions'::regclass AND conname='chk_receipt_source') THEN
 ALTER TABLE payment_transactions ADD CONSTRAINT chk_receipt_source CHECK (
 (source='GATEWAY' AND payment_method='VNPAY' AND confirmed_by IS NULL AND confirmed_by_user IS NULL) OR
 (source='MANUAL' AND confirmed_by_user IS NOT NULL AND transaction_date IS NOT NULL AND status='SUCCESS' AND amount>0));
 END IF;
END $$;
CREATE INDEX IF NOT EXISTS idx_receipt_staff ON payment_transactions(confirmed_by) WHERE status='SUCCESS';
COMMENT ON COLUMN invoices.payment_method IS 'Receipt summary only. NULL for DRAFT, multiple methods or no receipts. payment_transactions is the evidence.';
COMMENT ON COLUMN invoice_details.stock_deducted IS 'Stock removed at POS append; retained at checkout; restored once when an unmerged DRAFT is cancelled. NULL means unknown legacy provenance.';
COMMIT;
-- END SECTION: invoice-receipts

-- BEGIN SECTION: import-actors
-- Add nullable real-user audit references; never invent a Staff or backfill history.
BEGIN;
SET LOCAL lock_timeout = '5s';
ALTER TABLE import_orders ADD COLUMN IF NOT EXISTS created_by_user UUID REFERENCES users(id);
ALTER TABLE import_orders ADD COLUMN IF NOT EXISTS updated_by_user UUID REFERENCES users(id);
COMMIT;
-- END SECTION: import-actors

-- BEGIN SECTION: booking-statuses
-- Refuse rather than reinterpret legacy statuses. No historical row is converted.
BEGIN;
SET LOCAL lock_timeout = '5s';
LOCK TABLE bookings IN SHARE ROW EXCLUSIVE MODE;
DO $$ DECLARE blocked text; BEGIN
 SELECT string_agg(id::text,', ') INTO blocked FROM bookings WHERE status NOT IN ('PENDING','PAID','COMPLETED','EXPIRED');
 IF blocked IS NOT NULL THEN RAISE EXCEPTION 'Legacy booking statuses require review; keep compatibility enum: %',blocked; END IF;
END $$;
ALTER TABLE bookings DROP CONSTRAINT IF EXISTS bookings_status_check;
ALTER TABLE bookings ADD CONSTRAINT bookings_status_check CHECK(status IN ('PENDING','PAID','COMPLETED','EXPIRED'));
COMMIT;
-- END SECTION: booking-statuses
