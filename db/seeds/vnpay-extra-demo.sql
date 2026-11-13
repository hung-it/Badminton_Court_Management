-- Opt-in demo only. Three simulated VNPay invoices on the first execution date.
-- No gateway call, stock movement, booking or rewrite of existing history.
BEGIN;
SET LOCAL timezone='Asia/Ho_Chi_Minh';
SELECT pg_advisory_xact_lock(734281906);
DO $seed$
DECLARE
 ns uuid:='bcd00000-0000-4000-8000-000000000001';
 i integer; inv uuid; line uuid; receipt uuid; customer uuid; staff uuid; actor uuid;
 product uuid; product_name text; price numeric; phone text; stamp timestamp;
 inv_count integer; line_count integer; receipt_count integer;
BEGIN
 SELECT count(*) INTO inv_count FROM invoices WHERE id IN
  (SELECT uuid_generate_v5(ns,'vnpay-extra-invoice-'||n) FROM generate_series(1,3) n);
 SELECT count(*) INTO line_count FROM invoice_details WHERE id IN
  (SELECT uuid_generate_v5(ns,'vnpay-extra-line-'||n) FROM generate_series(1,3) n);
 SELECT count(*) INTO receipt_count FROM payment_transactions WHERE id IN
  (SELECT uuid_generate_v5(ns,'vnpay-extra-receipt-'||n) FROM generate_series(1,3) n);
 IF inv_count=3 AND line_count=3 AND receipt_count=3 THEN
  FOR i IN 1..3 LOOP
   IF NOT EXISTS(SELECT 1 FROM invoices v JOIN invoice_details d ON d.invoice_id=v.id JOIN payment_transactions p ON p.invoice_id=v.id
    WHERE v.id=uuid_generate_v5(ns,'vnpay-extra-invoice-'||i) AND d.id=uuid_generate_v5(ns,'vnpay-extra-line-'||i)
     AND p.id=uuid_generate_v5(ns,'vnpay-extra-receipt-'||i) AND v.status='PAID' AND v.payment_method='VNPAY'
     AND d.product_type='SERVICE' AND d.stock_deducted=FALSE AND d.quantity*d.unit_price=v.total_amount
     AND p.amount=v.total_amount AND p.status='SUCCESS' AND p.source='GATEWAY'
     AND p.reference='BCM-DEMO-VNPAY-EXTRA-'||i) THEN
    RAISE EXCEPTION 'Existing VNPay demo changed; preserve and review, do not overwrite';
   END IF;
  END LOOP;
  RAISE NOTICE 'Three VNPay demo invoices already exist; no data changed';RETURN;
 ELSIF inv_count<>0 OR line_count<>0 OR receipt_count<>0 THEN
  RAISE EXCEPTION 'Partial VNPay demo dataset exists; preserve and review';
 END IF;
 SELECT c.id,c.phone INTO STRICT customer,phone FROM customers c JOIN users u ON u.id=c.user_id
  WHERE c.id=uuid_generate_v5(ns,'customer-6') AND c.deleted_at IS NULL AND u.deleted_at IS NULL;
 SELECT s.id,s.user_id INTO STRICT staff,actor FROM staffs s JOIN users u ON u.id=s.user_id
  WHERE s.id=uuid_generate_v5(ns,'staff-2') AND s.deleted_at IS NULL AND u.deleted_at IS NULL;
 SELECT p.id,p.name,p.price INTO STRICT product,product_name,price FROM products p
  WHERE p.name='Thuê vợt theo lượt' AND p.type='SERVICE' AND p.deleted_at IS NULL AND p.price>0;
 FOR i IN 1..3 LOOP
  inv:=uuid_generate_v5(ns,'vnpay-extra-invoice-'||i);
  line:=uuid_generate_v5(ns,'vnpay-extra-line-'||i);
  receipt:=uuid_generate_v5(ns,'vnpay-extra-receipt-'||i);
  stamp:=least(localtimestamp,current_date+interval '10 hours'+i*interval '1 minute');
  INSERT INTO invoices(id,customer_id,buyer_phone,payment_method,status,court_fee,product_fee,discount_amount,total_amount,
   created_by,updated_by,created_by_user,created_at,updated_at)
  VALUES(inv,customer,phone,'VNPAY','PAID',0,price,0,price,staff,staff,actor,stamp,stamp);
  INSERT INTO invoice_details(id,invoice_id,product_id,product_name,product_type,quantity,unit_price,discount_amount,
   added_by,added_by_user,stock_deducted,note,created_at)
  VALUES(line,inv,product,product_name,'SERVICE',1,price,0,staff,actor,FALSE,'DEMO VNPay: simulated payment, not a real gateway transaction',stamp);
  INSERT INTO payment_transactions(id,invoice_id,payment_method,transaction_id,status,amount,transaction_date,created_at,source,note,reference)
  VALUES(receipt,inv,'VNPAY','BCM-DEMO-VNPAY-EXTRA-'||i,'SUCCESS',price,stamp,stamp,'GATEWAY',
   'DEMO ONLY: simulated VNPay success; no external payment','BCM-DEMO-VNPAY-EXTRA-'||i);
 END LOOP;
 RAISE NOTICE 'Created 3 VNPay demo invoices, each % VND; no real payment or stock change',price;
END $seed$;
COMMIT;
