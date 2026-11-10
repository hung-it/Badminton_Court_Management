-- Canonical coursework seed: run after migration.sql and booking-ux-upgrade.sql.
-- Preserve historical district/metadata backfills before the current venue catalog.
-- Each section retains its original guards, fixture rows and transaction boundary.

-- BEGIN SECTION: districts
-- Historical pre-branch seed, retained for existing JDBC migration tests and setup references.
-- Compose now uses branches-seed.sql for the complete 35-court district/address/branch catalog.
-- This legacy step cannot create the superseded 50-court dataset.
-- Official COURSEWORK dataset, not verified real-world facilities or street addresses.
-- Order: migration.sql -> booking-ux-upgrade.sql -> this file (psql -v ON_ERROR_STOP=1 -f ...).
-- Existing UUIDs, prices, status, types, timestamps/history and foreign keys are preserved,
-- except name/district/updated_at on rows explicitly changed below. Never infers geography.
-- courts has no address/is_demo columns. District is the only simulated location field.
BEGIN;
-- Serialize seed executions; do not race an administrator editing the same catalog.
LOCK TABLE courts IN SHARE ROW EXCLUSIVE MODE;

CREATE TEMP TABLE project_court_fixture (
    legacy_id uuid PRIMARY KEY, court_code integer UNIQUE, name text,
    type text, base_price numeric, district text, actual_id uuid
) ON COMMIT DROP;
INSERT INTO project_court_fixture(legacy_id,court_code,name,type,base_price,district) VALUES
('d83b877d-f7ca-4f9a-ab70-1c60f280a9e0',1,'Sân cầu lông Star City','WOODEN_FLOOR',180000,'Quận 1'),
('cab07c5c-2a83-4c92-8c2b-69b484b125a5',2,'Green Court Arena','STANDARD_MAT',220000,'Quận 3'),
('80a0280f-d646-481c-a710-e9f2bc82a840',3,'Sky Sports Center','WOODEN_FLOOR',150000,'Quận 5'),
('200517f6-c5cb-433f-bfd1-1401d62a9fce',4,'Sân cầu lông Quận 7','STANDARD_MAT',200000,'Quận 7'),
('b6ef93d0-1d22-4eb4-a186-21e0d740ed47',5,'Sân cầu lông Bình Thạnh','WOODEN_FLOOR',160000,'Bình Thạnh'),
('c5a1bcf1-f974-45ce-942d-e2eda801abed',6,'Sân cầu lông Tân Bình','STANDARD_MAT',190000,'Tân Bình'),
('16d1dff0-611c-447c-a8ce-3ec425dcc8c4',7,'Sân cầu lông Thủ Đức','WOODEN_FLOOR',175000,'Quận 1'),
('4dc9aed8-401e-4891-a643-9a243ac56604',8,'Sân cầu lông Gò Vấp','STANDARD_MAT',165000,'Gò Vấp');
-- Above UUIDs were inspected on the existing project runtime. Fresh migration.sql assigns
-- random UUIDs: resolve those ONLY by the unique court_code plus the complete known
-- migration fixture name/type/price signature, then update by the resolved UUID below.
-- The name "Thủ Đức" is retained as a fixture name; Quận 1 is an explicit simulation
-- assignment authorized for this dataset, not a geographic deduction from that name.
UPDATE project_court_fixture f SET actual_id=c.id FROM courts c
WHERE c.court_code=f.court_code AND c.name=f.name
  AND (c.id=f.legacy_id OR (c.type=f.type AND c.base_price=f.base_price)
       OR NULLIF(btrim(c.district),'') IS NOT NULL);

DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM project_court_fixture WHERE actual_id IS NULL) THEN
        RAISE EXCEPTION 'Original project fixtures are missing or changed; verify their UUID/address provenance before assigning districts.';
    END IF;
END $$;

