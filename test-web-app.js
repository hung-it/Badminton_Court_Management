const { chromium } = require('playwright');

(async () => {
  const browser = await chromium.launch({ headless: false });
  const context = await browser.newContext();
  const page = await context.newPage();

  console.log('🧪 Starting Web App Tests...\n');

  // Test 1: Trang chủ
  console.log('📄 Test 1: Trang chủ (Home Page)');
  await page.goto('http://localhost:5173');
  await page.waitForLoadState('networkidle');

  const title = await page.title();
  console.log(`   ✓ Title: ${title}`);

  const heroText = await page.locator('h1').first().textContent();
  console.log(`   ✓ Hero heading: ${heroText}`);

  await page.screenshot({ path: 'screenshots/home.png' });
  console.log('   ✓ Screenshot saved: screenshots/home.png\n');

  // Test 2: Đăng ký
  console.log('📝 Test 2: Trang đăng ký (Register Page)');
  await page.goto('http://localhost:5173/register');
  await page.waitForLoadState('networkidle');

  // Fill form
  await page.fill('input[name="fullName"]', 'Test User');
  await page.fill('input[name="email"]', 'test@example.com');
  await page.fill('input[name="phone"]', '0123456789');
  await page.fill('input[name="password"]', 'Test123!@#');
  await page.fill('input[name="confirmPassword"]', 'Test123!@#');

  console.log('   ✓ Form filled');
  await page.screenshot({ path: 'screenshots/register-filled.png' });

  // Try submit (won't actually submit to avoid DB side effects)
  const submitBtn = await page.locator('button[type="submit"]');
  const isDisabled = await submitBtn.isDisabled();
  console.log(`   ✓ Submit button ${isDisabled ? 'disabled' : 'enabled'}`);
  console.log('   ✓ Screenshot saved: screenshots/register-filled.png\n');

  // Test 3: Đăng nhập
  console.log('🔐 Test 3: Trang đăng nhập (Login Page)');
  await page.goto('http://localhost:5173/login');
  await page.waitForLoadState('networkidle');

  // Fill login form
  await page.fill('input[name="email"]', 'admin@bcm.com');
  await page.fill('input[name="password"]', 'Admin123!@#');

  console.log('   ✓ Login form filled');
  await page.screenshot({ path: 'screenshots/login-filled.png' });
  console.log('   ✓ Screenshot saved: screenshots/login-filled.png\n');

  // Check navigation links
  console.log('🔗 Test 4: Navigation');
  await page.goto('http://localhost:5173');
  const navLinks = await page.locator('nav a').count();
  console.log(`   ✓ Found ${navLinks} navigation links`);

  const links = await page.locator('nav a').allTextContents();
  console.log(`   ✓ Links: ${links.join(', ')}\n`);

  console.log('✅ All tests completed!');
  console.log('📸 Screenshots saved in ./screenshots/');

  await browser.close();
})();
