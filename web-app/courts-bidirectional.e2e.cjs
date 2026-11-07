const {chromium,expect}=require('playwright/test');
const assert=require('node:assert/strict');
const base=process.env.BCM_E2E_WEB_URL||'http://127.0.0.1:5174',date='2031-02-20';
const slots=[6,7,8,9,10].map(h=>({id:'h'+h,startTime:`${String(h).padStart(2,'0')}:00`,endTime:`${String(h+1).padStart(2,'0')}:00`}));
const courts=['a','b'].flatMap(branch=>[1,2,3,4,5,6].map(n=>({id:branch+n,branchId:branch,branchCourtNumber:n,courtNumber:n,name:'Venue '+branch,basePrice:100000,type:'STANDARD_MAT'})));
const free={a1:[6,7],a2:[7,8],a3:[6,7],a4:[8],a5:[],a6:[9]};
(async()=>{
 const browser=await chromium.launch({channel:'msedge',headless:true});
 try{
  const page=await browser.newPage({viewport:{width:1440,height:1100}}),errors=[];page.on('pageerror',e=>errors.push(e.message));
  await page.clock.install({time:new Date(date+'T05:00:00+07:00')});await page.clock.pauseAt(new Date(date+'T05:00:00+07:00'));
  await page.addInitScript(()=>{if(location.protocol==='http:'){sessionStorage.setItem('bcm.accessToken','fixture');sessionStorage.setItem('bcm.user',JSON.stringify({id:'fixture',roles:['CUSTOMER']}));}});
  let held=false,allSixHeld=false,payload=null;
  await page.route('**/api/**',async route=>{
   const url=new URL(route.request().url());let data;
   if(route.request().method()==='POST'&&url.pathname.endsWith('/bookings')){payload=route.request().postDataJSON();await route.fulfill({status:201,json:{success:true,data:{bookingId:'new-bidirectional-booking'}}});return;}
   assert.equal(route.request().method(),'GET');
   if(url.pathname.endsWith('/public/booking-clock'))data={serverTime:date+'T05:00:00+07:00',today:date,timeZone:'Asia/Ho_Chi_Minh'};
   else if(url.pathname.endsWith('/public/courts'))data=courts;
   else if(url.pathname.endsWith('/public/time-slots'))data=slots;
   else if(url.pathname.endsWith('/public/availability'))data={date,courts:courts.map(c=>({courtId:c.id,slots:slots.map(s=>({...s,timeSlotId:s.id,price:100000,available:(c.branchId==='b'||free[c.id].includes(Number(s.id.slice(1))))&&!(held&&c.id==='a1'&&s.id==='h6')&&!(allSixHeld&&c.branchId==='a'&&s.id==='h6')}))}))};
   else if(url.pathname.endsWith('/bookings/customer-profile'))data={customerId:'fixture-customer'};
   else if(url.pathname.endsWith('/bookings'))data={content:[],totalElements:0};
   else throw Error('Unexpected request '+url.pathname);
   await route.fulfill({json:{success:true,data}});
  });
  await page.route('**/checkout',r=>r.fulfill({contentType:'text/html',body:'Checkout handoff'}));
  const card=page.locator('[data-court-id=a1]'),other=page.locator('[data-court-id=b1]');
  const court=n=>card.locator(`.customer-branch-courts input[value=a${n}]`),hour=h=>card.locator(`.customer-extra-slots input[value=h${h}]`),book=card.getByRole('button',{name:'Đặt sân',exact:true});
  const courtIds=()=>card.locator('.customer-branch-courts input').evaluateAll(nodes=>nodes.map(n=>n.value));
  const hourIds=()=>card.locator('.customer-extra-slots input').evaluateAll(nodes=>nodes.map(n=>n.value));
  const refresh=()=>page.evaluate(()=>window.dispatchEvent(new Event('focus')));
  const clean=async()=>{
   assert.doesNotMatch(await card.innerText(),/Trống:|Bận:|Giờ đang lọc:|Chọn giờ để lọc sân|Chọn sân trước khi chọn giờ/);
   await expect(card.locator('.customer-branch-context,.customer-active-court,.customer-branch-instruction')).toHaveCount(0);
   for(const item of await card.locator('.customer-branch-courts label').allTextContents())assert.match(item.trim(),/^Sân 0[1-6]$/);
  };
  await page.goto(base+'/courts?date='+date);await expect(page.locator('.customer-court-card')).toHaveCount(2);
  await expect(page.getByLabel('Khung giờ',{exact:true})).toHaveValue('');
  assert.deepEqual(await courtIds(),['a1','a2','a3','a4','a6']);assert.deepEqual(await hourIds(),['h6','h7','h8','h9']);
  await expect(page.locator('.customer-branch-courts input:checked')).toHaveCount(0);await expect(page.locator('.customer-extra-slots input:checked')).toHaveCount(0);await clean();
  await hour(6).check();assert.deepEqual(await courtIds(),['a1','a3']);await expect(book).toBeDisabled();
  await hour(7).check();assert.deepEqual(await courtIds(),['a1','a3']);
  await hour(8).check();await expect(card.locator('.customer-branch-courts input')).toHaveCount(0);
  await hour(8).uncheck();await court(1).check();await expect(hour(6)).toBeChecked();await expect(hour(7)).toBeChecked();
  await court(3).check();assert.deepEqual(await hourIds(),['h6','h7']);await expect(book).toBeEnabled();await clean();
  await court(1).uncheck();await court(3).uncheck();assert.deepEqual(await hourIds(),['h6','h7','h8','h9']);
  await hour(6).uncheck();await hour(7).uncheck();assert.deepEqual(await courtIds(),['a1','a2','a3','a4','a6']);
  await expect(card.locator('input:checked')).toHaveCount(0);
  console.log('PASS plain original checkbox lists; initial union/available courts; hour-first AND filtering; no auto-selection; clearing restores aggregate lists');
  await court(1).check();assert.deepEqual(await hourIds(),['h6','h7']);await expect(card.locator('.customer-extra-slots input:checked')).toHaveCount(0);
  await court(2).check();assert.deepEqual(await hourIds(),['h7']);await expect(card.locator('.customer-extra-slots input:checked')).toHaveCount(0);
  await hour(7).check();assert.deepEqual(await courtIds(),['a1','a2','a3']);await court(3).check();assert.deepEqual(await hourIds(),['h7']);
  await court(1).uncheck();await court(2).uncheck();assert.deepEqual(await hourIds(),['h6','h7']);
  await court(3).uncheck();assert.deepEqual(await hourIds(),['h6','h7','h8','h9']);await hour(7).uncheck();await clean();
  console.log('PASS court-first filtering, intersection for multiple courts, no automatically checked hours, deselection in either direction');
  await hour(6).check();await court(1).check();
  held=true;await refresh();await expect(court(1)).toHaveCount(0);await expect(card.locator('.customer-branch-courts input:checked')).toHaveCount(0);await expect(hour(6)).toBeChecked();await expect(book).toBeDisabled();
  held=false;await refresh();await expect(court(1)).not.toBeChecked();await court(1).check();await expect(hour(6)).toBeChecked();
  await other.locator('.customer-extra-slots input[value=h7]').check();await other.locator('.customer-branch-courts input[value=b1]').check();
  await refresh();await expect(court(1)).toBeChecked();await expect(hour(6)).toBeChecked();await expect(other.locator('.customer-extra-slots input[value=h7]')).toBeChecked();
  await court(1).uncheck();allSixHeld=true;await refresh();await expect(hour(6)).toHaveCount(0);
  allSixHeld=false;await refresh();await expect(hour(6)).not.toBeChecked();await hour(6).check();await court(1).check();await court(3).check();await clean();
  console.log('PASS backend refresh unchecks invalid courts/hours; release never silently reselects; other branches and valid choices survive');
  for(const width of [1440,820,375,320]){await page.setViewportSize({width,height:1100});assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false,`overflow at ${width}`);}
  await book.click();await expect(page).toHaveURL(base+'/checkout');
  assert.deepEqual(payload.details,[{courtId:'a1',timeSlotId:'h6',bookingDate:date},{courtId:'a3',timeSlotId:'h6',bookingDate:date}]);
  assert.equal(new Set(payload.details.map(d=>d.courtId+':'+d.timeSlotId)).size,2);assert.deepEqual(errors,[]);
  console.log('PASS unchanged checkout payload with exact valid pairs, retained prices, desktop/mobile and no browser errors');
 }finally{await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
