-- Coursework BCM only. Run after the five canonical schema/catalog scripts.
-- One atomic dataset; a stable admin ID is the completion marker. Reruns never
-- restore deleted fixtures, reset passwords/prices, change dates or replay stock.
BEGIN;
SET LOCAL timezone = 'Asia/Ho_Chi_Minh';
SELECT pg_advisory_xact_lock(734281905);

CREATE OR REPLACE FUNCTION pg_temp.seed_id(key text) RETURNS uuid LANGUAGE sql IMMUTABLE AS
$$ SELECT uuid_generate_v5('bcd00000-0000-4000-8000-000000000001'::uuid, key) $$;

DO $dataset$
DECLARE
    origin date := (date_trunc('month',current_date-1) - interval '11 months')::date;
    uid uuid; cid uuid; sid uuid; pid uuid; cat uuid; bid uuid; iid uuid; oid uuid;
    court uuid; slot uuid; actor uuid; actor_user uuid;
    goods uuid[] := '{}'; services uuid[] := '{}'; customers_seed uuid[] := '{}';
    staff_ids uuid[] := '{}'; staff_users uuid[] := '{}';
    i integer; j integer; q integer; n integer;
    email_value text; role_value text; name_value text; phone_value text;
    at_time timestamp; played date; fee numeric; product_total numeric; price_value numeric;
    service_price numeric; import_total numeric; unit_cost numeric;
    row_data record;
    seed_hash text := '$2a$10$Jpk74ejhUJir0XXzqluaEOWcHIbzSS0rJgVehXBoLEbIx9wLM27C2';