CREATE TEMP TABLE project_additional_court (
    id uuid PRIMARY KEY, court_code integer UNIQUE, name text,
    type text, base_price numeric, district text
) ON COMMIT DROP;
INSERT INTO project_additional_court VALUES
('bcdd0000-0000-4000-8000-000000000001',10001,'Cơ sở Quận 1 - Sân 01','STANDARD_MAT',100000,'Quận 1'),
('bcdd0000-0000-4000-8000-000000000002',10002,'Cơ sở Quận 3 - Sân 01','WOODEN_FLOOR',120000,'Quận 3'),
('bcdd0000-0000-4000-8000-000000000003',10003,'Cơ sở Quận 5 - Sân 01','STANDARD_MAT',130000,'Quận 5'),
('bcdd0000-0000-4000-8000-000000000004',10004,'Cơ sở Quận 7 - Sân 01','WOODEN_FLOOR',150000,'Quận 7'),
('bcdd0000-0000-4000-8000-000000000005',10005,'Cơ sở Bình Thạnh - Sân 01','STANDARD_MAT',140000,'Bình Thạnh'),
('bcdd0000-0000-4000-8000-000000000006',10006,'Cơ sở Tân Bình - Sân 01','WOODEN_FLOOR',160000,'Tân Bình'),
('bcdd0000-0000-4000-8000-000000000007',10007,'Cơ sở Gò Vấp - Sân 01','STANDARD_MAT',110000,'Gò Vấp');
DO $$ BEGIN
    IF EXISTS (
        SELECT 1 FROM project_additional_court f JOIN courts c
          ON c.id=f.id OR c.court_code=f.court_code
        WHERE c.id<>f.id OR c.court_code<>f.court_code
           OR (c.name NOT IN (f.name, '[DEMO] ' || f.name) AND to_jsonb(c)->>'branch_id' IS NULL)
    ) THEN
        RAISE EXCEPTION 'Reserved project court identifiers conflict with existing records; transaction aborted.';
    END IF;
END $$;

-- Exact UUID update; fill missing districts only, including whitespace-only values.
UPDATE courts c SET district=f.district, updated_at=CURRENT_TIMESTAMP
FROM project_court_fixture f
WHERE c.id=f.actual_id AND NULLIF(btrim(c.district),'') IS NULL;

-- Convert the seven known records in place, or create them on a new project database.
-- No generic LIKE/replace operation and no delete/reinsert. Operator changes are retained.
INSERT INTO courts(id,court_code,name,type,status,base_price,district)
SELECT id,court_code,name,type,'AVAILABLE',base_price,district FROM project_additional_court
ON CONFLICT (id) DO UPDATE SET
    name=CASE WHEN courts.name IN (EXCLUDED.name,'[DEMO] '||EXCLUDED.name) THEN EXCLUDED.name ELSE courts.name END,
    district=CASE WHEN NULLIF(btrim(courts.district),'') IS NULL THEN EXCLUDED.district ELSE courts.district END,
    updated_at=CURRENT_TIMESTAMP
WHERE courts.name='[DEMO] '||EXCLUDED.name OR NULLIF(btrim(courts.district),'') IS NULL;
COMMIT;
-- END SECTION: districts

-- BEGIN SECTION: court-metadata
-- Historical metadata backfill retained for existing JDBC migration tests/references.
-- Compose now gets all district/address/branch data from branches-seed.sql.
-- COURSEWORK FIXTURES ONLY. Simulated areas, not verified facilities/street addresses.
-- Run after courts-districts-seed.sql and courts-metadata-upgrade.sql.
-- Fixed runtime UUIDs; fresh database UUIDs resolved by exact original fixture signature.
-- UPDATE ONLY: no court insert, no overwrite of valid surfaces/addresses or operator districts.
BEGIN;
LOCK TABLE courts IN SHARE ROW EXCLUSIVE MODE;
CREATE TEMP TABLE court_metadata_fixture (
    id uuid, court_code integer, name text, district text, type text, price numeric
) ON COMMIT DROP;
INSERT INTO court_metadata_fixture VALUES
('d83b877d-f7ca-4f9a-ab70-1c60f280a9e0',1,'Sân cầu lông Star City','Quận 1','WOODEN_FLOOR',180000),
('cab07c5c-2a83-4c92-8c2b-69b484b125a5',2,'Green Court Arena','Quận 3','STANDARD_MAT',220000),
('80a0280f-d646-481c-a710-e9f2bc82a840',3,'Sky Sports Center','Quận 5','WOODEN_FLOOR',150000),
('200517f6-c5cb-433f-bfd1-1401d62a9fce',4,'Sân cầu lông Quận 7','Quận 7','STANDARD_MAT',200000),
('b6ef93d0-1d22-4eb4-a186-21e0d740ed47',5,'Sân cầu lông Bình Thạnh','Bình Thạnh','WOODEN_FLOOR',160000),
('c5a1bcf1-f974-45ce-942d-e2eda801abed',6,'Sân cầu lông Tân Bình','Tân Bình','STANDARD_MAT',190000),
('16d1dff0-611c-447c-a8ce-3ec425dcc8c4',7,'Sân cầu lông Thủ Đức','Quận 1','WOODEN_FLOOR',175000),
('4dc9aed8-401e-4891-a643-9a243ac56604',8,'Sân cầu lông Gò Vấp','Gò Vấp','STANDARD_MAT',165000),
('bcdd0000-0000-4000-8000-000000000001',10001,'Cơ sở Quận 1 - Sân 01','Quận 1','STANDARD_MAT',100000),
('bcdd0000-0000-4000-8000-000000000002',10002,'Cơ sở Quận 3 - Sân 01','Quận 3','WOODEN_FLOOR',120000),
('bcdd0000-0000-4000-8000-000000000003',10003,'Cơ sở Quận 5 - Sân 01','Quận 5','STANDARD_MAT',130000),
('bcdd0000-0000-4000-8000-000000000004',10004,'Cơ sở Quận 7 - Sân 01','Quận 7','WOODEN_FLOOR',150000),
('bcdd0000-0000-4000-8000-000000000005',10005,'Cơ sở Bình Thạnh - Sân 01','Bình Thạnh','STANDARD_MAT',140000),
('bcdd0000-0000-4000-8000-000000000006',10006,'Cơ sở Tân Bình - Sân 01','Tân Bình','WOODEN_FLOOR',160000),
('bcdd0000-0000-4000-8000-000000000007',10007,'Cơ sở Gò Vấp - Sân 01','Gò Vấp','STANDARD_MAT',110000);

