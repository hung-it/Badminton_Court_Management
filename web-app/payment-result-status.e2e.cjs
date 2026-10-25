// UI contract fixtures only: no backend/database writes.
const {chromium,expect}=require('playwright/test');
const assert=require('node:assert/strict');
const web=process.env.BCM_E2E_WEB_URL||'http://127.0.0.1:5174';
(async()=>{
 const browser=await chromium.launch({channel:'msedge',headless:true});
 try{
  for(const [method,status,confirmation,bookingStatus,title] of [
   ['BANK_TRANSFER','PENDING','CUSTOMER_DECLARED','PAID','Đã xác nhận đặt sân'],
   ['BANK_TRANSFER','SUCCESS','CUSTOMER_DECLARED','PAID','Thanh toán thành công'],
   ['VNPAY','SUCCESS',null,'PAID','Thanh toán thành công'],
   ['VNPAY','FAILED',null,'PENDING','Thanh toán không thành công'],
   ['BANK_TRANSFER','PENDING',null,'PENDING','Đã ghi nhận yêu cầu chuyển khoản'],
   ['BANK_TRANSFER','PENDING','CUSTOMER_DECLARED','EXPIRED','Đơn đặt sân đã hết thời gian thanh toán']]){
   const page=await browser.newPage({viewport:{width:375,height:1000}}),errors=[];page.on('pageerror',error=>errors.push(error.message));
   const testImageFailure=method==='BANK_TRANSFER'&&status==='PENDING'&&bookingStatus==='PAID';
   if(testImageFailure)await page.route('**/assets/courts/court-00001.webp',route=>route.abort());
   await page.addInitScript(()=>{sessionStorage.setItem('bcm.accessToken','fixture');sessionStorage.setItem('bcm.user',JSON.stringify({id:'fixture',fullName:'Payment fixture',roles:['CUSTOMER']}));});
   await page.route('**/api/**',async route=>{
    assert.equal(route.request().method(),'GET');const path=new URL(route.request().url()).pathname;let data;
    if(path.endsWith('/public/courts'))data=[{id:'child',branchId:'branch',branchCourtNumber:1,courtNumber:1}];
    else if(path.endsWith('/bookings'))data={content:[],totalElements:0};
    else if(path.endsWith('/bookings/fixture'))data={bookingId:'fixture',status:bookingStatus,courtFee:410000,discountAmount:0,finalAmount:410000,
      details:[{courtId:'child',branchId:'branch',branchCourtNumber:2,courtNumber:1000012,courtName:'Court',branchName:'Branch',address:'Address',type:'STANDARD_MAT',bookingDate:'2035-01-01',startTime:'15:00',endTime:'16:00',price:410000}],
      payments:[{paymentAttemptId:'attempt',paymentMethod:method,paymentStatus:status,confirmationMethod:confirmation,amount:410000}]};
    else throw Error('Unexpected API '+path);
    await route.fulfill({json:{success:true,data}});
   });
   await page.goto(web+'/payment-result?bookingId=fixture&paymentAttemptId=attempt',{waitUntil:'domcontentloaded'});
   await expect(page.locator('.payment-result-message h1')).toHaveText(title);
   await expect(page.locator('.payment-result-total span')).toHaveText(method==='BANK_TRANSFER'?'Chuyển khoản ngân hàng':'VNPay');
   await expect(page.locator('.payment-result-total')).not.toContainText('Chờ xác nhận');
   await expect(page.locator('.payment-result-total b')).toHaveText('410.000₫');
   if(status==='SUCCESS')await expect(page.locator('.payment-result-court-copy strong')).toHaveText('Đặt sân thành công');
   else await expect(page.locator('.payment-result-message h1')).not.toHaveText('Thanh toán thành công');
   if(testImageFailure){await expect(page.locator('.payment-result-photo-wrap img')).toHaveAttribute('src','/assets/guide-court.jpg');await expect.poll(()=>page.locator('.payment-result-photo-wrap img').evaluate(img=>img.naturalWidth)).toBeGreaterThan(0);}
   await page.reload({waitUntil:'domcontentloaded'});await expect(page.locator('.payment-result-message h1')).toHaveText(title);
   assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false);assert.deepEqual(errors,[]);
   await page.close();
  }
  console.log('PASS payment UI: verified bank/VNPay success, pending declaration, failure and expiry; clean method line, original amount, refresh/mobile and zero writes');
 }finally{await browser.close();}
})().catch(error=>{console.error(error);process.exitCode=1;});
