const { chromium } = require('playwright');
const assert = require('assert');

(async () => {
  const browser = await chromium.launch({ channel: 'msedge', headless: true });
  const page = await browser.newPage({ viewport: { width: 1280, height: 900 } });
  
  const invoice = {
    id: 'dc665041-381c-4bca-8798-251c6e1aa2ea',
    bookingId: 'f04e3ab8-8bcf-4f9e-a89e-df1a6bc6b9f4',
    customerId: 'cust-123',
    customerName: 'Nguyễn Văn A',
    customerPhone: '0901234567',
    customerEmail: 'nguyenvana@gmail.com',
    createdAt: '2026-11-16T14:30:00',
    courtFee: 180000,
    productFee: 45000,
    discountAmount: 10000,
    totalAmount: 215000,
    invoiceStatus: 'PAID',
    bookingStatus: 'COMPLETED',
    paymentMethods: ['CASH']
  };

  const invoiceDetail = {
    invoice,
    creatorName: 'Lê Hoàng Nam',
    createdBy: 'e8b67d16-6c70-4f81-80f2-e717a659ef8c',
    receipts: [
      {
        id: 'rec-1',
        target: 'COURT_PREPAYMENT',
        method: 'CASH',
        status: 'SUCCESS',
        amount: 180000,
        actorName: 'Lê Hoàng Nam',
        confirmedBy: 'e8b67d16-6c70-4f81-80f2-e717a659ef8c',
        confirmedAt: '2026-11-16T14:35:00',
        reference: 'REF-12345'
      }
    ],
    courts: [
      {
        name: 'Sân 1 (Thảm tiêu chuẩn)',
        date: '2026-11-16',
        startTime: '17:00:00',
        endTime: '19:00:00',
        price: 180000
      }
    ],
    items: [
      {
        name: 'Nước suối Aquafina 500ml',
        promotionName: 'Mua 2 tặng 1',
        quantity: 3,
        unitPrice: 15000,
        discountAmount: 10000,
        totalAmount: 35000
      },
      {
        name: 'Ống cầu lông Yonex Aerosensa 50 (12 quả)',
        promotionName: null,
        quantity: 1,
        unitPrice: 280000,
        discountAmount: 0,
        totalAmount: 280000
      }
    ]
  };

  await page.route('**/api/**', async route => {
    const url = new URL(route.request().url());
    if (url.pathname === '/api/analytics/invoices') {
      return route.fulfill({ json: { success: true, data: { invoices: [invoice], totalPages: 1, totalElements: 1 } } });
    }
    if (url.pathname.startsWith('/api/analytics/invoices/')) {
      return route.fulfill({ json: { success: true, data: invoiceDetail } });
    }
    return route.fulfill({ json: { success: true, data: [] } });
  });

  await page.addInitScript(() => {
    sessionStorage.setItem('bcm-session', JSON.stringify({
      accessToken: 'test-token',
      user: { id: 'admin-1', roles: ['ADMIN'], fullName: 'Quản trị viên' }
    }));
  });

  await page.goto('http://localhost:5173/?tab=invoices');
  await page.waitForTimeout(500);
  await page.getByRole('button', { name: 'Chi tiết' }).first().click();
  await page.waitForTimeout(500);

  // 1. Check all table cards
  const tableStyles = await page.evaluate(() => {
    const cards = Array.from(document.querySelectorAll('.invoice-table-card'));
    return cards.map(c => ({
      tableClass: c.querySelector('table') ? c.querySelector('table').className : '',
      border: window.getComputedStyle(c).border,
      borderRadius: window.getComputedStyle(c).borderRadius
    }));
  });
  console.log('All table cards styles:', tableStyles);
  assert.equal(tableStyles.length, 3, 'All 3 tables must have invoice-table-card');
  for (const ts of tableStyles) {
    assert.match(ts.border, /1px solid/);
    assert.match(ts.borderRadius, /8px/);
  }

  // 2. Check column alignments for all 3 tables
  const cols = await page.evaluate(() => {
    return Array.from(document.querySelectorAll('.invoice-receipt-table, .invoice-court-table, .invoice-item-table')).map(table => {
      const ths = Array.from(table.querySelectorAll('thead th')).map(th => ({
        text: th.innerText,
        align: window.getComputedStyle(th).textAlign,
        width: Math.round(th.getBoundingClientRect().width * 10) / 10
      }));
      const tds = Array.from(table.querySelectorAll('tbody tr:first-child td')).map(td => ({
        text: td.innerText.split('\n')[0],
        align: window.getComputedStyle(td).textAlign,
        width: Math.round(td.getBoundingClientRect().width * 10) / 10
      }));
      return { className: table.className, ths, tds };
    });
  });
  console.log('Columns alignment count:', cols.length);
  assert.equal(cols.length, 3);
  for (const table of cols) {
    for (let i = 0; i < table.ths.length; i++) {
      assert.equal(table.ths[i].width, table.tds[i].width, `Width mismatch at ${table.className} column ${table.ths[i].text}`);
      assert.equal(table.ths[i].align, table.tds[i].align, `Align mismatch at ${table.className} column ${table.ths[i].text}`);
    }
  }

  // 3. Check labels & gap
  const dialog = page.getByRole('dialog');
  const discountLabel = await dialog.locator('.invoice-row span').nth(2).innerText();
  const totalLabel = await dialog.locator('.pos-summary-total span').first().innerText();
  assert.equal(discountLabel.trim(), 'Giảm giá');
  assert.equal(totalLabel.trim(), 'Tổng hóa đơn');

  const gap = await page.evaluate(() => {
    const itemTable = document.querySelector('.invoice-item-table');
    const tableScroll = itemTable ? itemTable.closest('.invoice-table-card') : null;
    const summaryBlock = document.querySelector('.invoice-summary-block');
    if (!tableScroll || !summaryBlock) return null;
    const tRect = tableScroll.getBoundingClientRect();
    const sRect = summaryBlock.getBoundingClientRect();
    return sRect.top - tRect.bottom;
  });
  console.log('Measured gap:', gap, 'px');
  assert(gap >= 12 && gap <= 16);

  await page.screenshot({ path: 'scratch/modal_all_three_cards.png' });
  console.log('Saved screenshot to scratch/modal_all_three_cards.png');
  await browser.close();
  console.log('ALL THREE TABLES VERIFIED SUCCESSFULLY!');
})();
