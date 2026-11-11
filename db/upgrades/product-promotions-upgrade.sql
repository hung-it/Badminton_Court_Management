-- Product quantity promotions. Run after migration.sql and booking-ux-upgrade.sql.
-- Additive; existing voucher/invoice rules and historical invoices are preserved.
BEGIN;
ALTER TABLE discount_rules ADD COLUMN IF NOT EXISTS min_quantity INTEGER NOT NULL DEFAULT 1;
ALTER TABLE invoice_details ADD COLUMN IF NOT EXISTS discount_amount NUMERIC(10,2) NOT NULL DEFAULT 0;
ALTER TABLE invoice_details ADD COLUMN IF NOT EXISTS promotion_id UUID REFERENCES promotions(id) ON DELETE SET NULL;
ALTER TABLE invoice_details ADD COLUMN IF NOT EXISTS promotion_name VARCHAR(255);
ALTER TABLE invoice_details ADD COLUMN IF NOT EXISTS discount_percent NUMERIC(5,2) NOT NULL DEFAULT 0;
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='chk_rule_min_quantity') THEN
        ALTER TABLE discount_rules ADD CONSTRAINT chk_rule_min_quantity CHECK (min_quantity > 0);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='chk_invoice_line_discount') THEN
        ALTER TABLE invoice_details ADD CONSTRAINT chk_invoice_line_discount
            CHECK (discount_amount >= 0 AND discount_amount <= quantity * unit_price
                   AND discount_percent >= 0 AND discount_percent <= 100);
    END IF;
END $$;
COMMIT;

-- BEGIN SECTION: shared-catalog-seed
-- Shared catalog samples only; never changes courts, bookings or existing inventory.
-- Integrated catalog from origin/codex/catalog-seed; insert-only, including soft-deleted identity checks.
BEGIN;
-- Serialize concurrent startup seeds so the NOT EXISTS checks cannot insert duplicates.
LOCK TABLE categories, products IN SHARE ROW EXCLUSIVE MODE;

DO $catalog$ BEGIN
 IF EXISTS(SELECT 1 FROM categories GROUP BY lower(btrim(regexp_replace(btrim(category_name), '[[:space:]]+', ' ', 'g'))) HAVING count(*)>1) THEN
  RAISE EXCEPTION 'Ambiguous existing category names; review before catalog seed';
 END IF;
END $catalog$;
INSERT INTO categories(category_name)
SELECT v.name FROM (VALUES
    ('Nước giải khát'), ('Phụ kiện'), ('Thuê vợt')
) AS v(name)
WHERE NOT EXISTS (
    SELECT 1 FROM categories c
    WHERE lower(btrim(regexp_replace(btrim(c.category_name), '[[:space:]]+', ' ', 'g'))) = lower(btrim(regexp_replace(btrim(v.name), '[[:space:]]+', ' ', 'g')))
);
-- Include soft-deleted rows in existence checks: startup must not resurrect deleted samples.
INSERT INTO products(category_id,name,type,unit,price,stock_quantity)
SELECT c.id,v.name,v.type,v.unit,v.price,v.stock
FROM (VALUES
    ('Nước giải khát','Nước suối 500ml','GOODS','chai',10000,0),
    ('Phụ kiện','Cầu lông tiêu chuẩn','GOODS','quả',25000,0),
    ('Thuê vợt','Thuê vợt theo lượt','SERVICE','lượt',20000,0)
) AS v(category,name,type,unit,price,stock)
JOIN categories c ON lower(btrim(regexp_replace(btrim(c.category_name), '[[:space:]]+', ' ', 'g'))) = lower(btrim(regexp_replace(btrim(v.category), '[[:space:]]+', ' ', 'g')))
                  AND c.deleted_at IS NULL
WHERE NOT EXISTS (
    SELECT 1 FROM products p
    WHERE p.category_id = c.id AND lower(btrim(regexp_replace(btrim(p.name), '[[:space:]]+', ' ', 'g'))) = lower(btrim(regexp_replace(btrim(v.name), '[[:space:]]+', ' ', 'g')))
);
COMMIT;

-- END SECTION: shared-catalog-seed
