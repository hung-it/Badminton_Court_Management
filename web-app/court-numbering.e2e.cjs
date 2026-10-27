// Real normalized API and PostgreSQL; run only against the dedicated audit frontend.
const {chromium,request,expect}=require('playwright/test');
const assert=require('node:assert/strict'),crypto=require('node:crypto');
const web='http://127.0.0.1:5175';
(async()=>{
 const api=await request.newContext({baseURL:web});
 const registered=await api.post('/api/auth/register',{data:{email:crypto.randomUUID()+'@example.test',password:crypto.randomBytes(18).toString('hex'),fullName:'Court number E2E',phone:'0900000000'}});
 assert.equal(registered.status(),201);const session=(await registered.json()).data,headers={Authorization:'Bearer '+session.accessToken};
 const get=async path=>{const r=await api.get('/api'+path,{headers});assert.equal(r.status(),200);return(await r.json()).data;};
 const catalog=await get('/public/courts'),branches=await get('/public/branches'),slots=await get('/public/time-slots');
 assert.equal(catalog.length,210);assert.equal(branches.length,35);assert.equal(new Set(catalog.map(c=>c.courtCode)).size,210);
 for(const branch of branches){const group=catalog.filter(c=>c.branchId===branch.id);assert.deepEqual(group.map(c=>c.courtNumber).sort((a,b)=>a-b),[1,2,3,4,5,6]);assert.ok(group.every(c=>c.branchCourtNumber===c.courtNumber));}
 const selected=catalog.find(c=>c.courtCode===1207043);assert.ok(selected);assert.equal(selected.courtNumber,3);
 const siblings=catalog.filter(c=>c.branchId===selected.branchId),representative=siblings.find(c=>c.courtNumber===1),four=siblings.find(c=>c.courtNumber===4);
 const at=hour=>slots.find(s=>Number(s.startTime.slice(0,2))===hour);
 const d=new Date('2042-01-01T00:00:00Z');d.setUTCDate(d.getUTCDate()+crypto.randomInt(3000));const day=d.toISOString().slice(0,10);
 const browser=await chromium.launch({channel:'msedge',headless:true});
 try{
  const page=await browser.newPage({viewport:{width:1440,height:1100}}),errors=[];page.on('pageerror',e=>errors.push(e.message));
  await page.addInitScript(s=>{sessionStorage.setItem('bcm.accessToken',s.accessToken);sessionStorage.setItem('bcm.user',JSON.stringify(s.user));},session);
  await page.goto(web+'/courts?date='+day);
  const card=page.locator(`[data-court-id="${representative.id}"]`),choice=id=>card.locator(`.customer-branch-courts input[value="${id}"]`),hour=n=>card.locator(`.customer-extra-slots input[value="${at(n).id}"]`);
  await expect(card.locator('.customer-branch-courts input:checked')).toHaveCount(0);
  await choice(selected.id).check();await expect(card.locator('.customer-court-type')).toContainText('Sân 03');
  await expect(card.locator('.customer-court-type')).not.toContainText('1207043');await expect(card.locator('.customer-court-type')).not.toContainText('Mã sân');
  await hour(7).check();await hour(8).check();await page.getByLabel('Sắp xếp sân').selectOption('price-desc');await expect(choice(selected.id)).toBeChecked();
  await choice(four.id).check();await hour(8).check();
  const photo=await card.locator('.customer-court-photo').getAttribute('src');
  await card.getByRole('button',{name:'Đặt sân',exact:true}).click();await expect(page).toHaveURL(/checkout/);
  await expect(page.getByRole('button',{name:'Thanh toán',exact:true})).toBeEnabled();
  await expect(page.locator('.checkout-booking-facts')).toContainText('Sân 03, Sân 04');
  await expect(page.locator('.checkout-booking-facts')).not.toContainText('1207043');
  await expect(page.locator('.checkout-court-photo')).toHaveAttribute('src',photo);
  const id=new URL(page.url()).searchParams.get('bookingId');let booking=await get('/bookings/'+id);
  assert.equal(booking.details.length,3);assert.deepEqual([...new Set(booking.details.map(d=>d.courtId))].sort(),[selected.id,four.id].sort());
  assert.ok(booking.details.every(d=>d.courtNumber===catalog.find(c=>c.id===d.courtId).courtNumber));
  assert.ok(booking.details.every(d=>d.courtCode===catalog.find(c=>c.id===d.courtId).courtCode));
  assert.equal(Number(booking.courtFee),booking.details.reduce((sum,d)=>sum+Number(d.price),0));
  await page.reload();await expect(page.locator('.checkout-booking-facts')).toContainText('Sân 03, Sân 04');
  await page.locator('input[value=BANK_TRANSFER]').check();
  for(const [key,value]of Object.entries({bankName:'Audit bank',accountHolder:'Court number E2E',accountNumber:'001234',transferContent:'Number audit'}))await page.locator('#checkout-'+key).fill(value);
  await page.getByRole('button',{name:'Thanh toán',exact:true}).click();await expect(page).toHaveURL(/payment-result/);
  await expect(page.locator('.payment-result-facts')).toContainText('Sân 03, Sân 04');
  await expect(page.locator('.payment-result-facts')).not.toContainText('1207043');
  await expect(page.locator('.payment-result-photo-wrap img')).toHaveAttribute('src',photo);
  await page.reload();await expect(page.locator('.payment-result-facts')).toContainText('Sân 03, Sân 04');
  const history=await get('/bookings?page=0&size=100');assert.ok(history.content.some(b=>b.bookingId===id));
  booking=await get('/bookings/'+id);assert.equal(booking.payments.length,1);assert.equal(booking.payments[0].paymentStatus,'PENDING');
  for(const width of [1440,820,375,320]){await page.setViewportSize({width,height:1100});assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false);}
  assert.deepEqual(errors,[]);
  console.log('PASS all 35 branches/210 courts numbered 1..6; 1207043 -> Sân 03 across courts/checkout/result/history; original UUID selection, price, images, refresh and responsive');
 }finally{await browser.close();await api.dispose();}
})().catch(error=>{console.error(error);process.exitCode=1;});
