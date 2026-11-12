// Mock every API request: these checks never write to a backend or database.
const {chromium}=require(process.env.PLAYWRIGHT_MODULE||'playwright');
const assert=require('node:assert/strict');
(async()=>{
 const browser=await chromium.launch({headless:true,channel:'msedge'});
 try{
  const page=await browser.newPage({viewport:{width:1366,height:900}});
  await page.route('https://fonts.googleapis.com/**',r=>r.fulfill({body:'',contentType:'text/css'}));
  const id='11111111-1111-4111-8111-111111111111';let received=false,receipt,password,attempts=0,actualAmount=180000,failReceipt=true;
  await page.route('**/api/**',route=>{
   const req=route.request(),path=new URL(req.url()).pathname;let data=[];
   if(path==='/api/checkout/active-bookings')data=[{bookingId:id,shortId:id.slice(0,8),status:received?'PAID':'PENDING',customerName:'Khách kiểm thử',customerPhone:'0900000000',courtFee:200000,finalAmount:actualAmount,courtDetails:'Sân thử (10/11/2026 18:00-20:00)'}];
   if(path==='/api/pos/bookings/'+id+'/receive'){
    receipt=req.postDataJSON();
    if(failReceipt){failReceipt=false;actualAmount=190000;return route.fulfill({status:409,json:{success:false,message:'Số tiền sân đã thay đổi. Đóng hộp thoại và mở lại để xác nhận số tiền mới.'}});}
    received=true;data=actualAmount;
   }
   if(path==='/api/checkout/'+id)data={bookingId:id,court_fee:200000,product_fee:20000,total_amount:actualAmount+20000,paidCourtAmount:actualAmount,amountDue:20000,checkoutEligible:true,latestEndAt:'2099-11-10T20:00:00'};
   if(path==='/api/users/me/password'){
    password=req.postDataJSON();attempts++;
    if(attempts===1)return route.fulfill({status:400,json:{success:false,message:'Mật khẩu hiện tại không đúng.'}});
    data=null;
   }
   return route.fulfill({json:{success:true,data}});
  });
  await page.addInitScript(()=>sessionStorage.setItem('bcm-session',JSON.stringify({accessToken:'fixture',user:{id:'staff-fixture',roles:['STAFF'],fullName:'Staff'}})));
  await page.goto(process.env.ADMIN_TEST_URL||'http://127.0.0.1:5175/?tab=checkout');
  await page.getByRole('button',{name:'Thu tiền tại quầy',exact:true}).click();
  let dialog=page.getByRole('dialog');
  assert.match(await dialog.innerText(),/180\.000/);
  assert.equal(await dialog.getByRole('button',{name:'Xác nhận thu tiền',exact:true}).isDisabled(),true);
  await dialog.getByLabel('Phương thức',{exact:true}).selectOption('VNPAY');
  await dialog.getByLabel('Mã tham chiếu (nếu có)',{exact:true}).fill('counter-reference');
  await dialog.getByRole('checkbox').check();
  await dialog.getByRole('button',{name:'Xác nhận thu tiền',exact:true}).click();
  await dialog.getByRole('alert').filter({hasText:'Số tiền sân đã thay đổi.'}).waitFor();
  assert.equal(received,false);assert.equal(receipt.expectedAmount,180000);
  await Promise.all([page.waitForResponse(r=>new URL(r.url()).pathname==='/api/checkout/active-bookings'),dialog.getByRole('button',{name:'Đóng thu tiền',exact:true}).click()]);
  await page.getByRole('button',{name:'Thu tiền tại quầy',exact:true}).click();
  dialog=page.getByRole('dialog');
  assert.match(await dialog.innerText(),/190\.000/);
  assert.equal(await dialog.getByRole('checkbox').isChecked(),false);
  await dialog.getByLabel('Phương thức',{exact:true}).selectOption('VNPAY');
  await dialog.getByLabel('Mã tham chiếu (nếu có)',{exact:true}).fill('counter-reference');
  await dialog.getByRole('checkbox').check();
  await dialog.getByRole('button',{name:'Xác nhận thu tiền',exact:true}).click();
  await page.getByRole('button',{name:'Kết toán',exact:true}).waitFor();
  assert.deepEqual(receipt,{paymentMethod:'VNPAY',paymentConfirmed:true,reference:'counter-reference',expectedAmount:190000});
  await page.getByRole('button',{name:'Kết toán',exact:true}).click();
  await page.getByRole('heading',{name:'Bill & Kết toán trả sân'}).waitFor();
  assert.equal(await page.getByText('Ca chơi chưa bắt đầu, không thể kết toán.',{exact:true}).count(),0);
  await page.getByRole('button',{name:'Đóng bill',exact:true}).click();
  await page.getByRole('button',{name:'Đổi mật khẩu',exact:true}).click();
  dialog=page.getByRole('dialog');
  await dialog.getByLabel('Mật khẩu hiện tại',{exact:true}).fill('Original123!');
  await dialog.getByLabel('Mật khẩu mới',{exact:true}).fill('Updated123!');
  await dialog.getByLabel('Xác nhận mật khẩu mới',{exact:true}).fill('Different123!');
  await dialog.getByRole('button',{name:'Lưu mật khẩu',exact:true}).click();
  await dialog.getByRole('alert').filter({hasText:'Mật khẩu xác nhận không khớp.'}).waitFor();assert.equal(attempts,0);
  await dialog.getByLabel('Xác nhận mật khẩu mới',{exact:true}).fill('Updated123!');
  await dialog.getByRole('button',{name:'Lưu mật khẩu',exact:true}).click();
  await dialog.getByRole('alert').filter({hasText:'Mật khẩu hiện tại không đúng.'}).waitFor();
  await dialog.getByRole('button',{name:'Lưu mật khẩu',exact:true}).click();
  await dialog.getByText('Đổi mật khẩu thành công.',{exact:true}).waitFor();
  assert.deepEqual(password,{currentPassword:'Original123!',newPassword:'Updated123!'});
  await page.setViewportSize({width:390,height:844});
  assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true);
  await page.screenshot({path:'backend/target/booking-fixes-admin-mobile.png',fullPage:true});
  const customer=await browser.newPage({viewport:{width:390,height:844}});let changes=0;
  await customer.route('https://fonts.googleapis.com/**',r=>r.fulfill({body:'',contentType:'text/css'}));
  await customer.addInitScript(()=>{sessionStorage.setItem('bcm.accessToken','fixture');sessionStorage.setItem('bcm.user',JSON.stringify({id:'customer-fixture',email:'fixture@test.invalid',fullName:'Khách thử',roles:['CUSTOMER']}));});
  await customer.route('**/api/**',route=>{if(new URL(route.request().url()).pathname==='/api/users/me/password')changes++;return route.fulfill({json:{success:true,data:null}})});
  await customer.goto(process.env.CUSTOMER_TEST_URL||'http://127.0.0.1:5176/profile');
  await customer.getByLabel('Mật khẩu hiện tại',{exact:true}).fill('Original123!');
  await customer.getByLabel('Mật khẩu mới',{exact:true}).fill('Updated123!');
  await customer.getByLabel('Xác nhận mật khẩu mới',{exact:true}).fill('Updated123!');
  await customer.getByRole('button',{name:'Lưu mật khẩu',exact:true}).click();
  await customer.getByRole('status').filter({hasText:'Đổi mật khẩu thành công.'}).waitFor();assert.equal(changes,1);
  assert.equal(await customer.getByLabel('Mật khẩu mới',{exact:true}).inputValue(),'');
  assert.equal(await customer.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true);
  await customer.screenshot({path:'backend/target/booking-fixes-customer-mobile.png',fullPage:true});
  console.log('Counter receipt, early checkout preview, password forms, errors and mobile layout passed.');
 }finally{await browser.close()}
})().catch(e=>{console.error(e);process.exitCode=1});
