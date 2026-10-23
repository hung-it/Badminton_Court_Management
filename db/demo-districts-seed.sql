-- OPT-IN local/demo fixture only, never a factual address backfill.
-- Apply booking-ux-upgrade.sql first. In the SAME psql session:
--   SET bcm.allow_demo_seed = 'on';
-- These are legacy area labels for the coursework demo, not a current administrative directory.
-- courts has no street-address column: each explicitly marked demo facility is located only
-- at its district label. Existing courts and their unknown/verified locations are untouched.
BEGIN;
DO $$
BEGIN
    IF current_setting('bcm.allow_demo_seed', true) IS DISTINCT FROM 'on' THEN
        RAISE EXCEPTION 'Demo seed requires explicit SET bcm.allow_demo_seed = on after verifying the environment.';
    END IF;
    IF EXISTS (
        SELECT 1 FROM (VALUES
            ('bcdd0000-0000-4000-8000-000000000001'::uuid, 10001),
            ('bcdd0000-0000-4000-8000-000000000002'::uuid, 10002),
            ('bcdd0000-0000-4000-8000-000000000003'::uuid, 10003),
            ('bcdd0000-0000-4000-8000-000000000004'::uuid, 10004),
            ('bcdd0000-0000-4000-8000-000000000005'::uuid, 10005),
            ('bcdd0000-0000-4000-8000-000000000006'::uuid, 10006),
            ('bcdd0000-0000-4000-8000-000000000007'::uuid, 10007)
        ) AS expected(id, court_number)
        JOIN courts c ON c.id = expected.id OR c.court_number = expected.court_number
        WHERE c.id <> expected.id OR c.court_number <> expected.court_number OR c.name NOT LIKE '[DEMO] %'
    ) THEN
        RAISE EXCEPTION 'Reserved demo court identifiers conflict with existing records; no changes applied.';
    END IF;
END $$;

INSERT INTO courts(id, court_number, name, type, status, base_price, district) VALUES
('bcdd0000-0000-4000-8000-000000000001',10001,'[DEMO] Cơ sở Quận 1 - Sân 01','STANDARD_MAT','AVAILABLE',100000,'Quận 1'),
('bcdd0000-0000-4000-8000-000000000002',10002,'[DEMO] Cơ sở Quận 3 - Sân 01','WOODEN_FLOOR','AVAILABLE',120000,'Quận 3'),
('bcdd0000-0000-4000-8000-000000000003',10003,'[DEMO] Cơ sở Quận 5 - Sân 01','STANDARD_MAT','AVAILABLE',130000,'Quận 5'),
('bcdd0000-0000-4000-8000-000000000004',10004,'[DEMO] Cơ sở Quận 7 - Sân 01','WOODEN_FLOOR','AVAILABLE',150000,'Quận 7'),
('bcdd0000-0000-4000-8000-000000000005',10005,'[DEMO] Cơ sở Bình Thạnh - Sân 01','STANDARD_MAT','AVAILABLE',140000,'Bình Thạnh'),
('bcdd0000-0000-4000-8000-000000000006',10006,'[DEMO] Cơ sở Tân Bình - Sân 01','WOODEN_FLOOR','AVAILABLE',160000,'Tân Bình'),
('bcdd0000-0000-4000-8000-000000000007',10007,'[DEMO] Cơ sở Gò Vấp - Sân 01','STANDARD_MAT','AVAILABLE',110000,'Gò Vấp')
ON CONFLICT (id) DO NOTHING;
-- Existing demo rows (including an operator's district/price/status edits) are never overwritten.
COMMIT;
