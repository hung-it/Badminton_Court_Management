// Read-only runtime smoke: real catalog/availability/assets; never creates a booking or payment.
const {chromium,request,expect}=require('playwright/test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const crypto=require('node:crypto');
const web=process.env.BCM_E2E_WEB_URL||'http://127.0.0.1:5174';
(async()=>{
 const {courtImageSource}=await import('./src/lib/courtImages.js');
 const api=await request.newContext({baseURL:web});
 const get=async path=>{const r=await api.get('/api'+path);assert.equal(r.status(),200);return(await r.json()).data;};
 const catalog=await get('/public/courts'),clock=await get('/public/booking-clock');
 const venues=catalog.filter(c=>!c.branchId||c.branchCourtNumber===1);
 assert.equal(venues.length,35);
 const photoPaths=venues.map(courtImageSource);
 assert.equal(catalog.length,210);
 for(const court of catalog){
  const photo=courtImageSource(court,catalog);assert.ok(fs.existsSync('web-app/public'+photo));
  const representative=catalog.find(item=>item.branchId===court.branchId&&item.branchCourtNumber===1);
  assert.ok(representative);assert.equal(photo,courtImageSource(representative,catalog));
 }
 const credits=JSON.parse(fs.readFileSync('web-app/public/assets/courts/credits.json'));
 for(const venue of venues)assert.equal(credits.images.find(i=>i.courtNumber===(venue.courtCode??venue.courtNumber)).type,venue.type);
 assert.equal(new Set(photoPaths).size,35,'Every card must have its own image URL');
 const hashes=photoPaths.map(p=>crypto.createHash('sha256').update(fs.readFileSync('web-app/public'+p)).digest('hex'));
 assert.equal(new Set(hashes).size,35,'Renaming identical files must not count as distinct images');
 const future=new Date(clock.today+'T00:00:00Z');future.setUTCDate(future.getUTCDate()+20);
 const date=future.toISOString().slice(0,10),grid=await get('/public/availability?date='+date);
 for(const path of photoPaths){const r=await api.get(path);assert.equal(r.status(),200);assert.ok((await r.body()).length<250000);}
 const browser=await chromium.launch({channel:'msedge',headless:true});
 try{
  const page=await browser.newPage({viewport:{width:1440,height:1000}}),errors=[],failures=[];
  page.on('pageerror',e=>errors.push(e.message));
  page.on('response',r=>{if(photoPaths.includes(new URL(r.url()).pathname)&&r.status()>=400)failures.push(r.status());});
  page.on('request',r=>{if(new URL(r.url()).pathname.startsWith('/api/'))assert.equal(r.method(),'GET','Image smoke must be read-only');});
  await page.goto(`${web}/courts?date=${date}`);
  const cards=page.locator('.customer-court-card'),photos=cards.locator('.customer-court-photo');
  await expect(cards).toHaveCount(35);await expect(photos).toHaveCount(35);
  for(const image of await photos.all()){
   await image.scrollIntoViewIfNeeded();await expect(image).toHaveJSProperty('complete',true);
   assert.ok(await image.evaluate(img=>img.naturalWidth>0));
   await expect(image).toHaveAttribute('loading','lazy');await expect(image).toHaveAttribute('decoding','async');
   await expect(image).toHaveAttribute('alt',/^Ảnh minh họa sân cầu lông /);
  }
  const mapping=()=>cards.evaluateAll(nodes=>nodes.map(c=>[c.dataset.courtId,new URL(c.querySelector('.customer-court-photo').src).pathname]));
  const original=await mapping();
  for(const [id,path]of original){const c=venues.find(v=>v.id===id);assert.equal(path,courtImageSource(c));}
  await page.reload();await expect(photos).toHaveCount(35);assert.deepEqual(await mapping(),original);
  for(const width of[1440,820,375,320]){
   await page.setViewportSize({width,height:1000});
   assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false);
   assert.equal(await cards.evaluateAll(nodes=>nodes.every(card=>{
    const media=card.querySelector('.customer-court-image'),image=media.querySelector('img'),badge=media.querySelector('.customer-court-number');
    const r=image.getBoundingClientRect(),m=media.getBoundingClientRect(),b=badge.getBoundingClientRect();
    return getComputedStyle(image).objectFit==='cover'&&Math.abs(r.width-m.width)<1&&Math.abs(r.height-m.height)<1
      &&b.left>=m.left&&b.right<=m.right&&b.top>=m.top&&b.bottom<=m.bottom&&getComputedStyle(media).overflow==='hidden';
   })),true,`Image/badge geometry ${width}`);
   // Compare against the exact old placeholder at the same viewport. No CSS or card layout changes.
   assert.equal(await cards.evaluateAll(nodes=>nodes.every(card=>{
    const image=card.querySelector('.customer-court-photo'),info=card.querySelector('.customer-court-info');
    const rect=()=>[card.getBoundingClientRect().width,card.getBoundingClientRect().height,info.getBoundingClientRect().left,info.getBoundingClientRect().top];
    const before=rect(),placeholder=document.createElement('div');placeholder.className='customer-court-illustration';
    placeholder.innerHTML='<div class="court-lines"><i></i><b></b></div><span>Chưa có ảnh sân</span>';
    image.replaceWith(placeholder);const previous=rect();placeholder.replaceWith(image);
    return before.every((v,i)=>Math.abs(v-previous[i])<1);
   })),true,`Old/new card layout ${width}`);
  }
  await page.setViewportSize({width:1440,height:1000});
  fs.mkdirSync('backend/target/court-images',{recursive:true});
  await cards.first().screenshot({path:'backend/target/court-images/card-desktop.png'});
  await page.setViewportSize({width:375,height:1000});
  await cards.first().screenshot({path:'backend/target/court-images/card-mobile.png'});
  await page.setViewportSize({width:1440,height:1000});
  const target=venues.find(v=>catalog.filter(c=>c.branchId===v.branchId).slice(0,2).every(c=>grid.courts.find(g=>g.courtId===c.id)?.slots.filter(s=>s.available).length>=2));
  assert.ok(target);const siblings=catalog.filter(c=>c.branchId===target.branchId).sort((a,b)=>a.branchCourtNumber-b.branchCourtNumber);
  const card=page.locator(`[data-court-id="${target.id}"]`);
  const normal=grid.courts.find(g=>g.courtId===siblings[0].id).slots.find(s=>s.available&&Number(s.priceMultiplier)===1);
  const peak=grid.courts.find(g=>g.courtId===siblings[1].id).slots.find(s=>s.available&&Number(s.priceMultiplier)>1);
  assert.ok(normal&&peak);
  await card.locator(`.customer-branch-courts input[value="${siblings[0].id}"]`).check();
  await card.locator(`.customer-extra-slots input[value="${normal.timeSlotId}"]`).check();
  await expect(card.getByRole('button',{name:'Đặt sân',exact:true})).toBeEnabled();
  await card.locator(`.customer-branch-courts input[value="${siblings[1].id}"]`).check();
  await expect(card.getByRole('button',{name:'Đặt sân',exact:true})).toBeDisabled();
  await card.locator(`.customer-extra-slots input[value="${peak.timeSlotId}"]`).check();
  const expectedPrice=new Intl.NumberFormat('vi-VN',{style:'currency',currency:'VND',maximumFractionDigits:0}).format(Number(peak.price));
  await expect(card.locator('.customer-court-price-row strong')).toHaveText(expectedPrice);
  const district=page.locator('.customer-district-filter');await district.getByLabel(target.district,{exact:true}).check();
  await expect(cards).toHaveCount(venues.filter(c=>c.district===target.district).length);
  await district.getByRole('button',{name:'Xóa lựa chọn',exact:true}).click();await expect(cards).toHaveCount(35);
  await page.route('**/login?redirect=%2Fcheckout',r=>r.fulfill({contentType:'text/html',body:'Checkout handoff: no booking writes'}));
  await card.getByRole('button',{name:'Đặt sân',exact:true}).click();await expect(page).toHaveURL(/login\?redirect=/);
  const draft=await page.evaluate(()=>JSON.parse(sessionStorage.getItem('bcm-booking-draft')));
  assert.deepEqual(draft.details,[{courtId:siblings[0].id,timeSlotId:normal.timeSlotId,bookingDate:date},{courtId:siblings[1].id,timeSlotId:peak.timeSlotId,bookingDate:date}]);
  console.log('PASS all 210 courts resolve to their branch images; 35 unique existing files/hashes, no 404/broken images, stable mapping, lazy loading, badges and unchanged layout at 1440/820/375/320');
  console.log('PASS real availability, one/multiple courts, normal/peak prices, district filter and exact checkout draft; no backend writes');
  const fallback=await browser.newPage({viewport:{width:375,height:1000}});let imageRequests=0;
  await fallback.route('**/assets/**',async route=>{
   if(photoPaths.includes(new URL(route.request().url()).pathname)){imageRequests++;await route.abort();}else await route.continue();
  });
  await fallback.goto(`${web}/courts?date=${date}`);
  const fallbackCards=fallback.locator('.customer-court-card');await expect(fallbackCards).toHaveCount(35);
  for(const item of await fallbackCards.all()){await item.scrollIntoViewIfNeeded();await expect(item.locator('.customer-court-illustration')).toBeVisible();await expect(item.locator('.customer-court-photo')).toHaveCount(0);}
  const requestsBefore=imageRequests;
  await fallback.getByLabel('Sắp xếp sân').selectOption('price-desc');
  await expect(fallback.locator('.customer-court-illustration')).toHaveCount(35);assert.equal(imageRequests,requestsBefore);
  await fallback.locator('.customer-branch-courts input:not(:disabled)').first().check();
  await expect(fallback.locator('.customer-branch-courts input:checked')).toHaveCount(1);
  assert.equal(imageRequests,requestsBefore);assert.deepEqual(failures,[]);assert.deepEqual(errors,[]);
  fs.mkdirSync('backend/target/court-images',{recursive:true});
  await fallback.screenshot({path:'backend/target/court-images/fallback-mobile.png',fullPage:false});
  console.log('PASS image failure restores old placeholder on all 35 cards, no broken icons/retry loop, booking selection still works');
  // Frontend-only checkout/promotion regression. No writes reach the real API.
  const checkout=await browser.newPage({viewport:{width:1440,height:1000}});
  const bookingId='aaaaaaaa-0000-4000-8000-000000000099';
  const now=new Date().toISOString().slice(0,-1),expiresAt=new Date(Date.now()+600000).toISOString().slice(0,-1);
  const invoice={bookingId,customerId:'fixture-customer',status:'PENDING',courtFee:520000,discountAmount:50000,finalAmount:470000,
   promotionCode:'INVOICE_TOTAL',requestedVoucher:null,expiresAt,createdAt:now,updatedAt:now,payments:[],
   details:siblings.slice(0,2).map(c=>({detailId:c.id,courtId:c.id,courtNumber:c.courtNumber,courtCode:c.courtCode,courtName:c.name,
    bookingDate:date,timeSlotId:normal.timeSlotId,startTime:normal.startTime,endTime:normal.endTime,price:260000}))};
  await checkout.addInitScript(()=>{sessionStorage.setItem('bcm.accessToken','image-regression-fixture');sessionStorage.setItem('bcm.user',JSON.stringify({id:'fixture',roles:['CUSTOMER'],fullName:'Image regression'}));});
  await checkout.route('**/api/**',async route=>{
   const url=new URL(route.request().url());let data;
   if(url.pathname.endsWith('/promotion/apply')){
    assert.equal(route.request().method(),'POST');assert.deepEqual(route.request().postDataJSON(),{code:'KMKH10'});
    data={subtotal:520000,discountAmount:52000,finalAmount:468000,promotionCode:'KMKH10',requestedVoucher:'KMKH10',voucherValid:true,message:'Đã áp dụng KMKH10.'};
   }else{
    assert.equal(route.request().method(),'GET','No checkout/payment writes are permitted in this UI fixture');
    if(url.pathname.endsWith('/contact'))data={fullName:'Image regression',phone:'0900000000',email:'image@example.test'};
    else if(url.pathname.endsWith('/checkout-clock'))data={status:'PENDING',serverTime:now,expiresAt};
    else if(url.pathname.endsWith('/'+bookingId))data=invoice;
    else if(url.pathname.endsWith('/bookings'))data={content:[],totalElements:0};
    else {await route.fulfill({response:await route.fetch()});return;}
   }
   await route.fulfill({json:{success:true,data}});
  });
  await checkout.goto(`${web}/checkout?bookingId=${bookingId}`);
  await expect(checkout.locator('.checkout-grand-total')).toContainText('470.000');
  await expect(checkout.getByRole('button',{name:'Thanh toán',exact:true})).toBeEnabled();
  await checkout.getByPlaceholder('Mã khuyến mãi').fill('KMKH10');
  await checkout.getByRole('button',{name:'Áp dụng',exact:true}).click();
  await expect(checkout.locator('.checkout-grand-total')).toContainText('468.000');
  await expect(checkout.locator('.checkout-discount')).toContainText('KMKH10 · 10%');
  console.log('PASS checkout/promotion UI still displays authoritative subtotal/discount/final quote (frontend fixture, no real payment)');
 }finally{await browser.close();await api.dispose();}
})().catch(e=>{console.error(e);process.exitCode=1;});
