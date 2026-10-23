// Real browser/API/PostgreSQL. Synthetic location labels exist only on new audit DB fixtures.
const {chromium, request, expect} = require('playwright/test');
const {execFileSync} = require('node:child_process');
const crypto = require('node:crypto');
const assert = require('node:assert/strict');
const baseURL = process.env.BCM_E2E_WEB_URL || 'http://127.0.0.1:5174';
const run = crypto.randomUUID().slice(0, 8);
const ids = [crypto.randomUUID(), crypto.randomUUID(), crypto.randomUUID()];
const names = ['A', 'B', 'C'].map(letter => `UX ${run} ${letter}`);
const prices = [200000, 100000, 300000];
const dateAfter = days => { const d = new Date(); d.setDate(d.getDate() + days); return d.toLocaleDateString('en-CA'); };
const pass = name => console.log(`PASS ${name}`);
const bankTransfer = {bankName: 'Test bank', accountHolder: 'Test payer', accountNumber: '123456', transferContent: 'Test court booking'};
const sql = text => execFileSync('docker', ['exec', 'bcm-booking-audit-20261022', 'psql', '-U', 'bcm_test',
  '-d', 'bcm_booking_audit', '-v', 'ON_ERROR_STOP=1', '-c', text], {encoding: 'utf8'});

