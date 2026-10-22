import assert from 'node:assert/strict';
import {mkdir, readFile, writeFile} from 'node:fs/promises';
await mkdir('.tools/web-review', {recursive: true});
// Advance the fixture date on each run so held slots from an earlier run do not collide.
let fixtureDay = new Date();
fixtureDay.setDate(fixtureDay.getDate() + 30);
try { fixtureDay = new Date(`${(await readFile('.tools/web-review/last-date.txt', 'utf8')).trim()}T12:00:00`); } catch { /* First run. */ }
fixtureDay.setDate(fixtureDay.getDate() + 1);
const bookingDate = `${fixtureDay.getFullYear()}-${String(fixtureDay.getMonth() + 1).padStart(2, '0')}-${String(fixtureDay.getDate()).padStart(2, '0')}`;
await writeFile('.tools/web-review/last-date.txt', bookingDate);
const {chromium, expect} = await import(process.env.PLAYWRIGHT_MODULE || 'playwright/test');
const base = process.env.BCM_WEB_TEST_URL || 'http://127.0.0.1:5174';
const browser = await chromium.launch({channel: process.env.BCM_BROWSER_CHANNEL || 'msedge', headless: true});
const context = await browser.newContext({viewport: {width: 1440, height: 1000}});
const page = await context.newPage();
const errors = [];
page.on('pageerror', error => errors.push(error.message));
page.on('console', message => {
  if (message.type() === 'error' && /Cannot update a component|rendering a different component/.test(message.text())) errors.push(message.text());
});
const email = `web-review-${Date.now()}@example.test`;
const password = 'review-test-only';
let bookingCreates = 0;
let paymentRequests = 0;
page.on('request', request => {
  if (request.method() !== 'POST') return;
  if (new URL(request.url()).pathname === '/api/bookings') bookingCreates += 1;
  if (/\/api\/bookings\/[^/]+\/payments$/.test(new URL(request.url()).pathname)) paymentRequests += 1;
});

