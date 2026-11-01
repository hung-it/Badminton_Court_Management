const {chromium}=require(process.env.PLAYWRIGHT_MODULE||'playwright');
const assert=require('node:assert/strict');
(async()=>{
 const browser=await chromium.launch({headless:true,channel:'msedge'});
 try{
 const page=await browser.newPage({viewport:{width:1366,height:768}});
 const id='11111111-1111-4111-8111-111111111111';let completed=false,failBill=false,payment;
 await page.route('**/api/**',route=>{
  const req=route.request(),path=new URL(req.url()).pathname;let data=[];
  if(path==='/api/checkout/active-bookings')data=completed?[]:[{bookingId:id,shortId:'11111111',courtDetails:'Sân kiểm thử (30/10/2026 10:00-11:00)',customerName:'Khách thử',customerPhone:'0900000000',courtFee:200000,status:'PAID'}];
  if(path==='/api/checkout/'+id){
   if(failBill)return route.fulfill({status:500,json:{success:false,message:'Không tải được bill kiểm thử'}});
   data={bookingId:id,court_fee:200000,product_fee:30000,total_amount:230000,paidCourtAmount:200000,amountDue:30000};
   if(req.method()==='POST'){payment=req.postDataJSON();completed=true;data.invoiceId='paid-invoice';}
  }
  return route.fulfill({json:{success:true,data}});
 });
 await page.addInitScript(()=>sessionStorage.setItem('bcm-session',JSON.stringify({accessToken:'fixture',user:{roles:['STAFF'],fullName:'Staff'}})));
 await page.goto('http://127.0.0.1:5173');
 assert.equal(await page.getByRole('button',{name:'Sân đã đặt',exact:true}).count(),0);
 assert.equal(await page.getByText('Tiền hàng được gộp với tiền sân khi kết toán. Áp dụng đơn đã thanh toán sân, chưa kết toán.',{exact:true}).count(),0);
 assert.equal(await page.getByText('Không nhập mã vẫn tự áp dụng ưu đãi sản phẩm tốt nhất. Nhập mã để kiểm tra điều kiện; không cộng dồn giảm hai lần và không đổi tiền sân.',{exact:true}).count(),0);
 await page.getByRole('button',{name:'Trả sân',exact:true}).click();
 await page.getByRole('cell',{name:'11111111',exact:false}).waitFor();
 assert.equal(await page.getByRole('dialog').count(),0);
 await page.getByRole('textbox',{name:'Tìm kiếm',exact:true}).fill('không khớp');
 await page.getByText('Chưa có sân đã đặt',{exact:true}).waitFor();
 await page.getByRole('textbox',{name:'Tìm kiếm',exact:true}).fill('0900000000');
 failBill=true;await page.getByRole('button',{name:'Kết toán',exact:true}).click();
 await page.getByRole('dialog').getByText('Không tải được bill kiểm thử',{exact:true}).waitFor();
 assert.equal(await page.getByRole('button',{name:'✓ Xác nhận & thu tiền',exact:true}).isDisabled(),true);
 failBill=false;await page.getByRole('button',{name:'Tải lại bill',exact:true}).click();
 await page.getByText('TỔNG BILL SAU GIẢM:',{exact:true}).waitFor();
 assert.match(await page.locator('.invoice-paper .total-line').innerText(),/30\.000/);
 assert.equal(await page.locator('.payment-picker button').count(),3);
 await page.getByRole('button',{name:'Đóng bill',exact:true}).click();
 assert.equal(await page.getByRole('dialog').count(),0);
 await page.setViewportSize({width:390,height:844});
 await page.getByRole('button',{name:'Kết toán',exact:true}).click();
 await page.getByText('TỔNG BILL SAU GIẢM:',{exact:true}).waitFor();
 assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true);
 await page.getByRole('button',{name:/Chuyển khoản \/ QR/}).click();
 await page.getByRole('button',{name:'✓ Xác nhận & thu tiền',exact:true}).click();
 await page.getByText(/Kết toán thành công!/).waitFor();
 assert.equal(payment.paymentMethod,'BANK_TRANSFER');assert.equal(await page.getByRole('dialog').count(),0);
 assert.equal(await page.getByRole('button',{name:'Kết toán',exact:true}).count(),0);
 console.log('PASS: merged checkout list, search, bill retry, three payment methods, close, mobile, successful checkout removes booking.');
 }finally{await browser.close()}
})().catch(e=>{console.error(e);process.exitCode=1});