BEGIN
    IF EXISTS (SELECT 1 FROM users WHERE id=pg_temp.seed_id('user-1')) THEN
        RAISE NOTICE 'BCM BCM v1 already installed; all existing data preserved';
        RETURN;
    END IF;

    -- Four staff and nine named login customers. Walk-in buyers have no login.
    -- ADMIN also has a Staff profile, as permitted by the current actor mapping.
    FOR i IN 1..14 LOOP
        uid := pg_temp.seed_id('user-'||i);
        role_value := CASE WHEN i=1 THEN 'ADMIN' WHEN i<=5 THEN 'STAFF' ELSE 'CUSTOMER' END;
        email_value := CASE WHEN i=1 THEN 'admin@bcm.test'
            WHEN i<=5 THEN 'staff'||(i-1)||'@bcm.test'
            WHEN i=15 THEN 'guest@bcm.com' ELSE 'customer'||(i-5)||'@bcm.test' END;
        name_value := CASE WHEN i=1 THEN 'Nguyễn Văn An'
            WHEN i=2 THEN 'Trần Thị Mai'
            WHEN i=3 THEN 'Lê Hoàng Nam'
            WHEN i=4 THEN 'Phạm Đức Huy'
            WHEN i=5 THEN 'Hoàng Minh Tuấn'
            WHEN i=15 THEN 'Khách vãng lai'
            WHEN i=6 THEN 'Nguyễn Thị Bích'
            WHEN i=7 THEN 'Trần Văn Cường'
            WHEN i=8 THEN 'Lê Thị Dung'
            WHEN i=9 THEN 'Phạm Văn Dũng'
            WHEN i=10 THEN 'Hoàng Thị Giang'
            WHEN i=11 THEN 'Vũ Văn Hải'
            WHEN i=12 THEN 'Đỗ Thị Hạnh'
            WHEN i=13 THEN 'Ngô Văn Hùng'
            WHEN i=14 THEN 'Bùi Thị Hương'
            ELSE 'Khách hàng '||(i-5) END;
        phone_value := CASE WHEN i=15 THEN '0000000000' ELSE '090000'||lpad(i::text,4,'0') END;
            INSERT INTO users(id,email,password_hash,full_name,phone,address,created_at,updated_at)
            VALUES(uid,email_value,seed_hash,name_value,phone_value,'TP. Hồ Chí Minh',origin,origin);
            IF i<=5 THEN
                sid := pg_temp.seed_id('staff-'||i);
                INSERT INTO staffs(id,user_id,full_name,phone,position,created_at,updated_at)
                VALUES(sid,uid,name_value,phone_value,CASE WHEN i=1 THEN 'Quản trị' ELSE 'Thu ngân' END,origin,origin);
                staff_ids := array_append(staff_ids,sid);
                staff_users := array_append(staff_users,uid);
            ELSE
                cid := pg_temp.seed_id('customer-'||i);
                INSERT INTO customers(id,user_id,full_name,phone,address,created_at,updated_at)
                VALUES(cid,uid,name_value,phone_value,'TP. Hồ Chí Minh',origin,origin);
            END IF;
        INSERT INTO user_roles(user_id,role_id)
        SELECT uid,id FROM roles WHERE role_name=role_value ON CONFLICT DO NOTHING;
        IF i>5 THEN customers_seed := array_append(customers_seed,cid); END IF;
    END LOOP;
    IF (SELECT count(*) FROM roles WHERE role_name IN ('ADMIN','STAFF','CUSTOMER') AND deleted_at IS NULL)<>3 THEN
        RAISE EXCEPTION 'Canonical roles are missing/deleted';
    END IF;

    -- Reuse canonical catalog identities, including the three older catalog seeds.
    FOR row_data IN SELECT * FROM (VALUES
        (1,'Nước giải khát','Nước suối 500ml','GOODS','chai',10000),
        (2,'Nước giải khát','Nước điện giải 500ml','GOODS','chai',15000),
        (3,'Nước giải khát','Nước thể thao 500ml','GOODS','chai',18000),
        (4,'Phụ kiện','Cầu lông tiêu chuẩn','GOODS','quả',25000),
        (5,'Phụ kiện','Ống cầu lông 12 quả','GOODS','ống',250000),
        (6,'Phụ kiện','Quấn cán vợt','GOODS','cái',20000),
        (7,'Phụ kiện','Quấn cán vợt cao cấp','GOODS','cái',35000),
        (8,'Phụ kiện','Băng cổ tay thể thao','GOODS','đôi',45000),
        (9,'Phụ kiện','Khăn thể thao','GOODS','cái',50000),
        (10,'Thuê vợt','Thuê vợt theo lượt','SERVICE','lượt',20000),
        (11,'Thuê vợt','Căng cước vợt','SERVICE','lần',120000)
    ) AS v(seq,category,name,type,unit,price) LOOP
        SELECT id INTO STRICT cat FROM categories WHERE category_name=row_data.category AND deleted_at IS NULL;
        SELECT count(*) INTO n FROM products WHERE lower(btrim(name))=lower(row_data.name) AND category_id=cat;
        IF n>1 THEN RAISE EXCEPTION 'Ambiguous BCM product: %',row_data.name; END IF;
        IF n=1 THEN
            SELECT id INTO STRICT pid FROM products WHERE lower(btrim(name))=lower(row_data.name)
                AND category_id=cat AND type=row_data.type AND deleted_at IS NULL;
        ELSE
            pid := pg_temp.seed_id('product-'||row_data.seq);
            INSERT INTO products(id,category_id,name,type,unit,price,stock_quantity,created_by,updated_by,created_at,updated_at)
            VALUES(pid,cat,row_data.name,row_data.type,row_data.unit,row_data.price,0,staff_ids[1],staff_ids[1],origin,origin);
        END IF;
        IF row_data.type='GOODS' THEN goods:=array_append(goods,pid); ELSE services:=array_append(services,pid); END IF;
    END LOOP;

    FOR i IN 1..3 LOOP
        INSERT INTO suppliers(id,name,phone,address,created_at,updated_at)
        VALUES(pg_temp.seed_id('supplier-'||i),
            CASE i WHEN 1 THEN 'Nhà phân phối nước uống An Bình'
                   WHEN 2 THEN 'Dụng cụ cầu lông Minh Phát' ELSE 'Phụ kiện thể thao Việt' END,
            '0280000000'||i,'Địa chỉ mẫu - TP. Hồ Chí Minh',origin,origin);
    END LOOP;

    -- Twelve RECEIVED imports. Stock starts at zero for newly inserted products;
    -- the first receipt provides enough opening inventory for chronological sales.
    FOR i IN 1..12 LOOP
        oid:=pg_temp.seed_id('import-'||i);
        at_time:=origin + ((i-1)||' months')::interval + interval '7 hours';
        actor:=staff_ids[2+(i%4)]; actor_user:=staff_users[2+(i%4)];
        INSERT INTO import_orders(id,supplier_id,status,total_amount,import_date,created_by,updated_by,created_by_user,updated_by_user,created_at,updated_at)
        VALUES(oid,pg_temp.seed_id('supplier-'||(1+(i%3))),'RECEIVED',0,at_time::date,actor,actor,actor_user,actor_user,at_time,at_time);
        import_total:=0;
        FOR j IN 1..array_length(goods,1) LOOP
            pid:=goods[j];
            q:=CASE WHEN i=1 THEN 30+(j*7)%21 ELSE 5+(i*j)%5 END;
            SELECT round(price*0.65,2) INTO unit_cost FROM products WHERE id=pid;
            INSERT INTO import_order_details(id,import_order_id,product_id,quantity,import_price,created_at)
            VALUES(pg_temp.seed_id('import-line-'||i||'-'||j),oid,pid,q,unit_cost,at_time);
            UPDATE products SET stock_quantity=stock_quantity+q,version=version+1 WHERE id=pid;
            import_total:=import_total+q*unit_cost;
        END LOOP;
        UPDATE import_orders SET total_amount=import_total WHERE id=oid;
    END LOOP;

    -- 100 completed court bookings and 150 paid invoices (100 checkout + 50 POS).
    -- Historical cash receipts are explicitly simulated MANUAL receipts; no fake
    -- gateway SUCCESS is presented as a real VNPay verification.
    SELECT id INTO STRICT slot FROM time_slots WHERE start_time='10:00' AND end_time='11:00' AND deleted_at IS NULL;
    FOR i IN 1..150 LOOP
        played:=origin+floor((i-1)*(current_date-origin-1)::numeric/149)::integer;
        at_time:=played+time '11:15';
        cid:=customers_seed[1+((i-1)%array_length(customers_seed,1))];
        actor:=staff_ids[2+(i%4)]; actor_user:=staff_users[2+(i%4)];
        bid:=NULL; fee:=0;
        IF i%3<>0 THEN
            -- Stable ordering across fresh machines, independent of random UUIDs.
            SELECT id INTO STRICT court FROM courts WHERE status='AVAILABLE' AND deleted_at IS NULL
                ORDER BY court_code LIMIT 1 OFFSET ((i-1)%8);
            SELECT round(c.base_price*t.price_multiplier,2) INTO fee FROM courts c CROSS JOIN time_slots t
                WHERE c.id=court AND t.id=slot;
            bid:=pg_temp.seed_id('booking-'||i);
            INSERT INTO bookings(id,customer_id,status,court_fee,expires_at,created_by,updated_by,created_at,updated_at,contact_full_name,contact_phone,checkout_release_at)
            SELECT bid,cid,'COMPLETED',fee,played+time '09:15',actor,actor,played+time '09:00',at_time,c.full_name,c.phone,played+time '11:00'
            FROM customers c WHERE c.id=cid;
            INSERT INTO booking_details(id,booking_id,court_id,time_slot_id,booking_date,price,created_at)
            VALUES(pg_temp.seed_id('slot-'||i),bid,court,slot,played,fee,played+time '09:00');
            INSERT INTO payment_transactions(id,booking_id,payment_method,transaction_id,status,amount,transaction_date,created_at,note,source,confirmed_by,confirmed_by_user,reference)
            VALUES(pg_temp.seed_id('court-receipt-'||i),bid,'CASH','BCM-V1-COURT-'||i,'SUCCESS',fee,played+time '09:05',played+time '09:05',
                'Phiếu thu giả lập đồ án; không phải giao dịch gateway','MANUAL',actor,actor_user,'BCM-V1-COURT-'||i);
        END IF;
        iid:=pg_temp.seed_id('invoice-'||i);
        pid:=goods[1+((i-1)%array_length(goods,1))]; q:=1+(i%3);
        SELECT price INTO price_value FROM products WHERE id=pid;
        product_total:=q*price_value;
        IF i%3=0 THEN
            SELECT price INTO service_price FROM products WHERE id=services[1+(i%2)];
            product_total:=product_total+service_price;
        END IF;
        INSERT INTO invoices(id,booking_id,customer_id,payment_method,status,court_fee,product_fee,discount_amount,total_amount,created_by,updated_by,created_by_user,created_at,updated_at)
        VALUES(iid,bid,cid,'CASH','PAID',fee,product_total,0,fee+product_total,actor,actor,actor_user,at_time,at_time);
        INSERT INTO invoice_details(id,invoice_id,product_id,product_name,quantity,unit_price,note,added_by,added_by_user,stock_deducted,created_at,product_type)
        SELECT pg_temp.seed_id('goods-line-'||i),iid,pid,name,q,price_value,'Hóa đơn bán lẻ',actor,actor_user,true,at_time,'GOODS' FROM products WHERE id=pid;
        UPDATE products SET stock_quantity=stock_quantity-q,version=version+1 WHERE id=pid;
        IF i%3=0 THEN
            INSERT INTO invoice_details(id,invoice_id,product_id,product_name,quantity,unit_price,note,added_by,added_by_user,stock_deducted,created_at,product_type)
            SELECT pg_temp.seed_id('service-line-'||i),iid,id,name,1,service_price,'Dịch vụ',actor,actor_user,false,at_time,'SERVICE'
            FROM products WHERE id=services[1+(i%2)];
        END IF;
        INSERT INTO payment_transactions(id,invoice_id,payment_method,transaction_id,status,amount,transaction_date,created_at,note,source,confirmed_by,confirmed_by_user,reference)
        VALUES(pg_temp.seed_id('sale-receipt-'||i),iid,'CASH','BCM-V1-SALE-'||i,'SUCCESS',product_total,at_time,at_time,
            'Phiếu thu giả lập đồ án; không phải giao dịch gateway','MANUAL',actor,actor_user,'BCM-V1-SALE-'||i);
    END LOOP;

    FOR i IN 1..3 LOOP
        pid:=pg_temp.seed_id('promotion-'||i);
        INSERT INTO promotions(id,code,name,valid_from,valid_to,is_active,created_by)
        VALUES(pid,'KM-OFF-'||i,CASE i WHEN 1 THEN 'Giảm nước uống theo số lượng'
            WHEN 2 THEN 'Ưu đãi hóa đơn tiền sân' ELSE 'Voucher khách hàng thân thiết' END,
            current_date,current_date+365,false,staff_ids[1]);
        INSERT INTO discount_rules(id,promotion_id,rule_type,target_product_id,min_invoice_amount,voucher_code,max_usage_per_customer,discount_type,discount_value,min_quantity)
        VALUES(pg_temp.seed_id('rule-'||i),pid,CASE i WHEN 1 THEN 'PRODUCT' WHEN 2 THEN 'INVOICE_TOTAL' ELSE 'VOUCHER' END,
            CASE WHEN i=1 THEN goods[1] END,CASE WHEN i>1 THEN 300000 END,
            CASE WHEN i=3 THEN 'KM-OFF-3' END,CASE WHEN i=3 THEN 1 END,
            CASE WHEN i=1 THEN 'PERCENT' ELSE 'AMOUNT' END,CASE WHEN i=1 THEN 10 ELSE 30000 END,CASE WHEN i=1 THEN 3 ELSE 1 END);
    END LOOP;
    RAISE NOTICE 'BCM BCM v1 installed: 14 login accounts, 11 catalog products, 3 suppliers, 12 imports, 100 bookings, 150 invoices, 3 disabled promotions';
