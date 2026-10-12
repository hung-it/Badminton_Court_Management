-- Optional demo data for an isolated development database only.
BEGIN;
INSERT INTO courts(court_number,name,type,status,base_price)
SELECT v.n,v.name,v.type,v.status,v.price FROM (VALUES
(1,'Sân 01','STANDARD_MAT','AVAILABLE',100000),
(2,'Sân 02','STANDARD_MAT','AVAILABLE',100000),
(3,'Sân 03','WOODEN_FLOOR','AVAILABLE',120000),
(4,'Sân 04','WOODEN_FLOOR','MAINTENANCE',120000)) AS v(n,name,type,status,price)
WHERE NOT EXISTS(SELECT 1 FROM courts c WHERE c.court_number=v.n);
INSERT INTO categories(category_name)
SELECT v.name FROM (VALUES ('Nước giải khát'),('Phụ kiện'),('Thuê vợt')) v(name)
WHERE NOT EXISTS(SELECT 1 FROM categories c WHERE c.category_name=v.name AND c.deleted_at IS NULL);
INSERT INTO products(category_id,name,type,unit,price,stock_quantity)
SELECT c.id,v.name,v.type,v.unit,v.price,v.stock FROM (VALUES
('Nước giải khát','Nước suối 500ml','GOODS','chai',10000,50),
('Phụ kiện','Cầu lông tiêu chuẩn','GOODS','quả',25000,30),
('Thuê vợt','Thuê vợt theo lượt','SERVICE','lượt',20000,0)) v(category,name,type,unit,price,stock)
JOIN categories c ON c.category_name=v.category AND c.deleted_at IS NULL
WHERE NOT EXISTS(SELECT 1 FROM products p WHERE p.name=v.name AND p.deleted_at IS NULL);
COMMIT;
