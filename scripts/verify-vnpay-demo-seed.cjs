// All writes are to session TEMP tables; the runtime database is only read.
const {spawnSync}=require('node:child_process'),{readFileSync}=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const seed=readFileSync(path.join(__dirname,'../db/seeds/vnpay-extra-demo.sql'),'utf8');
const runtime=readFileSync(path.join(__dirname,'../db/checks/verify-runtime.sql'),'utf8');
const ids=kind=>`(SELECT uuid_generate_v5('bcd00000-0000-4000-8000-000000000001'::uuid,'vnpay-extra-${kind}-'||n) FROM generate_series(1,3) n)`;
const setup=['invoices','invoice_details','payment_transactions','products'].map(t=>`CREATE TEMP TABLE ${t} AS SELECT * FROM public.${t};`).join('\n')+`
 DELETE FROM pg_temp.payment_transactions WHERE id IN ${ids('receipt')};
 DELETE FROM pg_temp.invoice_details WHERE id IN ${ids('line')};
 DELETE FROM pg_temp.invoices WHERE id IN ${ids('invoice')};
`;
const fingerprint=`SELECT md5(json_build_array(
 (SELECT json_agg(t ORDER BY id) FROM pg_temp.invoices t),
 (SELECT json_agg(t ORDER BY id) FROM pg_temp.invoice_details t),
 (SELECT json_agg(t ORDER BY id) FROM pg_temp.payment_transactions t),
 (SELECT json_agg(t ORDER BY id) FROM pg_temp.products t))::text) AS value`;
const verify=`DO $test$ BEGIN
 IF (SELECT count(*) FROM pg_temp.invoices WHERE id IN ${ids('invoice')})<>3 OR
 (SELECT count(*) FROM pg_temp.invoice_details WHERE id IN ${ids('line')})<>3 OR
 (SELECT count(*) FROM pg_temp.payment_transactions WHERE id IN ${ids('receipt')})<>3 THEN RAISE EXCEPTION 'Expected exactly 3 invoices, lines and receipts'; END IF;
 IF EXISTS(SELECT 1 FROM pg_temp.invoices WHERE id IN ${ids('invoice')} AND
  (created_at::date<>(current_timestamp AT TIME ZONE 'Asia/Ho_Chi_Minh')::date OR created_at>(current_timestamp AT TIME ZONE 'Asia/Ho_Chi_Minh'))) THEN RAISE EXCEPTION 'Wrong demo date'; END IF;
 IF EXISTS(SELECT 1 FROM pg_temp.invoice_details WHERE id IN ${ids('line')} AND (product_type IS DISTINCT FROM 'SERVICE' OR stock_deducted IS DISTINCT FROM FALSE)) THEN RAISE EXCEPTION 'Wrong service snapshot'; END IF;
 END $test$;`;
function run(name,body,error){
 const r=spawnSync('docker',['exec','-i','bcm-postgres','psql','-X','-U','bcm_admin','-d','badminton_court_db','-v','ON_ERROR_STOP=1','-q'],{input:setup+body,encoding:'utf8'});
 if(r.error)throw r.error;
 if(error){assert.notEqual(r.status,0,name);assert.match(r.stderr,error,name);}
 else assert.equal(r.status,0,name+': '+r.stderr);
 console.log('PASS: '+name);
}
run('3 reconciled VNPay invoices; rerun unchanged',seed+verify+`CREATE TEMP TABLE signature AS ${fingerprint};`+seed+`
 DO $test$ BEGIN IF (SELECT value FROM signature) IS DISTINCT FROM (${fingerprint}) THEN RAISE EXCEPTION 'Seed rewrote data on rerun'; END IF; END $test$;`+runtime);
run('partial data is rejected without repair',`INSERT INTO pg_temp.invoices(id) SELECT uuid_generate_v5('bcd00000-0000-4000-8000-000000000001'::uuid,'vnpay-extra-invoice-1');`+seed,/Partial VNPay demo/);
run('modified history is rejected without overwrite',seed+`UPDATE pg_temp.invoice_details SET product_type='GOODS' WHERE id=uuid_generate_v5('bcd00000-0000-4000-8000-000000000001'::uuid,'vnpay-extra-line-1');`+seed,/Existing VNPay demo changed/);
console.log('VNPay seed scenarios passed using temporary session tables only.');
