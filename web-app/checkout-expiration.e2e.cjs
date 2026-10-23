// Real API/browser/PostgreSQL checks. Uses only the isolated audit DB, never the app DB.
const {chromium, request, expect} = require('playwright/test');
const {execFileSync} = require('node:child_process');
const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const baseURL = process.env.BCM_E2E_WEB_URL || 'http://127.0.0.1:5174';
const bankTransfer = {bankName: 'Test bank', accountHolder: 'Test payer', accountNumber: '123456', transferContent: 'Test declaration'};

(async () => {
  const api = await request.newContext({baseURL});
  const registered = await api.post('/api/auth/register', {data: {email: `expiration-${crypto.randomUUID()}@example.test`,
    password: crypto.randomBytes(18).toString('hex'), fullName: 'Expiration E2E', phone: '0900000000'}});
  assert.equal(registered.status(), 201);
  const session = (await registered.json()).data;
  const headers = {Authorization: `Bearer ${session.accessToken}`};
  const customerId = (await (await api.get('/api/bookings/customer-profile', {headers})).json()).data.customerId;
  const date = new Date(); date.setDate(date.getDate() + 45);
  const bookingDate = date.toLocaleDateString('en-CA');
  const grid = (await (await api.get(`/api/availability?date=${bookingDate}`, {headers})).json()).data;
  const court = grid.courts.find(c => c.slots.some(s => s.available));
  assert.ok(court);
  const slot = court.slots.find(s => s.available);
  const created = await api.post('/api/bookings', {headers, data: {customerId,
    details: [{bookingDate, courtId: court.courtId, timeSlotId: slot.timeSlotId}]}});
  assert.equal(created.status(), 201);
  const booking = (await created.json()).data;
  assert.match(booking.bookingId, /^[0-9a-f-]{36}$/i);
  // Fixture mutation is constrained to this test-created ID in the dedicated audit database.
  const setDeadline = seconds => {
    assert.ok(Number.isInteger(seconds));
    const changed = execFileSync('docker', ['exec', 'bcm-booking-audit-20261022', 'psql', '-U', 'bcm_test',
      '-d', 'bcm_booking_audit', '-v', 'ON_ERROR_STOP=1', '-c',
      `UPDATE bookings SET expires_at=LOCALTIMESTAMP + interval '${seconds} seconds' WHERE id='${booking.bookingId}' AND customer_id='${customerId}' AND status='PENDING'`], {encoding: 'utf8'});
    assert.match(changed, /UPDATE 1/);
  };
  const browser = await chromium.launch({channel: 'msedge', headless: true});
  try {
    const context = await browser.newContext();
    await context.addInitScript(s => {
      sessionStorage.setItem('bcm.accessToken', s.accessToken);
      sessionStorage.setItem('bcm.user', JSON.stringify(s.user));
    }, session);
    const page = await context.newPage();
    const errors = []; let writes = 0;
    page.on('pageerror', error => errors.push(error.message));
    context.on('request', req => { if (req.method() === 'POST' && /\/bookings/.test(req.url())) writes++; });
    await page.goto(`${baseURL}/checkout?bookingId=${booking.bookingId}`);
    await expect(page.getByRole('button', {name: 'Thanh toán', exact: true})).toBeEnabled();
    const readClock = async p => {
      await expect(p.locator('.checkout-alert strong').first()).toHaveText(/^\d\d:\d\d$/);
      const text = await p.locator('.checkout-alert strong').first().innerText();
      const [m, s] = text.split(':').map(Number); return m * 60 + s;
    };
    const before = await readClock(page);
    assert.ok(before > 540 && before <= 600);
    await page.reload();
    const after = await readClock(page);
    assert.ok(after <= before);
    const tab = await context.newPage();
    await tab.goto(page.url()); // No booking draft copied: reconstruct from owned backend data.
    const other = await readClock(tab);
    assert.ok(Math.abs(other - after) <= 3);
    assert.equal(writes, 0, 'StrictMode, refresh and new tab must not create a booking or payment');
    await page.locator('input[value=BANK_TRANSFER]').check();
    assert.ok(await readClock(page) <= after, 'Method switch must not reset the deadline');
    setDeadline(330);
    await page.evaluate(() => window.dispatchEvent(new Event('focus')));
    await expect.poll(() => readClock(page)).toBeLessThanOrEqual(330);
    assert.ok(await readClock(page) >= 325);
    setDeadline(59);
    await page.evaluate(() => window.dispatchEvent(new Event('focus')));
    await expect(page.locator('.checkout-alert-error')).toContainText('Sắp hết thời gian giữ sân');
    assert.ok(await readClock(page) <= 59);
    for (const [name, value] of Object.entries(bankTransfer)) await page.locator(`#checkout-${name}`).fill(value);
    setDeadline(2);
    await page.evaluate(() => window.dispatchEvent(new Event('focus')));
    await expect.poll(() => readClock(page)).toBeLessThanOrEqual(2);
    await expect(page.getByRole('button', {name: 'Thanh toán', exact: true})).toBeDisabled({timeout: 5000});
    const late = await api.post(`/api/bookings/${booking.bookingId}/payments`, {headers, data: {paymentMethod: 'BANK_TRANSFER', bankTransfer}});
    assert.equal(late.status(), 409, 'Backend rejects after natural countdown expiry even while the other tab has stale state');
    await page.evaluate(() => window.dispatchEvent(new Event('focus')));
    await expect(page.getByRole('button', {name: 'Thanh toán', exact: true})).toBeDisabled();
    await expect(page.getByText('Thời gian giữ sân 10 phút đã hết.', {exact: false})).toBeVisible();
    await expect(page.locator('.checkout-alert-error').getByText('00:00', {exact: true})).toBeVisible();
    await expect(page.locator('.checkout-expired').locator('a,button')).toHaveCount(0);
    for (const width of [1440, 375, 320]) {
      await page.setViewportSize({width, height: 1000});
      const layout = await page.locator('.checkout-expired').evaluate(node => {
        const text = node.querySelector('p'), style = getComputedStyle(node);
        return {children: node.children.length, gap: style.gap, background: style.backgroundColor,
          border: style.borderColor, color: style.color, margin: getComputedStyle(text).margin,
          trailing: node.getBoundingClientRect().bottom - text.getBoundingClientRect().bottom};
      });
      assert.equal(layout.children, 2); assert.equal(layout.gap, '8px'); assert.equal(layout.margin, '0px');
      assert.equal(layout.background, 'rgb(255, 247, 247)'); assert.equal(layout.border, 'rgb(254, 202, 202)');
      assert.equal(layout.color, 'rgb(153, 27, 27)'); assert.ok(layout.trailing <= 14);
      assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false);
    }
    await expect(page).toHaveURL(new RegExp(`/checkout\\?bookingId=${booking.bookingId}$`));
    assert.equal(writes, 0);
    assert.deepEqual(errors, []);
    const detail = (await (await api.get(`/api/bookings/${booking.bookingId}`, {headers})).json()).data;
    assert.equal(detail.payments.length, 0);
    console.log('PASS real PostgreSQL/API/browser: countdown, expiry, API rejection, expired clock/message without home action, compact desktop/mobile layout, no redirect/duplicate writes/runtime errors');
  } finally { await browser.close(); await api.dispose(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