(async () => {
  const number = crypto.randomInt(1000000, 1900000000);
  sql(`INSERT INTO courts(id,court_number,name,type,status,base_price,district) VALUES ${ids.map((id, i) =>
    `('${id}',${number + i},'${names[i]}','WOODEN_FLOOR','AVAILABLE',${prices[i]},${i === 2 ? 'NULL' : `'Khu kiểm thử ${i === 0 ? 'A' : 'B'}'`})`).join(',')}`);
  const api = await request.newContext({baseURL});
  const register = async suffix => {
    const response = await api.post('/api/auth/register', {data: {email: `ux-${run}-${suffix}@example.test`,
      password: crypto.randomBytes(18).toString('hex'), fullName: 'UX test', phone: '0900000000'}});
    assert.equal(response.status(), 201); return (await response.json()).data;
  };
  const owner = await register('owner'), foreign = await register('foreign');
  const headers = {Authorization: `Bearer ${owner.accessToken}`}, foreignHeaders = {Authorization: `Bearer ${foreign.accessToken}`};
  const customerId = (await (await api.get('/api/bookings/customer-profile', {headers})).json()).data.customerId;
  const foreignCustomerId = (await (await api.get('/api/bookings/customer-profile', {headers: foreignHeaders})).json()).data.customerId;
  const publicSlots = (await (await api.get('/api/public/time-slots')).json()).data;
  const browser = await chromium.launch({channel: 'msedge', headless: true});
  try {
    const context = await browser.newContext({viewport: {width: 1440, height: 1000}});
    await context.addInitScript(s => {
      sessionStorage.setItem('bcm.accessToken', s.accessToken); sessionStorage.setItem('bcm.user', JSON.stringify(s.user));
    }, owner);
    const page = await context.newPage(); const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await page.goto(`${baseURL}/courts?date=${dateAfter(150)}`);
    await page.getByLabel('Tên sân', {exact: true}).fill(`UX ${run}`);
    const cards = page.locator('.customer-court-card');
    const card = index => page.locator(`[data-court-id="${ids[index]}"]`);
    await expect(cards).toHaveCount(3);
    await card(0).locator('.customer-extra-slots input').nth(0).check();
    await expect(card(1).locator('.customer-extra-slots input:checked')).toHaveCount(0);
    await card(1).locator('.customer-extra-slots input').nth(1).check();
    await expect(card(0).locator('.customer-extra-slots input:checked')).toHaveCount(1);
    await card(0).locator('.customer-extra-slots input').nth(0).uncheck();
    await expect(card(1).locator('.customer-extra-slots input:checked')).toHaveCount(1);
    await card(0).locator('.customer-extra-slots input').nth(0).check();
    await card(0).locator('.customer-slot-row').first().getByRole('button').nth(2).click();
    await expect(card(1).locator('.customer-slot-row').first().getByRole('button').first()).toHaveAttribute('aria-pressed', 'true');
    await expect(card(0).locator('.customer-extra-slots input:checked')).toHaveCount(1);
    await expect(card(1).locator('.customer-extra-slots input:checked')).toHaveCount(1);
    await page.getByLabel('Sắp xếp sân').selectOption('price');
    await expect(cards.first()).toHaveAttribute('data-court-id', ids[1]);
    await expect(card(0).locator('.customer-extra-slots input:checked')).toHaveCount(1);
    await page.getByLabel('Tên sân', {exact: true}).fill(names[0]); await expect(cards).toHaveCount(1);
    await page.getByLabel('Tên sân', {exact: true}).fill(`UX ${run}`);
    await expect(card(1).locator('.customer-extra-slots input:checked')).toHaveCount(1);
    pass('Court UUID/slot state isolation: select, deselect, card primary time, sorting and filtering');

    const district = page.locator('.customer-district-filter');
    await district.getByLabel('Khu kiểm thử A', {exact: true}).check(); await expect(cards).toHaveCount(1);
    await district.getByLabel('Khu kiểm thử B', {exact: true}).check(); await expect(cards).toHaveCount(2);
    await page.getByRole('spinbutton', {name: 'Giá tối đa mỗi khung giờ'}).fill('150000');
    await expect(cards).toHaveCount(1); await expect(cards.first()).toHaveAttribute('data-court-id', ids[1]);
    await page.getByLabel('Tên sân', {exact: true}).fill(names[0]); await expect(cards).toHaveCount(0);
    await page.getByRole('spinbutton', {name: 'Giá tối đa mỗi khung giờ'}).fill(''); await expect(cards).toHaveCount(1);
    await page.getByLabel('Tên sân', {exact: true}).fill(`UX ${run}`);
    await district.getByRole('button', {name: 'Xóa lựa chọn'}).click(); await expect(cards).toHaveCount(3);
    pass('Backend district labels: multi-select OR, clear, name/price composition and unknown district compatibility');

    for (const width of [1440, 820, 375, 320]) {
      await page.setViewportSize({width, height: 1000});
      const layout = await card(0).evaluate(node => {
        const rows = node.querySelectorAll('.customer-slot-row');
        return {gap: rows[1].getBoundingClientRect().top - rows[0].getBoundingClientRect().bottom,
          overlap: [...rows].some(row => [...row.querySelectorAll('.customer-slot-chip')].some(chip => chip.getBoundingClientRect().top < row.firstElementChild.getBoundingClientRect().bottom - 1))};
      });
      assert.ok(layout.gap >= 8 && layout.gap <= 12, `Spacing at ${width}: ${layout.gap}`);
      assert.equal(layout.overlap, false);
      assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false);
    }
    pass('Courts desktop/tablet/mobile: 10px spacing, labels/chips do not overlap, no horizontal overflow');
    await page.setViewportSize({width: 1440, height: 1000});
    await card(1).getByRole('button', {name: 'Đặt sân', exact: true}).click();
    await expect(page.getByRole('button', {name: 'Thanh toán', exact: true})).toBeEnabled();
    const secondDraft = await page.evaluate(() => JSON.parse(sessionStorage.getItem('bcm-booking-draft')));
    assert.equal(secondDraft.details.length, 2); assert.ok(secondDraft.details.every(d => d.courtId === ids[1]));
    // Simulate a persisted provider attempt without claiming any receipt or gateway settlement.
    sql(`INSERT INTO payment_transactions(booking_id,payment_method,status,amount,note) SELECT id,'VNPAY','PENDING',court_fee,'UX test pending attempt fixture' FROM bookings WHERE id='${secondDraft.bookingId}'`);
    await page.reload();
    await expect(page.getByText('Đã khởi tạo thanh toán; ngày và khung giờ của đơn này không thể thay đổi.', {exact: true})).toBeVisible();
    await expect(page.getByRole('button', {name: 'Chỉnh sửa thông tin', exact: true})).toBeDisabled();
    await page.goto(`${baseURL}/courts?date=${dateAfter(150)}`);
    await page.getByLabel('Tên sân', {exact: true}).fill(`UX ${run}`);
    await expect(card(0)).toBeVisible();
    await expect(card(0).locator('.customer-extra-slots input:checked')).toHaveCount(0);
    await card(0).locator('.customer-extra-slots input').first().check();
    await card(0).getByRole('button', {name: 'Đặt sân', exact: true}).click();
    await expect(page.getByRole('button', {name: 'Thanh toán', exact: true})).toBeEnabled();
    let draft = await page.evaluate(() => JSON.parse(sessionStorage.getItem('bcm-booking-draft')));
    assert.equal(draft.details.length, 2); assert.ok(draft.details.every(d => d.courtId === ids[0]));
    const bookingId = draft.bookingId;
    const getBooking = async () => (await (await api.get(`/api/bookings/${bookingId}`, {headers})).json()).data;
    const original = await getBooking(); const originalDeadline = original.expiresAt;
    assert.equal(original.details.length, 2); assert.ok(original.details.every(d => d.courtId === ids[0]));
    pass('Each card books only its own slots; returning does not copy another court selection; pending payment locks editing');

    const note = page.getByLabel('Ghi chú cho đơn đặt sân', {exact: true});
    await note.fill('Ghi chú chưa lưu — giữ sau refresh');
    await page.reload();
    await expect(note).toHaveValue('Ghi chú chưa lưu — giữ sau refresh');
    await expect(page.getByRole('button', {name: 'Thanh toán', exact: true})).toBeDisabled();
    await page.getByRole('button', {name: 'Lưu ghi chú', exact: true}).click();
    await expect(page.getByText('Đã lưu ghi chú.', {exact: true})).toBeVisible();
    assert.equal((await getBooking()).customerNote, 'Ghi chú chưa lưu — giữ sau refresh');
    await page.locator('input[value=BANK_TRANSFER]').check();
    await page.getByRole('button', {name: 'Thanh toán', exact: true}).click();
    await expect(page.locator('.checkout-bank-transfer-error')).toHaveCount(4);
    await expect(note).toHaveValue('Ghi chú chưa lưu — giữ sau refresh');
    const forbidden = await api.patch(`/api/bookings/${bookingId}/notes`, {headers: foreignHeaders, data: {note: 'foreign'}});
    assert.equal(forbidden.status(), 403);
    pass('Booking note: real persistence, draft refresh, method switch/invalid bank form retention and ownership');

    await page.getByRole('button', {name: 'Chỉnh sửa thông tin', exact: true}).click();
    await expect(page.getByRole('button', {name: 'Thanh toán', exact: true})).toBeDisabled();
    await page.locator('#checkout-edit-date').fill(dateAfter(151));
    const editor = page.locator('.checkout-booking-editor');
    await expect(editor.locator('input[type=checkbox]')).toHaveCount(publicSlots.length);
    await editor.locator('input[type=checkbox]').nth(3).check();
    await editor.locator('input[type=checkbox]').nth(4).check();
    await editor.getByRole('button', {name: 'Lưu thay đổi', exact: true}).click();
    await expect(editor).toHaveCount(0);
    let current = await getBooking();
    assert.equal(current.expiresAt, originalDeadline); assert.equal(current.details.length, 2);
    assert.ok(current.details.every(d => d.bookingDate === dateAfter(151)));
    const expected = current.details.reduce((sum, d) => sum + Number(d.price), 0);
    assert.equal(Number(current.courtFee), expected);
    await expect(page.locator('.checkout-grand-total b')).toContainText(new Intl.NumberFormat('vi-VN').format(expected));
    await page.reload(); await expect(page.getByRole('button', {name: 'Thanh toán', exact: true})).toBeEnabled();
    await expect(note).toHaveValue(current.customerNote);
    pass('Edit date/multiple slots: server price, original deadline and authoritative draft after refresh');

    await page.getByRole('button', {name: 'Chỉnh sửa thông tin', exact: true}).click();
    await page.locator('#checkout-edit-date').fill(dateAfter(152));
    await expect(editor.locator('input[type=checkbox]')).toHaveCount(publicSlots.length);
    await editor.locator('input[type=checkbox]').nth(5).check();
    const conflictSlot = await editor.locator('input[type=checkbox]').nth(5).inputValue();
    // Take the slot after the editor loaded availability, exercising the server recheck.
    const competitor = await api.post('/api/bookings', {headers: foreignHeaders, data: {customerId: foreignCustomerId,
      details: [{courtId: ids[0], timeSlotId: conflictSlot, bookingDate: dateAfter(152)}]}});
    assert.equal(competitor.status(), 201);
    await editor.getByRole('button', {name: 'Lưu thay đổi', exact: true}).click();
    await expect(editor.getByRole('alert')).toBeVisible();
    assert.deepEqual((await getBooking()).details, current.details);
    await editor.getByRole('button', {name: 'Hủy', exact: true}).click();
    pass('Availability race returns conflict and preserves the existing hold/details/price');

    for (const width of [820, 375, 320]) {
      await page.setViewportSize({width, height: 1000});
      assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false, `Checkout overflow ${width}`);
    }
    await page.locator('input[value=BANK_TRANSFER]').check();
    for (const [key, value] of Object.entries(bankTransfer)) await page.locator(`#checkout-${key}`).fill(value);
    await page.getByRole('button', {name: 'Thanh toán', exact: true}).click();
    await expect(page.getByRole('heading', {name: 'Đã xác nhận đặt sân', exact: true})).toBeVisible();
    current = await getBooking(); assert.equal(current.status, 'PAID');
    assert.equal(Number(current.payments[0].amount), expected);
    assert.equal(current.payments[0].confirmationMethod, 'CUSTOMER_DECLARED');
    assert.equal(current.payments[0].paymentStatus, 'PENDING');
    await expect(page.getByText('Ghi chú chưa lưu — giữ sau refresh', {exact: true})).toBeVisible();
    const confirmedEdit = await api.patch(`/api/bookings/${bookingId}/details`, {headers, data: {bookingDate: dateAfter(153), timeSlotIds: [publicSlots[0].id]}});
    assert.equal(confirmedEdit.status(), 409);
    await page.reload(); await expect(page.getByRole('heading', {name: 'Đã xác nhận đặt sân', exact: true})).toBeVisible();
    assert.deepEqual(errors, []);
    pass('BANK_TRANSFER after editing uses new amount; note remains separate, confirmed booking immutable, result refresh and responsive UI pass');
    console.log(`Fixtures retained in audit DB only: ${ids.join(', ')}`);
  } finally { await browser.close(); await api.dispose(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
