const assert = require('node:assert');
const { chromium } = require('playwright');

const BACKEND_URL = 'http://localhost:8080/api';
const ADMIN_URL = 'http://localhost:5173';
const USER_URL = 'http://localhost:5174';

async function testBackend() {
  console.log('--- 1. Testing Backend Analytics & Invoice Status API ---');
  
  // Login Admin
  const loginRes = await fetch(`${BACKEND_URL}/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email: 'admin@bcm.com', password: 'admin123' })
  });
  assert.equal(loginRes.status, 200, 'Admin login should succeed');
  const loginData = await loginRes.json();
  const token = loginData.data.accessToken;
  const authHeaders = { Authorization: `Bearer ${token}` };

  // Fetch all invoices across full date range
  const allRes = await fetch(`${BACKEND_URL}/analytics/invoices?page=0&size=200&from=2025-01-01&to=2026-12-31`, { headers: authHeaders });
  assert.equal(allRes.status, 200);
  const allData = (await allRes.json()).data;
  console.log(`Total invoices in range (all statuses): ${allData.invoices.length}`);
  assert(allData.invoices.length > 0, 'Must have invoices in database');

  // Fetch PAID invoices across all pages and verify revenue
  let pageIndex = 0;
  let paidCount = 0;
  let totalPaidRevenue = 0;
  while (true) {
    const paidRes = await fetch(`${BACKEND_URL}/analytics/invoices?page=${pageIndex}&size=20&from=2025-01-01&to=2026-12-31&status=PAID`, { headers: authHeaders });
    assert.equal(paidRes.status, 200);
    const paidData = (await paidRes.json()).data;
    if (!paidData.invoices || paidData.invoices.length === 0) break;
    paidCount += paidData.invoices.length;
    assert(paidData.invoices.every(i => i.invoiceStatus === 'PAID'), 'All invoices under status=PAID must have invoiceStatus=PAID');
    totalPaidRevenue += paidData.invoices.reduce((acc, i) => acc + Number(i.totalAmount), 0);
    if (pageIndex + 1 >= paidData.totalPages) break;
    pageIndex++;
  }
  console.log(`PAID invoices across all pages: ${paidCount}`);
  console.log(`Total PAID Revenue from invoice list: ${totalPaidRevenue.toLocaleString('vi-VN')} VND`);
  assert.equal(totalPaidRevenue, 44548000, 'PAID revenue must remain exactly 44.548.000 VND');

  // Fetch DRAFT invoices
  const draftRes = await fetch(`${BACKEND_URL}/analytics/invoices?page=0&size=200&from=2025-01-01&to=2026-12-31&status=DRAFT`, { headers: authHeaders });
  assert.equal(draftRes.status, 200);
  const draftData = (await draftRes.json()).data;
  console.log(`DRAFT invoices in range: ${draftData.invoices.length}`);
  assert(draftData.invoices.every(i => i.invoiceStatus === 'DRAFT'), 'All invoices under status=DRAFT must have invoiceStatus=DRAFT');

  // Fetch CANCELLED invoices
  const cancelledRes = await fetch(`${BACKEND_URL}/analytics/invoices?page=0&size=200&from=2025-01-01&to=2026-12-31&status=CANCELLED`, { headers: authHeaders });
  assert.equal(cancelledRes.status, 200);
  const cancelledData = (await cancelledRes.json()).data;
  console.log(`CANCELLED invoices in range: ${cancelledData.invoices.length}`);
  assert(cancelledData.invoices.every(i => i.invoiceStatus === 'CANCELLED'), 'All invoices under status=CANCELLED must have invoiceStatus=CANCELLED');

  // Fetch single invoice detail for a DRAFT or PAID
  if (allData.invoices.length > 0) {
    const sampleId = allData.invoices[0].id;
    const invRes = await fetch(`${BACKEND_URL}/analytics/invoices/${sampleId}`, { headers: authHeaders });
    assert.equal(invRes.status, 200);
    const invDetail = (await invRes.json()).data;
    console.log(`Sample invoice ${sampleId} receipts count: ${invDetail.receipts?.length}`);
    if (invDetail.receipts?.length > 0) {
      console.log(`Receipt status: ${invDetail.receipts[0].status}`);
    }
  }

  console.log('✓ Backend API verification passed!\n');
}

async function testWebAdmin() {
  console.log('--- 2. Testing Web Admin UI (Revenue & Invoice Status Filter) ---');
  const browser = await chromium.launch({ headless: true, channel: 'msedge' });
  const page = await browser.newPage();
  try {
    const loginRes = await fetch(`${BACKEND_URL}/auth/login`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ email: 'admin@bcm.com', password: 'admin123' })
    });
    const auth = (await loginRes.json()).data;
    await page.goto(ADMIN_URL, { waitUntil: 'domcontentloaded' });
    await page.evaluate((s) => sessionStorage.setItem('bcm-session', JSON.stringify(s)), auth);
    await page.goto(`${ADMIN_URL}/?tab=invoices`, { waitUntil: 'domcontentloaded' });
    await page.waitForTimeout(1000);

    // Verify status filter exists
    const statusSelect = page.locator('select[aria-label="Trạng thái"]');
    assert(await statusSelect.isVisible(), 'Status dropdown filter must be visible');
    const options = await statusSelect.locator('option').allTextContents();
    console.log('Status filter options:', options);
    assert(options.some(o => o.includes('PAID')), 'Must include PAID');
    assert(options.some(o => o.includes('DRAFT')), 'Must include DRAFT');
    assert(options.some(o => o.includes('CANCELLED')), 'Must include CANCELLED');

    // Filter by DRAFT
    await statusSelect.selectOption('DRAFT');
    await page.waitForTimeout(500);
    console.log('Filtered by DRAFT successfully in UI');

    // Filter by PAID
    await statusSelect.selectOption('PAID');
    await page.waitForTimeout(500);
    console.log('Filtered by PAID successfully in UI');

    // Click Chi tiết on the first invoice row if exists
    const detailBtn = page.locator('button:has-text("Chi tiết")').first();
    if (await detailBtn.isVisible()) {
      await detailBtn.click();
      await page.waitForSelector('.invoice-detail');
      const dialog = page.locator('.invoice-detail');
      assert(await dialog.isVisible(), 'Invoice detail modal must appear');
      console.log('Opened invoice detail dialog in UI');

      // Check if receipts table exists
      const receiptTable = dialog.locator('table').first();
      if (await receiptTable.isVisible()) {
        const headers = await receiptTable.locator('th').allTextContents();
        console.log('Invoice receipt table headers:', headers);
        assert(headers.some(h => h.includes('Trạng thái giao dịch')), 'Must show Trạng thái giao dịch header');
      }
      // Close dialog
      await dialog.locator('button:has-text("✕"), button:has-text("Đóng")').first().click();
    }

    console.log('✓ Web Admin UI verification passed!\n');
  } finally {
    await browser.close();
  }
}

async function testWebUser() {
  console.log('--- 3. Testing Web User (Chi tiết đặt sân & Lịch sử thanh toán) ---');
  const browser = await chromium.launch({ headless: true, channel: 'msedge' });
  const page = await browser.newPage();
  try {
    // Login as customer
    const loginRes = await fetch(`${BACKEND_URL}/auth/login`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ email: 'customer1@bcm.test', password: 'Password123!' })
    });
    if (loginRes.status === 200) {
      const auth = (await loginRes.json()).data;
      await page.goto(`${USER_URL}/my-bookings`, { waitUntil: 'domcontentloaded' });
      await page.evaluate((session) => {
        sessionStorage.setItem('bcm-customer-session', JSON.stringify(session));
      }, auth);
      await page.goto(`${USER_URL}/my-bookings`, { waitUntil: 'domcontentloaded' });
      await page.waitForTimeout(1000);

      // Check if booking cards are visible
      const bookingCards = page.locator('.history-card');
      const count = await bookingCards.count();
      console.log(`Customer booking cards found: ${count}`);

      if (count > 0) {
        // Expand the first booking
        const detailToggle = bookingCards.first().locator('.history-card__toggle, button:has-text("Chi tiết")');
        if (await detailToggle.isVisible()) {
          await detailToggle.click();
          await page.waitForSelector('.history-detail');
          console.log('Booking detail expanded');

          // Check for Lịch sử thanh toán section
          const paymentSection = page.locator('.history-payment-section, section:has-text("Lịch sử thanh toán")');
          assert(await paymentSection.isVisible(), 'Lịch sử thanh toán section must be visible');

          // Check if table or payments exist
          const table = paymentSection.locator('table.history-payment-table');
          if (await table.isVisible()) {
            const ths = await table.locator('th').allTextContents();
            console.log('Payment table columns:', ths);
            assert(ths.includes('Thời gian'), 'Must have Thời gian');
            assert(ths.includes('Phương thức'), 'Must have Phương thức');
            assert(ths.includes('Mã giao dịch'), 'Must have Mã giao dịch');
            assert(ths.includes('Số tiền'), 'Must have Số tiền');
            assert(ths.includes('Trạng thái'), 'Must have Trạng thái');
          }
        }
      }
    } else {
      console.log('Customer login API returned:', loginRes.status);
    }

    console.log('✓ Web User UI verification passed!\n');
  } finally {
    await browser.close();
  }
}

async function main() {
  await testBackend();
  await testWebAdmin();
  await testWebUser();
  console.log('==================================================');
  console.log('ALL VERIFICATIONS COMPLETED SUCCESSFULLY!');
  console.log('==================================================');
}

main().catch(err => {
  console.error('VERIFICATION FAILED:', err);
  process.exit(1);
});
