-- Product quantity promotions. Run after migration.sql and member2-upgrade.sql.
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
