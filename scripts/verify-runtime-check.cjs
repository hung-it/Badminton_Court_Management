// Session-local PostgreSQL fixtures only: never create a database or modify public tables.
const {spawnSync}=require('node:child_process');
const {readFileSync}=require('node:fs');
const path=require('node:path');
const assert=require('node:assert/strict');
const check=readFileSync(path.join(__dirname,'../db/checks/verify-runtime.sql'),'utf8');
const tables=['invoices','invoice_details','bookings','booking_details','import_orders','import_order_details','products','payment_transactions','courts'];
const fixture=tables.map(t=>`CREATE TEMP TABLE ${t} AS SELECT * FROM public.${t} WHERE FALSE;`).join('\n')+`
 INSERT INTO pg_temp.bookings(id,status,court_fee,final_amount) VALUES ('00000000-0000-4000-8000-000000000001','COMPLETED',100,100);
 INSERT INTO pg_temp.booking_details(booking_id,price) VALUES ('00000000-0000-4000-8000-000000000001',100);
 INSERT INTO pg_temp.invoices(id,booking_id,parent_invoice_id,status,court_fee,product_fee,discount_amount,total_amount,payment_method) VALUES
 ('00000000-0000-4000-8000-000000000002','00000000-0000-4000-8000-000000000001',NULL,'PAID',100,20,0,120,NULL),
 ('00000000-0000-4000-8000-000000000003','00000000-0000-4000-8000-000000000001','00000000-0000-4000-8000-000000000002','CANCELLED',0,20,0,20,NULL);
 INSERT INTO pg_temp.invoice_details(invoice_id,quantity,unit_price) VALUES ('00000000-0000-4000-8000-000000000002',1,20);
 INSERT INTO pg_temp.payment_transactions(booking_id,invoice_id,payment_method,status,source,confirmed_by_user,amount) VALUES
 ('00000000-0000-4000-8000-000000000001',NULL,'VNPAY','SUCCESS','GATEWAY',NULL,100),
 (NULL,'00000000-0000-4000-8000-000000000002','CASH','SUCCESS','MANUAL','00000000-0000-4000-8000-000000000004',20);
`;
const cases=[
 ['merged draft with lines transferred to paid parent','',null],
 ['standalone cancelled draft retains its lines',`INSERT INTO pg_temp.invoices(id,status,court_fee,product_fee,discount_amount,total_amount) VALUES ('00000000-0000-4000-8000-000000000005','CANCELLED',0,20,0,20); INSERT INTO pg_temp.invoice_details(invoice_id,quantity,unit_price) VALUES ('00000000-0000-4000-8000-000000000005',1,20);`,null],
 ['unmerged draft missing its lines',`UPDATE pg_temp.invoices SET parent_invoice_id=NULL,status='DRAFT' WHERE id='00000000-0000-4000-8000-000000000003';`,'Invoice snapshots'],
 ['paid parent receipt mismatch',`UPDATE pg_temp.payment_transactions SET amount=19 WHERE source='MANUAL';`,'Invoice snapshots'],
 ['merged draft has no valid parent',`UPDATE pg_temp.invoices SET parent_invoice_id='00000000-0000-4000-8000-000000000099' WHERE status='CANCELLED';`,'Invalid merged draft'],
 ['merged child is not cancelled',`UPDATE pg_temp.invoices SET status='DRAFT' WHERE status='CANCELLED';`,'Invalid merged draft'],
 ['merged child retains product lines',`INSERT INTO pg_temp.invoice_details(invoice_id,quantity,unit_price) VALUES ('00000000-0000-4000-8000-000000000003',1,20);`,'Invalid merged draft'],
 ['merged child header total mismatch',`UPDATE pg_temp.invoices SET total_amount=21 WHERE status='CANCELLED';`,'Invoice snapshots']
];
for(const [name,change,error] of cases){
 const result=spawnSync('docker',['exec','-i','bcm-postgres','psql','-X','-U','bcm_admin','-d','badminton_court_db','-v','ON_ERROR_STOP=1','-q'],{input:fixture+change+'\n'+check,encoding:'utf8'});
 if(result.error)throw result.error;
 if(error){assert.notEqual(result.status,0,name);assert.match(result.stderr,new RegExp(error),name);}
 else assert.equal(result.status,0,name+': '+result.stderr);
 console.log('PASS: '+name);
}
console.log('All 8 runtime-check scenarios passed; fixtures were temporary session tables.');
