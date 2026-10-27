// Frontend-only fixtures: no backend or database writes. Verify the /courts draft handoff.
const {chromium, expect} = require('playwright/test');
const assert = require('node:assert/strict');
const baseURL = process.env.BCM_E2E_WEB_URL || 'http://127.0.0.1:5174';
const date = '2031-02-20';
const money = value => new Intl.NumberFormat('vi-VN', {style: 'currency', currency: 'VND', maximumFractionDigits: 0}).format(value);
const templates = [
  {id: 'past', startTime: '10:00:00', endTime: '11:00:00'},
  {id: 'normal', startTime: '12:00:00', endTime: '13:00:00'},
  {id: 'later', startTime: '13:00:00', endTime: '14:00:00'},
  {id: 'peak', startTime: '17:00:00', endTime: '18:00:00'},
  {id: 'occupied', startTime: '18:00:00', endTime: '19:00:00'},
];
const courts = ['a', 'b'].map((id, i) => ({id, courtNumber: i + 1, name: `Court ${id}`,
  type: 'STANDARD_MAT', district: i ? 'Quận 7' : 'Quận 1', address: 'Test address', basePrice: 100000}));

(async () => {
  const browser = await chromium.launch({channel: 'msedge', headless: true});
  try {
    const page = await browser.newPage({viewport: {width: 1440, height: 1000}, timezoneId: 'America/Los_Angeles'});
    page.setDefaultTimeout(15000);
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await page.clock.install({time: new Date(`${date}T11:59:59+07:00`)});
    await page.clock.pauseAt(new Date(`${date}T11:59:59+07:00`));
    await page.addInitScript(() => {
      sessionStorage.setItem('bcm.accessToken', 'frontend-fixture');
      sessionStorage.setItem('bcm.user', JSON.stringify({id: 'fixture', roles: ['CUSTOMER'], fullName: 'Test'}));
    });
    let occupied = false;
    await page.route('**/api/**', async route => {
      if (route.request().method()==='POST' && new URL(route.request().url()).pathname.endsWith('/bookings')) {
        await route.fulfill({status:201,json:{success:true,data:{bookingId:'new-fixture-booking'}}});return;
      }
      assert.equal(route.request().method(), 'GET');
      const url = new URL(route.request().url());
      let data;
      if (url.pathname.endsWith('/public/booking-clock')) data = {serverTime: `${date}T11:59:59+07:00`, today: date, timeZone: 'Asia/Ho_Chi_Minh'};
      else if (url.pathname.endsWith('/public/courts')) data = courts;
      else if (url.pathname.endsWith('/public/branches')) data = [];
      else if (url.pathname.endsWith('/public/time-slots')) data = templates;
      else if (url.pathname.endsWith('/public/availability')) data = {date, courts: courts.map(court => ({courtId: court.id,
        slots: templates.map(slot => ({...slot, timeSlotId: slot.id, available: slot.id !== 'occupied' && !(occupied && court.id === 'a' && slot.id === 'later'),
          price: slot.id === 'peak' ? 150000 : 100000}))}))};
      else if (url.pathname.endsWith('/bookings/customer-profile')) data = {customerId:'fixture-customer'};
      else if (url.pathname.endsWith('/bookings')) data = {content: [], totalElements: 0};
      else throw new Error(`Unexpected API ${url.pathname}`);
      await route.fulfill({json: {success: true, data}});
    });
    // Inspect the exact navigation payload without executing checkout or creating a hold.
    await page.route('**/checkout', route => route.fulfill({contentType: 'text/html', body: '<p>Checkout handoff</p>'}));
    await page.goto(`${baseURL}/courts?date=${date}&timeSlotId=normal`, {waitUntil: 'domcontentloaded'});
    const card = id => page.locator(`[data-court-id="${id}"]`);
    const check = (id, slot) => card(id).locator(`input[value="${slot}"]`);
    const price = id => card(id).locator('.customer-court-price-row strong');
    const book = id => card(id).getByRole('button', {name: 'Đặt sân', exact: true});
    const selectCourt = id => card(id).locator(`.customer-branch-courts input[value="${id}"]`);
    await expect(page.locator('.customer-court-card')).toHaveCount(2);
    for (const id of ['a', 'b']) {
      await expect(card(id).getByText('Khung giờ còn:', {exact: true})).toHaveCount(1);
      await expect(card(id).locator('.customer-slot-row')).toHaveCount(1);
      await expect(card(id).locator('button.customer-slot-chip')).toHaveCount(0);
      await expect(card(id).getByRole('checkbox')).toHaveCount(4);
      assert.deepEqual(await card(id).locator('.customer-extra-slots input').evaluateAll(nodes => nodes.map(n => n.value)), ['normal', 'later', 'peak']);
      await expect(selectCourt(id)).not.toBeChecked();
    }
    await expect(page.getByText('Đặt thêm khung giờ:', {exact: true})).toHaveCount(0);
    await expect(check('a', 'normal')).not.toBeChecked();
    await expect(check('a', 'normal')).toBeEnabled();
    await selectCourt('a').check();
    await expect(check('a', 'normal')).toBeEnabled();
    await expect(book('a')).toBeDisabled();
    await check('a', 'normal').uncheck();
    await expect(book('a')).toBeDisabled();
    await check('a', 'peak').check();
    await expect(price('a')).toHaveText(money(150000));
    await check('a', 'later').check();
    await expect(card('a').locator('.customer-extra-slots input:checked')).toHaveCount(2);
    await expect(check('a', 'peak').locator('..')).toContainText(money(150000));
    await expect(check('a', 'later').locator('..')).toContainText(money(100000));
    await check('a', 'peak').uncheck();
    await expect(price('a')).toHaveText(money(100000));
    await check('a', 'peak').check();
    await expect(price('a')).toHaveText(money(100000));
    await expect(card('b').locator('input:checked')).toHaveCount(0);
    await expect(check('b', 'normal')).not.toBeChecked();
    await selectCourt('b').check();
    await check('b', 'normal').check();
    await expect(card('a').locator('input:checked')).toHaveCount(3);
    await expect(book('a')).toBeEnabled();
    await selectCourt('a').check();
    await check('a', 'later').check();
    await check('a', 'peak').check();
    await expect(card('b').locator('input:checked')).toHaveCount(2);
    console.log('PASS one checkbox list, no chips/duplicates, select/deselect/reselect, normal/peak prices, independent courts');

    await page.getByLabel('Sắp xếp sân').selectOption('price');
    await page.getByLabel('Tên sân', {exact: true}).fill('Court a');
    await expect(page.locator('.customer-court-card')).toHaveCount(1);
    await page.getByLabel('Tên sân', {exact: true}).fill('');
    await page.locator('.customer-district-filter').getByLabel('Quận 1', {exact: true}).check();
    await expect(page.locator('.customer-court-card')).toHaveCount(1);
    await page.locator('.customer-district-filter').getByLabel('Quận 7', {exact: true}).check();
    await expect(page.locator('.customer-court-card')).toHaveCount(2);
    await page.getByRole('spinbutton', {name: 'Giá tối đa mỗi khung giờ', exact: true}).fill('99999');
    await expect(page.locator('.customer-court-card')).toHaveCount(0);
    await page.getByRole('spinbutton', {name: 'Giá tối đa mỗi khung giờ', exact: true}).fill('');
    await expect(check('a', 'peak')).toBeChecked();
    await expect(check('b', 'normal')).toBeChecked();
    for (const width of [1440, 820, 375, 320]) {
      await page.setViewportSize({width, height: 1000});
      assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false, `Overflow at ${width}`);
      assert.equal(await card('a').evaluate(node => {
        const row = node.querySelector('.customer-slot-row');
        const heading = row.firstElementChild.getBoundingClientRect();
        const labels = [...row.querySelectorAll('label')].map(label => label.getBoundingClientRect());
        return labels.every(rect => rect.top >= heading.bottom && rect.right <= node.getBoundingClientRect().right);
      }), true, `Checkbox layout at ${width}`);
    }
    console.log('PASS name/district OR/price filters, sorting preserve selections; desktop/tablet/mobile 1440/820/375/320');

    await page.clock.runFor(1250);
    await expect(page.locator('.customer-slot-row input[value="normal"]')).toHaveCount(0);
    await expect(page.locator('option[value="normal"]')).toHaveCount(0);
    await expect(check('a', 'later')).toBeChecked();
    await expect(check('a', 'peak')).toBeChecked();
    await expect(book('a')).toBeEnabled();
    await expect(book('b')).toBeDisabled();
    // Availability changes prune only the occupied slot, retaining the other choice.
    occupied = true;
    await page.evaluate(() => window.dispatchEvent(new Event('focus')));
    await expect(check('a', 'later')).toHaveCount(0);
    await expect(check('a', 'peak')).toBeChecked();
    await expect(price('a')).toHaveText(money(150000));
    occupied = false;
    await page.evaluate(() => window.dispatchEvent(new Event('focus')));
    await expect(check('a', 'later')).not.toBeChecked();
    await check('a', 'later').check();
    console.log('PASS started/unavailable slots disappear; valid selections retained; first remaining slot supplies price');
    await book('a').click();
    await expect(page).toHaveURL(`${baseURL}/checkout`);
    const draft = await page.evaluate(() => JSON.parse(sessionStorage.getItem('bcm-booking-draft')));
    assert.equal(draft.timeSlotId, 'peak');
    assert.deepEqual(draft.details, ['peak', 'later'].map(timeSlotId => ({courtId: 'a', bookingDate: date, timeSlotId})));
    assert.deepEqual(errors, []);
    console.log('PASS multi-slot checkout handoff: exact unique slots and primary, no backend writes or JS errors');
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