try {
  await page.goto(base);
  await expect(page.getByRole('heading', {name: 'Sân nổi bật hôm nay'})).toBeVisible();
  await expect(page.locator('#search-slot option')).not.toHaveCount(1);
  await page.locator('.court-card__link').first().click();
  await expect(page).toHaveURL(/\/courts\?courtId=/);
  await expect(page.locator('.customer-court-card')).toHaveCount(1);
  await page.getByRole('button', {name: 'Đặt lại bộ lọc', exact: true}).click();
  await expect(page.locator('.customer-court-card')).not.toHaveCount(1);
  await page.goto(base);
  await expect(page.locator('#search-slot option')).not.toHaveCount(1);
  await page.locator('#search-date').fill(bookingDate);
  const slotId = await page.locator('#search-slot option').nth(1).getAttribute('value');
  await page.locator('#search-slot').selectOption(slotId);
  await page.getByRole('button', {name: 'Giá tốt', exact: true}).click();
  await page.getByRole('button', {name: 'Tìm sân phù hợp'}).click();
  await expect(page).toHaveURL(/\/courts\?/);
  await expect(page.getByLabel('Ngày chơi', {exact: true})).toHaveValue(bookingDate);
  await expect(page.getByLabel('Khung giờ', {exact: true})).toHaveValue(slotId);
  await expect(page.getByLabel('Sắp xếp sân')).toHaveValue('price');
  await expect(page.locator('header')).toHaveCount(1);
  await expect(page.locator('.customer-book-button').first()).toBeEnabled();
  await page.locator('.customer-book-button').first().click();
  await expect(page).toHaveURL(/\/login\?redirect=%2Fcheckout/);
  await page.getByRole('link', {name: 'Đăng ký', exact: true}).last().click();
  await expect(page).toHaveURL(/\/register\?redirect=%2Fcheckout/);
  console.log('PASS home search filters, guest booking draft and auth redirect');

  await page.getByLabel('Họ và tên *', {exact: true}).fill('Booking Web Review');
  await page.getByLabel('Email *', {exact: true}).fill(email);
  await page.getByLabel('Số điện thoại *', {exact: true}).fill('090 1234567');
  await page.getByLabel('Mật khẩu *', {exact: true}).fill(password);
  await page.getByLabel('Xác nhận mật khẩu *', {exact: true}).fill(password);
  await page.getByLabel(/Tôi đồng ý/).check();
  await page.getByLabel('Ghi nhớ đăng nhập', {exact: true}).uncheck();
  await page.getByRole('button', {name: 'Tạo tài khoản', exact: true}).click();
  await expect(page.getByText('Số điện thoại phải là 10–20 chữ số, không khoảng trắng hoặc dấu +.', {exact: true})).toBeVisible();
  await page.getByLabel('Số điện thoại *', {exact: true}).fill('0901234567');
  await page.getByRole('button', {name: 'Tạo tài khoản', exact: true}).click();
  await expect(page).toHaveURL(`${base}/checkout`);
  const pay = page.getByRole('button', {name: 'Thanh toán', exact: true});
  await expect(pay).toBeEnabled();
  assert.equal(await page.evaluate(() => localStorage.getItem('bcm.user')), null);
  assert.equal(await page.evaluate(() => localStorage.getItem('bcm.refreshToken')), null);
  console.log('PASS registration validation, session-only credentials, shared checkout session');

  await pay.click();
  await expect(page.locator('.checkout-submit-error')).toBeVisible();
  await expect(pay).toBeEnabled();
  assert.equal(bookingCreates, 1);
  assert.equal(paymentRequests, 1);
  const bookingId = await page.evaluate(() => JSON.parse(sessionStorage.getItem('bcm-booking-draft')).bookingId);
  assert.ok(bookingId);
  await page.reload();
  await expect(pay).toBeEnabled();
  await expect(page.locator('.checkout-alert-error')).toHaveCount(0);
  await pay.click();
  await expect(page.locator('.checkout-submit-error')).toBeVisible();
  assert.equal(bookingCreates, 1, 'Reload/retry must reuse the held booking');
  assert.equal(paymentRequests, 2);
  const booking = await page.evaluate(async id => {
    const response = await fetch(`/api/bookings/${id}`, {headers: {Authorization: `Bearer ${sessionStorage.getItem('bcm.accessToken')}`}});
    return (await response.json()).data;
  }, bookingId);
  assert.equal(booking.status, 'PENDING');
  assert.equal(booking.details.length, 1);
  assert.equal(booking.payments.length, 0, 'Missing provider configuration must not create a payment');
  await page.screenshot({path: '.tools/web-review/checkout-desktop.png', fullPage: true});
  console.log('PASS server-resolved customer ID, booking creation and retry after provider 503');

  await page.goto(`${base}/guide`);
  await expect(page.locator('header')).toHaveCount(1);
  await page.goto(`${base}/contact`);
  await expect(page.locator('header')).toHaveCount(1);
  const invalidReturn = page.waitForResponse(response => response.url().includes('/api/payments/vnpay/return'));
  await page.goto(`${base}/payment-result?vnp_ResponseCode=00&vnp_TransactionStatus=00&vnp_SecureHash=invalid`);
  assert.ok([400, 503].includes((await invalidReturn).status()), 'Fake provider return must be rejected, including unconfigured provider');
  await expect(page.getByText('Thanh toán thành công', {exact: true})).toHaveCount(0);
  await page.goto(base);
  await page.getByRole('button', {name: 'Đăng xuất', exact: true}).first().click();
  assert.equal(await page.evaluate(() => sessionStorage.getItem('bcm.accessToken')), null);
  assert.equal(await page.evaluate(() => sessionStorage.getItem('bcm-booking-draft')), null);
  await page.goto(`${base}/login?redirect=%2F%2Fevil.example`);
  await page.getByLabel('Email *', {exact: true}).fill(email);
  await page.getByLabel('Mật khẩu *', {exact: true}).fill('incorrect-password');
  await page.getByRole('button', {name: 'Đăng nhập', exact: true}).click();
  await expect(page.getByRole('alert')).toHaveText('Email hoặc mật khẩu không đúng.');
  await page.getByLabel('Mật khẩu *', {exact: true}).fill(password);
  await page.getByRole('button', {name: 'Đăng nhập', exact: true}).click();
  await expect(page).toHaveURL(`${base}/`);
  console.log('PASS linked pages, unverified payment result, logout and safe login redirect');

  await page.evaluate(() => sessionStorage.clear());
  await page.reload();
  await expect(page.getByRole('button', {name: 'Đăng xuất', exact: true}).first()).toBeVisible();
  assert.ok(await page.evaluate(() => sessionStorage.getItem('bcm.accessToken')));
  const refreshes = [];
  page.on('request', request => { if (request.url().endsWith('/api/auth/refresh')) refreshes.push(request.url()); });
  await page.evaluate(() => sessionStorage.setItem('bcm.accessToken', 'expired-test-token'));
  await page.goto(`${base}/courts`);
  await expect(page.locator('.customer-court-card').first()).toBeVisible();
  await expect(page.locator('.customer-book-button').first()).toBeEnabled();
  assert.equal(refreshes.length, 1, 'Concurrent 401 responses should share one refresh');
  console.log('PASS remembered session restoration and single refresh across booking requests');

  for (const path of ['/', '/login', '/register', '/courts', '/guide', '/contact', '/checkout', '/payment-result']) {
    await page.goto(`${base}${path}`);
    await expect(page.locator('header')).toHaveCount(1);
  }
  await page.goto(base);
  for (const width of [1440, 1280, 1024, 768]) {
    await page.setViewportSize({width, height: 1000});
    await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(width + 1);
  }
  await page.setViewportSize({width: 1440, height: 1000});
  await page.screenshot({path: '.tools/web-review/home-desktop.png', fullPage: true});
  await page.setViewportSize({width: 390, height: 844});
  await page.goto(base);
  assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1));
  await page.screenshot({path: '.tools/web-review/home-mobile.png', fullPage: true});
  await page.goto(`${base}/courts`);
  await expect(page.locator('.customer-court-card').first()).toBeVisible();
  assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1));
  await page.screenshot({path: '.tools/web-review/courts-mobile.png', fullPage: true});
  await page.evaluate(() => {
    sessionStorage.setItem('bcm.accessToken', 'expired-test-token');
    localStorage.setItem('bcm.refreshToken', 'invalid-test-refresh');
  });
  await page.goto(`${base}/checkout`);
  await expect(page).toHaveURL(/\/login\?redirect=%2Fcheckout/);
  assert.equal(await page.evaluate(() => sessionStorage.getItem('bcm.accessToken')), null);
  assert.deepEqual(errors, []);
  console.log('PASS direct routes, one layout per page, responsive widths and no runtime/render errors');
} finally {
  await browser.close();
}
