-- Development and testing sample courts. Safe for existing PostgreSQL databases.
BEGIN;

INSERT INTO courts (court_number, name, type, status, base_price)
VALUES
    (1, 'Sân cầu lông Bình Minh - Sân 1', 'STANDARD_MAT', 'AVAILABLE', 100000.00),
    (2, 'Sân cầu lông Bình Minh - Sân 2', 'STANDARD_MAT', 'AVAILABLE', 120000.00),
    (3, 'Sân cầu lông Phú Thọ - Sân 1', 'WOODEN_FLOOR', 'AVAILABLE', 150000.00),
    (4, 'Sân cầu lông Tân Bình - Sân 1', 'WOODEN_FLOOR', 'AVAILABLE', 180000.00),
    (5, 'Sân cầu lông Thủ Đức - Sân 1', 'STANDARD_MAT', 'AVAILABLE', 130000.00)
ON CONFLICT (court_number) DO NOTHING;

DO $$
DECLARE
    conflicting_rows integer;
BEGIN
    SELECT count(*)
      INTO conflicting_rows
      FROM (VALUES
          (1, 'Sân cầu lông Bình Minh - Sân 1', 'STANDARD_MAT', 'AVAILABLE', 100000.00::numeric),
          (2, 'Sân cầu lông Bình Minh - Sân 2', 'STANDARD_MAT', 'AVAILABLE', 120000.00::numeric),
          (3, 'Sân cầu lông Phú Thọ - Sân 1', 'WOODEN_FLOOR', 'AVAILABLE', 150000.00::numeric),
          (4, 'Sân cầu lông Tân Bình - Sân 1', 'WOODEN_FLOOR', 'AVAILABLE', 180000.00::numeric),
          (5, 'Sân cầu lông Thủ Đức - Sân 1', 'STANDARD_MAT', 'AVAILABLE', 130000.00::numeric)
      ) AS expected(court_number, name, type, status, base_price)
      LEFT JOIN courts actual USING (court_number)
     WHERE actual.id IS NULL
        OR actual.name IS DISTINCT FROM expected.name
        OR actual.type IS DISTINCT FROM expected.type
        OR actual.status IS DISTINCT FROM expected.status
        OR actual.base_price IS DISTINCT FROM expected.base_price
        OR actual.deleted_at IS NOT NULL;

    IF conflicting_rows > 0 THEN
        RAISE EXCEPTION 'Sample court seed stopped: % court number(s) conflict with existing data.', conflicting_rows;
    END IF;
END;
$$;

COMMIT;
