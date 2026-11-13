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

-- BEGIN SECTION: product-promotions-seed
BEGIN;
LOCK TABLE promotions, discount_rules IN SHARE ROW EXCLUSIVE MODE;

INSERT INTO promotions(code, name, valid_from, valid_to, is_active)
SELECT v.code, v.name, v.valid_from::date, v.valid_to::date, true
FROM (VALUES
    ('KM-NUOCSUOI10', 'Ưu đãi nước suối: Mua từ 3 chai giảm 10%', '2024-01-01', '2030-12-31'),
    ('KM-DIENGIAI15', 'Ưu đãi nước điện giải: Mua từ 2 chai giảm 15%', '2024-01-01', '2030-12-31'),
    ('KM-CAULONG10', 'Ưu đãi cầu lông tiêu chuẩn: Mua từ 5 quả giảm 10%', '2024-01-01', '2030-12-31'),
    ('KM-ONGCAU5', 'Ưu đãi ống cầu lông: Mua từ 2 ống giảm 5%', '2024-01-01', '2030-12-31'),
    ('KM-QUANCAN20', 'Ưu đãi quấn cán vợt: Mua từ 3 cái giảm 20%', '2024-01-01', '2030-12-31'),
    ('KM-THUEVOT10', 'Ưu đãi thuê vợt: Thuê từ 2 lượt giảm 10%', '2024-01-01', '2030-12-31')
) AS v(code, name, valid_from, valid_to)
WHERE NOT EXISTS (
    SELECT 1 FROM promotions p WHERE upper(p.code) = upper(v.code)
);

INSERT INTO discount_rules(promotion_id, rule_type, target_product_id, min_quantity, discount_type, discount_value)
SELECT p.id, 'PRODUCT', prod.id, v.min_qty, 'PERCENT', v.discount_val
FROM (VALUES
    ('KM-NUOCSUOI10', 'Nước suối 500ml', 3, 10.00),
    ('KM-DIENGIAI15', 'Nước điện giải 500ml', 2, 15.00),
    ('KM-CAULONG10', 'Cầu lông tiêu chuẩn', 5, 10.00),
    ('KM-ONGCAU5', 'Ống cầu lông 12 quả', 2, 5.00),
    ('KM-QUANCAN20', 'Quấn cán vợt', 3, 20.00),
    ('KM-THUEVOT10', 'Thuê vợt theo lượt', 2, 10.00)
) AS v(code, prod_name, min_qty, discount_val)
JOIN promotions p ON upper(p.code) = upper(v.code)
JOIN products prod ON lower(btrim(prod.name)) = lower(btrim(v.prod_name)) AND prod.deleted_at IS NULL
WHERE NOT EXISTS (
    SELECT 1 FROM discount_rules d WHERE d.promotion_id = p.id
);
COMMIT;
-- END SECTION: product-promotions-seed
