\set ON_ERROR_STOP on
-- Run ONLY in a disposable database named bcm_catalog_seed_test_*.
DO $$ BEGIN
 IF current_database() !~ '^bcm_catalog_seed_test_' THEN
  RAISE EXCEPTION 'Use a dedicated bcm_catalog_seed_test_* database';
 END IF;
END $$;
\ir ../migration.sql
CREATE TEMP TABLE catalog_seed_baseline AS
 SELECT (SELECT count(*) FROM courts) courts, (SELECT count(*) FROM time_slots) slots;
\ir ../catalog-seed.sql
\ir ../catalog-seed.sql
DO $$ BEGIN
 IF (SELECT count(*) FROM categories)<>3 OR (SELECT count(*) FROM products)<>3 THEN
  RAISE EXCEPTION 'Initial/repeated seed must contain exactly 3 categories and 3 products';
 END IF;
 IF NOT EXISTS(SELECT 1 FROM products WHERE name='Nước suối 500ml' AND price=10000 AND stock_quantity=50 AND unit='chai')
 OR NOT EXISTS(SELECT 1 FROM products WHERE name='Cầu lông tiêu chuẩn' AND price=25000 AND stock_quantity=30)
 OR NOT EXISTS(SELECT 1 FROM products WHERE name='Thuê vợt theo lượt' AND type='SERVICE' AND stock_quantity=0) THEN
  RAISE EXCEPTION 'Sample values or category/product mapping are incorrect';
 END IF;
 IF EXISTS(SELECT 1 FROM products p JOIN categories c ON c.id=p.category_id WHERE
  (p.name='Nước suối 500ml' AND c.category_name<>'Nước giải khát') OR
  (p.name='Cầu lông tiêu chuẩn' AND c.category_name<>'Phụ kiện') OR
  (p.name='Thuê vợt theo lượt' AND c.category_name<>'Thuê vợt')) THEN
  RAISE EXCEPTION 'Incorrect category FK';
 END IF;
END $$;
UPDATE products SET price=9900.50,stock_quantity=17,unit='chai 500ml',version=11 WHERE name='Nước suối 500ml';
UPDATE categories SET category_name=' nước giải khát ' WHERE category_name='Nước giải khát';
\ir ../catalog-seed.sql
DO $$ BEGIN
 IF (SELECT count(*) FROM categories)<>3 OR (SELECT count(*) FROM products)<>3
 OR NOT EXISTS(SELECT 1 FROM products WHERE name='Nước suối 500ml' AND price=9900.50 AND stock_quantity=17 AND unit='chai 500ml' AND version=11) THEN
  RAISE EXCEPTION 'Seed duplicated normalized names or reset user price/stock/unit/version';
 END IF;
END $$;
UPDATE products SET deleted_at=CURRENT_TIMESTAMP WHERE name IN ('Nước suối 500ml','Cầu lông tiêu chuẩn');
UPDATE categories SET deleted_at=CURRENT_TIMESTAMP WHERE category_name='Phụ kiện';
\ir ../catalog-seed.sql
DO $$ BEGIN
 IF (SELECT count(*) FROM categories)<>3 OR (SELECT count(*) FROM products)<>3
 OR (SELECT count(*) FROM products WHERE deleted_at IS NOT NULL)<>2
 OR (SELECT count(*) FROM categories WHERE deleted_at IS NOT NULL)<>1 THEN
  RAISE EXCEPTION 'Seed resurrected deleted data';
 END IF;
 IF (SELECT count(*) FROM courts)<>(SELECT courts FROM catalog_seed_baseline)
 OR (SELECT count(*) FROM time_slots)<>(SELECT slots FROM catalog_seed_baseline) THEN
  RAISE EXCEPTION 'Seed changed courts/time slots';
 END IF;
END $$;
SELECT 'PASS: initial seed, repeat, FK, preserved edits, normalized names, soft deletes and court/slot counts' result;
