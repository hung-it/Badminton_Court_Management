BEGIN;
ALTER TABLE discount_rules ADD COLUMN IF NOT EXISTS max_discount_amount NUMERIC(10,2);
ALTER TABLE discount_rules ADD COLUMN IF NOT EXISTS max_total_usage INTEGER;
DO $$ BEGIN
 IF NOT EXISTS(SELECT 1 FROM pg_constraint WHERE conname='court_promotion_limits') THEN
 ALTER TABLE discount_rules ADD CONSTRAINT court_promotion_limits CHECK ((max_discount_amount IS NULL OR max_discount_amount>0) AND (max_total_usage IS NULL OR max_total_usage>0));
 END IF;
END $$;
INSERT INTO promotions(code,name,valid_from,valid_to,is_active)
SELECT 'COURT-BILL-300','Tiền sân từ 300.000đ',DATE '2020-01-01',NULL,true WHERE NOT EXISTS(SELECT 1 FROM promotions WHERE code='COURT-BILL-300');
INSERT INTO promotions(code,name,valid_from,valid_to,is_active)
SELECT 'COURT-BILL-500','Tiền sân từ 500.000đ',DATE '2020-01-01',NULL,true WHERE NOT EXISTS(SELECT 1 FROM promotions WHERE code='COURT-BILL-500');
INSERT INTO discount_rules(promotion_id,rule_type,min_invoice_amount,discount_type,discount_value)
SELECT id,'INVOICE_TOTAL',CASE code WHEN 'COURT-BILL-300' THEN 300000 ELSE 500000 END,'AMOUNT',CASE code WHEN 'COURT-BILL-300' THEN 30000 ELSE 50000 END FROM promotions p WHERE code IN ('COURT-BILL-300','COURT-BILL-500') AND NOT EXISTS(SELECT 1 FROM discount_rules d WHERE d.promotion_id=p.id);
COMMIT;
