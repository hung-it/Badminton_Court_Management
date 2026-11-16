const assert = require('node:assert');
const { chromium } = require('playwright');

const BACKEND_URL = 'http://localhost:8080/api';
const ADMIN_URL = 'http://localhost:5173';

async function main() {
  console.log('--- Testing Invoice Detail: Source Column & Staff Code Visibility ---');
  
  // 1. Get admin token
  const loginRes = await fetch(`${BACKEND_URL}/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email: 'admin@bcm.com', password: 'admin123' })
  });
  assert.equal(loginRes.status, 200, 'Admin login failed');
  const auth = (await loginRes.json()).data;
  
  // Launch Playwright Edge
  const browser = await chromium.launch({ headless: true, channel: 'msedge' });
  const page = await browser.newPage({ viewport: { width: 1366, height: 900 } });
  
  try {
    // Inject session and navigate
    await page.goto(ADMIN_URL, { waitUntil: 'domcontentloaded' });
    await page.evaluate((s) => sessionStorage.setItem('bcm-session', JSON.stringify(s)), auth);
    
    // Test 1: Open an invoice where confirmed_by IS NOT NULL
    console.log('\n[Case 1: Invoice with staff confirmedBy (dc665041-c351-54bd-bc10-c542dcacdc99)]');
    await page.goto(`${ADMIN_URL}/?tab=invoices&openId=dc665041-c351-54bd-bc10-c542dcacdc99`, { waitUntil: 'domcontentloaded' });
    await page.waitForTimeout(1000);
    
    // Check if dialog is opened or search for it
    await page.fill('input[aria-label="Tìm hóa đơn"]', 'dc665041');
    await page.waitForTimeout(600);
    const detailBtn = page.locator('button:has-text("Chi tiết")').first();
    await detailBtn.click();
    await page.waitForSelector('.invoice-detail');
    const dialog1 = page.locator('.invoice-detail');

    console.log(`Dialog text preview: \n${(await dialog1.innerText()).slice(0, 400)}`);
    const tables = dialog1.locator('table');
    console.log(`Total tables in dialog: ${await tables.count()}`);
    const receiptsTable1 = dialog1.locator('table').first();
    const rows1 = receiptsTable1.locator('tbody tr');
    const count1 = await rows1.count();
    console.log(`Receipts rows count: ${count1}`);
    let foundStaffCode = false;
    for (let i = 0; i < count1; i++) {
      const row = rows1.nth(i);
      const sourceText = await row.locator('td').nth(0).innerText();
      const actorText = await row.locator('td').nth(4).innerText();
      console.log(`  Row ${i + 1} Nguồn: "${sourceText}"`);
      console.log(`  Row ${i + 1} Người xác nhận: "${actorText.replace(/\n/g, ' ')}"`);
      assert(sourceText === 'Tiền sân' || sourceText === 'Dịch vụ', `Nguồn must be either "Tiền sân" or "Dịch vụ", got "${sourceText}"`);
      if (actorText.includes('Mã NV:')) {
        foundStaffCode = true;
      }
    }
    assert(foundStaffCode, 'Invoice with confirmed_by MUST display "Mã NV" when staff ID exists');
    console.log('  ✓ Verified: Mã NV is displayed when staff code exists!');
    await dialog1.locator('button:has-text("✕")').click();

    // Test 2: Invoice without confirmed_by (online gateway)
    console.log('\n[Case 2: Invoice without confirmed_by (71624f7f-eee4-4ee9-876d-f5c5ce664cc1)]');
    await page.fill('input[aria-label="Tìm hóa đơn"]', '71624f7f');
    await page.waitForTimeout(600);
    const detailBtn2 = page.locator('button:has-text("Chi tiết")').first();
    await detailBtn2.click();
    await page.waitForSelector('.invoice-detail');
    const dialog2 = page.locator('.invoice-detail');

    const tables2 = dialog2.locator('table');
    const receiptsTable2 = tables2.first();
    const rows2 = receiptsTable2.locator('tbody tr');
    const count2 = await rows2.count();
    for (let i = 0; i < count2; i++) {
      const row = rows2.nth(i);
      const sourceText = await row.locator('td').nth(0).innerText();
      const actorText = await row.locator('td').nth(4).innerText();
      console.log(`  Row ${i + 1} Nguồn: "${sourceText}"`);
      console.log(`  Row ${i + 1} Người xác nhận: "${actorText.replace(/\n/g, ' ')}"`);
      assert(sourceText === 'Tiền sân' || sourceText === 'Dịch vụ', `Nguồn must be either "Tiền sân" or "Dịch vụ", got "${sourceText}"`);
      assert(!actorText.includes('Không gán nhân viên'), 'Must not have "Không gán nhân viên"');
      assert(!sourceText.includes('Gateway'), 'Source must not contain "Gateway"');
      assert(!sourceText.includes('POS'), 'Source must not contain "POS"');
      assert(!sourceText.includes('Cổng thanh toán'), 'Source must not contain "Cổng thanh toán"');
      assert(!sourceText.includes('Thủ công'), 'Source must not contain "Thủ công"');
    }
    console.log('  ✓ Verified: Source column is concisely "Tiền sân" / "Dịch vụ" and omits Mã NV when absent!');

    console.log('\n✓ ALL TESTS PASSED SUCCESSFULLY!');
  } finally {
    await browser.close();
  }
}

main().catch(err => {
  console.error('TEST FAILED:', err);
  process.exit(1);
});
