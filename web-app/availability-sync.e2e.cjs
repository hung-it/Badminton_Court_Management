// Real two-customer browsers, HTTP/JWT and PostgreSQL; dedicated E2E database only.
const {chromium,request,expect}=require('playwright/test');
const {execFileSync}=require('node:child_process');
const assert=require('node:assert/strict'),crypto=require('node:crypto');
const base=process.env.BCM_E2E_WEB_URL||'http://127.0.0.1:5175';
const database=process.env.BCM_E2E_DB||'bcm_availability_sync_e2e_20261025';
assert.match(database,/^bcm_availability_sync_e2e_\d{8}$/,'Never run fixture SQL on the application database');
const sql=query=>execFileSync('docker',['exec','bcm-checkout-tests-20261025','psql','-U','bcm_test','-d',database,'-v','ON_ERROR_STOP=1','-tAc',query],{encoding:'utf8'}).trim();
const snapshot=id=>JSON.parse(sql(`SELECT json_build_object('status',b.status,'expiresAt',b.expires_at,'releaseAt',b.checkout_release_at,'details',(SELECT count(*) FROM booking_details d WHERE d.booking_id=b.id),'payments',(SELECT count(*) FROM payment_transactions p WHERE p.booking_id=b.id)) FROM bookings b WHERE b.id='${id}'`));
const sleep=ms=>new Promise(resolve=>setTimeout(resolve,ms)); // Test observation, never a release mechanism.
(async()=>{
 const api=await request.newContext({baseURL:base});
 await expect.poll(async()=>{try{return(await api.get('/api/health')).status();}catch{return 0;}},{timeout:45000}).toBe(200);
 const register=async()=>{const r=await api.post('/api/auth/register',{data:{email:`sync-${crypto.randomUUID()}@example.test`,password:crypto.randomBytes(18).toString('hex'),fullName:'Availability Sync',phone:'0900000000'}});assert.equal(r.status(),201);const s=(await r.json()).data;s.headers={Authorization:`Bearer ${s.accessToken}`};s.customerId=(await(await api.get('/api/bookings/customer-profile',{headers:s.headers})).json()).data.customerId;return s;};
 const a=await register(),b=await register();
 const get=async(path,actor=a)=>{const r=await api.get('/api'+path,{headers:actor?.headers});assert.equal(r.status(),200,await r.text());return(await r.json()).data;};
 const post=async(path,data,actor=a)=>{const r=await api.post('/api'+path,{headers:actor.headers,data});assert.ok(r.ok(),`${r.status()} ${await r.text()}`);return(await r.json()).data;};
 const catalog=await get('/public/courts'),branches=await get('/public/branches'),slots=await get('/public/time-slots');
 // Other regression fixtures may add small audit branches; use the 35 seeded six-court branches.
 const completeBranches=new Set(branches.filter(branch=>catalog.filter(c=>c.branchId===branch.id).length===6).map(branch=>branch.id));
 const branchCourts=catalog.filter(c=>completeBranches.has(c.branchId));assert.equal(branchCourts.length,210);assert.equal(completeBranches.size,35);
 const courts=branchCourts.filter(c=>c.branchId===branchCourts[0].branchId).sort((x,y)=>x.branchCourtNumber-y.branchCourtNumber);assert.equal(courts.length,6);
 const hour=n=>slots.find(s=>s.startTime.startsWith(String(n).padStart(2,'0')+':'));
 const seed=new Date('2045-01-01T00:00:00Z');seed.setUTCDate(seed.getUTCDate()+crypto.randomInt(10000));
 const day=n=>new Date(seed.getTime()+n*86400000).toISOString().slice(0,10);
 const tuple=(n,h,c=0)=>({bookingDate:day(n),courtId:courts[c].id,timeSlotId:hour(h).id});
 const create=async(details,actor=a,extra={})=>post('/bookings',{customerId:actor.customerId,details,...extra},actor);
 const occupancy=async(detail,actor)=>{const grid=await get('/public/availability?date='+detail.bookingDate,actor);return grid.courts.find(c=>c.courtId===detail.courtId).slots.find(s=>s.timeSlotId===detail.timeSlotId);};
 const assertSame=async(detail,available,state)=>{
  const values=await Promise.all([occupancy(detail,a),occupancy(detail,b),occupancy(detail,null)]);
  for(const value of values){assert.equal(value.available,available);assert.equal(value.occupancy,state);}
 };
 const browser=await chromium.launch({channel:'msedge',headless:true});
 const errors=[];
 const context=async(engine,actor)=>{const c=await engine.newContext({viewport:{width:1440,height:1000}});await c.addInitScript(({session,origin})=>{if(location.origin!==origin)return;sessionStorage.setItem('bcm.accessToken',session.accessToken);sessionStorage.setItem('bcm.user',JSON.stringify(session.user));},{session:actor,origin:new URL(base).origin});c.on('page',p=>p.on('pageerror',e=>errors.push(e.message)));return c;};
 const card=p=>p.locator(`[data-court-id="${courts[0].id}"]`);
 const selectCourt=async p=>{const box=card(p).locator(`.customer-branch-courts input[value="${courts[0].id}"]`);await box.check();};
 const chip=(p,h)=>card(p).locator(`.customer-extra-slots input[value="${hour(h).id}"]`);
 const checkout=async(p,id)=>{await p.goto(`${base}/checkout?bookingId=${id}`);await expect(p.getByRole('button',{name:'Thanh toán',exact:true})).toBeEnabled();};
 const browse=async(p,n)=>{await p.goto(`${base}/courts?date=${day(n)}&timeSlotId=${hour(7).id}`);await expect(card(p)).toBeVisible();await selectCourt(p);};
 const bookHour=async(p,h)=>{
  await chip(p,h).check();const sent=p.waitForRequest(r=>r.method()==='POST'&&new URL(r.url()).pathname==='/api/bookings');
  const response=p.waitForResponse(r=>r.request().method()==='POST'&&new URL(r.url()).pathname==='/api/bookings').then(r=>r.status());
  await card(p).getByRole('button',{name:'Đặt sân',exact:true}).click();assert.equal(await response,201);assert.equal((await sent).postDataJSON().replacesBookingId,undefined);
  await expect(p).toHaveURL(/\/checkout(?:\?|$)/);await expect(p.getByRole('button',{name:'Thanh toán',exact:true})).toBeEnabled();
  const id=await p.evaluate(()=>JSON.parse(sessionStorage.getItem('bcm-booking-draft')).bookingId);return get('/bookings/'+id);
 };
 try{
  const ca=await context(browser,a),cb=await context(browser,b),pa=await ca.newPage(),pb=await cb.newPage();
  const details=[tuple(0,6),tuple(0,8),tuple(0,6,1),tuple(0,8,1)],old=await create(details);
  await checkout(pa,old.bookingId);await browse(pb,0);await expect(chip(pb,6)).toHaveCount(0);await assertSame(details[0],false,'HELD');
  console.log('PASS KH1 holds four slots across two courts; KH2 and anonymous API see HELD, no owner privilege');
  const holdBeforeLeave=65000;
  console.log(`WAIT holding the original checkout for ${holdBeforeLeave/1000} real seconds before clicking back`);
  await sleep(holdBeforeLeave);assert.equal(snapshot(old.bookingId).releaseAt,null);
  const before=await get(`/bookings/${old.bookingId}/checkout-clock`);
  const leaveRequest=pa.waitForRequest(r=>r.method()==='POST'&&r.url().endsWith(`/bookings/${old.bookingId}/checkout/leave`));
  await pa.locator('.checkout-back').click();await leaveRequest;await expect(pa).toHaveURL(/\/courts/);
  const original=snapshot(old.bookingId);assert.equal(original.status,'PENDING');assert.equal(original.details,4);
  assert.ok(Date.parse(original.releaseAt)-Date.parse(before.serverTime)>=59000);assert.ok(Date.parse(original.releaseAt)-Date.parse(before.serverTime)<=62000);
  await selectCourt(pa);await expect(chip(pa,6)).toHaveCount(0);await expect(chip(pb,6)).toHaveCount(0);
  // Refresh and a stale legacy owner marker cannot manufacture free slots or renew the schedule.
  await pa.evaluate(id=>sessionStorage.setItem('bcm-checkout-replacement',id),old.bookingId);await pa.reload();await selectCourt(pa);
  await expect(chip(pa,6)).toHaveCount(0);assert.equal(snapshot(old.bookingId).releaseAt,original.releaseAt);
  const duplicate=await api.post('/api/bookings',{headers:b.headers,data:{customerId:b.customerId,details}});assert.equal(duplicate.status(),409);
  assert.equal(snapshot(old.bookingId).details,4);await assertSame(details[0],false,'HELD');
  let laterWrites=0;pa.on('request',r=>{if(r.method()!=='GET'&&new URL(r.url()).pathname.startsWith('/api/bookings'))laterWrites++;});
  console.log(`PASS leave after ${holdBeforeLeave/1000} seconds schedules a full new 60 seconds; refresh/stale storage preserve deadline; KH2 duplicate rejected with 409`);
  // Create another booking before its old hold's deadline; exercise the real courts button.
  const oldB=await create([tuple(2,6)]);await post(`/bookings/${oldB.bookingId}/checkout/leave`);const originalB=snapshot(oldB.bookingId);
  const pageB=await ca.newPage();await pageB.goto(`${base}/courts?date=${day(2)}&timeSlotId=${hour(7).id}`);
  await expect(card(pageB)).toBeVisible();await chip(pageB,7).check();
  await expect(card(pageB).locator('.customer-branch-courts input:checked')).toHaveCount(0);
  await selectCourt(pageB);await expect(chip(pageB,7)).toBeChecked();await expect(chip(pageB,6)).toHaveCount(0);const nextB=await bookHour(pageB,7);
  assert.notEqual(nextB.bookingId,oldB.bookingId);assert.deepEqual(snapshot(oldB.bookingId),originalB);
  const nextClock=await get(`/bookings/${nextB.bookingId}/checkout-clock`);assert.ok(Date.parse(nextClock.expiresAt)-Date.parse(nextClock.serverTime)>590000);
  // Actual signed test IPN confirms payment, without contacting the provider.
  const attempt=await post(`/bookings/${nextB.bookingId}/payments`,{paymentMethod:'VNPAY'});
  const fields={vnp_TmnCode:'BCMTEST1',vnp_TxnRef:attempt.paymentAttemptId.replaceAll('-',''),vnp_Amount:String(Number(attempt.amount)*100),vnp_BankCode:'NCB',vnp_OrderInfo:'Availability sync',vnp_ResponseCode:'00',vnp_TransactionStatus:'00',vnp_TransactionNo:String(Date.now()),vnp_PayDate:'20261025180000'};
  const ordered=Object.fromEntries(Object.entries(fields).sort(([x],[y])=>x.localeCompare(y)));
  fields.vnp_SecureHash=crypto.createHmac('sha512','promotion-e2e-test-only').update(new URLSearchParams(ordered).toString()).digest('hex');
  const ipn=await api.get('/api/payments/vnpay/ipn',{params:fields});assert.equal((await ipn.json()).RspCode,'00');
  await post(`/bookings/${nextB.bookingId}/checkout/leave`);assert.equal(snapshot(nextB.bookingId).status,'PAID');await assertSame(tuple(2,7),false,'BOOKED');
  console.log('PASS new 07:00 booking has a fresh ten-minute hold and leaves old 06:00 deadline/details untouched; signed SUCCESS payment protected');
  // Entire second browser closes; its independent multi-slot schedule must survive it.
  const closed=await create([tuple(3,6),tuple(3,8),tuple(3,6,1),tuple(3,8,1)]),otherBrowser=await chromium.launch({channel:'msedge',headless:true});
  try{const cc=await context(otherBrowser,a),p=await cc.newPage();await checkout(p,closed.bookingId);await p.locator('.checkout-back').click();await expect(p).toHaveURL(/\/courts/);}finally{await otherBrowser.close();}
  const resumed=await create([tuple(4,6)]);await post(`/bookings/${resumed.bookingId}/checkout/leave`);const pr=await ca.newPage();await checkout(pr,resumed.bookingId);await pr.reload();await expect(pr.getByRole('button',{name:'Thanh toán',exact:true})).toBeEnabled();assert.equal(snapshot(resumed.bookingId).releaseAt,null);
  const earlier=await create([tuple(5,6)]);sql(`UPDATE bookings SET expires_at=localtimestamp+interval '5 seconds' WHERE id='${earlier.bookingId}' AND customer_id='${a.customerId}' AND status='PENDING'`);
  const earlyLeave=await post(`/bookings/${earlier.bookingId}/checkout/leave`);assert.equal(earlyLeave.checkoutReleaseAt,earlyLeave.expiresAt);
  await expect.poll(()=>snapshot(earlier.bookingId).status,{timeout:10000,intervals:[1000]}).toBe('EXPIRED');await assertSame(tuple(5,6),true,'AVAILABLE');
  await expect.poll(()=>snapshot(old.bookingId).status,{timeout:65000,intervals:[1000]}).toBe('EXPIRED');
  await expect(chip(pa,6)).toBeVisible({timeout:8000});await expect(chip(pb,6)).toBeVisible({timeout:8000});
  assert.equal(laterWrites,0,'Old hold expires without any further booking write from its courts page');
  assert.equal(snapshot(old.bookingId).details,0);assert.equal(snapshot(old.bookingId).expiresAt,original.expiresAt);await assertSame(details[0],true,'AVAILABLE');
  console.log('PASS no further action: both KH1/KH2 automatically see free slots; PostgreSQL EXPIRED/zero details agrees with both authenticated and public availability');
  const nextA=await bookHour(pa,7);assert.notEqual(nextA.bookingId,old.bookingId);assert.equal(snapshot(old.bookingId).status,'EXPIRED');
  await expect.poll(()=>snapshot(closed.bookingId).status,{timeout:65000,intervals:[1000]}).toBe('EXPIRED');assert.equal(snapshot(closed.bookingId).details,0);
  await expect.poll(()=>snapshot(oldB.bookingId).status,{timeout:10000,intervals:[1000]}).toBe('EXPIRED');
  assert.equal(snapshot(nextB.bookingId).status,'PAID');assert.equal(snapshot(nextB.bookingId).details,1);assert.equal(snapshot(nextB.bookingId).payments,1);
  assert.equal(snapshot(resumed.bookingId).status,'PENDING');assert.equal(snapshot(resumed.bookingId).releaseAt,null);
  console.log('PASS booking again after release, whole-browser closure, multi-court release, earlier original expiry, resume/refresh and paid reservation survival');
  const racing=[tuple(6,6)],countBefore=Number(sql('SELECT count(*) FROM bookings'));
  let go;const gate=new Promise(resolve=>go=resolve);
  const race=actor=>(async()=>{await gate;return api.post('/api/bookings',{headers:actor.headers,data:{customerId:actor.customerId,details:racing}});})();
  const one=race(a),two=race(b);go();const results=await Promise.all([one,two]);assert.deepEqual(results.map(r=>r.status()).sort(),[201,409]);
  assert.equal(Number(sql('SELECT count(*) FROM bookings')),countBefore+1);
  assert.equal(Number(sql(`SELECT count(*) FROM booking_details WHERE booking_date='${day(6)}' AND court_id='${courts[0].id}' AND time_slot_id='${hour(6).id}'`)),1);
  await assertSame(racing[0],false,'HELD');assert.deepEqual(errors,[]);
  console.log('PASS two distinct authenticated customers start together: exactly one 201, one 409, one persisted slot, no orphan booking or browser errors');
 }finally{await browser.close();await api.dispose();}
})().catch(e=>{console.error(e);process.exitCode=1;});
