-- Member 2: additive upgrade, run after migration.sql (also on existing databases).
BEGIN;
ALTER TABLE products ADD COLUMN IF NOT EXISTS unit VARCHAR(50) NOT NULL DEFAULT 'cái';
ALTER TABLE products ADD COLUMN IF NOT EXISTS image_url VARCHAR(2048);
ALTER TABLE time_slots ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMP;
CREATE UNIQUE INDEX IF NOT EXISTS uq_categories_active_name ON categories(lower(btrim(category_name))) WHERE deleted_at IS NULL;
DO $$ BEGIN
IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='master_court_values') THEN
ALTER TABLE courts ADD CONSTRAINT master_court_values CHECK (btrim(name)<>'' AND base_price>=0 AND court_number>0);
END IF;
IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='master_category_name') THEN
ALTER TABLE categories ADD CONSTRAINT master_category_name CHECK (btrim(category_name)<>'');
END IF;
IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='master_product_values') THEN
ALTER TABLE products ADD CONSTRAINT master_product_values CHECK (btrim(name)<>'' AND btrim(unit)<>'' AND price>=0 AND stock_quantity>=0 AND (type<>'SERVICE' OR stock_quantity=0));
END IF;
IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='master_slot_multiplier') THEN
ALTER TABLE time_slots ADD CONSTRAINT master_slot_multiplier CHECK (price_multiplier>0);
END IF;
IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='master_slot_no_overlap') THEN
ALTER TABLE time_slots ADD CONSTRAINT master_slot_no_overlap EXCLUDE USING gist
(int4range(extract(epoch from start_time)::integer,extract(epoch from end_time)::integer,'[)') WITH &&) WHERE (deleted_at IS NULL);
END IF;
END $$;
COMMIT;