END $dataset$;

-- Additive v1.1 work queue: also executes for databases that already installed v1.
-- Stable IDs preserve approvals/cancellations/edits on every later rerun.
DO $inventory$
DECLARE
    item record; order_id uuid; product_id uuid; actor uuid; actor_user uuid;
    stamp timestamp := localtimestamp;
BEGIN
    actor:=pg_temp.seed_id('staff-2');
    actor_user:=pg_temp.seed_id('user-2');
    FOR item IN SELECT * FROM (VALUES
        (1,'Nước suối 500ml',60,6500),
        (2,'Nước điện giải 500ml',24,9750),
        (3,'Quấn cán vợt',20,13000)
    ) AS v(seq,name,quantity,unit_cost) LOOP
        order_id:=pg_temp.seed_id('draft-import-'||item.seq);
        IF EXISTS(SELECT 1 FROM import_orders WHERE id=order_id) THEN CONTINUE; END IF;
        SELECT id INTO STRICT product_id FROM products
            WHERE name=item.name AND type='GOODS' AND deleted_at IS NULL;
        INSERT INTO import_orders(id,supplier_id,status,total_amount,import_date,
            created_by,updated_by,created_by_user,updated_by_user,created_at,updated_at)
        VALUES(order_id,pg_temp.seed_id('supplier-'||item.seq),'DRAFT',item.quantity*item.unit_cost,
            current_date,actor,actor,actor_user,actor_user,stamp,stamp);
        INSERT INTO import_order_details(id,import_order_id,product_id,quantity,import_price,created_at)
        VALUES(pg_temp.seed_id('draft-import-line-'||item.seq),order_id,product_id,item.quantity,item.unit_cost,stamp);
    END LOOP;
    RAISE NOTICE 'BCM inventory work queue ready: existing approvals, dates and stock preserved';
