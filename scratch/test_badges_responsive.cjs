const { chromium } = require('playwright');
const assert = require('assert');

(async () => {
  const browser = await chromium.launch({ channel: 'msedge', headless: true });
  const statuses = ['PAID', 'DRAFT', 'CANCELLED', 'REFUNDED'];

  for (const st of statuses) {
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
      invoiceStatus: st,
      bookingStatus: 'COMPLETED',
      paymentMethods: ['CASH']
    };
    const invoiceDetail = {
      invoice,
      creatorName: 'Lê Hoàng Nam',
      createdBy: 'e8b67d16-6c70-4f81-80f2-e717a659ef8c',
      receipts: [],
      courts: [{ name: 'Sân 1', date: '2026-11-16', startTime: '17:00:00', endTime: '19:00:00', price: 180000 }],
      items: [{ name: 'Nước suối', quantity: 2, unitPrice: 15000, discountAmount: 0, totalAmount: 30000 }]
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
    await page.waitForTimeout(400);
    await page.getByRole('button', { name: 'Chi tiết' }).first().click();
    await page.waitForTimeout(400);

    const badgeWidth = await page.evaluate(() => {
      const b = document.querySelector('.invoice-detail-meta .badge');
      const dd = b ? b.parentElement : null;
      return {
        badgeWidth: b ? Math.round(b.getBoundingClientRect().width * 10) / 10 : 0,
        ddWidth: dd ? Math.round(dd.getBoundingClientRect().width * 10) / 10 : 0,
        text: b ? b.innerText : ''
      };
    });

    console.log(`Status ${st} [${badgeWidth.text}]: badgeWidth=${badgeWidth.badgeWidth}px, ddWidth=${badgeWidth.ddWidth}px`);
    assert(badgeWidth.badgeWidth < 150, 'Badge should hug text, not stretch!');
    assert(badgeWidth.ddWidth > 300, 'dd column width should be wide');

    // Test viewports for responsive
    for (const vp of [1440, 1024, 768, 375]) {
      await page.setViewportSize({ width: vp, height: 800 });
      const noPageOverflow = await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth);
      assert(noPageOverflow, `Page should not overflow horizontally at ${vp}px`);

      if (vp === 375) {
        // Verify table-scroll allows horizontal scroll
        const scrollInfo = await page.evaluate(() => {
          const scrolls = Array.from(document.querySelectorAll('.invoice-detail .table-scroll'));
          return scrolls.map(s => ({
            scrollWidth: s.scrollWidth,
            clientWidth: s.clientWidth,
            canScroll: s.scrollWidth > s.clientWidth
          }));
        });
        for (const s of scrollInfo) {
          assert(s.canScroll, 'Table should have horizontal scroll on mobile (375px) without compressing columns');
        }
      }
    }

    await page.close();
  }
  await browser.close();
  console.log('ALL STATUSES & RESPONSIVE CHECKS PASSED!');
})();
