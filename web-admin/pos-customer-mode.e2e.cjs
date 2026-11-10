// UI contract checks with isolated API fixtures; never writes to the runtime DB.
const {chromium}=require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const assert=require('node:assert/strict');
const bookingId='11111111-1111-4111-8111-111111111111';
const productId='22222222-2222-4222-8222-222222222222';
(async()=>{
 const browser=await chromium.launch({headless:true,channel:'msedge'});
 try{
 const page=await browser.newPage({viewport:{width:1366,height:768}});
 const sales=[];const checkouts=[];
 const preview={bookingId,court_fee:200000,product_fee:20000,courtDiscount:0,productDiscount:0,total_amount:220000,paidCourtAmount:200000,amountDue:20000};
 await page.route('**/api/**',async route=>{
  const req=route.request(),path=new URL(req.url()).pathname;
  let data=[];
  if(path==='/api/pos/products')data=[{id:productId,name:'Nước kiểm thử',type:'GOODS',price:10000,stockQuantity:10,unit:'chai'}];
  if(path==='/api/pos/quote'){
   const body=req.postDataJSON();
   if(body.bookingId && body.bookingId!==bookingId)return route.fulfill({status:404,json:{success:false,message:'Không tìm thấy booking'}});
   data={productFee:10000,discountAmount:0,totalAmount:10000,lines:[{productId,unitPrice:10000}]};
  }
  if(path==='/api/pos/sales'){
   const body=req.postDataJSON();sales.push(body);
   data={id:'invoice-'+sales.length,totalAmount:10000,status:body.bookingId?'DRAFT':'PAID'};
  }
  if(path==='/api/checkout/'+bookingId){
   data=sales.some(s=>s.bookingId)?{...preview,product_fee:30000,total_amount:230000,amountDue:30000}:preview;
   if(req.method()==='POST'){
    checkouts.push(req.postDataJSON());
    data={...data,invoiceId:'combined-invoice'};
   }else data={...data,paidCourtAmount:0,amountDue:data.total_amount,courtReceiptRequired:true};
  }
  return route.fulfill({json:{success:true,data}});
 });
 await page.addInitScript(()=>sessionStorage.setItem('bcm-session',JSON.stringify({accessToken:'fixture',user:{roles:['STAFF'],fullName:'POS Test'}})));
 await page.goto('http://127.0.0.1:5173');
 await page.getByRole('button',{name:'Thêm +',exact:true}).click();
 const add=page.getByRole('button',{name:'Thêm hàng & xem bill gộp',exact:true});
 assert.equal(await add.isDisabled(),true);
 await page.getByLabel('Mã đơn đặt sân (UUID)',{exact:true}).fill('invalid');
 await page.getByText('Vui lòng nhập mã đơn đặt sân (UUID) hợp lệ.',{exact:true}).waitFor();
 assert.equal(await add.isDisabled(),true);
 await page.getByLabel('Mã đơn đặt sân (UUID)',{exact:true}).fill(bookingId);
 await page.locator('.pos-combined-bill').waitFor();
 assert.match(await page.locator('.pos-combined-bill').innerText(),/Tiền hàng:\s*30\.000/);
 assert.equal(await page.getByText('Tiền hàng sau giảm giá:',{exact:true}).count(),0);
 assert.equal(await page.getByText('Tổng bill dự kiến:',{exact:true}).count(),0);
 assert.equal(await page.getByText('Tiền sân đã thanh toán:',{exact:true}).count(),0);
 await add.click();
 await page.getByText('CHỜ ĐỐI SOÁT TIỀN SÂN:',{exact:true}).waitFor();
 assert.equal(sales[0].bookingId,bookingId);
 assert.equal(sales[0].courtFee,0);
 assert.match(await page.locator('.invoice-paper .total-line').innerText(),/Chưa xác nhận/);
 const confirm=page.getByRole('button',{name:'✓ Xác nhận & thu tiền',exact:true});
 assert.equal(await confirm.isDisabled(),true);
 await page.getByRole('checkbox').check();
 assert.match(await page.locator('.invoice-paper .total-line').innerText(),/30\.000/);
 await confirm.click();
 await page.getByText(/Kết toán thành công! Hóa đơn combined-invoice/).waitFor();
 assert.equal(checkouts[0].courtReceiptConfirmed,true);
 assert.equal(await page.getByRole('dialog').count(),0);
 await page.getByRole('button',{name:'Bán tại quầy',exact:true}).click();
 await page.getByLabel('Khách vãng lai',{exact:true}).check();
 assert.equal(await page.getByLabel('Mã đơn đặt sân (UUID)',{exact:true}).count(),0);
 await page.getByRole('button',{name:'Thêm +',exact:true}).click();
 const cash=page.getByRole('button',{name:'✓ Thu tiền hàng (CASH)',exact:true});
 await cash.waitFor();
 await page.waitForFunction(()=>[...document.querySelectorAll('button')].some(b=>b.textContent==='✓ Thu tiền hàng (CASH)'&&!b.disabled));
 await cash.click();
 await page.getByText('Bán hàng thành công! Hóa đơn chỉ gồm hàng hóa, không kèm tiền sân.',{exact:true}).waitFor();
 assert.equal(sales.length,2);assert.equal(sales[1].bookingId,null);assert.equal(sales[1].courtFee,0);
 console.log('PASS: invalid UUID blocked, booking bill combined, walk-in sale detached.');
 }finally{await browser.close()}
})().catch(e=>{console.error(e);process.exitCode=1});
