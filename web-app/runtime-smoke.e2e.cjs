// Local demo runtime verification. Adds one clearly labelled diagnostic customer, never deletes
// existing data, never initiates payment, and verifies that a past-slot POST is rejected.
const {chromium, request, expect} = require('playwright/test');
const crypto = require('node:crypto');
const assert = require('node:assert/strict');

(async () => {
  const {isFutureSlot} = await import('./src/lib/bookingTime.js');
  const api = await request.newContext({baseURL: 'http://localhost:8080'});
  const catalogResponse = await api.get('/api/public/courts'); assert.equal(catalogResponse.status(), 200);
  const catalog = (await catalogResponse.json()).data;
  const districts = [...new Set(catalog.map(c => c.district).filter(Boolean))];
  assert.equal(districts.length, 7);
  const promotedIds = Array.from({length: 7}, (_, i) => `bcdd0000-0000-4000-8000-${String(i + 1).padStart(12, '0')}`);
  const demo = catalog.filter(c => promotedIds.includes(c.id)); assert.equal(demo.length, 7);
  assert.equal(catalog.length, 35);
  assert.ok(catalog.every(c => c.district && !c.name.includes('[DEMO]')));
  for (const origin of ['http://localhost:5173', 'http://localhost:5174']) {
    assert.equal((await api.get(`${origin}/`)).status(), 200);
    const proxied = await api.get(`${origin}/api/public/courts`); assert.equal(proxied.status(), 200);
    assert.equal((await proxied.json()).data.length, catalog.length);
  }
  assert.equal((await api.get('/api/public/time-slots')).status(), 200);
  const registered = await api.post('/api/auth/register', {data: {email: `runtime-check-${crypto.randomUUID()}@example.test`,
    password: crypto.randomBytes(18).toString('hex'), fullName: 'Runtime smoke test (demo)', phone: '0900000000'}});
  assert.equal(registered.status(), 201); const session = (await registered.json()).data;
  const headers = {Authorization: `Bearer ${session.accessToken}`};
  const clock = (await (await api.get('/api/public/booking-clock')).json()).data;
  const now = Date.parse(clock.serverTime); assert.equal(clock.timeZone, 'Asia/Ho_Chi_Minh');
  const gridResponse = await api.get(`/api/availability?date=${clock.today}`, {headers}); assert.equal(gridResponse.status(), 200);
  const grid = (await gridResponse.json()).data;
  const options = grid.courts.flatMap(court => court.slots);
  for (const option of options) if (!isFutureSlot(clock.today, option.startTime, now)) assert.equal(option.available, false);
  const profile = (await (await api.get('/api/bookings/customer-profile', {headers})).json()).data;
  const oldDate = new Date(Date.UTC(...clock.today.split('-').map((v, i) => Number(v) - (i === 1 ? 1 : 0))));
  oldDate.setUTCDate(oldDate.getUTCDate() - 1);
  const yesterday = `${oldDate.getUTCFullYear()}-${String(oldDate.getUTCMonth() + 1).padStart(2, '0')}-${String(oldDate.getUTCDate()).padStart(2, '0')}`;
  const target = grid.courts.find(c => c.courtId === demo[0].id);
  const pastToday = target.slots.find(slot => !isFutureSlot(clock.today, slot.startTime, now));
  const rejected = await api.post('/api/bookings', {headers, data: {customerId: profile.customerId,
    details: [{courtId: demo[0].id, timeSlotId: (pastToday || target.slots[0]).timeSlotId, bookingDate: pastToday ? clock.today : yesterday}]}});
  assert.equal(rejected.status(), 400);
  const history = (await (await api.get('/api/bookings?page=0&size=1', {headers})).json()).data;
  assert.equal(history.totalElements, 0, 'Rejected booking must not leave a header');
  console.log('PASS live 8080 + both 5173/5174 proxies: catalog/time slots/clock/authorized availability 200; past-slot API 400 and no orphan booking');
  const browser = await chromium.launch({channel: 'msedge', headless: true});
  try {
    const context = await browser.newContext();
    await context.addInitScript(s => { sessionStorage.setItem('bcm.accessToken', s.accessToken); sessionStorage.setItem('bcm.user', JSON.stringify(s.user)); }, session);
    const page = await context.newPage(); const errors = []; page.on('pageerror', error => errors.push(error.message));
    await page.goto('http://localhost:5174/courts');
    await expect(page.getByLabel('Ngày chơi', {exact: true})).toHaveValue(clock.today);
    const filter = page.locator('.customer-district-filter');
    await expect(filter.locator('input[type=checkbox]')).toHaveCount(7);
    const labels = districts.slice(0, 2);
    for (const label of labels) await filter.getByLabel(label, {exact: true}).check();
    const cards = page.locator('.customer-court-card');
    const future = target.slots.some(slot => isFutureSlot(clock.today, slot.startTime, now));
    if (future) {
      await expect(cards).toHaveCount(catalog.filter(c => labels.includes(c.district)).length);
      await expect(page.locator('.customer-branch-card')).toHaveCount(0);
      const options = await page.getByRole('combobox', {name: 'Khung giờ', exact: true}).locator('option:not([value=""])').evaluateAll(nodes => nodes.map(node => node.textContent));
      for (const option of options) assert.equal(isFutureSlot(clock.today, option.split('–')[0].trim(), now), true);
    } else await expect(page.getByRole('button', {name: 'Chọn ngày mai', exact: true})).toBeVisible();
    await page.getByRole('button', {name: 'Đặt lại bộ lọc', exact: true}).click();
    await expect(filter.locator('input:checked')).toHaveCount(0);
    assert.deepEqual(errors, []);
    console.log('PASS live customer /courts: real Vietnam today/clock, seven DB district choices, OR/reset, past slots absent; no mock API/clock and no runtime JS errors');
  } finally { await browser.close(); await api.dispose(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
