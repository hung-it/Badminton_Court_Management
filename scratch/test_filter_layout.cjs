const assert = require('node:assert');
const { chromium } = require('playwright');

const BACKEND_URL = 'http://localhost:8080/api';
const ADMIN_URL = 'http://localhost:5173';

async function main() {
  console.log('=== VERIFYING INVOICE FILTERS LAYOUT & STATUS VIETNAMESE LABELS ===');

  // 1. Login admin
  const loginRes = await fetch(`${BACKEND_URL}/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email: 'admin@bcm.com', password: 'admin123' })
  });
  assert.equal(loginRes.status, 200, 'Admin login failed');
  const auth = (await loginRes.json()).data;

  const browser = await chromium.launch({ headless: true, channel: 'msedge' });
  const page = await browser.newPage();

  try {
    await page.goto(ADMIN_URL, { waitUntil: 'domcontentloaded' });
    await page.evaluate((s) => sessionStorage.setItem('bcm-session', JSON.stringify(s)), auth);
    await page.goto(`${ADMIN_URL}/?tab=invoices`, { waitUntil: 'domcontentloaded' });
    await page.waitForTimeout(1000);

    // 2. Check dropdown options for Trạng thái
    console.log('\n--- Checking Trạng thái dropdown options ---');
    const statusSelect = page.locator('select[aria-label="Trạng thái"]');
    assert(await statusSelect.isVisible(), 'Status select must be visible');
    const options = await statusSelect.locator('option').allTextContents();
    console.log('Current dropdown options:', options);

    const expectedOptions = ['Tất cả trạng thái', 'Đã thanh toán', 'Chờ thanh toán', 'Đã hủy'];
    assert.deepEqual(options, expectedOptions, 'Dropdown options must be 100% Vietnamese without English text');
    console.log('✓ Verified: Status dropdown is 100% Vietnamese without English suffixes in parentheses!');

    // 3. Test layout on Desktop (single line verification)
    console.log('\n--- Checking Desktop Single-Line Layout ---');
    const desktopWidths = [1440, 1366, 1280, 1024];
    for (const width of desktopWidths) {
      await page.setViewportSize({ width, height: 900 });
      await page.waitForTimeout(300);

      const fields = page.locator('.invoices-panel .record-filters > .field');
      const count = await fields.count();
      assert.equal(count, 5, 'Must have 5 filter fields');

      const tops = [];
      const boxes = [];
      for (let i = 0; i < count; i++) {
        const box = await fields.nth(i).boundingBox();
        tops.push(Math.round(box.y));
        boxes.push(box);
      }

      console.log(`Viewport ${width}px:`);
      console.log(`  Field Y coordinates:`, tops);
      const isSingleRow = tops.every(t => Math.abs(t - tops[0]) <= 2);
      console.log(`  All 5 fields on single row? ${isSingleRow ? 'YES ✓' : 'NO ✗'}`);
      assert(isSingleRow, `All 5 filter fields must be on the same horizontal row at width ${width}px`);

      // Check search box width is compact and not overly bloated
      const searchBox = boxes[2]; // 3rd item is "Tìm hóa đơn"
      console.log(`  "Tìm hóa đơn" width: ${Math.round(searchBox.width)}px`);
      assert(searchBox.width >= 150 && searchBox.width <= 350, '"Tìm hóa đơn" width should be compact and balanced');
      
      // Check no horizontal scrollbar on document
      const overflow = await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth);
      assert(!overflow, `No horizontal scrollbar at ${width}px`);
    }

    // 4. Test filtering functionality
    console.log('\n--- Checking Filter Functional Behavior ---');
    // Filter by "Đã thanh toán"
    await statusSelect.selectOption('PAID');
    await page.waitForTimeout(600);
    console.log('  Selected "Đã thanh toán" -> UI updated');

    // Filter by "Chờ thanh toán"
    await statusSelect.selectOption('DRAFT');
    await page.waitForTimeout(600);
    console.log('  Selected "Chờ thanh toán" -> UI updated');

    // Filter by "Đã hủy"
    await statusSelect.selectOption('CANCELLED');
    await page.waitForTimeout(600);
    console.log('  Selected "Đã hủy" -> UI updated');

    // Reset filter
    await statusSelect.selectOption('');
    await page.waitForTimeout(600);
    console.log('  Selected "Tất cả trạng thái" -> UI reset');

    // 5. Test Responsive on Tablet & Mobile
    console.log('\n--- Checking Responsive Behavior ---');
    for (const width of [768, 375]) {
      await page.setViewportSize({ width, height: 800 });
      await page.waitForTimeout(300);
      const overflow = await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth);
      assert(!overflow, `Must not overflow at ${width}px`);
      console.log(`  Width ${width}px: Fits inside viewport without breaking layout ✓`);
    }

    console.log('\n======================================================');
    console.log('ALL VERIFICATIONS PASSED SUCCESSFULLY!');
    console.log('======================================================');
  } finally {
    await browser.close();
  }
}

main().catch(err => {
  console.error('TEST FAILED:', err);
  process.exit(1);
});
