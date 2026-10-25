// Read-only API/browser checks for all 35 names, neutral image badges and sorting.
const {chromium, request, expect} = require('playwright/test');
const assert = require('node:assert/strict');
const web = process.env.BCM_E2E_WEB_URL || 'http://127.0.0.1:5174';
const apiURL = process.env.BCM_E2E_API_URL || web;
const names = new Intl.Collator('vi', {numeric:true,sensitivity:'base'});

(async () => {
  const api = await request.newContext({baseURL:apiURL});
  const get = async path => {const r=await api.get(`/api${path}`);assert.equal(r.status(),200);return (await r.json()).data;};
  const catalog=await get('/public/courts'),clock=await get('/public/booking-clock');
  assert.equal(catalog.length,210);assert.equal(new Set(catalog.map(c=>c.branchId)).size,35);
  assert.equal(new Set(catalog.map(c=>c.name)).size,210);
  assert.ok(catalog.every(c=>! / - S[\u00e2]n [0-9]+$/.test(c.name)));
  assert.ok(catalog.every(c=>!/^\s*(?:\[DEMO\]\s*)?Cơ sở|mô phỏng|giả lập/i.test(c.name)));
  const date=new Date(`${clock.today}T00:00:00Z`);date.setUTCDate(date.getUTCDate()+10);
  const day=date.toISOString().slice(0,10),grid=await get(`/public/availability?date=${day}`);
  const findSlot=time=>grid.courts[0].slots.find(s=>s.startTime.startsWith(time)).timeSlotId;
  const normal=findSlot('12:00'),peak=findSlot('17:00');
  const prices=Object.fromEntries(grid.courts.map(c=>[c.courtId,Number(c.slots.find(s=>s.timeSlotId===peak&&s.available)?.price)]));
  assert.ok(Object.values(prices).every(Number.isFinite));
  const browser=await chromium.launch({channel:'msedge',headless:true});
  try {
    const page=await browser.newPage({viewport:{width:1440,height:1000}});const errors=[];let reads=0;
    page.on('pageerror',e=>errors.push(e.message));
    page.on('request',req=>{if(new URL(req.url()).pathname.startsWith('/api/'))reads++;});
    await page.clock.install({time:new Date()});await page.clock.pauseAt(new Date());
    if(apiURL!==web)await page.route('**/api/**',async route=>{const u=new URL(route.request().url());await route.fulfill({response:await route.fetch({url:new URL(u.pathname+u.search,apiURL).href})});});
    await page.goto(`${web}/courts?date=${day}&timeSlotId=${peak}`,{waitUntil:'domcontentloaded'});
    const cards=page.locator('.customer-court-card'),sort=page.getByLabel('Sắp xếp sân',{exact:true});
    await expect(cards).toHaveCount(35);
    await expect(sort).toHaveValue('recommended');
    assert.deepEqual(await sort.locator('option').allTextContents(),['Đề xuất cho bạn','Giá thấp đến cao','Giá cao đến thấp']);
    const ids=()=>cards.evaluateAll(nodes=>nodes.map(n=>n.dataset.courtId));
    const expected=(order,subset=catalog.filter(c=>c.branchCourtNumber===1))=>[...subset].sort((a,b)=>{
      const stable=()=>names.compare(a.name,b.name)||a.id.localeCompare(b.id);
      return order==='recommended'?stable():((order==='price'?1:-1)*(prices[a.id]-prices[b.id])||stable());
    }).map(c=>c.id);
    assert.deepEqual(await ids(),expected('recommended'));
    for(const order of ['price','price-desc','recommended']){await sort.selectOption(order);assert.deepEqual(await ids(),expected(order));}
    const target=catalog.find(c=>(c.courtCode??c.courtNumber)===1),card=page.locator(`[data-court-id="${target.id}"]`);
    await card.locator(`.customer-extra-slots input[value="${peak}"]`).uncheck();
    await card.locator(`.customer-extra-slots input[value="${normal}"]`).check();
    await card.locator(`.customer-extra-slots input[value="${peak}"]`).check();
    prices[target.id]=Number(grid.courts.find(c=>c.courtId===target.id).slots.find(s=>s.timeSlotId===normal).price);
    const other=catalog.find(c=>c.branchCourtNumber===1&&c.branchId!==target.branchId);
    await page.locator(`[data-court-id="${other.id}"] .customer-extra-slots input[value="${normal}"]`).check();
    prices[target.id]=Number(grid.courts.find(c=>c.courtId===target.id).slots.find(s=>s.timeSlotId===peak).price);
    prices[other.id]=Number(grid.courts.find(c=>c.courtId===other.id).slots.find(s=>s.timeSlotId===normal).price);
    await expect(card.locator('input:checked')).toHaveCount(0);
    await expect(page.locator('.customer-branch-courts')).toHaveCount(35);
    const state=()=>page.locator('.customer-court-card input:checked').evaluateAll(nodes=>nodes.map(n=>`${n.closest('article').dataset.courtId}:${n.value}`).sort());
    const selected=await state(),beforeReads=reads;
    for(const order of ['price','price-desc','recommended']){await sort.selectOption(order);assert.deepEqual(await ids(),expected(order));assert.deepEqual(await state(),selected);}
    assert.equal(reads,beforeReads,'Sorting must not fetch or lose selections');
    console.log('PASS 35 unique API names, exact three sort options, normal/peak reference prices, stable ties, independent selections and no extra fetches');

    await sort.selectOption('price-desc');
    const districts=page.locator('.customer-district-filter');
    await districts.getByLabel('Quận 1',{exact:true}).check();await districts.getByLabel('Quận 7',{exact:true}).check();
    const subset=catalog.filter(c=>c.branchCourtNumber===1).filter(c=>['Quận 1','Quận 7'].includes(c.district));
    assert.deepEqual(await ids(),expected('price-desc',subset));
    const stem=catalog.find(c=>(c.courtCode??c.courtNumber)===10001).name.split(' - ')[0];
    await page.getByLabel('Tên sân',{exact:true}).fill(stem);
    await page.getByRole('spinbutton',{name:'Giá tối đa mỗi khung giờ',exact:true}).fill('150000');
    assert.deepEqual(await ids(),expected('price-desc',subset.filter(c=>c.name.includes(stem)&&prices[c.id]<=150000)));
    await page.getByLabel('Tên sân',{exact:true}).fill('');await page.getByRole('spinbutton',{name:'Giá tối đa mỗi khung giờ',exact:true}).fill('');
    await districts.getByRole('button',{name:'Xóa lựa chọn',exact:true}).click();
    assert.deepEqual(await state(),selected);
    for(const width of [1440,820,375,320]){
      await page.setViewportSize({width,height:1000});
      await expect(page.locator('.customer-court-number')).toHaveCount(35);
      const badges=await page.locator('.customer-court-number').allTextContents();
      assert.ok(new Set(badges).size>=3);
      assert.ok(badges.every(label=>!/[0-9]/.test(label)));
      const badgePairs=await cards.evaluateAll(nodes=>nodes.map(n=>[n.dataset.courtId,n.querySelector('.customer-court-number').textContent]));
      if(width===1440)page.badgePairs=badgePairs;else assert.deepEqual(badgePairs,page.badgePairs);
      assert.equal(await page.locator('.customer-court-number').evaluateAll(nodes=>nodes.every(n=>{const r=n.getBoundingClientRect(),p=n.parentElement.getBoundingClientRect();return r.left>=p.left&&r.right<=p.right&&r.top>=p.top&&r.bottom<=p.bottom;})),true,`Badge overflow ${width}`);
      assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false,`Page overflow ${width}`);
    }
    assert.deepEqual(errors,[]);
    console.log('PASS name/district/price composition, selected state retained and all neutral badges fit desktop/mobile 1440/820/375/320');
  } finally {await browser.close();await api.dispose();}
})().catch(e=>{console.error(e);process.exitCode=1;});
