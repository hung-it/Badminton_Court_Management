const {requireTestEnvironment,fakeCheckout}=require('./payment-test-fixture.cjs');
requireTestEnvironment();
// Requires the existing root Playwright dependency, a real backend/PostgreSQL and available court data.
// Run only against a dedicated test database: this registers users and creates real pending bookings.
const {chromium, request, expect} = require('playwright/test');
const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');

const baseURL = process.env.BCM_E2E_WEB_URL || 'http://127.0.0.1:5174';
const report = {environment: baseURL, mockApi: false, checks: [], gateway: {}};
let debugPage;
const pass = name => { report.checks.push({name, result: 'PASS'}); console.log(`PASS ${name}`); };
const dateAfter = days => { const date = new Date(); date.setDate(date.getDate() + days + Number(process.env.BCM_E2E_DAY_OFFSET || 0)); return date.toLocaleDateString('en-CA'); };

(async () => {
  const api = await request.newContext({baseURL});
  for (let attempt = 0; attempt < 30; attempt++) {
    const health = await api.get('/api/health').catch(() => null);
    if (health?.ok()) break;
    if (attempt === 29) throw new Error('Backend health is unavailable');
    await new Promise(resolve => setTimeout(resolve, 1000));
  }
  const register = async suffix => {
    const response = await api.post('/api/auth/register', {data: {email: `booking-e2e-${Date.now()}-${suffix}@example.test`,
      password: crypto.randomBytes(18).toString('hex'), fullName: 'Booking E2E', phone: '0900000000'}});
    assert.equal(response.status(), 201, `Registration HTTP ${response.status()}`);
    return (await response.json()).data;
  };
  const owner = await register('owner');
  const headers = {Authorization: `Bearer ${owner.accessToken}`};
  const browser = await chromium.launch({channel: 'msedge', headless: true});
  try {
    const context = await browser.newContext({viewport: {width: 1440, height: 1000}});
    await context.addInitScript(session => {
      sessionStorage.setItem('bcm.accessToken', session.accessToken);
      sessionStorage.setItem('bcm.user', JSON.stringify(session.user));
    }, owner);
    const page = await context.newPage();await fakeCheckout(page,api);
    debugPage = page;
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    let writes = 0;
    page.on('request', req => { if (req.method() === 'POST' && /\/bookings/.test(req.url())) writes++; });
    page.on('response', async res => {
      if (res.request().method() === 'POST' && /\/api\/bookings/.test(res.url())) {
        let body = {}; try { body = await res.json(); } catch { /* Transport failure. */ }
        (report.httpWrites ||= []).push({path: new URL(res.url()).pathname, status: res.status(), message: body.message});
      }
    });
    await page.goto(`${baseURL}/courts`);
    await page.getByLabel('Ngày chơi', {exact: true}).fill(dateAfter(2));
    const availability = (await (await api.get(`/api/availability?date=${dateAfter(2)}`, {headers})).json()).data;
    const freeCourt = availability.courts.find(court => court.slots.filter(slot => slot.available).length >= 2);
    assert.ok(freeCourt, 'A court with two available slots is required');
    await page.getByLabel('Khung giờ', {exact: true}).selectOption(freeCourt.slots.find(slot => slot.available).timeSlotId);
    const article = page.locator(`[data-court-id="${freeCourt.courtId}"]`);
    await article.locator(`.customer-extra-slots input[value="${freeCourt.slots.find(slot => slot.available).timeSlotId}"]`).check();
    await expect(article.getByRole('button', {name: 'Đặt sân', exact: true})).toBeEnabled();
    const extra = article.locator('.customer-extra-slots input[type=checkbox]:not(:checked)').first();
    await expect(extra).toBeVisible();
    await extra.check();
    await article.getByRole('button', {name: 'Đặt sân', exact: true}).click();
    await expect(page).toHaveURL(/\/checkout(?:\?|$)/);
    await expect(page.getByRole('button', {name: 'Thanh toán', exact: true})).toBeEnabled();
    await expect(page.locator('.checkout-note').getByText('2 khung giờ đã chọn')).toBeVisible();
    await expect(page.locator('.checkout-methods input')).toHaveCount(1);
    await expect(page.locator('input[value=VNPAY]')).toBeChecked();
    for(const width of [1440,375,320]){await page.setViewportSize({width,height:900});assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false);}
    pass('Checkout: VNPay-only, server total and responsive layout');
    const paymentResponse = page.waitForResponse(res => res.request().method() === 'POST' && /\/bookings\/[^/]+\/payments$/.test(new URL(res.url()).pathname));
    await page.getByRole('button', {name: 'Thanh toán', exact: true}).click();
    const accepted = await paymentResponse; assert.equal(accepted.status(), 201);
    await expect(page).toHaveURL(/\/payment-result\?/);
    const resultParams = new URL(page.url()).searchParams;
    const payment = {bookingId: resultParams.get('bookingId'), paymentAttemptId: resultParams.get('paymentAttemptId')};
    await expect(page.getByRole('heading', {name: 'Thanh toán thành công', exact: true})).toBeVisible();
    const detail = (await (await api.get(`/api/bookings/${payment.bookingId}`, {headers})).json()).data;
    assert.equal(detail.status, 'PAID'); assert.equal(detail.details.length, 2);
    assert.equal(Number(detail.courtFee), detail.details.reduce((sum, d) => sum + Number(d.price), 0));
    assert.equal(detail.payments.length, 1); assert.equal(detail.payments[0].paymentStatus, 'SUCCESS');
    assert.equal(detail.payments[0].paymentMethod,'VNPAY');
    await expect(page.getByText('Chuyển khoản do khách tự khai báo.', {exact: false})).toHaveCount(0);
    await expect(page.locator('.payment-result-total span')).toHaveText('VNPay');
    await expect(page.getByText('Đã thanh toán toàn bộ', {exact: true})).toHaveCount(0);
    const previousWrites = writes; await page.reload();
    await expect(page.getByRole('heading', {name: 'Thanh toán thành công', exact: true})).toBeVisible();
    assert.equal(writes, previousWrites);
    report.bookingId = payment.bookingId;
    pass('VNPay fixture: confirmed reservation, unverified PENDING payment, two details and authoritative price; refresh causes no payment write');

    const cash = await api.post(`/api/bookings/${payment.bookingId}/payments`, {headers, data: {paymentMethod: 'CASH'}});
    assert.equal(cash.status(), 400);
    const foreign = await register('foreign');
    const foreignHeaders = {Authorization: `Bearer ${foreign.accessToken}`};
    assert.equal((await api.get(`/api/bookings/${payment.bookingId}`, {headers: foreignHeaders})).status(), 403);
    const conflict = await api.post('/api/bookings', {headers, data: {customerId: detail.customerId,
      details: detail.details.map(({courtId, timeSlotId, bookingDate}) => ({courtId, timeSlotId, bookingDate}))}});
    assert.equal(conflict.status(), 409);
    assert.equal((await api.get(`/api/bookings/${payment.bookingId}`)).status(), 401);
    pass('Real HTTP: CASH 400, conflicting booking 409, foreign detail 403, anonymous detail 401');
    await page.goto(`${baseURL}/my-bookings`);
    await expect(page.locator('#upcoming-booking')).toBeVisible();
    await expect(page.locator('#booking-history')).toHaveCount(0);
    await page.setViewportSize({width: 320, height: 900});
    report.mobileOverflow = await page.evaluate(() => [...document.querySelectorAll('body *')].filter(node => node.getBoundingClientRect().right > window.innerWidth + 1).slice(0, 10).map(node => ({tag: node.tagName, className: String(node.className), width: node.getBoundingClientRect().width})));
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth), false, 'Courts page must fit mobile width');
    await page.setViewportSize({width: 375, height: 900});
    pass('Courts renders without inline booking history');

    const selected = detail.details[0];
    const created = await api.post('/api/bookings', {headers, data: {customerId: detail.customerId,
      details: [{courtId: selected.courtId, timeSlotId: selected.timeSlotId, bookingDate: dateAfter(3)}]}});
    assert.equal(created.status(), 201); const booking = (await created.json()).data;
    await page.evaluate(draft => sessionStorage.setItem('bcm-booking-draft', JSON.stringify(draft)),
      {bookingId: booking.bookingId, courtId: selected.courtId, timeSlotId: selected.timeSlotId, bookingDate: dateAfter(3)});
    await page.goto(`${baseURL}/checkout`);
    await expect(page.getByRole('button', {name: 'Thanh toán', exact: true})).toBeEnabled();
    const gatewayResponse = page.waitForResponse(res => res.request().method() === 'POST' && /\/payments$/.test(new URL(res.url()).pathname));
    await page.getByRole('button', {name: 'Thanh toán', exact: true}).click();
    const gateway = await gatewayResponse;
    report.gateway.initiationHttpStatus = gateway.status();
    assert.equal(gateway.status(),201);
    await expect(page).toHaveURL(/payment-result/);
    report.gateway.checkout='Controlled signed callback fixture; no Sandbox E2E';
    assert.deepEqual(errors, []);
    pass('No uncaught frontend JavaScript errors');
  } catch (error) {
    if (debugPage && !debugPage.isClosed()) report.visibleErrors = await debugPage.locator('.checkout-submit-error,.checkout-alert-error').allTextContents().catch(() => []);
    throw error;
  } finally { await browser.close(); await api.dispose(); }
})().catch(error => { report.error = error.message.split('\n')[0]; console.error(report.error); process.exitCode = 1; })
  .finally(() => {
    const output = process.env.BCM_E2E_REPORT || path.join(require('node:os').tmpdir(), 'bcm-booking-e2e-report.json');
    fs.writeFileSync(output, JSON.stringify(report, null, 2));
    console.log(`Report: ${output}`);
  });
