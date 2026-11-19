// Main/Sub UI fixtures only: no runtime database writes.
const {chromium}=require(process.env.PLAYWRIGHT_MODULE||'playwright'),assert=require('node:assert/strict');
(async()=>{const b=await chromium.launch({headless:true,channel:'msedge'});try{const p=await b.newPage({viewport:{width:1440,height:1000}}),errors=[],calls=[];p.setDefaultTimeout(10000);p.on('pageerror',e=>{errors.push(e.message);console.error('JS:',e.message)});let failSave=false,failProgramSave=false;
const accounts=[1,2].map(n=>({id:'user-'+n,customerId:'customer-'+n,fullName:'Khách '+n,email:'customer'+n+'@test.local',phone:'0912345678',roles:['CUSTOMER'],createdAt:'2026-11-01',canLock:true,canUnlock:false,deletedAt:null}));
const categories=[{id:'cat',categoryName:'Nước'},{id:'cat2',categoryName:'Dịch vụ'}],products=[{id:'product',name:'Nước suối',categoryId:'cat',type:'GOODS',unit:'chai',price:10000,stockQuantity:10}],courts=[{id:'court',branchId:'branch',name:'Sân 1',courtNumber:1,branchCourtNumber:1,status:'AVAILABLE',type:'STANDARD_MAT',basePrice:100000}],slots=[{id:'slot',startTime:'06:00:00',endTime:'07:00:00',priceMultiplier:1}];
const invoices=[1,2].map(n=>({id:'invoice-'+n,customerName:'Khách '+n,customerPhone:'0912345678',createdAt:'2026-11-01',invoiceStatus:'PAID',paymentMethods:n===1?['VNPAY','CASH']:['CASH'],courtFee:100000,productFee:10000,discountAmount:5000,totalAmount:105000}));
const promotion={id:'promo',name:'Ưu đãi nước',code:'WATER10',productId:'product',productName:'Nước suối',minQuantity:2,discountPercent:10,validFrom:'2026-01-01',validTo:'2030-01-01',active:true};
const rule=(id,kind)=>({id,promotion_id:'program',rule_type:kind,target_product_id:kind==='PRODUCT'?'product':null,product_name:'Nước suối',min_quantity:2,discount_type:'PERCENT',discount_value:10,min_invoice_amount:0,max_discount_amount:null,max_total_usage:10,max_usage_per_customer:1,voucher_code:kind==='VOUCHER'?'MIXED10':null,is_active:true});
let programs=[{id:'program',name:'Cùng tên',description:'Chương trình phối hợp',valid_from:'2026-01-01',valid_to:'2030-01-01',is_active:true,rules:[rule('rp','PRODUCT'),rule('ri','INVOICE_TOTAL'),rule('rv','VOUCHER')]},{id:'program2',name:'Cùng tên',description:'Chương trình độc lập',valid_from:'2026-01-01',valid_to:'2030-01-01',is_active:true,rules:[{...rule('rp2','PRODUCT'),promotion_id:'program2'}]}];
await p.route('https://fonts.googleapis.com/**',r=>r.fulfill({body:'',contentType:'text/css'}));
const serve=r=>{const req=r.request(),u=new URL(req.url()),path=u.pathname;calls.push({path,method:req.method(),body:req.postData()});let data=[];
if(path==='/api/admin/court-promotions/programs'||path==='/api/pos/promotion-programs'){
 if(req.method()==='POST'){if(failProgramSave)return r.fulfill({status:409,json:{success:false,message:'Không lưu được chương trình'}});const v=req.postDataJSON();data='created';programs.push({id:data,name:v.name,description:v.description,valid_from:v.validFrom,valid_to:v.validTo,is_active:v.active,rules:v.rules.filter(x=>!x.removed).map((x,n)=>({id:'new-'+n,promotion_id:data,rule_type:x.ruleType,target_product_id:x.productId,product_name:'Nước suối',min_quantity:x.minQuantity,discount_type:x.discountType,discount_value:x.discountValue,min_invoice_amount:x.minInvoiceAmount,max_discount_amount:x.maxDiscountAmount,max_total_usage:x.maxTotalUsage,max_usage_per_customer:x.maxUsagePerCustomer,voucher_code:x.voucherCode,is_active:x.active}))});}
 else data=path.startsWith('/api/pos/')?programs.map(p=>({...p,rules:p.rules.filter(x=>x.rule_type==='PRODUCT')})).filter(p=>p.rules.length):programs;
}
if(path.startsWith('/api/admin/court-promotions/programs/')&&req.method()==='PUT'){const v=req.postDataJSON(),program=programs.find(p=>path.endsWith(p.id));Object.assign(program,{name:v.name,description:v.description,valid_from:v.validFrom,valid_to:v.validTo,is_active:v.active,rules:v.rules.filter(x=>!x.removed).map((x,n)=>({id:x.id||'added-'+n,promotion_id:program.id,rule_type:x.ruleType,target_product_id:x.productId,product_name:'Nước suối',min_quantity:x.minQuantity,discount_type:x.discountType,discount_value:x.discountValue,min_invoice_amount:x.minInvoiceAmount,max_discount_amount:x.maxDiscountAmount,max_total_usage:x.maxTotalUsage,max_usage_per_customer:x.maxUsagePerCustomer,voucher_code:x.voucherCode,is_active:x.active}))});data=program.id;}
if(path.startsWith('/api/admin/court-promotions/programs/')&&req.method()==='PATCH')programs.find(p=>path.includes('/'+p.id+'/')).is_active=req.postDataJSON().active;
if(path.startsWith('/api/admin/court-promotions/programs/')&&req.method()==='DELETE')programs=programs.filter(p=>!path.endsWith('/'+p.id));
if(path==='/api/admin/accounts')data={accounts:accounts.filter(a=>!u.searchParams.get('search')||a.fullName.includes(u.searchParams.get('search'))),totalPages:1};
if(path.startsWith('/api/admin/accounts/user-'))data=accounts.find(a=>path.endsWith(a.id));
if(path==='/api/admin/categories'){if(req.method()==='POST')data={};else data=categories;}
if(path.startsWith('/api/admin/categories/')&&req.method()==='PUT'){if(failSave)return r.fulfill({status:409,json:{success:false,message:'Lỗi lưu kiểm thử'}});Object.assign(categories.find(c=>path.endsWith(c.id)),req.postDataJSON());}
if(path.startsWith('/api/admin/categories/')&&req.method()==='DELETE')categories.splice(categories.findIndex(c=>path.endsWith(c.id)),1);
if(path==='/api/admin/products'||path==='/api/pos/products')data=products;
if(path==='/api/admin/courts')data=courts;
if(path==='/api/admin/time-slots')data=slots;
if(path==='/api/admin/branches'||path==='/api/public/branches')data=[{id:'branch',name:'Chi nhánh 1',district:'Quận 1',address:'Địa chỉ',active:true}];
if(path==='/api/admin/product-promotions'||path==='/api/pos/product-promotions')data=[promotion];
if(path==='/api/admin/court-promotions')data=[{id:'cpromo',name:'Ưu đãi sân',code:'COURT',rule_type:'INVOICE_TOTAL',discount_type:'AMOUNT',discount_value:10000,min_invoice_amount:100000,valid_from:'2026-01-01',is_active:true}];
if(path==='/api/analytics/invoices')data={invoices:u.searchParams.get('search')==='missing'?[]:invoices,totalPages:1,totalElements:2};
if(path.startsWith('/api/analytics/invoices/')){const invoice=invoices.find(i=>path.endsWith(i.id));data={invoice,items:[{name:'Nước suối',quantity:1,unitPrice:10000,discountAmount:0,totalAmount:10000}],courts:[{name:'Sân 1',date:'2026-11-01',startTime:'06:00',endTime:'07:00',price:100000}],receipts:invoice.id==='invoice-1'?[{id:'r1',method:'VNPAY',status:'SUCCESS',amount:50000},{id:'r2',method:'CASH',status:'SUCCESS',amount:55000}]:[{id:'r3',method:'CASH',status:'SUCCESS',amount:105000}]};}
if(path==='/api/analytics/statistics/matrix')data={type:u.searchParams.get('type')||'TIME',columns:Array.from({length:12},(_,i)=>'Tháng '+(i+1)),rows:[{id:'total',label:'Tổng thu',values:[1,12,3,10,5,8,7,6,9,4,11,2],total:78}],totals:[1,12,3,10,5,8,7,6,9,4,11,2],total:78};
if(path==='/api/pos/drafts')data={content:[{id:'draft',customerName:'Khách POS',createdAt:'2026-11-01',totalAmount:10000,items:[{productName:'Nước suối',quantity:1,lineAmount:10000}]}],page:0,totalPages:1,totalElements:1};
if(path==='/api/import-orders')data=[{id:'import',supplierId:'supplier',importDate:'2026-11-19',status:'DRAFT',totalAmount:10000}];
if(path==='/api/import-orders/import')data={id:'import',supplierId:'supplier',importDate:'2026-11-19',status:'DRAFT',totalAmount:10000,details:[{id:'d1',productId:'product',quantity:1,importPrice:10000}]};
if(path==='/api/suppliers')data=[{id:'supplier',name:'Nhà cung cấp'}];
if(path==='/api/checkout/active-bookings')data=[{bookingId:'booking',customerName:'Khách sân',customerPhone:'0912345678',status:'PAID',finalAmount:100000},{bookingId:'pending-booking',customerName:'Khách chờ',status:'PENDING',finalAmount:100000}];
if(path==='/api/checkout/booking')data={bookingId:'booking',court_fee:100000,product_fee:0,total_amount:100000,paidCourtAmount:100000,amountDue:0};
if(path==='/api/public/availability')data={courts:[{courtId:'court',slots:[]}]};
return r.fulfill({json:{success:true,data}})};await p.route('**/api/**',serve);
await p.addInitScript(()=>sessionStorage.setItem('bcm-session',JSON.stringify({accessToken:'fixture',user:{id:'admin',roles:['ADMIN'],fullName:'Quản lý'}})));
const base=process.env.ADMIN_TEST_URL||'http://127.0.0.1:5173';
async function menu(tab){console.log('CHECK',tab);await p.goto(base+'/?tab='+tab);await p.locator('.ms-layout').waitFor();assert.equal(await p.locator('.ms-layout.ms-open').count(),0);}
async function close(){for(const width of [1440,1024,768,375]){await p.setViewportSize({width,height:1000});assert(await p.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),'panel overflow '+width+' '+p.url());}await p.setViewportSize({width:1440,height:1000});await p.locator('.ms-sub:visible button:visible').filter({hasText:/^(X|✕)$/}).first().click();await p.locator('.ms-sub').waitFor({state:'hidden'});assert.equal(await p.locator('.ms-main tr[aria-selected=true]').count(),0);}
await menu('accounts');await p.locator('.accounts-panel tbody tr').first().click();await p.locator('.ms-sub:visible').waitFor();assert.match(await p.locator('.ms-sub').innerText(),/Khách 1/);await p.locator('.accounts-panel tbody tr').nth(1).click();await p.locator('.ms-sub').getByRole('heading',{name:'Khách 2',exact:true}).waitFor();await close();
await menu('invoices');await p.locator('.invoices-panel tbody tr').first().waitFor();assert.equal(await p.locator('.invoices-panel> .table-scroll thead th').count(),6);assert.match(await p.locator('.invoices-panel tbody tr').first().innerText(),/VNPay \+ Tiền mặt/);await p.locator('.invoices-panel tbody tr').first().click();await p.locator('.ms-sub:visible .invoice-item-table').waitFor();assert.equal(await p.locator('[role=dialog]').count(),0);assert.equal(await p.locator('.ms-sub .invoice-court-table th').count(),6);const main=await p.locator('.ms-main').boundingBox(),sub=await p.locator('.ms-sub').boundingBox();assert(Math.abs(main.width/sub.width-2)<.05);assert.match(await p.locator('.ms-sub .invoice-court-table td').last().innerText(),/95\.000/);assert.equal(await p.locator('.ms-sub .invoice-receipt-table').count(),0);assert.equal(await p.locator('.ms-sub .invoice-summary-block').count(),0);assert.equal(await p.locator('.ms-sub').getByText('Chưa lưu giảm giá và thành tiền cho từng khung giờ').count(),0);assert.equal(await p.locator('.ms-sub').getByText('Booking:').count(),0);assert.equal(await p.locator('.ms-sub .invoice-detail-meta .invoice-meta-col').count(),2);assert.equal(await p.locator('.ms-sub .invoice-detail-meta').evaluate(e=>getComputedStyle(e).gridTemplateColumns.split(' ').length),2);assert.match(await p.locator('.ms-sub').innerText(),/VNPay \+ Tiền mặt/);assert.equal(await p.locator('.ms-sub:visible button:visible').filter({hasText:/^Đóng$/}).count(),0);assert.equal(await p.locator('.ms-sub:visible button:visible').filter({hasText:/^(X|✕)$/}).count(),1);await p.locator('.ms-main .invoices-panel tbody tr').nth(1).click();await p.locator('.ms-sub').getByText('Khách 2',{exact:true}).waitFor();assert.match(await p.locator('.ms-sub').innerText(),/Tiền mặt/);for(const width of [1440,1024,768,375]){await p.setViewportSize({width,height:1000});assert(await p.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),'overflow '+width);}await p.getByLabel('Tìm hóa đơn',{exact:true}).fill('missing');await p.locator('.ms-sub').waitFor({state:'hidden'});assert.equal(await p.getByLabel('Tìm hóa đơn',{exact:true}).inputValue(),'missing');await p.getByLabel('Tìm hóa đơn',{exact:true}).fill('');await p.locator('.ms-main tbody tr').first().waitFor();assert.equal(await p.locator('.ms-layout.ms-open').count(),0);await p.setViewportSize({width:1440,height:1000});
await menu('statistics');await p.locator('.stat-table tbody tr').waitFor();assert.equal(await p.locator('.stat-arrow').count(),0);await p.getByRole('button',{name:/^Xem thống kê$/i}).click();await p.locator('.ms-sub svg').waitFor();assert.equal(await p.locator('.ms-sub svg rect').count(),5);assert.deepEqual(await p.locator('.ms-sub svg rect title').allTextContents(),['Tháng 2: 12 ₫','Tháng 11: 11 ₫','Tháng 4: 10 ₫','Tháng 9: 9 ₫','Tháng 6: 8 ₫']);const barWidth=await p.locator('.ms-sub svg rect').first().evaluate(el=>Number(el.getAttribute('width')));assert(barWidth<=46);await p.getByRole('button',{name:/^Xem thống kê$/i}).click();await p.locator('.ms-sub').waitFor({state:'hidden'});await p.locator('.stat-table tbody tr').click();await p.locator('.ms-sub svg').waitFor();assert.equal(await p.locator('.ms-sub .stat-row-detail').count(),0);await close();
await menu('categories');await p.locator('.ms-main tbody tr').first().click();await p.locator('.ms-sub form').waitFor();const field=p.locator('.ms-sub input').first();await field.fill('Tên mới');p.once('dialog',d=>d.dismiss());await p.locator('.ms-main tbody tr').nth(1).click();assert.equal(await field.inputValue(),'Tên mới');p.once('dialog',d=>d.dismiss());await p.getByLabel('Tìm kiếm',{exact:true}).fill('missing');assert.equal(await p.getByLabel('Tìm kiếm',{exact:true}).inputValue(),'');assert.equal(await field.inputValue(),'Tên mới');failSave=true;await p.locator('.ms-sub button.primary').click();await p.locator('.ms-sub').getByRole('alert').waitFor();assert.equal(await field.inputValue(),'Tên mới');failSave=false;await p.locator('.ms-sub button.primary').click();await p.locator('.ms-sub').waitFor({state:'hidden'});await p.locator('.ms-main').getByText('Tên mới',{exact:true}).waitFor();await p.locator('.ms-main tbody tr').first().click();await p.locator('.ms-sub form').waitFor();await p.locator('.ms-main tbody tr').first().getByRole('button',{name:'Xóa',exact:true}).click();await p.getByRole('dialog').getByRole('button',{name:'Xác nhận xóa',exact:true}).click();await p.locator('.ms-sub').waitFor({state:'hidden'});assert.equal(await p.locator('.ms-main tr[aria-selected=true]').count(),0);
for(const tab of ['time-slots','products','product-promotions']){await menu(tab);await p.locator('.ms-main tbody tr').first().click();await p.locator('.ms-sub form').waitFor();assert.equal(await p.locator('[role=dialog]').count(),0);await close();}
await menu('product-promotions');assert.equal(await p.getByRole('tab').count(),0);await p.locator('.promotion-program-table tbody tr').first().waitFor();assert.equal(await p.locator('.promotion-program-table tbody tr').count(),2);assert.match(await p.locator('.promotion-program-table tbody tr').first().innerText(),/Sản phẩm \+ Hóa đơn \+ Voucher/);await p.locator('.promotion-program-table tbody tr').first().click();assert.equal(await p.locator('.ms-sub .promotion-rule').count(),3);assert.equal(await p.getByLabel('Mô tả',{exact:true}).inputValue(),'Chương trình phối hợp');await p.locator('.promotion-program-table tbody tr').nth(1).click();assert.equal(await p.getByLabel('Mô tả',{exact:true}).inputValue(),'Chương trình độc lập');await close();
await p.getByRole('button',{name:'+ Thêm chương trình',exact:true}).click();await p.getByLabel('Tên chương trình',{exact:true}).fill('Chương trình mới');await p.getByLabel('Ngày kết thúc',{exact:true}).fill('2030-01-01');await p.getByRole('button',{name:'+ Thêm quy tắc',exact:true}).click();await p.getByLabel('Sản phẩm',{exact:true}).selectOption('product');await p.getByLabel('Số lượng tối thiểu',{exact:true}).fill('2');await p.getByLabel('Loại quy tắc mới').selectOption('INVOICE_TOTAL');await p.getByRole('button',{name:'+ Thêm quy tắc',exact:true}).click();assert.equal(await p.getByLabel('Mã voucher',{exact:true}).count(),0);await p.getByLabel('Loại quy tắc mới').selectOption('VOUCHER');await p.getByRole('button',{name:'+ Thêm quy tắc',exact:true}).click();await p.getByLabel('Mã voucher',{exact:true}).fill('NEW10');await p.getByLabel('Tổng lượt sử dụng',{exact:true}).fill('5');failProgramSave=true;await p.getByRole('button',{name:'Lưu chương trình',exact:true}).click();await p.locator('.ms-sub').getByRole('alert').waitFor();assert.equal(programs.length,2);assert.equal(await p.getByLabel('Tên chương trình',{exact:true}).inputValue(),'Chương trình mới');failProgramSave=false;await p.getByRole('button',{name:'Lưu chương trình',exact:true}).click();await p.locator('.ms-sub').waitFor({state:'hidden'});const created=calls.find(c=>c.path==='/api/admin/court-promotions/programs'&&c.method==='POST');assert.equal(JSON.parse(created.body).rules.length,3);await p.locator('.promotion-program-table tbody tr').filter({hasText:'Chương trình mới'}).click();assert.equal(await p.locator('.ms-sub .promotion-rule').count(),3);p.once('dialog',d=>d.accept());await p.getByRole('button',{name:'Xóa quy tắc',exact:true}).click();await p.getByRole('button',{name:'Lưu chương trình',exact:true}).click();await p.locator('.ms-sub').waitFor({state:'hidden'});assert.equal(programs.find(x=>x.id==='created').rules.length,2);await p.locator('.promotion-program-table tbody tr').filter({hasText:'Chương trình mới'}).click();await p.getByRole('button',{name:'Tắt quy tắc',exact:true}).click();await p.getByRole('button',{name:'Lưu chương trình',exact:true}).click();await p.locator('.ms-sub').waitFor({state:'hidden'});assert.equal(programs.find(x=>x.id==='created').rules[0].is_active,false);await p.locator('.promotion-program-table tbody tr').filter({hasText:'Chương trình mới'}).click();await p.getByRole('button',{name:'Tắt chương trình',exact:true}).click();await p.locator('.ms-sub').waitFor({state:'hidden'});assert.equal(programs.find(x=>x.id==='created').is_active,false);await p.locator('.promotion-program-table tbody tr').filter({hasText:'Chương trình mới'}).click();p.once('dialog',d=>d.dismiss());await p.getByRole('button',{name:'Xóa chương trình',exact:true}).click();assert.equal(programs.length,3);p.once('dialog',d=>d.accept());await p.getByRole('button',{name:'Xóa chương trình',exact:true}).click();await p.locator('.ms-sub').waitFor({state:'hidden'});assert.equal(programs.length,2);await p.locator('.promotion-program-table tbody tr').first().click();await p.getByRole('button',{name:'+ Thêm quy tắc',exact:true}).click();p.once('dialog',d=>d.dismiss());await p.getByRole('button',{name:'Đóng chương trình',exact:true}).click();assert.equal(await p.locator('.ms-sub:visible .promotion-rule').count(),4);p.once('dialog',d=>d.accept());await p.getByRole('button',{name:'Đóng chương trình',exact:true}).click();await p.locator('.ms-sub').waitFor({state:'hidden'});await p.locator('.promotion-program-table tbody tr').first().click();await p.getByLabel('Tìm chương trình',{exact:true}).fill('missing');await p.locator('.ms-sub').waitFor({state:'hidden'});await p.getByLabel('Tìm chương trình',{exact:true}).fill('');assert.equal(await p.getByRole('tab').count(),0);assert.doesNotMatch(await p.locator('.promotions-page').innerText(),/Nhập mã tại POS|Dùng mã này tại Bán tại quầy/);console.log('PASS unified programs: real IDs, same-name separation, 3 rule kinds/1 row, create/update/remove/toggle, filters, short UI.');
await menu('courts');await p.locator('.ms-main tbody tr').first().click();await p.locator('.ms-sub:visible').waitFor();await p.locator('.ms-main tbody tr').first().click();await p.locator('.ms-sub .court-schedule').waitFor();await close();
await menu('checkout');await p.locator('.ms-main tbody tr').first().click();await p.locator('.ms-sub .checkout-slip-content').waitFor();await close();await p.locator('.ms-main tbody tr').nth(1).click();await p.locator('.ms-sub [aria-label="Chi tiết đặt sân chờ thanh toán"]').waitFor();await close();
await menu('inventory');await p.locator('.ms-main tbody tr').first().click();await p.locator('.ms-sub:visible').waitFor();assert.match(await p.locator('.ms-sub').innerText(),/Chi tiết phiếu nhập/);assert.equal(await p.locator('.ms-sub .import-detail-table th').count(),4);assert.match(await p.locator('.ms-sub .import-detail-table').innerText(),/Nước suối/);await close();
await menu('pos');await p.locator('.ms-main [aria-label="Đơn POS chờ kết toán"] tbody tr').first().click();await p.locator('.ms-sub:visible').waitFor();await close();
const staff=await b.newPage();staff.setDefaultTimeout(10000);await staff.route('**/api/**',serve);await staff.addInitScript(()=>sessionStorage.setItem('bcm-session',JSON.stringify({accessToken:'fixture',user:{id:'staff',roles:['STAFF']}})));await staff.goto(base+'/?tab=product-promotions');await staff.locator('.ms-main tbody tr').first().click();await staff.locator('.ms-sub form').waitFor();assert.equal(await staff.locator('.ms-sub fieldset').first().getAttribute('disabled'),'');assert(await staff.locator('.ms-sub input').first().isDisabled());assert(await staff.locator('.promotion-rule-heading').first().isEnabled());assert.equal(await staff.locator('[role=dialog]').count(),0);await staff.close();assert.deepEqual(errors,[]);console.log('PASS Main/Sub: all record menus, invoice6 columns/unknown court amounts, Top5/toggle, row switching, dirty cancel, save error/success, 4 responsive widths, no JS errors; fixtures only.');
}finally{await b.close()}await accountInvoiceRegression()})().catch(e=>{console.error(e);process.exitCode=1});

