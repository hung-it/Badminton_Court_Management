const { chromium } = require('playwright');

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

  // Inject styles dynamically to test
  await page.addStyleTag({
    content: `
      .invoice-detail .table-scroll:has(.invoice-court-table),
      .invoice-detail .table-scroll:has(.invoice-item-table) {
        border: 1px solid #e2e8dc;
        border-radius: 8px;
        overflow-x: auto;
        background: #fff;
        margin-bottom: 14px;
      }
      .invoice-detail .table-scroll:has(.invoice-court-table) table,
      .invoice-detail .table-scroll:has(.invoice-item-table) table {
        border-collapse: separate;
        border-spacing: 0;
      }
      .invoice-detail .table-scroll:has(.invoice-court-table) th,
      .invoice-detail .table-scroll:has(.invoice-item-table) th {
        border-bottom: 1px solid #e2e8dc;
      }
      .invoice-detail .table-scroll:has(.invoice-court-table) td,
      .invoice-detail .table-scroll:has(.invoice-item-table) td {
        border-top: 1px solid #edf0e8;
      }
      .invoice-detail .table-scroll:has(.invoice-court-table) tbody tr:first-child td,
      .invoice-detail .table-scroll:has(.invoice-item-table) tbody tr:first-child td {
        border-top: none;
      }
      .invoice-detail .table-scroll:has(.invoice-court-table) th:first-child,
      .invoice-detail .table-scroll:has(.invoice-item-table) th:first-child {
        border-top-left-radius: 7px;
      }
      .invoice-detail .table-scroll:has(.invoice-court-table) th:last-child,
      .invoice-detail .table-scroll:has(.invoice-item-table) th:last-child {
        border-top-right-radius: 7px;
      }
      .invoice-detail .table-scroll:has(.invoice-court-table) tbody tr:last-child td:first-child,
      .invoice-detail .table-scroll:has(.invoice-item-table) tbody tr:last-child td:first-child {
        border-bottom-left-radius: 7px;
      }
      .invoice-detail .table-scroll:has(.invoice-court-table) tbody tr:last-child td:last-child,
      .invoice-detail .table-scroll:has(.invoice-item-table) tbody tr:last-child td:last-child {
        border-bottom-right-radius: 7px;
      }
      .invoice-detail .table-scroll:has(.invoice-item-table) + .invoice-row,
      .invoice-detail .invoice-row:first-of-type {
        margin-top: 14px !important;
      }
    `
  });

  const check = await page.evaluate(() => {
    const itemTable = document.querySelector('.invoice-item-table');
    const tableScroll = itemTable ? itemTable.closest('.table-scroll') : null;
    const firstRow = document.querySelector('.invoice-detail .invoice-row');
    const tableRect = tableScroll.getBoundingClientRect();
    const rowRect = firstRow.getBoundingClientRect();
    return {
      tableBottom: tableRect.bottom,
      rowTop: rowRect.top,
      gap: rowRect.top - tableRect.bottom,
      tableBorder: window.getComputedStyle(tableScroll).border,
      tableBorderRadius: window.getComputedStyle(tableScroll).borderRadius
    };
  });

  console.log('Result with injected styles:', check);
  await page.screenshot({ path: 'scratch/test_injected_style.png' });
  console.log('Screenshot saved to scratch/test_injected_style.png');
  await browser.close();
})();
