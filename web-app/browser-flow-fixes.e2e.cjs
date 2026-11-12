// Deterministic UI integration. Every API request is intercepted; no project DB is used.
const {chromium,expect}=require(process.env.PLAYWRIGHT_MODULE||'playwright/test');
const assert=require('node:assert/strict');
const web=process.env.BCM_WEB_TEST_URL||'http://127.0.0.1:5184',admin=process.env.ADMIN_TEST_URL||'http://127.0.0.1:5185';
for(const url of [web,admin])assert.equal(new URL(url).hostname,'127.0.0.1');
const bookingId='11111111-1111-4111-8111-111111111111',invoiceId='22222222-2222-4222-8222-222222222222';
(async()=>{
 const browser=await chromium.launch({channel:'msedge',headless:true}),errors=[];
 const offlineFonts=async page=>{
  await page.route('https://fonts.googleapis.com/**',r=>r.fulfill({body:'',contentType:'text/css'}));
  await page.route('https://fonts.gstatic.com/**',r=>r.abort());
 };
 try{
  const customer=await browser.newPage();customer.on('pageerror',e=>errors.push(e.message));
  await offlineFonts(customer);
  await customer.addInitScript(()=>{sessionStorage.setItem('bcm.accessToken','fixture');sessionStorage.setItem('bcm.user',JSON.stringify({id:'customer',fullName:'Customer',roles:['CUSTOMER']}));});
  let expired=false,created=0,resumes=0,duration=600,policyFails=false;
  const court={id:'court',courtNumber:1,name:'Fixture court',basePrice:220000,type:'WOODEN_FLOOR',status:'AVAILABLE'};
  const summary={bookingId,status:'PENDING',courtFee:440000,discountAmount:30000,finalAmount:410000,expiresAt:'2030-01-02T10:00:00',createdAt:'2030-01-02T09:50:00'};
  await customer.route('**/api/**',route=>{
   const req=route.request(),path=new URL(req.url()).pathname.replace('/api','');
   const send=data=>route.fulfill({json:{success:true,data}});
   if(path==='/public/booking-clock')return policyFails?route.fulfill({status:503,json:{success:false,message:'offline'}}):send({serverTime:'2030-01-02T02:55:00Z',today:'2030-01-02',timeZone:'Asia/Ho_Chi_Minh',holdDurationSeconds:duration});
   if(path==='/public/courts')return send([court]);
   if(path==='/public/time-slots')return send([]);
   if(path==='/bookings'&&req.method()==='POST')created++;
   if(path==='/bookings')return send({content:[summary,{...summary,bookingId:'paid-booking',status:'PAID'},{...summary,bookingId:'expired-booking',status:'EXPIRED'}],totalElements:3,totalPages:1});
   if(path.endsWith('/checkout/resume')){resumes++;return send({...summary,status:expired?'EXPIRED':'PENDING'});}
   if(path.endsWith('/checkout-clock'))return send({...summary,status:expired?'EXPIRED':'PENDING',serverTime:'2030-01-02T09:55:00'});
   if(path.endsWith('/contact'))return send({fullName:'Customer',phone:'0912345678',email:'customer@example.test'});
   if(path==='/bookings/'+bookingId)return send({...summary,payments:[],details:[{detailId:'detail',courtId:'court',courtName:'Fixture court',courtNumber:1,bookingDate:'2030-01-03',timeSlotId:'slot',startTime:'06:00:00',endTime:'08:00:00',price:440000}]});
   return send([]);
  });
  await customer.goto(web+'/my-bookings',{waitUntil:'domcontentloaded'});
  await expect(customer.getByRole('link',{name:'Tiếp tục thanh toán',exact:true})).toHaveCount(1);
  await customer.getByRole('link',{name:'Tiếp tục thanh toán',exact:true}).click();
  await expect(customer).toHaveURL(new RegExp('/checkout\\?bookingId='+bookingId));
  await expect(customer.getByRole('button',{name:'Thanh toán',exact:true})).toBeEnabled();
  assert.equal(created,0);assert.equal(resumes,1);
  await customer.reload({waitUntil:'domcontentloaded'});
  await expect(customer.getByRole('button',{name:'Thanh toán',exact:true})).toBeEnabled();assert.equal(created,0);
  expired=true;
  await customer.goto(web+'/my-bookings');
  await customer.getByRole('link',{name:'Tiếp tục thanh toán',exact:true}).click();
  await expect(customer.getByRole('button',{name:'Thanh toán',exact:true})).toBeDisabled();assert.equal(created,0);
  for(const [seconds,label] of [[600,'10 phút'],[900,'15 phút'],[90,'1 phút 30 giây']]){
   duration=seconds;await customer.goto(web+'/',{waitUntil:'domcontentloaded'});
   await customer.getByRole('button',{name:'Tôi có bao lâu để hoàn tất thanh toán?'}).click();
   await expect(customer.locator('.faq-item__a')).toContainText(label);
  }
  policyFails=true;await customer.goto(web+'/');
  await customer.getByRole('button',{name:'Tôi có bao lâu để hoàn tất thanh toán?'}).click();
  await expect(customer.locator('.faq-item__a')).toContainText('Thời hạn giữ chỗ được hiển thị');
  await expect(customer.locator('.court-card')).toHaveCount(1);

  const desk=await browser.newPage();desk.on('pageerror',e=>errors.push(e.message));
  await offlineFonts(desk);
  await desk.addInitScript(()=>sessionStorage.setItem('bcm-session',JSON.stringify({accessToken:'fixture',user:{id:'admin',fullName:'Admin',roles:['ADMIN']}})));
  let cancelled=false,draftError=false,cancellations=0;
  const draft={id:invoiceId,bookingId,customerName:'Booking contact',createdByName:'Cashier',createdAt:'2030-01-02T09:00:00',totalAmount:9000,items:[{productId:'product',productName:'Snapshot item',quantity:1,unitPrice:10000,discountAmount:1000,lineAmount:9000}]};
  await desk.route('**/api/**',route=>{
   const req=route.request(),url=new URL(req.url()),path=url.pathname.replace('/api','');const send=data=>route.fulfill({json:{success:true,data}});
   if(path==='/pos/drafts')return draftError?route.fulfill({status:500,json:{success:false,message:'Draft fixture failed'}}):send({content:cancelled?[]:[draft],totalElements:cancelled?0:1,totalPages:cancelled?0:1,page:0,size:20});
   if(path==='/pos/sales/'+invoiceId+'/cancel'){cancelled=true;cancellations++;return send({id:invoiceId,status:'CANCELLED'});}
   if(path==='/checkout/active-bookings')return send([{...summary,customerName:'Booking contact',customerPhone:'0987654321',courtSchedules:[{courtId:'court',courtNumber:1,courtName:'Fixture court',bookingDate:'2030-01-03',startTime:'06:00:00',endTime:'08:00:00'},{courtId:'court',courtNumber:1,courtName:'Fixture court',bookingDate:'2030-01-03',startTime:'10:00:00',endTime:'11:00:00'}]},{...summary,bookingId:'zero-amount',customerName:'Zero amount',finalAmount:0,courtSchedules:[]}]);
   return send([]);
  });
  await desk.goto(admin+'/?tab=pos',{waitUntil:'domcontentloaded'});
  const drafts=desk.getByRole('region',{name:'Đơn POS chờ kết toán'});
  await expect(drafts.getByText(invoiceId,{exact:true})).toBeVisible();
  await desk.reload({waitUntil:'domcontentloaded'});await expect(drafts.getByText(invoiceId,{exact:true})).toBeVisible();
  await drafts.getByText('Xem mặt hàng',{exact:true}).click();await expect(drafts).toContainText('Snapshot item');
  await drafts.getByRole('button',{name:'Hủy đơn nháp',exact:true}).click();
  await expect(desk.getByRole('dialog')).toContainText('9.000');
  await desk.getByRole('button',{name:'Xác nhận hủy đơn nháp',exact:true}).click();
  await expect(drafts).toContainText('Không có đơn POS nháp phù hợp.');assert.equal(cancellations,1);
  draftError=true;await desk.reload({waitUntil:'domcontentloaded'});await expect(drafts.getByRole('alert')).toContainText('Draft fixture failed');
  draftError=false;await drafts.getByRole('button',{name:'Thử lại',exact:true}).click();await expect(drafts).toContainText('Không có đơn POS nháp phù hợp.');
  await desk.goto(admin+'/?tab=checkout',{waitUntil:'domcontentloaded'});
  const row=desk.locator('tr').filter({hasText:'Booking contact'});
  await expect(row).toContainText('410.000');await expect(row).not.toContainText('440.000');
  await expect(row).toContainText('06:00–08:00');await expect(row).toContainText('10:00–11:00');
  await expect(desk.locator('tr').filter({hasText:'Zero amount'}).locator('td').nth(4)).toHaveText(/^0\s*₫$/);
  await desk.setViewportSize({width:390,height:844});assert.equal(await desk.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true);
  assert.deepEqual(errors,[]);
  console.log('PASS: persisted drafts/reload/cancel/error recovery; resume without duplicate booking and expiry guard; net amount/ranges; dynamic FAQ/fallback; mobile/no page errors');
 }finally{await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