async function accountInvoiceRegression(){
 const admin=process.env.ADMIN_TEST_URL||'http://127.0.0.1:5173';
 const browser=await chromium.launch({headless:true,channel:'msedge'});
 try{
  for(const role of ['ADMIN','STAFF','CUSTOMER']){
   const page=await browser.newPage({viewport:{width:1366,height:900}});
 await page.route("https://fonts.googleapis.com/**",r=>r.fulfill({body:"",contentType:"text/css"}));
   const jsErrors=[];page.on('pageerror',e=>jsErrors.push(e.message));
   const requests=[];let fail=false,failLock=false,failUnlock=false,holdMutation=null;
   const invoice={id:'11111111-1111-4111-8111-111111111111',customerId:'33333333-3333-4333-8333-333333333333',customerPhone:'0912345678',customerName:'Khách kiểm thử',createdAt:'2026-11-11T10:00:00',courtFee:200000,productFee:30000,discountAmount:5000,totalAmount:225000,invoiceStatus:'PAID'};
   const customer={id:'22222222-2222-4222-8222-222222222222',fullName:'Khách kiểm thử',email:'customer@ui.test',phone:'0912345678',address:'Test',roles:['CUSTOMER'],createdAt:'2026-11-11T10:00:00',deletedAt:null,canLock:true,canUnlock:false,customerId:invoice.customerId,bookingCount:0,totalSpent:0,bookings:[],invoices:[]};
   await page.route('**/api/**',async route=>{
    const r=route.request(),u=new URL(r.url());requests.push({method:r.method(),path:u.pathname,query:u.search,body:r.postDataJSON()});let data=[];
    if(u.pathname==='/api/admin/accounts')data={accounts:[customer],totalPages:1,totalElements:1};
    if(u.pathname==='/api/admin/accounts/'+customer.id&&r.method()==='DELETE'){
     if(holdMutation)await holdMutation;
     if(failLock)return route.fulfill({status:409,json:{success:false,message:'Không thể khóa lúc này'}});
     customer.deletedAt='2026-11-11T10:05:00';customer.canLock=false;customer.canUnlock=true;
    }else if(u.pathname==='/api/admin/accounts/'+customer.id)data=customer;
    if(u.pathname==='/api/admin/accounts/'+customer.id+'/unlock'&&r.method()==='POST'){
     if(holdMutation)await holdMutation;
     if(failUnlock)return route.fulfill({status:409,json:{success:false,message:'Không thể mở khóa lúc này'}});
     customer.deletedAt=null;customer.canLock=true;customer.canUnlock=false;
    }
    if(u.pathname==='/api/admin/customers')data=customer.customerId;
    if(u.pathname==='/api/pos/drafts')data={content:[],page:0,totalPages:0,totalElements:0};
    if(u.pathname==='/api/analytics/invoices'){
     if(fail)return route.fulfill({status:500,json:{success:false,message:'Không tải được lịch sử'}});
     data=u.searchParams.get('search')==='none'?{invoices:[],page:0,totalPages:0,totalElements:0}:{invoices:[invoice],page:Number(u.searchParams.get('page')),totalPages:2,totalElements:21};
    }
    if(u.pathname==='/api/analytics/invoices/'+invoice.id)data={invoice,creatorName:'Thu ngân',items:[{name:'Nước suối',quantity:3,unitPrice:10000,discountAmount:5000,totalAmount:25000}],courts:[{name:'Sân 1',date:'2026-11-11',startTime:'06:00',endTime:'07:00',price:200000}],receipts:[]};
    return route.fulfill({json:{success:true,data}});
   });
   await page.addInitScript(role=>sessionStorage.setItem('bcm-session',JSON.stringify({accessToken:'ui-fixture',user:{id:'admin-fixture',roles:[role],fullName:role+' Test'}})),role);
   await page.goto(admin+'/?tab=accounts');
   if(role==='CUSTOMER'){await page.getByRole('heading',{name:'Đăng nhập quản trị'}).waitFor();assert.equal(await page.locator('.sidebar').count(),0);console.log('PASS CUSTOMER: admin UI denied');await page.close();continue;}
   await page.locator('.sidebar').waitFor();
   const nav=page.locator('.sidebar button.nav');assert.equal(await nav.count(),12);
   if(role==='STAFF'){
    for(const name of ['Tài khoản','Thống kê','Doanh thu','Nhập kho','Sân cầu']){
     const b=nav.filter({hasText:name});assert(await b.isDisabled());assert.doesNotMatch(await b.innerText(),/🔒/);assert.equal(await b.getAttribute('title'),'Chức năng chỉ dành cho Quản lý');
    }
    for(const name of ['Bán tại quầy','Trả sân','Hóa đơn','Khuyến mãi'])assert(await page.getByRole('button',{name,exact:true}).isEnabled());
    for(const url of ['/?tab=statistics','/accounts','/?tab=revenue-breakdown']){
     await page.goto(admin+url);await page.waitForURL('**/?tab=pos');assert.equal(await page.getByRole('heading',{name:'Tài khoản',exact:true}).count(),0);
    }
    await page.evaluate(()=>{history.pushState(null,'','/?tab=accounts');dispatchEvent(new PopStateEvent('popstate'));});
    assert.equal(await page.getByRole('heading',{name:'Tài khoản',exact:true}).count(),0);
   }else{
    assert.equal(await nav.locator(':disabled').count(),0);
    assert.equal(await page.getByRole('button',{name:'Sửa',exact:true}).count(),0);
    await page.locator('.ms-main tbody tr').first().click();await page.locator('.ms-sub').getByText('Vai trò: Khách hàng',{exact:true}).waitFor();
    const accountDialog=page.locator('.ms-sub');
    assert.equal(await accountDialog.locator('.invoices-panel,table,input,select').count(),0);
    assert.equal(await accountDialog.getByText('Lịch sử hóa đơn',{exact:true}).count(),0);
    assert.equal(await accountDialog.getByText('Lịch sử đặt sân',{exact:true}).count(),0);
    assert.equal(requests.filter(r=>r.path.startsWith('/api/analytics/invoices')).length,0);
    await accountDialog.getByRole('button',{name:'Xem hóa đơn đã thanh toán',exact:true}).click();
    await page.waitForURL('**/?tab=invoices');
    await page.locator('.invoices-panel tbody tr').first().waitFor();
    assert.equal(new URLSearchParams(requests.filter(r=>r.path==='/api/analytics/invoices').at(-1).query).get('customerId'),invoice.customerId);
    assert.equal(new URLSearchParams(requests.filter(r=>r.path==='/api/analytics/invoices').at(-1).query).get('status'),'PAID');
    await page.getByLabel('Tìm hóa đơn',{exact:true}).fill('same-name');
    await page.waitForResponse(r=>new URL(r.url()).searchParams.get('search')==='same-name');
    await page.locator('.ms-main tbody tr').first().waitFor();
    assert.equal(new URLSearchParams(requests.filter(r=>r.path==='/api/analytics/invoices').at(-1).query).get('customerId'),invoice.customerId);
    assert.equal(new URLSearchParams(requests.filter(r=>r.path==='/api/analytics/invoices').at(-1).query).get('status'),'PAID');
    await page.getByRole('button',{name:'Bỏ lọc khách hàng',exact:true}).click();
    await page.locator('.invoices-panel tbody tr').first().waitFor();
    assert.equal(new URLSearchParams(requests.filter(r=>r.path==='/api/analytics/invoices').at(-1).query).has('customerId'),false);
    await page.getByRole('button',{name:'Tài khoản',exact:true}).click();
    await page.locator('.ms-main tbody tr').first().waitFor();
    await page.getByRole('button',{name:'+ Thêm khách hàng',exact:true}).click();
    const d=page.locator('.ms-sub');for(const [name,value] of [['Họ tên','Khách mới'],['Email','new@ui.test'],['Số điện thoại','0987654321'],['Địa chỉ','Test'],['Mật khẩu','Password123!']])await d.getByLabel(name,{exact:true}).fill(value);
    await d.getByRole('button',{name:'Lưu',exact:true}).click();await d.waitFor({state:'hidden'});
    const sent=requests.find(r=>r.path==='/api/admin/customers'&&r.method==='POST');assert(sent&&sent.body.password==='Password123!');assert.equal(sent.body.roles,undefined);
    await page.getByRole('button',{name:'Khóa tài khoản',exact:true}).click();assert.equal(requests.filter(r=>r.method==='DELETE').length,0);
    await page.getByRole('dialog').getByRole('button',{name:'Hủy',exact:true}).click();assert.equal(requests.filter(r=>r.method==='DELETE').length,0);
    assert.equal(await page.locator('tbody tr .record-actions button').count(),2);
    failLock=true;await page.getByRole('button',{name:'Khóa tài khoản',exact:true}).click();
    let dialog=page.getByRole('dialog');await dialog.getByRole('button',{name:'Khóa tài khoản',exact:true}).click();
    await dialog.getByText('Không thể khóa lúc này',{exact:true}).waitFor();
    assert.equal(customer.deletedAt,null);assert.equal(await page.getByText('Hoạt động',{exact:true}).count(),1);
    failLock=false;let release;holdMutation=new Promise(resolve=>{release=resolve});
    await dialog.getByRole('button',{name:'Khóa tài khoản',exact:true}).click();
    assert(await dialog.getByRole('button',{name:'Khóa tài khoản',exact:true}).isDisabled());
    assert(await dialog.getByRole('button',{name:'Hủy',exact:true}).isDisabled());
    assert(await page.getByRole('button',{name:'+ Thêm khách hàng',exact:true}).isDisabled());
    release();holdMutation=null;await dialog.waitFor({state:'hidden'});await page.getByText('Đã khóa',{exact:true}).waitFor();
    assert(await page.getByRole('button',{name:'Mở khóa tài khoản',exact:true}).isEnabled());
    await page.getByRole('button',{name:'Mở khóa tài khoản',exact:true}).click();
    dialog=page.getByRole('dialog');await dialog.getByRole('heading',{name:'Mở khóa tài khoản',exact:true}).waitFor();
    const unlockCount=()=>requests.filter(r=>r.path.endsWith('/unlock')).length;
    assert.equal(unlockCount(),0);await dialog.getByRole('button',{name:'Hủy',exact:true}).click();assert.equal(unlockCount(),0);
    await page.getByRole('button',{name:'Mở khóa tài khoản',exact:true}).click();dialog=page.getByRole('dialog');
    failUnlock=true;await dialog.getByRole('button',{name:'Mở khóa tài khoản',exact:true}).click();
    await dialog.getByText('Không thể mở khóa lúc này',{exact:true}).waitFor();
    assert.equal(customer.deletedAt,'2026-11-11T10:05:00');assert.equal(await page.getByText('Đã khóa',{exact:true}).count(),1);
    failUnlock=false;holdMutation=new Promise(resolve=>{release=resolve});
    await dialog.getByRole('button',{name:'Mở khóa tài khoản',exact:true}).click();
    assert(await dialog.getByRole('button',{name:'Mở khóa tài khoản',exact:true}).isDisabled());
    assert(await dialog.getByRole('button',{name:'Hủy',exact:true}).isDisabled());
    release();holdMutation=null;await dialog.waitFor({state:'hidden'});await page.getByText('Hoạt động',{exact:true}).waitFor();
    await page.getByText('Đã mở khóa tài khoản.',{exact:true}).waitFor();
    assert(await page.getByRole('button',{name:'Khóa tài khoản',exact:true}).isEnabled());
    assert.equal(await page.locator('tbody tr .record-actions button').count(),2);
    assert.equal(unlockCount(),2);
    assert(requests.filter(r=>r.path.endsWith('/unlock')).every(r=>r.method==='POST'));

   }
   await page.getByRole('button',{name:'Hóa đơn',exact:true}).click();assert.equal(await page.locator('.invoices-panel> .table-scroll th').count(),6);await page.locator('.invoices-panel tbody tr').first().waitFor();assert.equal(await page.getByRole('cell',{name:invoice.customerName,exact:true}).count(),0);await page.locator('.ms-main tbody tr').first().click();
   const dialog=page.locator('.ms-sub');await dialog.locator('.invoice-detail-phone').filter({hasText:invoice.customerPhone}).waitFor();assert.equal(await dialog.locator('.invoice-detail-customer strong').innerText(),invoice.customerName);assert.equal(await dialog.locator('h2').evaluate(e=>getComputedStyle(e).fontSize),'17px');await dialog.getByText('Nước suối',{exact:true}).waitFor();
   for(const width of [1440,1024,768,375]){await page.setViewportSize({width,height:900});assert(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth));assert(await dialog.evaluate(el=>{const family=getComputedStyle(el).fontFamily;return [...el.querySelectorAll('code,td,th,dt,dd,small,h2,h3')].every(e=>getComputedStyle(e).fontFamily===family)}));assert(await dialog.locator('small').evaluateAll(nodes=>nodes.every(e=>getComputedStyle(e).marginTop==='0px')));}
   await page.setViewportSize({width:1366,height:900});assert.match(await dialog.innerText(),/200\.000/);assert.match(await dialog.innerText(),/Thu ngân/);assert.equal(await dialog.getByRole('button',{name:/^Đóng$/i}).count(),0);assert.equal(await dialog.getByRole('button',{name:/^(X|✕|Đóng chi tiết hóa đơn)$/i}).count(),1);
   await page.keyboard.press('Escape');await page.getByRole('button',{name:'Trang sau',exact:true}).click();await page.getByText('Trang 2 / 2',{exact:true}).waitFor();
   await page.getByLabel('Tìm hóa đơn',{exact:true}).fill('none');await page.getByText('Không có hóa đơn phù hợp.',{exact:true}).waitFor();
   await page.getByLabel('Tìm hóa đơn',{exact:true}).fill('');await page.locator('.ms-main tbody tr').first().waitFor();
   await page.setViewportSize({width:390,height:844});await page.locator('.ms-main tbody tr').first().click();await page.locator('.ms-sub').getByText('Nước suối',{exact:true}).waitFor();assert(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth));await page.keyboard.press('Escape');invoice.customerPhone=null;await page.locator('.ms-main tbody tr').first().click();await page.locator('.ms-sub').getByText('Nước suối',{exact:true}).waitFor();assert.equal(await page.locator('.ms-sub').locator('.invoice-detail-phone').innerText(),'');await page.keyboard.press('Escape');invoice.customerPhone='0912345678';
   fail=true;await page.getByRole('button',{name:'Làm mới',exact:true}).click();await page.getByText('Không tải được lịch sử',{exact:true}).waitFor();
   assert(requests.filter(r=>r.path.startsWith('/api/analytics/invoices')).every(r=>r.method==='GET'));
   if(role==='STAFF')assert(!requests.some(r=>r.path.startsWith('/api/admin/')||r.path==='/api/analytics/statistics'||r.path==='/api/analytics/revenue-breakdown'));
   assert.deepEqual(jsErrors,[]);
   console.log('PASS '+role+': menu/URL guards, read-only invoices, pagination, details, payment information, errors and mobile'+(role==='ADMIN'?'; create/view/lock/unlock customer; one toggle button/modal; cancel/busy/error states preserve server state':''));await page.close();
  }
 }finally{await browser.close()}
}
