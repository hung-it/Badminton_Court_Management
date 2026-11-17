// Browser regression for admin checkout UI; all API traffic is mocked (no real sales).
import assert from 'node:assert/strict';
import {pathToFileURL} from 'node:url';
const moduleName=process.env.PLAYWRIGHT_MODULE||'playwright';
const {chromium}=await import(moduleName.includes(':')?pathToFileURL(moduleName).href:moduleName);
const base=process.env.BCM_ADMIN_UI_TEST_URL||'http://127.0.0.1:5183';
const browser=await chromium.launch({channel:'chrome',headless:true});
const page=await browser.newPage();
const errors=[];page.on('pageerror',e=>errors.push(e.message));
const booking='11111111-1111-4111-8111-111111111111';
const product={id:'22222222-2222-4222-8222-222222222222',name:'UI test water',type:'GOODS',price:10000,stockQuantity:100,unit:'chai'};
const quotes=[],payments=[];
let due=10000,conflict=true,failRefresh=false;
await page.addInitScript(()=>sessionStorage.setItem('bcm-session',JSON.stringify({accessToken:'mock-only',user:{email:'staff@ui.test',roles:['STAFF']}})));
await page.route('**/api/**',async route=>{
 const req=route.request(),url=new URL(req.url()),path=url.pathname.replace('/api','');
 let data=[],status=200,message='Success';
 if(path==='/pos/products')data=[product];
 if(path==='/pos/customers'){
  const phone=url.searchParams.get('phone');
  data=phone==='0900000003'?[{id:'buyer-c',fullName:'Buyer C',phone,kind:'REGISTERED'},{id:'buyer-d',fullName:'Buyer D',phone,kind:'REGISTERED'}]:[{id:phone==='0900000001'?'buyer-a':'buyer-b',fullName:'Test buyer',phone,kind:'REGISTERED'}];
 }
 if(path==='/pos/quote'){
  const body=req.postDataJSON();quotes.push(body);
  const auto=body.promotionCode==='VIP';
  data={productFee:10000,totalAmount:auto?8000:9000,discountAmount:auto?2000:1000,promotionCode:auto?'BILL-BEST':body.promotionCode,promotionName:auto?'Best automatic':'Voucher ten',lines:[{productId:product.id,unitPrice:10000,discountAmount:0}]};
 }
 if(path==='/checkout/active-bookings')data=[{bookingId:booking,shortId:'11111111',status:'PAID',customerName:'UI booking',customerPhone:'0900000001',courtFee:200000,finalAmount:200000,courtSchedules:[]}];
 if(path==='/checkout/'+booking){
  if(req.method()==='POST'){
   payments.push(req.postDataJSON());
   if(conflict){conflict=false;due=20000;status=409;message='Bill changed; reload and confirm the current amount due';}
   else data={bookingId:booking,invoiceId:'paid-ui-test',total_amount:200000+due,paidCourtAmount:200000,amountDue:due,remainingDue:0};
  }else if(failRefresh){status=503;message='Test refresh failure';}
  else data={bookingId:booking,court_fee:200000,product_fee:due,total_amount:200000+due,paidCourtAmount:200000,amountDue:due,checkoutEligible:true};
 }
 await route.fulfill({status,contentType:'application/json',body:JSON.stringify({success:status===200,data,message})});
});
try{
 await page.goto(base+'/?tab=pos');
 await page.getByRole('radio',{name:'Khách vãng lai'}).check();
 await page.getByRole('button',{name:'Thêm +',exact:true}).click();
 await page.getByLabel('Số điện thoại khách hàng',{exact:true}).fill('0900000001');
 await page.getByLabel('Mã voucher',{exact:true}).fill('VIP');
 await page.waitForFunction(()=>document.body.textContent.includes('Ưu đãi này có lợi hơn voucher đã nhập.'));
 assert.equal(quotes.at(-1).customerId,'buyer-a');assert.equal(quotes.at(-1).customerPhone,'0900000001');
 assert.equal(await page.getByText('Đã áp dụng mã voucher VIP',{exact:false}).count(),0);
 await page.getByLabel('Số điện thoại khách hàng',{exact:true}).fill('0900000002');
 await page.waitForResponse(r=>r.url().endsWith('/pos/quote')&&r.request().postDataJSON()?.customerId==='buyer-b');
 assert.equal(quotes.at(-1).customerPhone,'0900000002');
 await page.getByLabel('Số điện thoại khách hàng',{exact:true}).fill('0900000003');
 await page.getByLabel('Hồ sơ khách hàng',{exact:true}).selectOption('buyer-d');
 await page.waitForResponse(r=>r.url().endsWith('/pos/quote')&&r.request().postDataJSON()?.customerId==='buyer-d');
 await page.getByLabel('Mã voucher',{exact:true}).fill('VIP10');
 await page.getByText('Đã áp dụng mã voucher VIP10',{exact:false}).waitFor();
 await page.goto(base+'/?tab=checkout');
 await page.getByRole('button',{name:'Kết toán',exact:true}).click();
 const confirmation=page.getByRole('checkbox');
 await confirmation.check();
 await page.getByRole('button',{name:'✓ Xác nhận & thu tiền',exact:true}).click();
 await page.getByText('Bill đã thay đổi. Đã tải lại số tiền mới; vui lòng kiểm tra và xác nhận lại trước khi thu tiền.',{exact:true}).waitFor();
 assert.equal(await confirmation.isChecked(),false);
 assert.equal(await page.getByRole('button',{name:'✓ Xác nhận & thu tiền',exact:true}).isDisabled(),true);
 assert.equal(payments.length,1);assert.equal(payments[0].expectedAmountDue,10000);
 await page.getByText('Tôi xác nhận đã nhận',{exact:false}).filter({has:page.locator('strong')}).waitFor();
 await confirmation.check();
 await page.getByRole('button',{name:'✓ Xác nhận & thu tiền',exact:true}).click();
 await page.getByText(/Kết toán thành công!/).waitFor();
 assert.equal(payments.length,2);assert.equal(payments[1].expectedAmountDue,20000);
 // A failed refresh must clear the old bill and prevent another payment.
 conflict=true;due=10000;
 await page.reload();
 await page.getByRole('button',{name:'Kết toán',exact:true}).click();
 await page.getByRole('checkbox').check();failRefresh=true;
 await page.getByRole('button',{name:'✓ Xác nhận & thu tiền',exact:true}).click();
 await page.getByText('Không tải lại được bill. Test refresh failure',{exact:true}).waitFor();
 assert.equal(await page.locator('.checkout-submit-btn').isDisabled(),true);
 assert.equal(errors.length,0,errors.join('\n'));
 console.log('PASS: buyer phone/profile requote, actual promotion label, stale bill refresh/reconfirmation, refresh failure blocks payment; no runtime errors');
}finally{await browser.close();}