-- Only fill known fixtures with their unchanged district. Renamed/relocated unknown rows
-- remain for operator review. Nonempty address and supported type always win.
UPDATE courts c SET
    type=CASE WHEN NULLIF(btrim(c.type),'') IS NULL THEN f.type ELSE c.type END,
    address=CASE WHEN NULLIF(btrim(c.address),'') IS NULL
        THEN 'Khu thể thao mô phỏng ' || f.court_code || ', ' || f.district || ', TP. Hồ Chí Minh'
        ELSE c.address END,
    updated_at=CURRENT_TIMESTAMP
FROM court_metadata_fixture f
WHERE c.court_code=f.court_code AND c.name=f.name AND c.district=f.district
  AND (c.id=f.id OR (f.court_code BETWEEN 1 AND 8 AND c.type=f.type AND c.base_price=f.price))
  AND (NULLIF(btrim(c.address),'') IS NULL OR NULLIF(btrim(c.type),'') IS NULL);
COMMIT;
-- END SECTION: court-metadata

-- BEGIN SECTION: branches
-- Canonical COURSEWORK fixtures: 35 independent venues, six playing courts each, including all eight
-- original migration fixtures. Area addresses are fixture assignments, NOT verified venues.
-- Replaces district/metadata seed steps in bootstrap; historical migrations stay intact.
-- Reconciles the old 50-row catalog only by removing unchanged, unreferenced generated rows.
BEGIN;
LOCK TABLE branches, courts IN SHARE ROW EXCLUSIVE MODE;
CREATE TEMP TABLE catalog35_branches(seq int,code text,district text,type text,price numeric,id uuid) ON COMMIT DROP;
ALTER TABLE catalog35_branches ADD COLUMN brand text;
ALTER TABLE catalog35_branches ADD COLUMN court_names text[];
INSERT INTO catalog35_branches(seq,code,district,type,price) VALUES
(1,'Q1','Quận 1','STANDARD_MAT',100000),(2,'Q3','Quận 3','WOODEN_FLOOR',120000),
(3,'Q5','Quận 5','STANDARD_MAT',130000),(4,'Q7','Quận 7','WOODEN_FLOOR',150000),
(5,'BT','Bình Thạnh','STANDARD_MAT',140000),(6,'TB','Tân Bình','WOODEN_FLOOR',160000),
(7,'GV','Gò Vấp','STANDARD_MAT',110000);
UPDATE catalog35_branches SET id=('bcbb0000-0000-4000-8000-'||lpad(seq::text,12,'0'))::uuid;
UPDATE catalog35_branches SET brand=CASE code WHEN 'Q1' THEN 'Phố Xanh' WHEN 'Q3' THEN 'Ánh Dương'
WHEN 'Q5' THEN 'Thành Công' WHEN 'Q7' THEN 'Hoàng Gia' WHEN 'BT' THEN 'Hòa Bình'
WHEN 'TB' THEN 'Thanh Xuân' WHEN 'GV' THEN 'Sao Việt' END;
UPDATE catalog35_branches SET court_names=CASE code WHEN 'Q1' THEN ARRAY['Phố Xanh','Bình Minh','Ngọc Lan','Quang Minh','Vườn Trúc','Thanh Phong']
WHEN 'Q3' THEN ARRAY['Ánh Dương','Thiên Thanh','Ban Mai','Trúc Xanh','Thiên Hương','Đông Phương']
WHEN 'Q5' THEN ARRAY['Thành Công','Vạn Phúc','Kim Long','An Phú','Bạch Dương','Ngân Hà']
WHEN 'Q7' THEN ARRAY['Hoàng Gia','Nam Phong','Đại Dương','Minh Tâm','Mộc Lan','Bình An']
WHEN 'BT' THEN ARRAY['Hòa Bình','Hưng Thịnh','Bảo An','Gia Phát','Hướng Dương','Thảo Nguyên']
WHEN 'TB' THEN ARRAY['Thanh Xuân','Tân Tiến','Khải Hoàn','Lam Sơn','Vĩnh Phát','Tâm An']
WHEN 'GV' THEN ARRAY['Sao Việt','Thiên Phúc','Hải Đăng','An Nhiên','Xuân Mai','Việt Thắng'] END;
CREATE TEMP TABLE catalog35_original(number int,legacy_id uuid,name text,type text,price numeric,seq int,id uuid) ON COMMIT DROP;
INSERT INTO catalog35_original(number,legacy_id,name,type,price,seq) VALUES
(1,'d83b877d-f7ca-4f9a-ab70-1c60f280a9e0','Sân cầu lông Star City','WOODEN_FLOOR',180000,1),
(2,'cab07c5c-2a83-4c92-8c2b-69b484b125a5','Green Court Arena','STANDARD_MAT',220000,2),
(3,'80a0280f-d646-481c-a710-e9f2bc82a840','Sky Sports Center','WOODEN_FLOOR',150000,3),
(4,'200517f6-c5cb-433f-bfd1-1401d62a9fce','Sân cầu lông Quận 7','STANDARD_MAT',200000,4),
(5,'b6ef93d0-1d22-4eb4-a186-21e0d740ed47','Sân cầu lông Bình Thạnh','WOODEN_FLOOR',160000,5),
(6,'c5a1bcf1-f974-45ce-942d-e2eda801abed','Sân cầu lông Tân Bình','STANDARD_MAT',190000,6),
(7,'16d1dff0-611c-447c-a8ce-3ec425dcc8c4','Sân cầu lông Thủ Đức','WOODEN_FLOOR',175000,1),
(8,'4dc9aed8-401e-4891-a643-9a243ac56604','Sân cầu lông Gò Vấp','STANDARD_MAT',165000,7);
-- Fresh migration UUIDs are resolved by the complete original fixture signature.
UPDATE catalog35_original f SET id=c.id FROM courts c WHERE c.court_code=f.number AND c.name=f.name
AND (c.id=f.legacy_id OR (c.type=f.type AND c.base_price=f.price));
CREATE TEMP TABLE catalog35_protected(id uuid PRIMARY KEY) ON COMMIT DROP;
CREATE TEMP TABLE catalog35_known(id uuid PRIMARY KEY,seq int,kind int,removable boolean) ON COMMIT DROP;
CREATE TEMP TABLE catalog35_plan(id uuid PRIMARY KEY,branch_id uuid,local_number int) ON COMMIT DROP;
DO $$
DECLARE f record; original record; fk record; c courts%ROWTYPE; anchor courts%ROWTYPE;
candidate uuid; n int; needed int; next_number int; reference_column name;
BEGIN
IF EXISTS(SELECT 1 FROM catalog35_original WHERE id IS NULL) THEN
RAISE EXCEPTION 'BLOCKED: original fixture identity changed; manual review required';
END IF;
-- Already migrated catalogs retain their branch IDs and administrator metadata.
IF (SELECT count(*) FROM courts) IN (35,210) AND (SELECT count(*) FROM branches)=35
AND (SELECT count(DISTINCT branch_id) FROM courts)=35 THEN
INSERT INTO catalog35_known SELECT id,0,0,false FROM courts;
INSERT INTO catalog35_plan SELECT id,branch_id,court_number FROM courts;
RETURN;
END IF;
-- Inspect and lock EVERY referencing table; no concurrent insert can invalidate this plan.
FOR fk IN SELECT conrelid,conkey,confkey FROM pg_constraint WHERE confrelid='courts'::regclass AND contype='f' LOOP
IF cardinality(fk.conkey)<>1 OR cardinality(fk.confkey)<>1 OR
(SELECT attname FROM pg_attribute WHERE attrelid='courts'::regclass AND attnum=fk.confkey[1])<>'id' THEN
RAISE EXCEPTION 'BLOCKED: unsupported court FK requires manual dependency review';
END IF;
EXECUTE format('LOCK TABLE %s IN SHARE ROW EXCLUSIVE MODE',fk.conrelid::regclass);
SELECT attname INTO reference_column FROM pg_attribute WHERE attrelid=fk.conrelid AND attnum=fk.conkey[1];
EXECUTE format('INSERT INTO catalog35_protected SELECT DISTINCT %I FROM %s WHERE %I IS NOT NULL ON CONFLICT DO NOTHING',reference_column,fk.conrelid::regclass,reference_column);
END LOOP;
FOR f IN SELECT * FROM catalog35_branches ORDER BY seq LOOP
IF EXISTS(SELECT 1 FROM branches WHERE (id=f.id OR code=f.code) AND
(id<>f.id OR code<>f.code OR district<>f.district OR address<>f.district||', TP. Hồ Chí Minh')) THEN
RAISE EXCEPTION 'BLOCKED: branch identifier/address conflict: %',f.code;
END IF;
INSERT INTO branches(id,code,name,district,address,fixture_source)
VALUES(f.id,f.code,'Chi nhánh '||f.district,f.district,f.district||', TP. Hồ Chí Minh','coursework:35-courts-fixed-mapping')
ON CONFLICT(id) DO NOTHING;
next_number:=1;
FOR original IN SELECT o.*,ct.branch_id,ct.district,ct.address FROM catalog35_original o JOIN courts ct ON ct.id=o.id WHERE o.seq=f.seq ORDER BY o.number LOOP
IF (original.branch_id IS NOT NULL AND original.branch_id<>f.id)
OR (NULLIF(btrim(original.district),'') IS NOT NULL AND original.district<>f.district)
OR (NULLIF(btrim(original.address),'') IS NOT NULL AND original.address NOT IN
(f.district||', TP. Hồ Chí Minh','Khu thể thao mô phỏng '||original.number||', '||f.district||', TP. Hồ Chí Minh')) THEN
RAISE EXCEPTION 'BLOCKED: original facility changed; manual review required: %',original.number;
END IF;
INSERT INTO catalog35_known VALUES(original.id,f.seq,0,false);
INSERT INTO catalog35_plan VALUES(original.id,f.id,next_number);
next_number:=next_number+1;
END LOOP;
candidate:=('bcdd0000-0000-4000-8000-'||lpad(f.seq::text,12,'0'))::uuid;
IF EXISTS(SELECT 1 FROM courts WHERE court_code=10000+f.seq AND id<>candidate) THEN
RAISE EXCEPTION 'BLOCKED: reserved anchor court number conflict';
END IF;
INSERT INTO courts(id,court_code,name,type,status,base_price,district,address,branch_id,court_number)
VALUES(candidate,10000+f.seq,'Cơ sở '||f.district||' - Sân 01',f.type,'AVAILABLE',f.price,f.district,
f.district||', TP. Hồ Chí Minh',f.id,100000000+10000+f.seq) ON CONFLICT(id) DO NOTHING;
SELECT * INTO anchor FROM courts WHERE id=candidate;
IF anchor.court_code<>10000+f.seq
OR (anchor.branch_id IS NOT NULL AND anchor.branch_id<>f.id) OR anchor.district IS DISTINCT FROM f.district
OR (NULLIF(btrim(anchor.address),'') IS NOT NULL AND anchor.address NOT IN
(f.district||', TP. Hồ Chí Minh','Khu thể thao mô phỏng '||anchor.court_code||', '||f.district||', TP. Hồ Chí Minh')) THEN
RAISE EXCEPTION 'BLOCKED: anchor facility changed; manual review required';
END IF;
INSERT INTO catalog35_known VALUES(candidate,f.seq,1,false);
INSERT INTO catalog35_plan VALUES(candidate,f.id,next_number);
next_number:=next_number+1;
FOR n IN 2..6 LOOP
candidate:=md5('bcm:branch:'||f.code||':court:'||n)::uuid;
SELECT * INTO c FROM courts WHERE id=candidate;
IF FOUND THEN
IF c.court_code<>20000+f.seq*100+n OR c.branch_id IS DISTINCT FROM f.id THEN
RAISE EXCEPTION 'BLOCKED: generated fixture identity changed';
END IF;
INSERT INTO catalog35_known VALUES(candidate,f.seq,2,
(c.name='Cơ sở '||f.district||' - Sân '||lpad(n::text,2,'0') OR c.name ~ ('^Sân cầu lông '||f.brand||' - Sân 0[1-5]$') OR c.name='Sân cầu lông '||f.court_names[n]) AND c.type=anchor.type AND c.base_price=anchor.base_price
AND c.district=f.district AND c.address=f.district||', TP. Hồ Chí Minh' AND c.status='AVAILABLE' AND c.deleted_at IS NULL);
END IF;
END LOOP;
needed:=6-next_number;
IF (SELECT count(*) FROM catalog35_known k WHERE k.seq=f.seq AND k.kind=2 AND
(NOT k.removable OR EXISTS(SELECT 1 FROM catalog35_protected p WHERE p.id=k.id)))>needed THEN
RAISE EXCEPTION 'BLOCKED: too many protected courts in branch %; no history will be deleted',f.code;
END IF;
FOR c IN SELECT ct.* FROM courts ct JOIN catalog35_known k ON k.id=ct.id WHERE k.seq=f.seq AND k.kind=2
ORDER BY (NOT k.removable OR EXISTS(SELECT 1 FROM catalog35_protected p WHERE p.id=k.id)) DESC,ct.court_code LIMIT needed LOOP
INSERT INTO catalog35_plan VALUES(c.id,f.id,next_number);next_number:=next_number+1;
END LOOP;
-- Fresh databases insert only missing rows; never create the superseded 50-court catalog.
FOR n IN 2..6 LOOP
EXIT WHEN next_number>5;
candidate:=md5('bcm:branch:'||f.code||':court:'||n)::uuid;
IF EXISTS(SELECT 1 FROM courts WHERE id=candidate) THEN CONTINUE; END IF;
IF EXISTS(SELECT 1 FROM courts WHERE court_code=20000+f.seq*100+n) THEN
RAISE EXCEPTION 'BLOCKED: reserved generated court number conflict';
END IF;
INSERT INTO courts(id,court_code,name,type,status,base_price,district,address,branch_id,court_number)
VALUES(candidate,20000+f.seq*100+n,'Cơ sở '||f.district||' - Sân '||lpad(n::text,2,'0'),anchor.type,
'AVAILABLE',anchor.base_price,f.district,f.district||', TP. Hồ Chí Minh',f.id,100000000+20000+f.seq*100+n);
INSERT INTO catalog35_known VALUES(candidate,f.seq,2,true);
INSERT INTO catalog35_plan VALUES(candidate,f.id,next_number);next_number:=next_number+1;
END LOOP;
END LOOP;
IF EXISTS(SELECT 1 FROM courts ct WHERE NOT EXISTS(SELECT 1 FROM catalog35_known k WHERE k.id=ct.id)) THEN
RAISE EXCEPTION 'BLOCKED: unknown courts outside the authorized fixture catalog';
END IF;
IF EXISTS(SELECT 1 FROM catalog35_known k WHERE NOT EXISTS(SELECT 1 FROM catalog35_plan p WHERE p.id=k.id)
AND (k.kind<>2 OR NOT k.removable OR EXISTS(SELECT 1 FROM catalog35_protected d WHERE d.id=k.id))) THEN
RAISE EXCEPTION 'BLOCKED: removal would affect protected or nonfixture courts';
END IF;
END $$;
-- Complete keep/remove plan and all dependency checks exist BEFORE deleting any court.
SELECT ct.id AS removed_fixture_id,ct.court_code,ct.name FROM courts ct JOIN catalog35_known k ON k.id=ct.id
WHERE NOT EXISTS(SELECT 1 FROM catalog35_plan p WHERE p.id=ct.id) ORDER BY ct.court_code;
DELETE FROM courts ct USING catalog35_known k WHERE ct.id=k.id AND k.kind=2 AND k.removable
AND NOT EXISTS(SELECT 1 FROM catalog35_plan p WHERE p.id=ct.id)
AND NOT EXISTS(SELECT 1 FROM catalog35_protected d WHERE d.id=ct.id);
-- Positive staging numbers avoid transient collisions in the existing unique branch index.
UPDATE courts ct SET branch_id=p.branch_id,court_number=100000000+ct.court_code
FROM catalog35_plan p WHERE p.id=ct.id AND (ct.branch_id IS DISTINCT FROM p.branch_id OR ct.court_number IS DISTINCT FROM p.local_number);
UPDATE courts ct SET court_number=p.local_number,district=b.district,address=b.district||', TP. Hồ Chí Minh',updated_at=CURRENT_TIMESTAMP
FROM catalog35_plan p JOIN catalog35_branches b ON b.id=p.branch_id
WHERE ct.id=p.id AND (ct.court_number IS DISTINCT FROM p.local_number OR ct.district IS DISTINCT FROM b.district OR ct.address IS DISTINCT FROM b.district||', TP. Hồ Chí Minh');
DO $$ BEGIN
IF (SELECT count(*) FROM courts) NOT IN (35,210) OR (SELECT count(*) FROM branches) NOT IN (7,35)
OR EXISTS(SELECT 1 FROM courts WHERE branch_id IS NULL)
OR EXISTS(SELECT branch_id FROM courts GROUP BY branch_id HAVING count(*)<>CASE WHEN (SELECT count(*) FROM courts)=210 THEN 6 WHEN (SELECT count(*) FROM branches)=35 THEN 1 ELSE 5 END)
OR EXISTS(SELECT 1 FROM courts WHERE status<>'AVAILABLE' OR deleted_at IS NOT NULL) THEN
RAISE EXCEPTION 'BLOCKED: expected the known 35-court catalog before independent branch migration';
END IF;
END $$;
-- Fixture display-name normalization. This single DO block may also be applied on
-- its own to an existing catalog: UPDATE names only, never reconcile/move/delete courts.
-- Brands are coursework labels, not claims that real-world venues exist.
DO $fixture_names$
DECLARE f record; n integer; fixture_id uuid; renamed integer; total_renamed integer:=0;
BEGIN
    FOR f IN SELECT * FROM (VALUES
        (1,'Q1','Quận 1','Phố Xanh',ARRAY['Phố Xanh','Bình Minh','Ngọc Lan','Quang Minh','Vườn Trúc','Thanh Phong']),
        (2,'Q3','Quận 3','Ánh Dương',ARRAY['Ánh Dương','Thiên Thanh','Ban Mai','Trúc Xanh','Thiên Hương','Đông Phương']),
        (3,'Q5','Quận 5','Thành Công',ARRAY['Thành Công','Vạn Phúc','Kim Long','An Phú','Bạch Dương','Ngân Hà']),
        (4,'Q7','Quận 7','Hoàng Gia',ARRAY['Hoàng Gia','Nam Phong','Đại Dương','Minh Tâm','Mộc Lan','Bình An']),
        (5,'BT','Bình Thạnh','Hòa Bình',ARRAY['Hòa Bình','Hưng Thịnh','Bảo An','Gia Phát','Hướng Dương','Thảo Nguyên']),
        (6,'TB','Tân Bình','Thanh Xuân',ARRAY['Thanh Xuân','Tân Tiến','Khải Hoàn','Lam Sơn','Vĩnh Phát','Tâm An']),
        (7,'GV','Gò Vấp','Sao Việt',ARRAY['Sao Việt','Thiên Phúc','Hải Đăng','An Nhiên','Xuân Mai','Việt Thắng'])
    ) AS fixture(seq,code,district,brand,court_names) LOOP
        FOR n IN 1..6 LOOP
            fixture_id:=CASE WHEN n=1 THEN ('bcdd0000-0000-4000-8000-'||lpad(f.seq::text,12,'0'))::uuid
                ELSE md5('bcm:branch:'||f.code||':court:'||n)::uuid END;
            UPDATE courts SET name='Sân cầu lông '||f.court_names[n]
            WHERE id=fixture_id AND court_code=CASE WHEN n=1 THEN 10000+f.seq ELSE 20000+f.seq*100+n END
              AND (name IN ('Cơ sở '||f.district||' - Sân '||lpad(n::text,2,'0'),'[DEMO] Cơ sở '||f.district||' - Sân '||lpad(n::text,2,'0'))
                   OR name ~ ('^Sân cầu lông '||f.brand||' - Sân 0[1-5]$'));
            GET DIAGNOSTICS renamed=ROW_COUNT;
            total_renamed:=total_renamed+renamed;
        END LOOP;
    END LOOP;
    RAISE NOTICE 'Normalized % known fixture court names; all other fields and admin names preserved',total_renamed;