END $inventory$;

-- v1.2 live checkout examples, one set per explicit seed run date. Never replay
-- finished/expired examples or shift their dates. Normal application jobs apply.
DO $checkout$
DECLARE
    stamp timestamp := localtimestamp;
    play_date date := current_date;
    slot_id uuid; court_id uuid; booking_id uuid; customer_id uuid; invoice_id uuid;
    actor uuid := pg_temp.seed_id('staff-2');
    actor_user uuid := pg_temp.seed_id('user-2');
    water_id uuid; water_price numeric; court_price numeric; multiplier numeric;
    i integer;
BEGIN
    SELECT id,price_multiplier INTO slot_id,multiplier FROM time_slots
    WHERE deleted_at IS NULL AND start_time<=localtime ORDER BY start_time DESC LIMIT 1;
    IF slot_id IS NULL THEN
        play_date:=current_date-1;
        SELECT id,price_multiplier INTO STRICT slot_id,multiplier FROM time_slots
        WHERE deleted_at IS NULL ORDER BY end_time DESC LIMIT 1;
    END IF;
    FOR i IN 1..3 LOOP
        booking_id:=pg_temp.seed_id('live-checkout-'||current_date||'-'||i);
        IF EXISTS(SELECT 1 FROM bookings WHERE id=booking_id) THEN CONTINUE; END IF;
        -- Serialize with booking creation on the actual Court row; the unique
        -- slot index is still the final protection. Never overwrite occupied slots.
        SELECT c.id,round(c.base_price*multiplier,2) INTO STRICT court_id,court_price
        FROM courts c WHERE c.status='AVAILABLE' AND c.deleted_at IS NULL AND c.base_price>0
        AND NOT EXISTS(SELECT 1 FROM booking_details d
            WHERE d.court_id=c.id AND d.time_slot_id=slot_id AND d.booking_date=play_date)
        ORDER BY c.court_code LIMIT 1 FOR UPDATE OF c;
        customer_id:=pg_temp.seed_id('customer-'||(6+i));
        INSERT INTO bookings(id,customer_id,status,court_fee,expires_at,created_by,updated_by,
            created_at,updated_at,contact_full_name,contact_phone)
        SELECT booking_id,customer_id,CASE WHEN i=3 THEN 'PENDING' ELSE 'PAID' END,
            court_price,stamp+interval '10 minutes',actor,actor,stamp,stamp,
            c.full_name,c.phone FROM customers c WHERE c.id=customer_id;
        INSERT INTO booking_details(id,booking_id,court_id,time_slot_id,booking_date,price,created_at)
        VALUES(pg_temp.seed_id('live-slot-'||current_date||'-'||i),booking_id,court_id,slot_id,play_date,court_price,stamp);
        IF i<>3 THEN
            INSERT INTO payment_transactions(id,booking_id,payment_method,transaction_id,status,amount,
                transaction_date,created_at,note,source,confirmed_by,confirmed_by_user,reference)
            VALUES(pg_temp.seed_id('live-receipt-'||current_date||'-'||i),booking_id,'CASH',
                'BCM-LIVE-'||current_date||'-'||i,'SUCCESS',court_price,stamp,stamp,
                'Trả sân - đã thu tiền sân giả lập','MANUAL',actor,actor_user,'BCM-LIVE-'||current_date||'-'||i);
        END IF;
        IF i=2 THEN
            SELECT id,price INTO STRICT water_id,water_price FROM products
            WHERE name='Nước suối 500ml' AND type='GOODS' AND deleted_at IS NULL FOR UPDATE;
            UPDATE products SET stock_quantity=stock_quantity-2,version=version+1 WHERE id=water_id AND stock_quantity>=2;
            IF NOT FOUND THEN RAISE EXCEPTION 'BCM checkout requires two bottles in stock'; END IF;
            invoice_id:=pg_temp.seed_id('live-pos-'||current_date);
            INSERT INTO invoices(id,booking_id,customer_id,status,payment_method,court_fee,product_fee,
                total_amount,created_by,updated_by,created_by_user,created_at,updated_at)
            VALUES(invoice_id,booking_id,customer_id,'DRAFT',NULL,0,2*water_price,2*water_price,
                actor,actor,actor_user,stamp,stamp);
            INSERT INTO invoice_details(id,invoice_id,product_id,product_name,quantity,unit_price,note,
                added_by,added_by_user,stock_deducted,created_at,product_type)
            VALUES(pg_temp.seed_id('live-pos-line-'||current_date),invoice_id,water_id,'Nước suối 500ml',
                2,water_price,'Trả sân - tiền nước chờ thu',actor,actor_user,true,stamp,'GOODS');
        END IF;
    END LOOP;
    RAISE NOTICE 'Checkout BCM ready; paid receipts, pending POS and ten-minute unpaid hold follow normal lifecycle';
