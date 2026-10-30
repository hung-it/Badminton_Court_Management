const {chromium}=require(process.env.PLAYWRIGHT_MODULE||'playwright');
const assert=require('node:assert/strict');
(async()=>{
 const browser=await chromium.launch({headless:true,channel:'msedge'});
 try{
 const page=await browser.newPage();const id='11111111-1111-4111-8111-111111111111';
 let unverified=false,products=0,posted;
 await page.route('**/api/**',route=>{
  const req=route.request(),path=new URL(req.url()).pathname;let data=[];
  if(path==='/api/checkout/active-bookings')data=[{bookingId:id,shortId:'11111111',courtDetails:'Test court',customerName:'Test customer',status:'PAID',courtFee:200000}];
  if(path==='/api/checkout/'+id){
   data={bookingId:id,court_fee:200000,product_fee:products,total_amount:200000+products,paidCourtAmount:unverified?0:200000,amountDue:(unverified?200000:0)+products,courtReceiptRequired:unverified};
   if(req.method()==='POST'){posted=req.postDataJSON();data={...data,paidCourtAmount:200000,amountDue:products,invoiceId:'invoice-test'};}
  }
  return route.fulfill({json:{success:true,data}});
 });
 await page.addInitScript(()=>sessionStorage.setItem('bcm-session',JSON.stringify({accessToken:'fixture',user:{roles:['STAFF'],fullName:'Staff'}})));
 async function open(){await page.goto('http://127.0.0.1:5173');await page.getByRole('button',{name:'Trả sân',exact:true}).click();await page.getByRole('button',{name:'Kết toán',exact:true}).click();await page.locator('.invoice-row').first().waitFor();}
 await open();
 assert.match(await page.locator('.invoice-paper .total-line').innerText(),/0\s*₫/);
 assert.equal(await page.locator('.payment-picker').count(),0);
 await page.getByRole('button',{name:'✓ Hoàn tất trả sân',exact:true}).click();await page.getByText(/Kết toán thành công!/).waitFor();
 assert.equal(posted.courtReceiptConfirmed,false);
 unverified=true;await open();
 await page.getByText(/không yêu cầu khách chuyển lại tiền sân/).waitFor();
 assert.equal(await page.locator('.payment-picker').count(),0);
 await page.getByRole('checkbox').check();
 assert.match(await page.locator('.invoice-paper .total-line').innerText(),/0\s*₫/);
 await page.getByRole('button',{name:'✓ Hoàn tất trả sân',exact:true}).click();await page.getByText(/Kết toán thành công!/).waitFor();
 assert.equal(posted.courtReceiptConfirmed,true);
 unverified=false;products=30000;await open();
 assert.match(await page.locator('.invoice-paper .total-line').innerText(),/30\.000/);
 assert.equal(await page.locator('.payment-picker button').count(),3);
 console.log('PASS: prepaid court-only checkout collects zero, declaration reconciles without repayment, only goods charged when added.');
 }finally{await browser.close()}
})().catch(e=>{console.error(e);process.exitCode=1});