END $fixture_names$;
-- Independent branch migration: preserve every court and all booking/payment history.
-- Reuse seven old branch IDs for the first court in each; add exactly 28 branches.
DO $independent_branches$
DECLARE item record;
BEGIN
    LOCK TABLE branches, courts IN SHARE ROW EXCLUSIVE MODE;
    IF (SELECT count(*) FROM courts)=210 AND (SELECT count(*) FROM branches)=35
        AND NOT EXISTS(SELECT branch_id FROM courts GROUP BY branch_id HAVING count(*)<>6) THEN RETURN; END IF;
    IF (SELECT count(*) FROM courts)<>35 OR EXISTS(SELECT 1 FROM courts WHERE branch_id IS NULL) THEN
        RAISE EXCEPTION 'BLOCKED: independent branches require the verified 35-court catalog';
    END IF;
    CREATE TEMP TABLE independent_branch_plan(court_id uuid PRIMARY KEY,branch_id uuid UNIQUE) ON COMMIT DROP;
    INSERT INTO independent_branch_plan
    SELECT id,CASE WHEN id=first_value(id) OVER(PARTITION BY branch_id ORDER BY court_code,id) THEN branch_id
        ELSE md5('bcm:independent-branch:'||id)::uuid END FROM courts;
    FOR item IN SELECT c.*,p.branch_id target_branch FROM courts c JOIN independent_branch_plan p ON p.court_id=c.id LOOP
        IF EXISTS(SELECT 1 FROM branches WHERE code='COURT-'||item.court_code AND id<>item.target_branch) THEN
            RAISE EXCEPTION 'BLOCKED: independent branch code conflict for court %',item.court_code;
        END IF;
        INSERT INTO branches(id,code,name,district,address,fixture_source)
        VALUES(item.target_branch,'COURT-'||item.court_code,item.name,item.district,item.address,
            'coursework:independent:'||item.id) ON CONFLICT(id) DO NOTHING;
        UPDATE branches SET name=item.name,fixture_source='coursework:independent:'||item.id
        WHERE id=item.target_branch AND fixture_source LIKE 'coursework:%'
            AND fixture_source NOT LIKE 'coursework:independent:%';
    END LOOP;
    UPDATE courts c SET branch_id=p.branch_id,court_number=100000000+c.court_code
    FROM independent_branch_plan p WHERE p.court_id=c.id AND (c.branch_id<>p.branch_id OR c.court_number<>1);
    UPDATE courts SET court_number=1 WHERE court_number<>1;
    IF (SELECT count(*) FROM branches)<>35 OR (SELECT count(DISTINCT branch_id) FROM courts)<>35
        OR EXISTS(SELECT branch_id FROM courts GROUP BY branch_id HAVING count(*)<>1) THEN
        RAISE EXCEPTION 'BLOCKED: expected exactly 35 independent branches with one court each';
    END IF;