END $checkout$;

-- Explicit coursework simulation, never evidence of an external gateway payment.
DO $vnpay_dataset$
DECLARE i int; inv uuid; cust uuid; prod uuid; staff uuid; stamp timestamp; price numeric;
BEGIN
 SELECT id INTO STRICT cust FROM customers WHERE id=pg_temp.seed_id('customer-6');
 SELECT id,products.price INTO STRICT prod,price FROM products WHERE name='Thuê vợt theo lượt' AND deleted_at IS NULL;
 staff:=pg_temp.seed_id('staff-2');
 FOR i IN 1..12 LOOP
  inv:=pg_temp.seed_id('vnpay-invoice-'||i);
  IF EXISTS(SELECT 1 FROM invoices WHERE id=inv) THEN CONTINUE; END IF;
  stamp:=date_trunc('month',current_date)-((12-i)||' months')::interval+interval '10 hours';
  INSERT INTO invoices(id,customer_id,status,payment_method,court_fee,product_fee,discount_amount,total_amount,created_by,created_at,updated_at)
  VALUES(inv,cust,'PAID','VNPAY',0,price,0,price,staff,stamp,stamp);
  INSERT INTO invoice_details(id,invoice_id,product_id,product_name,quantity,unit_price,discount_amount,note,stock_deducted,created_at,product_type)
  VALUES(pg_temp.seed_id('vnpay-line-'||i),inv,prod,'Thuê vợt theo lượt',1,price,0,'Thuê vợt theo lượt',false,stamp,'SERVICE');
  INSERT INTO payment_transactions(id,invoice_id,payment_method,transaction_id,status,amount,transaction_date,created_at,source,note,reference)
  VALUES(pg_temp.seed_id('vnpay-receipt-'||i),inv,'VNPAY','BCM-VNPAY-'||i,'SUCCESS',price,stamp,stamp,'GATEWAY','Mô phỏng VNPay; không phải giao dịch thật','BCM-VNPAY-'||i);
 END LOOP;
END $vnpay_dataset$;
-- Product promotions seed: safe rerun, never creates duplicates or changes existing rules
DO $product_promotions$
BEGIN
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
END $product_promotions$;
COMMIT;
