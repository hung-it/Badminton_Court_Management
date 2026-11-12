// All HTTP APIs are fixtures; this suite never contacts the project database.
const {chromium,expect}=require(process.env.PLAYWRIGHT_MODULE||'playwright/test');
const assert=require('node:assert/strict');
const web=process.env.BCM_WEB_TEST_URL||'http://127.0.0.1:5184';
const admin=process.env.ADMIN_TEST_URL||'http://127.0.0.1:5185';
for(const url of [web,admin])assert.equal(new URL(url).hostname,'127.0.0.1');
(async()=>{
 const browser=await chromium.launch({channel:'msedge',headless:true});
 const errors=[];
 try{
  const page=await browser.newPage();page.on('pageerror',e=>errors.push(e.message));
  await page.route('https://fonts.googleapis.com/**',r=>r.fulfill({body:'',contentType:'text/css'}));
  await page.addInitScript(()=>{sessionStorage.setItem('bcm.accessToken','fixture');sessionStorage.setItem('bcm.user',JSON.stringify({id:'customer',fullName:'Customer',roles:['CUSTOMER']}));});
  let password;
  await page.route('**/api/**',route=>{
   const req=route.request(),path=new URL(req.url()).pathname;
   const summary={bookingId:'booking-p2',status:'PAID',courtFee:300000,discountAmount:30000,finalAmount:270000,createdAt:'2030-01-01T10:00:00'};
   let data=path==='/api/bookings'?{content:[summary],totalElements:1,totalPages:1}:summary;
   if(path==='/api/users/me/password'){password=req.postDataJSON();data=null;}
   return route.fulfill({json:{success:true,data}});
  });
  await page.goto(web+'/my-bookings');
  await expect(page.locator('.history-card__meta strong')).toContainText('270.000');
  await page.locator('.history-card__summary').click();
  await expect(page.locator('.history-detail')).toContainText('300.000');
  await expect(page.locator('.history-detail')).toContainText('30.000');
  await expect(page.locator('.history-detail')).toContainText('270.000');
  await page.goto(web+'/profile');
  await page.getByLabel('Mật khẩu hiện tại',{exact:true}).fill('a'.repeat(80));
  await page.getByLabel('Mật khẩu mới',{exact:true}).fill('ắ'.repeat(25));
  await page.getByLabel('Xác nhận mật khẩu mới',{exact:true}).fill('ắ'.repeat(25));
  await page.getByRole('button',{name:'Lưu mật khẩu',exact:true}).click();
  await expect(page.getByRole('alert')).toContainText('72 byte');assert.equal(password,undefined);
  await page.getByLabel('Mật khẩu mới',{exact:true}).fill('NewPassword123');
  await page.getByLabel('Xác nhận mật khẩu mới',{exact:true}).fill('NewPassword123');
  await page.getByRole('button',{name:'Lưu mật khẩu',exact:true}).click();
  await expect(page.getByRole('status')).toContainText('thành công');
  assert.equal(password.currentPassword.length,80);
  const signup=await browser.newPage();signup.on('pageerror',e=>errors.push(e.message));
  await signup.route('https://fonts.googleapis.com/**',r=>r.fulfill({body:'',contentType:'text/css'}));
  let registration;
  await signup.route('**/api/**',route=>{
   if(new URL(route.request().url()).pathname==='/api/auth/register'){
    registration=route.request().postDataJSON();
    return route.fulfill({status:409,json:{success:false,message:'Fixture email already registered'}});
   }
   return route.fulfill({json:{success:true,data:[]}});
  });
  await signup.goto(web+'/register');
  await signup.getByLabel('Họ và tên *',{exact:true}).fill('P2 Customer');
  await signup.getByLabel('Email *',{exact:true}).fill('p2@example.test');
  await signup.getByLabel('Số điện thoại *',{exact:true}).fill('0912345678');
  await signup.getByRole('checkbox').first().check();
  const fillSignupPassword=async value=>{
   await signup.getByLabel('Mật khẩu *',{exact:true}).fill(value);
   await signup.getByLabel('Xác nhận mật khẩu *',{exact:true}).fill(value);
   await signup.getByRole('button',{name:'Tạo tài khoản',exact:true}).click();
  };
  await fillSignupPassword('short12');
  await expect(signup.getByText('Mật khẩu phải có ít nhất 8 ký tự.',{exact:true})).toBeVisible();
  assert.equal(registration,undefined);
  await fillSignupPassword('ắ'.repeat(25));
  await expect(signup.getByText('Mật khẩu không được vượt quá 72 byte UTF-8.',{exact:true})).toBeVisible();
  assert.equal(registration,undefined);
  await fillSignupPassword('ắ'.repeat(24));
  await expect(signup.getByRole('alert')).toContainText('Dữ liệu đã tồn tại.');
  assert.equal(Buffer.byteLength(registration.password,'utf8'),72);
  const desk=await browser.newPage();desk.on('pageerror',e=>errors.push(e.message));
  await desk.route('https://fonts.googleapis.com/**',r=>r.fulfill({body:'',contentType:'text/css'}));
  await desk.addInitScript(()=>sessionStorage.setItem('bcm-session',JSON.stringify({accessToken:'fixture',user:{id:'admin',roles:['ADMIN'],fullName:'Admin'}})));
  const court={id:'court-p2',branchId:'branch-p2',courtNumber:1,courtCode:101,name:'Wood court',type:'WOODEN_FLOOR',status:'AVAILABLE',basePrice:100000};
  let saved;
  await desk.route('**/api/**',route=>{
   const req=route.request(),path=new URL(req.url()).pathname;let data=[];
   if(path==='/api/admin/courts')data=[court];
   if(path==='/api/public/branches')data=[{id:'branch-p2',name:'P2 Branch',district:'District',address:'Address'}];
   if(path==='/api/admin/courts/court-p2'){saved=req.postDataJSON();Object.assign(court,saved);data=court;}
   return route.fulfill({json:{success:true,data}});
  });
  await desk.goto(admin+'/?tab=courts');
  await desk.getByRole('row').filter({hasText:'P2 Branch'}).getByRole('button',{name:'Xem sân',exact:true}).click();
  await expect(desk.getByRole('cell',{name:'Sàn gỗ',exact:true})).toBeVisible();
  await desk.getByRole('button',{name:'Sửa',exact:true}).click();
  const dialog=desk.getByRole('dialog');
  await expect(dialog.getByLabel('Loại sân',{exact:true})).toHaveValue('WOODEN_FLOOR');
  await dialog.getByLabel('Tên sân',{exact:true}).fill('Renamed court');
  const price=dialog.getByLabel('Giá gốc (VNĐ / khung giờ)',{exact:true});
  await price.fill('0');assert.equal(await price.evaluate(e=>e.checkValidity()),false);
  await price.fill('120000');
  await dialog.getByRole('button',{name:'Lưu thay đổi',exact:true}).click();
  await expect(dialog).toHaveCount(0);assert.equal(saved.type,'WOODEN_FLOOR');assert.equal(Number(saved.basePrice),120000);
  assert.deepEqual(errors,[]);
  console.log('PASS: discounted history and breakdown, legacy password submission, UTF-8 limits in registration and change password, wooden court retained, zero court price rejected, no page errors');
 }finally{await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
