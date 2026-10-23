// Clock-only fixtures test the rendered boundary cases. Catalog, availability, auth and holds
// use a real backend/PostgreSQL. Run against the dedicated audit runtime, never production.
const {chromium, request, expect} = require('playwright/test');
const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const baseURL = process.env.BCM_E2E_WEB_URL || 'http://127.0.0.1:5174';
const date = '2031-02-20';
const dayAfter = '2031-02-21';
const demoIds = ['bcdd0000-0000-4000-8000-000000000001', 'bcdd0000-0000-4000-8000-000000000002'];

(async () => {
  const api = await request.newContext({baseURL});
  const registration = await api.post('/api/auth/register', {data: {email: `realtime-${crypto.randomUUID()}@example.test`,
    password: crypto.randomBytes(18).toString('hex'), fullName: 'Realtime E2E', phone: '0900000000'}});
  assert.equal(registration.status(), 201);
  const session = (await registration.json()).data;
  const headers = {Authorization: `Bearer ${session.accessToken}`};
  const profile = (await (await api.get('/api/bookings/customer-profile', {headers})).json()).data;
  const templates = (await (await api.get('/api/public/time-slots')).json()).data;
  const slotAt = hour => templates.find(slot => slot.startTime.slice(0, 5) === `${hour}:00`);
  const browser = await chromium.launch({channel: 'msedge', headless: true});
  try {
    const context = await browser.newContext({timezoneId: 'America/Los_Angeles'});
    await context.addInitScript(s => {
      sessionStorage.setItem('bcm.accessToken', s.accessToken); sessionStorage.setItem('bcm.user', JSON.stringify(s.user));
    }, session);
    const page = await context.newPage(); const errors = []; let writes = 0;
    page.on('pageerror', error => errors.push(error.message));
    page.on('request', req => { if (['POST', 'PATCH'].includes(req.method()) && /\/bookings/.test(req.url())) writes++; });
    let epoch = Date.parse(`${date}T00:00:00+07:00`);
    await page.clock.install({time: new Date(epoch)});
    await page.clock.pauseAt(new Date(epoch));
    await page.route('**/api/public/booking-clock', route => route.fulfill({json: {success: true,
      data: {serverTime: new Date(epoch).toISOString(), timeZone: 'Asia/Ho_Chi_Minh', today: date}}}));
    for (const time of ['00:00', '06:59', '07:00', '11:59', '12:00', '12:30', '23:59']) {
      epoch = Date.parse(`${date}T${time}:00+07:00`); await page.clock.setSystemTime(new Date(epoch));
      await page.goto(`${baseURL}/courts?date=${date}`);
      await expect(page.locator('.customer-district-filter').getByLabel('Quận 1', {exact: true})).toBeVisible();
      const expected = templates.filter(slot => slot.startTime.slice(0, 8) > `${time}:00`).map(slot => slot.id);
      const options = page.getByRole('combobox', {name: 'Khung giờ', exact: true}).locator('option:not([value=""])');
      await expect(options).toHaveCount(expected.length);
      assert.deepEqual(await options.evaluateAll(nodes => nodes.map(node => node.value)), expected);
      await expect(page.getByLabel('Ngày chơi', {exact: true})).toHaveAttribute('min', date);
      for (const text of await page.locator('.customer-slot-chip').allTextContents()) {
        const start = /\d{2}:\d{2}/.exec(text)?.[0]; if (start) assert.ok(start > time, `Past chip rendered at ${time}: ${text}`);
      }
      if (!expected.length) {
        await expect(page.getByText('Hôm nay không còn khung giờ bắt đầu trong tương lai.', {exact: false})).toBeVisible();
        await page.getByRole('button', {name: 'Chọn ngày mai', exact: true}).click();
        await expect(page.getByLabel('Ngày chơi', {exact: true})).toHaveValue(dayAfter);
        await expect(options).toHaveCount(templates.length);
      }
    }
    console.log('PASS UI fixed Vietnam clock: 00:00/06:59/07:00/11:59/12:00/12:30/23:59; past slots absent, tomorrow works, client timezone independent');

    epoch = Date.parse(`${date}T11:59:59+07:00`); await page.clock.setSystemTime(new Date(epoch));
    await page.goto(`${baseURL}/courts?date=${date}`);
    const district = page.locator('.customer-district-filter');
    await district.getByLabel('Quận 1', {exact: true}).check(); await district.getByLabel('Quận 3', {exact: true}).check();
    await page.getByLabel('Tên sân', {exact: true}).fill('[DEMO]');
    await page.getByRole('spinbutton', {name: 'Giá tối đa mỗi khung giờ'}).fill('200000');
    await page.getByRole('combobox', {name: 'Khung giờ', exact: true}).selectOption(slotAt('12').id);
    const first = page.locator(`[data-court-id="${demoIds[0]}"]`), second = page.locator(`[data-court-id="${demoIds[1]}"]`);
    await first.locator(`.customer-extra-slots input[value="${slotAt('13').id}"]`).check();
    await second.locator(`.customer-extra-slots input[value="${slotAt('14').id}"]`).check();
    epoch += 1000; await page.clock.runFor(1250);
    await expect(page.locator(`option[value="${slotAt('12').id}"]`)).toHaveCount(0);
    await expect(page.locator(`.customer-extra-slots input[value="${slotAt('12').id}"]`)).toHaveCount(0);
    await expect(second.locator(`.customer-extra-slots input[value="${slotAt('14').id}"]`)).toBeChecked();
    await expect(district.getByLabel('Quận 1', {exact: true})).toBeChecked();
    await expect(district.getByLabel('Quận 3', {exact: true})).toBeChecked();
    await expect(page.getByLabel('Tên sân', {exact: true})).toHaveValue('[DEMO]');
    await expect(page.getByRole('spinbutton', {name: 'Giá tối đa mỗi khung giờ'})).toHaveValue('200000');
    assert.notEqual(await page.getByRole('combobox', {name: 'Khung giờ', exact: true}).inputValue(), slotAt('12').id);
    console.log('PASS open UI crosses a start boundary without refresh; expired IDs removed and other-court checkbox/district/name/price preserved');

    epoch = Date.parse(`${date}T23:59:59+07:00`); await page.clock.setSystemTime(new Date(epoch));
    await page.goto(`${baseURL}/courts?date=${date}`);
    await expect(page.getByRole('button', {name: 'Chọn ngày mai', exact: true})).toBeVisible();
    epoch += 1000; await page.clock.runFor(1250);
    await expect(page.getByLabel('Ngày chơi', {exact: true})).toHaveValue(dayAfter);
    await expect(page.getByRole('combobox', {name: 'Khung giờ', exact: true}).locator('option:not([value=""])')).toHaveCount(templates.length);
    console.log('PASS Vietnam midnight advances the selected day and its minimum without using the browser local day');

    const created = await api.post('/api/bookings', {headers, data: {customerId: profile.customerId,
      details: [{bookingDate: date, courtId: demoIds[0], timeSlotId: slotAt('13').id}]}});
    assert.equal(created.status(), 201); const booking = (await created.json()).data;
    epoch = Date.parse(`${date}T12:59:59+07:00`); await page.clock.setSystemTime(new Date(epoch));
    await page.goto(`${baseURL}/checkout?bookingId=${booking.bookingId}`);
    await page.getByRole('button', {name: 'Chỉnh sửa thông tin', exact: true}).click();
    const editor = page.locator('.checkout-booking-editor');
    await expect(editor.locator('input[type=checkbox]')).toHaveCount(templates.filter(slot => slot.startTime.slice(0, 5) > '12:59').length);
    await expect(editor.locator(`input[value="${slotAt('13').id}"]`)).toBeChecked();
    await expect(editor.locator(`input[value="${slotAt('12').id}"]`)).toHaveCount(0);
    epoch += 1000; await page.clock.runFor(1250);
    await expect(editor.locator(`input[value="${slotAt('13').id}"]`)).toHaveCount(0);
    await expect(editor.getByRole('button', {name: 'Lưu thay đổi', exact: true})).toBeDisabled();
    await expect(editor.locator('#checkout-edit-date')).toHaveAttribute('min', date);
    assert.equal(writes, 0, 'Clock updates must not create holds, payments or edits');
    assert.deepEqual(errors, []);
    console.log('PASS checkout editor hides/prunes started slots, preserves validation and makes no automatic writes; no uncaught JS errors');
  } finally { await browser.close(); await api.dispose(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
