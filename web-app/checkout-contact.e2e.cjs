// Real HTTP/browser/PostgreSQL. Writes only to the dedicated audit runtime.
const {chromium, request, expect} = require('playwright/test');
const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const fs = require('node:fs');
const baseURL = process.env.BCM_E2E_WEB_URL || 'http://127.0.0.1:5175';
assert.ok(baseURL.includes(':5175'), 'Use the dedicated audit frontend');

(async () => {
  const api = await request.newContext({baseURL});
  const reg = async () => {
    const r = await api.post('/api/auth/register', {data: {email: `contact-${crypto.randomUUID()}@example.test`,
      password: crypto.randomBytes(18).toString('hex'), fullName: 'Contact original', phone: '0900000000'}});
    assert.equal(r.status(), 201); return (await r.json()).data;
  };
  const owner = await reg(), foreign = await reg();
  const headers = {Authorization: `Bearer ${owner.accessToken}`};
  const get = async path => { const r = await api.get(`/api${path}`, {headers}); assert.equal(r.status(), 200); return (await r.json()).data; };
  const profileBefore = await get('/users/me');
  const {customerId} = await get('/bookings/customer-profile');
  const date = '2035-07-20';
  const grid = await get(`/public/availability?date=${date}`);
  const court = grid.courts.find(c => c.slots.some(s => s.available));
  const slot = court.slots.find(s => s.available);
  const created = await api.post('/api/bookings', {headers, data: {customerId,
    details: [{courtId: court.courtId, timeSlotId: slot.timeSlotId, bookingDate: date}]}});
  assert.equal(created.status(), 201); const hold = (await created.json()).data;
  const storedBefore = await get(`/bookings/${hold.bookingId}`);
  const path = `/bookings/${hold.bookingId}/contact`;
  const original = await get(path);
  const browser = await chromium.launch({channel: 'msedge', headless: true});
  try {
    const context = await browser.newContext({viewport: {width: 1440, height: 1000}});
    await context.addInitScript(s => {
      sessionStorage.setItem('bcm.accessToken', s.accessToken); sessionStorage.setItem('bcm.user', JSON.stringify(s.user));
    }, owner);
    const page = await context.newPage(); const errors = []; let patches = 0;
    page.on('pageerror', e => errors.push(e.message));
    page.on('request', req => { if (req.method() === 'PATCH' && req.url().endsWith('/contact')) patches++; });
    await page.goto(`${baseURL}/checkout?bookingId=${hold.bookingId}`);
    const card = page.locator('.checkout-customer-card');
    await expect(card.getByRole('button', {name: 'Chỉnh sửa', exact: true})).toBeEnabled();
    await card.getByRole('button', {name: 'Chỉnh sửa', exact: true}).click();
    const editor = page.getByRole('form', {name: 'Chỉnh sửa thông tin người đặt'});
    await expect(editor.getByLabel('Email nhận xác nhận')).toHaveValue(original.email);
    await expect(editor.getByLabel('Email nhận xác nhận')).toHaveAttribute('readonly', '');
    await expect(page.getByRole('button', {name: 'Thanh toán', exact: true})).toBeDisabled();
    await editor.getByLabel('Họ và tên', {exact: false}).fill(' ');
    await editor.getByLabel('Số điện thoại', {exact: false}).fill('abc');
    await editor.getByRole('button', {name: 'Lưu thông tin người đặt'}).click();
    await expect(editor.getByRole('alert')).toHaveCount(2); assert.equal(patches, 0);
    await editor.getByLabel('Họ và tên', {exact: false}).fill('Nguyễn Người Đặt');
    await editor.getByLabel('Số điện thoại', {exact: false}).fill('0912345678');
    await editor.getByRole('button', {name: 'Lưu thông tin người đặt'}).click();
    await expect(editor).toHaveCount(0);
    await expect(card).toContainText('Nguyễn Người Đặt'); await expect(card).toContainText('0912345678');
    await page.reload(); await expect(card).toContainText('Nguyễn Người Đặt');
    const saved = await get(path);
    assert.deepEqual(saved, {fullName: 'Nguyễn Người Đặt', phone: '0912345678', email: original.email});
    assert.deepEqual(await get('/users/me'), profileBefore);
    console.log('PASS edit/save/reload persists booking name and phone; email read-only; account unchanged; invalid input sends no PATCH');

    const forged = await api.patch(`/api${path}`, {headers, data: {fullName: 'Forged', phone: '0987654321', email: 'changed@example.test'}});
    assert.equal(forged.status(), 400); assert.deepEqual(await get(path), saved);
    assert.equal((await api.patch(`/api${path}`, {headers: {Authorization: `Bearer ${foreign.accessToken}`}, data: {fullName: 'Other', phone: '0987654321'}})).status(), 403);
    assert.equal((await api.patch(`/api${path}`, {data: {fullName: 'Other', phone: '0987654321'}})).status(), 401);
    console.log('PASS real API rejects email tampering (400), other owner (403), anonymous edits (401)');

    await card.getByRole('button', {name: 'Chỉnh sửa', exact: true}).click();
    await editor.getByLabel('Họ và tên', {exact: false}).fill('Unsaved name');
    await editor.getByRole('button', {name: 'Hủy', exact: true}).click();
    await expect(card).toContainText('Nguyễn Người Đặt'); assert.deepEqual(await get(path), saved);
    const current = await get(`/bookings/${hold.bookingId}`);
    assert.equal(current.expiresAt, storedBefore.expiresAt); assert.equal(Number(current.courtFee), Number(storedBefore.courtFee));
    assert.equal(current.status, 'PENDING'); assert.equal(current.details.length, 1); assert.equal(current.payments.length, 0);
    await expect(page.getByRole('button', {name: 'Thanh toán', exact: true})).toBeEnabled();
    await card.getByRole('button', {name: 'Chỉnh sửa', exact: true}).click();
    for (const width of [1440, 375, 320]) {
      await page.setViewportSize({width, height: 1000});
      assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false);
    }
    fs.mkdirSync('backups.local/checkout-contact-20261024', {recursive: true});
    await card.screenshot({path: 'backups.local/checkout-contact-20261024/contact-mobile.png'});
    await page.setViewportSize({width: 1440, height: 1000});
    await card.screenshot({path: 'backups.local/checkout-contact-20261024/contact-desktop.png'});
    await editor.getByRole('button', {name: 'Hủy', exact: true}).click();
    assert.deepEqual(errors, []);
    console.log('PASS cancel discards edits; price/deadline/details/payments unchanged; desktop/mobile fit, no checkout note or JS errors');
    await expect(page.locator('textarea')).toHaveCount(0);
  } finally { await browser.close(); await api.dispose(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
