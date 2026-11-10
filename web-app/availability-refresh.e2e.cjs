// Frontend response-order regression. No real backend writes or timers releasing slots.
const {chromium,expect}=require('playwright/test');
const assert=require('node:assert/strict');
const base=process.env.BCM_E2E_WEB_URL||'http://127.0.0.1:5174';
(async()=>{
 const browser=await chromium.launch({channel:'msedge',headless:true});
 let release;
 try{
  const page=await browser.newPage();
  await page.clock.install({time:new Date('2031-02-20T05:00:00+07:00')});await page.clock.pauseAt(new Date('2031-02-20T05:00:00+07:00'));
  await page.addInitScript(()=>{sessionStorage.setItem('bcm.accessToken','fixture');sessionStorage.setItem('bcm.user',JSON.stringify({id:'fixture',roles:['CUSTOMER']}));sessionStorage.setItem('bcm-checkout-replacement','stale-old-booking');});
  const courts=[1,2,3,4,5,6].map(n=>({id:'c'+n,branchId:'branch',branchCourtNumber:n,courtNumber:n,name:'Venue',basePrice:100000,type:'STANDARD_MAT'}));
  const slots=[{id:'six',startTime:'06:00',endTime:'07:00'},{id:'seven',startTime:'07:00',endTime:'08:00'}];
  let held=false,pauseNext=false,captured=false,privateRequests=0;
  await page.route('**/api/**',async route=>{
   const url=new URL(route.request().url());assert.equal(route.request().method(),'GET');let data;
   if(url.pathname.endsWith('/public/booking-clock'))data={serverTime:'2031-02-20T05:00:00+07:00',today:'2031-02-20',timeZone:'Asia/Ho_Chi_Minh'};
   else if(url.pathname.endsWith('/public/courts'))data=courts;
   else if(url.pathname.endsWith('/public/time-slots'))data=slots;
   else if(url.pathname.endsWith('/bookings'))data={content:[],totalElements:0};
   else if(url.pathname.endsWith('/public/availability')){
    const snapshot=held;data={date:'2031-02-20',courts:courts.map(c=>({courtId:c.id,slots:slots.map(s=>({...s,timeSlotId:s.id,price:100000,available:!(snapshot&&c.id==='c1'&&s.id==='six')}))}))};
    if(pauseNext){pauseNext=false;captured=true;await new Promise(resolve=>release=resolve);}
   }else{privateRequests++;throw Error('Unexpected private/stale owner availability: '+url.pathname);}
   await route.fulfill({json:{success:true,data}});
  });
  await page.goto(base+'/courts?date=2031-02-20&timeSlotId=seven');
  const card=page.locator('[data-court-id=c1]'),hour=card.locator('.customer-extra-slots input[value=six]');
  await card.locator('.customer-branch-courts input[value=c1]').check();await expect(hour).toBeVisible();
  held=true;await page.evaluate(()=>window.dispatchEvent(new Event('focus')));await expect(hour).toHaveCount(0);
  pauseNext=true;await page.evaluate(()=>window.dispatchEvent(new Event('focus')));await expect.poll(()=>captured).toBe(true);
  held=false;await page.locator('.customer-search-button').click();await expect(hour).toBeVisible();
  release();release=null;await page.waitForTimeout(200);await expect(hour).toBeVisible();
  assert.equal(privateRequests,0);await expect(card.locator('.customer-branch-courts input[value=c1]')).toBeChecked();
  console.log('PASS stale owner storage is ignored; a delayed held response cannot overwrite newer released availability; selections survive refresh');
 }finally{if(release)release();await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
