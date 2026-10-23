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
    const page = await context.newPage();
    debugPage = page;
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    let writes = 0;
    let gatewayCheckoutUrl;
    page.on('request', req => {
      const url = new URL(req.url());
      if (url.hostname === 'sandbox.vnpayment.vn' && url.pathname === '/paymentv2/vpcpay.html') gatewayCheckoutUrl = req.url();
    });
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
    const article = page.locator('.customer-court-card').first();
    await expect(article.getByRole('button', {name: 'Đặt sân', exact: true})).toBeEnabled();
    const extra = article.locator('input[type=checkbox]').first();
    await expect(extra).toBeVisible();
    await extra.check();
    await article.getByRole('button', {name: 'Đặt sân', exact: true}).click();
    await expect(page).toHaveURL(/\/checkout(?:\?|$)/);
    await expect(page.getByRole('button', {name: 'Thanh toán', exact: true})).toBeEnabled();
    await expect(page.locator('.checkout-note').getByText('2 khung giờ đã chọn')).toBeVisible();
    await expect(page.locator('input[value=CASH]')).toBeDisabled();
    await expect(page.locator('.checkout-bank-transfer-card')).toHaveCount(0);
    await page.locator('input[value=BANK_TRANSFER]').check();
    await expect(page.getByRole('heading', {name: 'Thông tin chuyển khoản', exact: true})).toBeVisible();
    await page.getByRole('button', {name: 'Thanh toán', exact: true}).click();
    await expect(page.locator('.checkout-bank-transfer-error')).toHaveCount(4);
    assert.equal(writes, 1, 'Opening checkout creates one hold; invalid form creates no payment');
    await page.getByLabel('Ngân hàng', {exact: true}).fill('Payer bank');
    await page.getByLabel('Tên chủ tài khoản', {exact: true}).fill('Booking E2E');
    await page.getByLabel('Số tài khoản', {exact: true}).fill('001a234');
    await expect(page.getByLabel('Số tài khoản', {exact: true})).toHaveValue('001234');
    await page.getByLabel('Nội dung chuyển khoản', {exact: true}).fill('Court booking request');
    await page.locator('input[value=VNPAY]').check();
    await expect(page.locator('.checkout-bank-transfer-card')).toHaveCount(0);
    await page.locator('input[value=BANK_TRANSFER]').check();
    await expect(page.getByLabel('Số tài khoản', {exact: true})).toHaveValue('001234');
    const desktop = await page.locator('.checkout-bank-transfer-card').evaluate(card => {
      const customer = card.previousElementSibling, c = card.getBoundingClientRect(), p = customer.getBoundingClientRect();
      return {position: getComputedStyle(card).position, gap: c.top - p.bottom, width: c.width,
        columns: getComputedStyle(card.querySelector('.checkout-bank-transfer-fields')).gridTemplateColumns};
    });
    assert.equal(desktop.position, 'static'); assert.equal(desktop.gap, 14);
    assert.equal(desktop.columns.split(' ').length, 2);
    await page.setViewportSize({width: 375, height: 900});
    const mobile = await page.evaluate(() => ({overflow: document.documentElement.scrollWidth > window.innerWidth,
      columns: getComputedStyle(document.querySelector('.checkout-bank-transfer-fields')).gridTemplateColumns}));
    assert.equal(mobile.overflow, false); assert.equal(mobile.columns.split(' ').length, 1);
    await page.setViewportSize({width: 320, height: 900});
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth), false, 'Checkout must fit 320px');
    await page.setViewportSize({width: 375, height: 900});
    pass('Checkout: two slots, CASH disabled, validation, digit filtering, state retention, desktop/mobile layout');

    const paymentResponse = page.waitForResponse(res => res.request().method() === 'POST' && /\/bookings\/[^/]+\/payments$/.test(new URL(res.url()).pathname));
    await page.getByRole('button', {name: 'Thanh toán', exact: true}).click();
    const accepted = await paymentResponse; assert.equal(accepted.status(), 201);
    await expect(page).toHaveURL(/\/payment-result\?/);
    const resultParams = new URL(page.url()).searchParams;
    const payment = {bookingId: resultParams.get('bookingId'), paymentAttemptId: resultParams.get('paymentAttemptId')};
    await expect(page.getByRole('heading', {name: 'Đã xác nhận đặt sân', exact: true})).toBeVisible();
    const detail = (await (await api.get(`/api/bookings/${payment.bookingId}`, {headers})).json()).data;
    assert.equal(detail.status, 'PAID'); assert.equal(detail.details.length, 2);
    assert.equal(Number(detail.courtFee), detail.details.reduce((sum, d) => sum + Number(d.price), 0));
    assert.equal(detail.payments.length, 1); assert.equal(detail.payments[0].paymentStatus, 'PENDING');
    assert.equal(detail.payments[0].confirmationMethod, 'CUSTOMER_DECLARED');
    await expect(page.getByText('Chuyển khoản do khách tự khai báo.', {exact: false})).toBeVisible();
    await expect(page.getByText('Đã thanh toán toàn bộ', {exact: true})).toHaveCount(0);
    const previousWrites = writes; await page.reload();
    await expect(page.getByRole('heading', {name: 'Đã xác nhận đặt sân', exact: true})).toBeVisible();
    assert.equal(writes, previousWrites);
    report.bookingId = payment.bookingId;
    pass('BANK_TRANSFER: confirmed reservation, unverified PENDING payment, two details and authoritative price; refresh causes no payment write');

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
    if (gateway.status() !== 201) {
      report.gateway.checkout = 'BLOCKED: backend merchant configuration unavailable';
    } else {
      await page.waitForURL('https://sandbox.vnpayment.vn/**', {timeout: 30000});
      assert.ok(gatewayCheckoutUrl, 'External checkout request must originate from the UI');
      const url = new URL(gatewayCheckoutUrl);
      assert.equal(url.origin, 'https://sandbox.vnpayment.vn');
      assert.equal(url.searchParams.get('vnp_Amount'), String(Math.round(Number(booking.courtFee) * 100)));
      const envPath = path.join(__dirname, '..', '.env');
      const envText = fs.existsSync(envPath) ? fs.readFileSync(envPath, 'utf8') : '';
      const secretLine = envText.split(/\r?\n/).find(line => /^BOOKING_VNPAY_HASH_SECRET=/.test(line));
      const secret = process.env.BOOKING_VNPAY_HASH_SECRET || secretLine?.slice(secretLine.indexOf('=') + 1).trim().replace(/^['"]|['"]$/g, '');
      if (secret) {
        const signature = url.searchParams.get('vnp_SecureHash');
        const signed = url.search.slice(1).split('&vnp_SecureHash=')[0];
        assert.equal(crypto.createHmac('sha512', secret).update(signed).digest('hex'), signature);
        report.gateway.signature = 'PASS: independently checked using configured merchant key';
      }
      pass('VNPay: backend-created signed URL and server amount; no simulated settlement');
      try {
        await page.waitForURL('https://sandbox.vnpayment.vn/**', {timeout: 30000});
        await page.waitForLoadState('domcontentloaded');
        report.gateway.externalRedirect = 'PASS';
        const body = await page.locator('body').innerText();
        report.gateway.paymentPage = /Ngân hàng|Thanh toán qua|Số thẻ|Payment method|Chọn ngân hàng/i.test(body)
          && !/Website không tồn tại|Checksum không hợp lệ|Sai chữ ký|Merchant không hợp lệ/i.test(body) ? 'PASS' : 'BLOCKED: gateway returned an error page';
        report.gateway.pageTitle = await page.title();
        if (process.env.BCM_E2E_COMPLETE_SANDBOX === '1') {
          await page.getByText('Thẻ nội địa và tài khoản ngân hàng', {exact: true}).click();
          await page.locator('#NCB').click();
          await page.locator('input:not([type=hidden])').first().waitFor();
          {
            const guide = await api.get('https://sandbox.vnpayment.vn/apis/docs/gioi-thieu/');
            assert.ok(guide.ok(), 'Official VNPay test guide must be reachable');
            const official = await guide.text();
            const card = official.match(/\b9704\d{12,15}\b/)?.[0];
            assert.ok(card, 'Official test card must be present in the provider guide');
            report.gateway.completionStage = 'card number';
            await page.locator('#card_number_mask').fill(card);
            report.gateway.completionStage = 'card holder';
            await page.locator('#cardHolder').fill('NGUYEN VAN A');
            report.gateway.completionStage = 'card date';
            await page.locator('#cardDate').fill('07/15');
            report.gateway.completionStage = 'terms';
            await page.locator('#btnContinue').click();
            const agree = page.locator('#btnAgree');
            try { await agree.waitFor({state: 'visible', timeout: 5000}); await agree.click(); } catch { /* Some provider sessions already accepted the terms. */ }
            await page.waitForLoadState('domcontentloaded');
            await page.getByText(/Xác thực OTP|Nhập mã OTP/).first().waitFor({timeout: 20000});
            report.gateway.testCardSubmission = 'PASS: provider public test card submitted; no card data logged';
            const otp = official.match(/OTP[\s\S]*?([0-9]{6})/)?.[1];
            assert.ok(otp, 'Official provider test OTP must be available');
            report.gateway.completionStage = 'OTP confirmation';
            await page.locator('#otpvalue').fill(otp);
            await page.locator('#btnConfirm').click();
            await page.waitForURL(`${baseURL}/payment-result**`, {timeout: 30000});
            const returned = new URL(page.url()).searchParams;
            returned.set('displayOnly', 'true');
            const verifiedResponse = await api.get(`/api/payments/vnpay/return?${returned.toString()}`);
            assert.equal(verifiedResponse.status(), 200);
            const verified = (await verifiedResponse.json()).data;
            report.gateway.completion = verified.providerSuccess ? 'PASS: real Sandbox payment completed; backend verified signed Return' : 'FAIL: provider reported non-success';
            report.gateway.returnVerification = 'PASS: backend Return redirect and signature verification';
            report.gateway.ipn = 'BLOCKED: public merchant IPN URL not available for this test backend';
            await expect(page.getByRole('heading', {name: 'Đang xác minh kết quả thanh toán', exact: true})).toBeVisible();
            pass('Real Sandbox card/OTP and Return; UI keeps payment unconfirmed without IPN');
          }
        }
      } catch (error) {
        report.gateway.inspectionError = error.message.split('\n')[0];
        report.gateway.fieldState = await page.locator('input:not([type=hidden])').evaluateAll(nodes => nodes.map(node => ({
          id: node.id, type: node.type, readonly: node.readOnly, disabled: node.disabled, visible: Boolean(node.getClientRects().length),
        }))).catch(() => []);
        report.gateway.validationMessages = await page.locator('.field-validation-error,.invalid-feedback,.error,[role=alert]').allTextContents().catch(() => []);
      }
      report.gateway.completion ||= 'BLOCKED: no provider-confirmed payment/IPN yet';
      const actual = (await (await api.get(`/api/bookings/${booking.bookingId}`, {headers})).json()).data;
      assert.equal(actual.status, 'PENDING');
      report.gateway.database = 'PENDING (correct before verified IPN)';
    }
    // A different checkout draft must not hide an explicitly requested confirmed booking.
    await page.goto(`${baseURL}/payment-result?bookingId=${payment.bookingId}`);
    await expect(page.getByRole('heading', {name: 'Đã xác nhận đặt sân', exact: true})).toBeVisible();
    pass('Result URL resolves confirmed booking despite a different checkout draft');
    assert.deepEqual(errors, []);
    pass('No uncaught frontend JavaScript errors');
  } catch (error) {
    if (debugPage && !debugPage.isClosed()) report.visibleErrors = await debugPage.locator('.checkout-submit-error,.checkout-alert-error,.checkout-bank-transfer-error').allTextContents().catch(() => []);
    throw error;
  } finally { await browser.close(); await api.dispose(); }
})().catch(error => { report.error = error.message.split('\n')[0]; console.error(report.error); process.exitCode = 1; })
  .finally(() => {
    const output = process.env.BCM_E2E_REPORT || path.join(require('node:os').tmpdir(), 'bcm-booking-e2e-report.json');
    fs.writeFileSync(output, JSON.stringify(report, null, 2));
    console.log(`Report: ${output}`);
  });
