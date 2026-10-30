-- Shared catalog samples only; never changes courts, bookings or existing inventory.
-- Run after migration.sql/member2-upgrade.sql. Safe for new and existing databases.
BEGIN;
-- Serialize concurrent startup seeds so the NOT EXISTS checks cannot insert duplicates.
LOCK TABLE categories, products IN SHARE ROW EXCLUSIVE MODE;

INSERT INTO categories(category_name)
SELECT v.name FROM (VALUES
    ('Nước giải khát'), ('Phụ kiện'), ('Thuê vợt')
) AS v(name)
WHERE NOT EXISTS (
    SELECT 1 FROM categories c
    WHERE lower(btrim(c.category_name)) = lower(btrim(v.name))
);
-- Include soft-deleted rows in existence checks: startup must not resurrect deleted samples.
INSERT INTO products(category_id,name,type,unit,price,stock_quantity)
SELECT c.id,v.name,v.type,v.unit,v.price,v.stock
FROM (VALUES
    ('Nước giải khát','Nước suối 500ml','GOODS','chai',10000,50),
    ('Phụ kiện','Cầu lông tiêu chuẩn','GOODS','quả',25000,30),
    ('Thuê vợt','Thuê vợt theo lượt','SERVICE','lượt',20000,0)
) AS v(category,name,type,unit,price,stock)
JOIN categories c ON lower(btrim(c.category_name)) = lower(btrim(v.category))
                  AND c.deleted_at IS NULL
WHERE NOT EXISTS (
    SELECT 1 FROM products p
    WHERE p.category_id = c.id AND lower(btrim(p.name)) = lower(btrim(v.name))
);
COMMIT;