END $independent_branches$;
-- Coursework playing surfaces: six independently bookable courts per venue.
-- Existing court IDs are retained as surface 01; prices/addresses are inherited.
DO $venue_surfaces$
DECLARE venue record; n integer; surface_id uuid; surface_number integer;
BEGIN
    LOCK TABLE branches, courts IN SHARE ROW EXCLUSIVE MODE;
    IF (SELECT count(*) FROM branches)<>35 OR (SELECT count(*) FROM courts) NOT IN (35,210) THEN
        RAISE EXCEPTION 'BLOCKED: expected the verified 35-venue catalog';
    END IF;
    FOR venue IN SELECT c.* FROM courts c WHERE court_number=1 ORDER BY court_code LOOP
        FOR n IN 2..6 LOOP
            surface_id:=md5('bcm:venue-surface:'||venue.id||':'||n)::uuid;
            surface_number:=1000000+venue.court_code*10+n;
            IF EXISTS(SELECT 1 FROM courts WHERE (id=surface_id OR court_code=surface_number OR
                (branch_id=venue.branch_id AND court_number=n)) AND
                (id<>surface_id OR court_code<>surface_number OR branch_id<>venue.branch_id OR court_number<>n)) THEN
                RAISE EXCEPTION 'BLOCKED: playing surface identity conflict';
            END IF;
            INSERT INTO courts(id,court_code,name,type,status,base_price,district,address,branch_id,court_number)
            VALUES(surface_id,surface_number,venue.name||' · Sân '||lpad(n::text,2,'0'),venue.type,'AVAILABLE',
                venue.base_price,venue.district,venue.address,venue.branch_id,n) ON CONFLICT(id) DO NOTHING;
        END LOOP;
    END LOOP;
    IF (SELECT count(*) FROM courts)<>210 OR EXISTS(SELECT branch_id FROM courts GROUP BY branch_id HAVING count(*)<>6) THEN
        RAISE EXCEPTION 'BLOCKED: expected 35 venues with six playing surfaces each';
    END IF;
END $venue_surfaces$;
DO $local_number_range$
BEGIN
    IF EXISTS (SELECT branch_id FROM courts GROUP BY branch_id
               HAVING array_agg(court_number ORDER BY court_number)<>ARRAY[1,2,3,4,5,6]) THEN
        RAISE EXCEPTION 'BLOCKED: each branch must have persisted court numbers 1..6';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid='courts'::regclass AND conname='chk_court_number_range') THEN
        ALTER TABLE courts ADD CONSTRAINT chk_court_number_range CHECK (court_number BETWEEN 1 AND 6);
    END IF;
END $local_number_range$;
COMMIT;
-- END SECTION: branches
