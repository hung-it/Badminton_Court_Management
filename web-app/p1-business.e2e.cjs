// UI integration with deterministic API fixtures. No project database is contacted.
const {chromium,expect}=require(process.env.PLAYWRIGHT_MODULE||'playwright/test');
const assert=require('node:assert/strict');
const web=process.env.BCM_WEB_TEST_URL||'http://127.0.0.1:5184';
assert.equal(new URL(web).hostname,'127.0.0.1');
const day='2030-02-01',branchId='branch-p1',slotId='slot-p1';
const courts=[2,3].map(n=>({id:'court-'+n,branchId,branchCourtNumber:n,courtNumber:n,courtCode:100+n,
 name:'Cơ sở P1',district:'Quận 1',address:'Địa chỉ kiểm thử',type:'STANDARD_MAT',status:'AVAILABLE',basePrice:100000}));
const slot={id:slotId,timeSlotId:slotId,startTime:'10:00:00',endTime:'11:00:00',priceMultiplier:1,price:100000,available:true};
const user={id:'user-p1',fullName:'P1 Browser',roles:['CUSTOMER']};
(async()=>{
 const browser=await chromium.launch({channel:'msedge',headless:true});
 try {
  const page=await browser.newPage({viewport:{width:1280,height:900}}),errors=[];
  page.on('pageerror',e=>errors.push(e.message));
  await page.addInitScript(user=>{
   if(sessionStorage.getItem('p1-initialized'))return;
   sessionStorage.setItem('p1-initialized','true');
   sessionStorage.setItem('bcm.accessToken','fixture-access');
   sessionStorage.setItem('bcm.user',JSON.stringify(user));
   sessionStorage.setItem('bcm.refreshToken','legacy-refresh');
  },user);
  let submitted=null,failAuth=false,refreshCalls=0,catalog=courts;
  await page.route('**/api/**',async route=>{
   const request=route.request(),url=new URL(request.url()),path=url.pathname.replace(/^\/api/,'');
   const send=data=>route.fulfill({json:{success:true,data}});
   if(failAuth&&(path.startsWith('/bookings')||path==='/auth/refresh')){
    if(path==='/auth/refresh')refreshCalls++;
    return route.fulfill({status:401,json:{success:false,message:'Authentication failed'}});
   }
   if(path==='/public/booking-clock')return send({serverTime:'2030-01-01T00:00:00Z',today:'2030-01-01',timeZone:'Asia/Ho_Chi_Minh'});
   if(path==='/public/courts')return send(catalog);
   if(path==='/public/time-slots')return send([slot]);
   if(path==='/public/availability')return send({date:url.searchParams.get('date'),courts:catalog.map(c=>({courtId:c.id,slots:[slot]}))});
   if(path==='/bookings/customer-profile')return send({customerId:'customer-p1'});
   if(path==='/bookings'&&request.method()==='POST'){
    submitted=request.postDataJSON();return send({bookingId:'booking-p1',status:'PENDING',courtFee:100000,expiresAt:'2030-02-01T09:00:00'});
   }
   if(path==='/bookings')return send({content:[],totalElements:0,totalPages:0});
   if(path.endsWith('/contact'))return send({fullName:'P1 Browser',phone:'0912345678',email:'p1@example.test'});
   if(path.includes('/checkout/'))return send({bookingId:'booking-p1',status:'PENDING',expiresAt:'2030-02-01T09:00:00'});
   if(path.startsWith('/bookings/'))return send({bookingId:'booking-p1',status:'PENDING',courtFee:100000,finalAmount:100000,discountAmount:0,payments:[],
    details:[{bookingDate:day,courtId:'court-2',courtName:'Cơ sở P1',timeSlotId:slotId,...slot,price:100000}],expiresAt:'2030-02-01T09:00:00'});
   return send([]);
  });
  await page.goto(web+'/courts?date='+day+'&courtId=court-3');
  await expect(page.locator('.customer-court-card')).toHaveCount(1);
  const card=page.locator('.customer-court-card');
  await expect(card).toHaveAttribute('data-court-id','court-2');
  await card.locator('.customer-branch-courts input[value="court-2"]').check();
  await card.locator('.customer-extra-slots input[value="'+slotId+'"]').check();
  await card.getByRole('button',{name:'Đặt sân',exact:true}).click();
  await page.waitForURL('**/checkout');
  assert.equal(submitted.branchId,branchId);
  assert.deepEqual(submitted.details,[{courtId:'court-2',bookingDate:day,timeSlotId:slotId}]);
  catalog=[];
  await page.goto(web+'/courts?date='+day);
  await expect(page.locator('.customer-court-card')).toHaveCount(0);
  failAuth=true;
  await page.goto(web+'/my-bookings');
  await page.waitForURL('**/login?redirect=**');
  assert.equal(refreshCalls,1);
  assert.equal(await page.evaluate(()=>sessionStorage.getItem('bcm.accessToken')),null);
  assert.deepEqual(errors,[]);
  console.log('PASS: branch without court 1, remaining-court checkout, empty catalog, legacy-refresh sign-in redirect, no page errors');
 } finally {await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
