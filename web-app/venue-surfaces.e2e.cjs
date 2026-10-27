// Writes only to the isolated PostgreSQL audit backend.
const {chromium, request, expect}=require('playwright/test');
const assert=require('node:assert/strict');
const crypto=require('node:crypto');
const web='http://127.0.0.1:5174', audit=process.env.BCM_E2E_API_URL;
assert.equal(new URL(audit).port,'8088');
(async()=>{
 const api=await request.newContext({baseURL:audit});
 const get=async(path,headers)=>{const r=await api.get('/api'+path,{headers});assert.equal(r.status(),200);return(await r.json()).data;};
 const catalog=await get('/public/courts'),branches=await get('/public/branches'),clock=await get('/public/booking-clock'),slots=await get('/public/time-slots');
 assert.equal(catalog.length,210);assert.equal(branches.length,35);
 for(const b of branches){assert.equal(b.courtCount,6);assert.equal(catalog.filter(c=>c.branchId===b.id).length,6);}
 const venue=catalog.find(c=>(c.courtCode??c.courtNumber)===5),siblings=catalog.filter(c=>c.branchId===venue.branchId).sort((a,b)=>a.branchCourtNumber-b.branchCourtNumber);
 const other=catalog.find(c=>c.branchCourtNumber===1&&c.branchId!==venue.branchId);
 const date=new Date(clock.today+'T00:00:00Z');date.setUTCDate(date.getUTCDate()+30+crypto.randomInt(900));const day=date.toISOString().slice(0,10);
 const normal=slots.find(s=>s.startTime.startsWith('12:00')).id,peak=slots.find(s=>s.startTime.startsWith('17:00')).id;
 const reg=await api.post('/api/auth/register',{data:{email:crypto.randomUUID()+'@example.test',password:crypto.randomBytes(18).toString('hex'),fullName:'Venue surfaces E2E',phone:'0900000000'}});
 assert.equal(reg.status(),201);const session=(await reg.json()).data,headers={Authorization:'Bearer '+session.accessToken};
 const profile=await get('/bookings/customer-profile',headers);
 const browser=await chromium.launch({channel:'msedge',headless:true});
 try{
  const page=await browser.newPage({viewport:{width:1440,height:1000}}),errors=[];page.on('pageerror',e=>errors.push(e.message));
  await page.addInitScript(s=>{sessionStorage.setItem('bcm.accessToken',s.accessToken);sessionStorage.setItem('bcm.user',JSON.stringify(s.user));},session);
  await page.route('**/api/**',async route=>{const u=new URL(route.request().url());await route.fulfill({response:await route.fetch({url:new URL(u.pathname+u.search,audit).href})});});
  await page.goto(web+'/courts?date='+day+'&timeSlotId='+normal);
  const card=page.locator('[data-court-id="'+venue.id+'"]'),otherCard=page.locator('[data-court-id="'+other.id+'"]');
  const hour=id=>card.locator('.customer-extra-slots input[value="'+id+'"]');
  const surface=n=>card.locator('.customer-branch-courts input[value="'+siblings[n-1].id+'"]');
  const focus=n=>card.locator('[data-focus-court-id="'+siblings[n-1].id+'"]');
  const book=card.getByRole('button',{name:'Đặt sân',exact:true});
  await expect(page.locator('.customer-court-card')).toHaveCount(35);
  for(const c of await page.locator('.customer-court-card').all()){
   await expect(c.locator('.customer-branch-courts input')).toHaveCount(6);
   assert.ok(await c.evaluate(el=>el.querySelector('.customer-extra-slots').compareDocumentPosition(el.querySelector('.customer-branch-courts'))&Node.DOCUMENT_POSITION_FOLLOWING));
  }
  await surface(1).check();await expect(book).toBeDisabled();await hour(normal).check();await hour(peak).check();
  await surface(2).check();await expect(book).toBeDisabled();await expect(hour(normal)).not.toBeChecked();await hour(peak).check();
  await surface(3).check();await hour(normal).check();
  await focus(1).click();await expect(hour(normal)).toBeChecked();await expect(hour(peak)).toBeChecked();
  await focus(2).click();await expect(hour(normal)).not.toBeChecked();await expect(hour(peak)).toBeChecked();
  await page.getByLabel('Sắp xếp sân').selectOption('price-desc');await expect(surface(3)).toBeChecked();
  for(const width of [1440,820,375,320]){await page.setViewportSize({width,height:1000});assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false);}
  await book.click();await expect(page.getByRole('button',{name:'Thanh toán',exact:true})).toBeEnabled();
  const draft=await page.evaluate(()=>JSON.parse(sessionStorage.getItem('bcm-booking-draft'))),booking=await get('/bookings/'+draft.bookingId,headers);
  assert.equal(booking.details.length,4);assert.equal(new Set(booking.details.map(d=>d.courtId)).size,3);
  assert.equal(Number(booking.courtFee),Number(venue.basePrice)*5);
  assert.ok(Math.abs((Date.parse(booking.expiresAt)-Date.parse(booking.createdAt))/1000-600)<2);
  await page.locator('input[value="BANK_TRANSFER"]').check();
  for(const [label,value] of [['Ngân hàng','Test'],['Tên chủ tài khoản','Surface E2E'],['Số tài khoản','123456'],['Nội dung chuyển khoản','Three courts']])await page.getByLabel(label,{exact:true}).fill(value);
  await page.getByRole('button',{name:'Thanh toán',exact:true}).click();await expect(page).toHaveURL(/payment-result/);
  const paid=await get('/bookings/'+draft.bookingId,headers);assert.equal(paid.status,'PAID');assert.equal(paid.payments.length,1);
  await page.goto(web+'/courts?date='+day+'&timeSlotId='+normal);
  await expect(surface(1)).toBeDisabled();await expect(hour(normal)).toHaveCount(0);await surface(4).check();await hour(normal).check();
  await otherCard.locator('.customer-branch-courts input').first().check();await expect(surface(4)).not.toBeChecked();
  const bad=await api.post('/api/bookings',{headers,data:{customerId:profile.customerId,branchId:venue.branchId,details:[{courtId:siblings[4].id,timeSlotId:peak,bookingDate:day},{courtId:other.id,timeSlotId:peak,bookingDate:day}]}});assert.equal(bad.status(),400);
  assert.deepEqual(errors,[]);
  console.log('PASS 35 cards / six API-backed court checkboxes below hours; independent hours and focus; incomplete court blocked; three courts / four slots / correct peak pricing / ten-minute hold / one payment; booked slot hidden; switching venue clears previous; cross-branch rejected; desktop/mobile');
 }finally{await browser.close();await api.dispose();}
})().catch(e=>{console.error(e);process.exitCode=1;});
