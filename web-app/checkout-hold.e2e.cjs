// Real browser + HTTP + PostgreSQL. Run only against the isolated checkout test runtime.
const {chromium, request, expect}=require('playwright/test');
const assert=require('node:assert/strict');
const crypto=require('node:crypto');
const base=process.env.BCM_E2E_WEB_URL||'http://127.0.0.1:5175';
(async()=>{
 const api=await request.newContext({baseURL:base});
 await expect.poll(async()=>{try{return (await api.get('/api/health')).status();}catch{return 0;}},{timeout:45000}).toBe(200);
 const registered=await api.post('/api/auth/register',{data:{email:`hold-${crypto.randomUUID()}@example.test`,password:crypto.randomBytes(18).toString('hex'),fullName:'Hold E2E',phone:'0900000000'}});
 assert.equal(registered.status(),201);const session=(await registered.json()).data, headers={Authorization:`Bearer ${session.accessToken}`};
 const get=async path=>{const r=await api.get('/api'+path,{headers});assert.equal(r.status(),200,await r.text());return (await r.json()).data;};
 const post=async(path,data)=>{const r=await api.post('/api'+path,{headers,data});assert.ok(r.ok(),`${r.status()} ${await r.text()}`);return (await r.json()).data;};
 const catalog=await get('/public/courts'),slots=await get('/public/time-slots');
 assert.equal(catalog.length,210);const siblings=catalog.filter(c=>c.branchId===catalog[0].branchId).sort((a,b)=>a.branchCourtNumber-b.branchCourtNumber);
 assert.equal(siblings.length,6);const selected=siblings.slice(0,2),hours=slots.slice(0,2);
 const date=`2038-${String(crypto.randomInt(1,13)).padStart(2,'0')}-${String(crypto.randomInt(1,28)).padStart(2,'0')}`;
 const {customerId}=await get('/bookings/customer-profile');
 const details=selected.flatMap(c=>hours.map(s=>({courtId:c.id,timeSlotId:s.id,bookingDate:date})));
 const held=async()=>{const grid=await get('/public/availability?date='+date);return details.every(d=>grid.courts.find(c=>c.courtId===d.courtId).slots.find(s=>s.timeSlotId===d.timeSlotId).available===false);};
 const browser=await chromium.launch({channel:'msedge',headless:true});
 try {
  const context=await browser.newContext({viewport:{width:1440,height:1000}});
  await context.addInitScript(s=>{sessionStorage.setItem('bcm.accessToken',s.accessToken);sessionStorage.setItem('bcm.user',JSON.stringify(s.user));},session);
  const page=await context.newPage(),errors=[];page.on('pageerror',e=>errors.push(e.message));
  let creates=0;page.on('request',r=>{if(r.method()==='POST'&&new URL(r.url()).pathname==='/api/bookings')creates++;});
  const pick=async(selection=hours)=>{
   const card=page.locator(`[data-court-id="${siblings[0].id}"]`);
   await expect(card.locator('.customer-branch-courts input:checked')).toHaveCount(0);
   for(const court of selected){await card.locator(`.customer-branch-courts input[value="${court.id}"]`).check();for(const slot of selection)await card.locator(`.customer-extra-slots input[value="${slot.id}"]`).check();}
   await card.getByRole('button',{name:'Đặt sân',exact:true}).click();
   await expect(page).toHaveURL(/\/checkout(?:\?|$)/);
   await expect(page.getByRole('button',{name:'Thanh toán',exact:true})).toBeEnabled();
   return page.evaluate(()=>JSON.parse(sessionStorage.getItem('bcm-booking-draft')).bookingId);
  };
  await page.goto(`${base}/courts?date=${date}&timeSlotId=${hours[0].id}`);
  const old=await pick();const original=await get('/bookings/'+old+'/checkout-clock');
  assert.ok(Date.parse(original.expiresAt)-Date.parse(original.serverTime)>590000);
  await page.locator('.checkout-back').click();await expect(page).toHaveURL(/\/courts/);
  assert.equal(await held(),true,'Other customers see every old slot held');
  const next=await pick(slots.slice(2,4));assert.notEqual(next,old);assert.equal(creates,2);
  const oldState=await get('/bookings/'+old);assert.equal(oldState.status,'PENDING');assert.equal(oldState.details.length,4);
  assert.equal((await get('/bookings/'+next)).details.length,4);assert.equal(await held(),true);
  console.log('PASS each courts click creates a fresh independent booking; new slots never trigger early release of the old four-slot hold; no default court selection');
  await page.goto(`${base}/checkout?bookingId=${next}`);
  await expect(page.getByRole('button',{name:'Thanh toán',exact:true})).toBeEnabled();
  await page.locator('.checkout-back').click();await expect(page).toHaveURL(/\/courts/);
  // Resume inside sixty seconds clears the schedule without changing the original deadline.
  await page.goto(`${base}/checkout?bookingId=${next}`);
  await expect(page.getByRole('button',{name:'Thanh toán',exact:true})).toBeEnabled();
  const originalNext=(await get('/bookings/'+next)).expiresAt;
  await page.locator('.checkout-back').click();await expect(page).toHaveURL(/\/courts/);
  const leave=await post(`/bookings/${next}/checkout/leave`);
  const remaining=Date.parse(leave.checkoutReleaseAt)-Date.parse((await get('/bookings/'+next+'/checkout-clock')).serverTime);
  assert.ok(remaining>55000&&remaining<=60000);assert.equal(await held(),true);
  assert.deepEqual(errors,[]);await context.close();
  const observer=await browser.newContext();const observerPage=await observer.newPage();
  await observerPage.goto(`${base}/courts?date=${date}&timeSlotId=${slots[2].id}`);
  const observerCard=observerPage.locator(`[data-court-id="${siblings[0].id}"]`);
  await observerCard.locator(`.customer-branch-courts input[value="${siblings[0].id}"]`).check();
  await expect(observerCard.locator(`.customer-extra-slots input[value="${hours[0].id}"]`)).toHaveCount(0);
  console.log('PASS resume and re-leave keep the original ten-minute deadline; browser closed while four slots remain held');
  // A second independent booking is resumed and must survive the first one's deadline.
  const resumeDate=date.slice(0,8)+'28';
  const resumed=await post('/bookings',{customerId,details:[{...details[0],bookingDate:resumeDate}]});
  await post(`/bookings/${resumed.bookingId}/checkout/leave`);await post(`/bookings/${resumed.bookingId}/checkout/resume`);
  // A confirmed booking is never affected by leave/expiry, including a pending declared payment.
  const paid=await post('/bookings',{customerId,details:[{...details[1],bookingDate:resumeDate}]});
  await post(`/bookings/${paid.bookingId}/payments`,{paymentMethod:'BANK_TRANSFER',bankTransfer:{bankName:'Test Bank',accountHolder:'Hold E2E',accountNumber:'00123456',transferContent:'Court test'}});
  const paidLeave=await post(`/bookings/${paid.bookingId}/checkout/leave`);assert.equal(paidLeave.status,'PAID');assert.equal(paidLeave.checkoutReleaseAt,null);
  await expect.poll(async()=> (await get('/bookings/'+next)).status,{timeout:65000,intervals:[1000]}).toBe('EXPIRED');
  assert.equal((await get('/bookings/'+next)).details.length,0);assert.equal(await held(),false);
  assert.equal((await get('/bookings/'+old)).status,'EXPIRED');
  const resumedState=await get('/bookings/'+resumed.bookingId);assert.equal(resumedState.status,'PENDING');
  // PostgreSQL TIMESTAMP rounds the creation response's nanoseconds to microseconds.
  assert.ok(Math.abs(Date.parse(resumedState.expiresAt)-Date.parse(resumed.expiresAt))<=1);
  const paidState=await get('/bookings/'+paid.bookingId);assert.equal(paidState.status,'PAID');assert.equal(paidState.details.length,1);assert.equal(paidState.payments.length,1);
  assert.equal((await get('/bookings/'+next)).expiresAt,originalNext);
  await expect(observerCard.locator(`.customer-extra-slots input[value="${hours[0].id}"]`)).toBeVisible({timeout:20000});
  console.log('PASS another customer sees slots reappear automatically on courts after backend releases them');
  console.log('PASS real sixty-second background release with browser closed; all four slots freed; resumed and paid bookings protected; deadlines/history preserved');
  // Reopen without a draft (new browser context) and ensure only expired information is shown.
  const reopened=await browser.newContext();await reopened.addInitScript(s=>{sessionStorage.setItem('bcm.accessToken',s.accessToken);sessionStorage.setItem('bcm.user',JSON.stringify(s.user));},session);
  const tab=await reopened.newPage();await tab.goto(`${base}/checkout?bookingId=${next}`);await expect(tab.locator('.checkout-expired')).toBeVisible();
  await expect(tab.getByRole('button',{name:'Thanh toán',exact:true})).toHaveCount(0);
  console.log('PASS released booking cannot pay when reopened in a fresh browser context');
 } finally {await browser.close();await api.dispose();}
})().catch(e=>{console.error(e);process.exitCode=1;});
