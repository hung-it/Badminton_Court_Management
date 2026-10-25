// Frontend fixtures only. Mock booking creation and inspect the exact /checkout handoff.
const {chromium,expect}=require('playwright/test');
const assert=require('node:assert/strict');
const web=process.env.BCM_E2E_WEB_URL||'http://127.0.0.1:5174';
const date='2031-02-20';
const slots=[{id:'past',startTime:'10:00',endTime:'11:00'}, {id:'normal',startTime:'12:00',endTime:'13:00'},
  {id:'later',startTime:'13:00',endTime:'14:00'}, {id:'peak',startTime:'17:00',endTime:'18:00'}];
const courts=['a','b'].flatMap((venue,v)=>[1,2,3,4,5,6].map(n=>({id:`${venue}${n}`,branchId:venue,branchCourtNumber:n,
  courtNumber:v*3+n,name:`Venue ${venue}`,district:v?'Quận 7':'Quận 1',basePrice:100000,type:'STANDARD_MAT'})));
(async()=>{
 const browser=await chromium.launch({channel:'msedge',headless:true});
 try{
  const page=await browser.newPage({viewport:{width:1440,height:1000}}),errors=[];
  page.on('pageerror',e=>errors.push(e.message));
  await page.clock.install({time:new Date(`${date}T11:59:59+07:00`)});
  await page.clock.pauseAt(new Date(`${date}T11:59:59+07:00`));
  await page.addInitScript(()=>{sessionStorage.setItem('bcm.accessToken','fixture');sessionStorage.setItem('bcm.user',JSON.stringify({id:'fixture',roles:['CUSTOMER']}));});
  let occupied=false, occupiedNormal=false, allOccupied=false, unionFixture=true;
  await page.route('**/api/**',async route=>{
   const url=new URL(route.request().url());let data;
   if(route.request().method()==='POST'&&url.pathname.endsWith('/bookings')) {
    assert.equal(route.request().postDataJSON().details.length,2);
    await route.fulfill({status:201,json:{success:true,data:{bookingId:'new-fixture-booking'}}});return;
   }
   assert.equal(route.request().method(),'GET');
   if(url.pathname.endsWith('/public/booking-clock'))data={serverTime:`${date}T11:59:59+07:00`,today:date,timeZone:'Asia/Ho_Chi_Minh'};
   else if(url.pathname.endsWith('/public/courts'))data=courts;
   else if(url.pathname.endsWith('/public/time-slots'))data=slots;
   else if(url.pathname.endsWith('/public/availability'))data={date:url.searchParams.get('date'),courts:courts.map(c=>({courtId:c.id,slots:slots.map(s=>({...s,timeSlotId:s.id,available:!((unionFixture&&c.branchId==='a'&&(s.id==='normal'||(s.id==='later'&&c.id!=='a6')))||(occupied&&c.id==='a2'&&s.id==='later')||(occupiedNormal&&c.id==='a2'&&s.id==='normal')||(allOccupied&&c.id==='a3')),price:s.id==='peak'?150000:100000}))}))};
   else if(url.pathname.endsWith('/bookings/customer-profile'))data={customerId:'fixture-customer'};
   else if(url.pathname.endsWith('/bookings'))data={content:[],totalElements:0};
   else throw Error(`Unexpected API ${url.pathname}`);
   await route.fulfill({json:{success:true,data}});
  });
  await page.route('**/checkout',r=>r.fulfill({contentType:'text/html',body:'Checkout handoff'}));
  await page.goto(`${web}/courts?date=${date}&timeSlotId=later`);
  const card=page.locator('[data-court-id=a1]');
  const court=n=>card.locator(`.customer-branch-courts input[value=a${n}]`);
  const hour=id=>card.locator(`.customer-extra-slots input[value=${id}]`);
  const focus=n=>card.locator(`[data-focus-court-id=a${n}]`);
  const book=card.getByRole('button',{name:'Đặt sân',exact:true});
  const empty=async()=>{
   await expect(page.locator('.customer-branch-courts input:checked')).toHaveCount(0);
   await expect(card).toHaveAttribute('data-active-court-id','');
   await expect(hour('peak')).toBeEnabled();await expect(book).toBeDisabled();
   await expect(card.locator('.customer-extra-slots input:checked')).toHaveCount(0);
   await expect(card.getByText('Chọn sân hoặc khung giờ để bắt đầu.',{exact:true})).toBeVisible();
  };
  await expect(page.locator('.customer-court-card')).toHaveCount(2);await empty();
  await expect(hour('normal')).toHaveCount(0);
  await expect(hour('later')).toBeEnabled(); // Only court 06 is free: union must still show it.
  await court(1).check();await expect(hour('later')).toHaveCount(0);
  await court(1).uncheck();await expect(hour('later')).toBeEnabled();
  await court(6).check();await expect(hour('later')).toBeEnabled();
  await court(6).uncheck();await empty();
  unionFixture=false;await page.evaluate(()=>window.dispatchEvent(new Event('focus')));
  await expect(hour('normal')).toBeEnabled();
  console.log('PASS six-court union, all-six occupied hidden, selected-court availability unchanged, no default selection');
  occupiedNormal=true;await page.evaluate(()=>window.dispatchEvent(new Event('focus')));
  await expect(court(2)).toBeEnabled();await court(2).check();
  await expect(hour('normal')).toHaveCount(0);await expect(hour('later')).toBeEnabled();
  await expect(court(1)).not.toBeChecked();await expect(court(3)).not.toBeChecked();
  await court(2).uncheck();occupiedNormal=false;allOccupied=true;
  await page.evaluate(()=>window.dispatchEvent(new Event('focus')));
  await expect(court(3)).toBeDisabled();await expect(court(3).locator('..')).toContainText('Hết khung giờ trống');
  allOccupied=false;await page.evaluate(()=>window.dispatchEvent(new Event('focus')));await expect(court(3)).toBeEnabled();await empty();
  console.log('PASS a booked filter hour does not disable the court; only a day without any future free slot is sold out; no automatic selection');
  // An hour event cannot invent a court selection.
  await hour('peak').dispatchEvent('change');await empty();
  assert.equal(await page.evaluate(()=>sessionStorage.getItem('bcm-booking-draft')),null);
  await court(3).check();await expect(court(1)).not.toBeChecked();await expect(court(2)).not.toBeChecked();
  await expect(card).toHaveAttribute('data-active-court-id','a3');await expect(book).toBeDisabled();
  await expect(card).toContainText('chưa có giờ được chọn');await hour('peak').check();
  await court(3).uncheck();await empty();await hour('peak').dispatchEvent('change');await empty();
  await court(3).check();await expect(hour('peak')).not.toBeChecked();
  await hour('peak').check();await court(2).check();await expect(book).toBeEnabled();
  await hour('normal').check();await hour('later').check();
  await focus(3).click();await expect(hour('peak')).toBeChecked();await expect(hour('normal')).not.toBeChecked();
  await court(3).uncheck();await expect(court(2)).toBeChecked();await expect(card).toHaveAttribute('data-active-court-id','');
  await expect(hour('peak')).toBeEnabled();await focus(2).click();
  await expect(hour('normal')).toBeChecked();await expect(hour('later')).toBeChecked();
  console.log('PASS empty initial state, hour guard, court 03 deselect/reselect, independent hours, deselection preserves court 02');
  await page.getByLabel('Sắp xếp sân').selectOption('price-desc');
  await page.locator('.customer-district-filter').getByLabel('Quận 1',{exact:true}).check();
  await expect(court(2)).toBeChecked();await expect(court(3)).not.toBeChecked();
  await page.locator('.customer-district-filter').getByRole('button',{name:'Xóa lựa chọn',exact:true}).click();
  await page.evaluate(()=>window.dispatchEvent(new Event('focus')));
  await expect(court(2)).toBeChecked();await expect(court(1)).not.toBeChecked();
  await page.getByLabel('Ngày chơi',{exact:true}).fill('2031-02-21');await empty();
  await page.reload();await empty();await page.getByLabel('Ngày chơi',{exact:true}).fill(date);await empty();
  console.log('PASS API refresh/filter/sort do not add courts; date change and reload have no selected court');
  await court(2).check();await hour('normal').check();await hour('later').check();
  await page.clock.runFor(1250);await expect(hour('normal')).toHaveCount(0);await expect(hour('later')).toBeChecked();
  occupied=true;await page.evaluate(()=>window.dispatchEvent(new Event('focus')));await expect(hour('later')).toHaveCount(0);
  await expect(court(2)).toBeChecked();await expect(book).toBeDisabled();
  occupied=false;await page.evaluate(()=>window.dispatchEvent(new Event('focus')));await expect(hour('later')).not.toBeChecked();
  await hour('later').check();await court(3).check();await hour('peak').check();
  await expect(card.locator('.customer-court-price-row strong')).toContainText('150.000');
  for(const width of [1440,820,375,320]){
   await page.setViewportSize({width,height:1000});assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false);
   assert.ok(await card.evaluate(el=>el.querySelector('.customer-extra-slots').compareDocumentPosition(el.querySelector('.customer-branch-courts'))&Node.DOCUMENT_POSITION_FOLLOWING));
  }
  await book.click();await expect(page).toHaveURL(`${web}/checkout`);
  const draft=await page.evaluate(()=>JSON.parse(sessionStorage.getItem('bcm-booking-draft')));
  assert.equal(draft.bookingId,'new-fixture-booking');
  assert.deepEqual(draft.details,[{courtId:'a2',timeSlotId:'later',bookingDate:date},{courtId:'a3',timeSlotId:'peak',bookingDate:date}]);
  assert.equal(new Set(draft.details.map(d=>`${d.courtId}:${d.timeSlotId}`)).size,2);
  const total=draft.details.reduce((sum,d)=>sum+(d.timeSlotId==='peak'?150000:100000),0);assert.equal(total,250000);
  assert.deepEqual(errors,[]);
  console.log('PASS past/unavailable slot pruning, normal/peak prices, desktop/mobile and exact two-court checkout draft without duplicates');
 }finally{await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
