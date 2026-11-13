// UI contract checks with isolated API fixtures; never writes to the runtime DB.
const {chromium}=require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const assert=require('node:assert/strict');
const admin=process.env.ADMIN_TEST_URL||'http://127.0.0.1:5173';
const bookingId='11111111-1111-4111-8111-111111111111';
const productId='22222222-2222-4222-8222-222222222222';
(async()=>{
 const browser=await chromium.launch({headless:true,channel:'msedge'});
 try{
 const page=await browser.newPage({viewport:{width:1366,height:768}});
 await page.route("https://fonts.googleapis.com/**",r=>r.fulfill({body:"",contentType:"text/css"}));
 const errors=[];page.on('pageerror',e=>errors.push(e.message));
 const sales=[];const checkouts=[];
 const preview={bookingId,court_fee:200000,product_fee:20000,courtDiscount:0,productDiscount:0,total_amount:220000,paidCourtAmount:200000,amountDue:20000,checkoutEligible:true,latestEndAt:'2026-10-30T11:00:00'};
 await page.route('**/api/**',async route=>{
  const req=route.request(),path=new URL(req.url()).pathname;
  let data=[];
  if(path==='/api/pos/customers'&&new URL(req.url()).searchParams.get('phone')==='0933557799')data=[
   {id:'33333333-3333-4333-8333-333333333333',fullName:'Người mua đã đăng ký',phone:'0933557799',kind:'REGISTERED'},
   {id:'44444444-4444-4444-8444-444444444444',fullName:'Hồ sơ vãng lai cũ',phone:'0933557799',kind:'WALK_IN'}
  ];
  if(path==='/api/pos/drafts')data={content:[],page:0,totalPages:0,totalElements:0};
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
   }
  }
  return route.fulfill({json:{success:true,data}});
 });
 await page.addInitScript(()=>sessionStorage.setItem('bcm-session',JSON.stringify({accessToken:'fixture',user:{roles:['STAFF'],fullName:'POS Test'}})));
 await page.goto(admin);
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
 await page.getByText('TỔNG BILL SAU GIẢM:',{exact:true}).waitFor();
 assert.equal(sales[0].bookingId,bookingId);assert.equal(sales[0].customerPhone,null);
 assert.equal(sales[0].courtFee,0);
 assert.match(await page.locator('.invoice-paper .total-line').innerText(),/230\.000/);
 assert.match(await page.locator('.invoice-paper').innerText(),/Còn phải thu:\s*30\.000/);
 const confirm=page.getByRole('button',{name:'✓ Xác nhận & thu tiền',exact:true});
 assert.equal(await confirm.isDisabled(),true);
 await page.getByRole('checkbox').check();
 assert.equal(await confirm.isEnabled(),true);
 await confirm.click();
 await page.getByText(/Kết toán thành công! Hóa đơn combined-invoice/).waitFor();
 assert.equal(checkouts[0].paymentConfirmed,true);assert.equal(checkouts[0].expectedAmountDue,30000);assert.equal(checkouts[0].paymentMethod,'CASH');
 assert.equal(await page.getByRole('dialog').count(),0);
 await page.getByRole('button',{name:'Bán tại quầy',exact:true}).click();
 await page.getByLabel('Khách vãng lai',{exact:true}).check();
 assert.equal(await page.getByText('Thu tiền hàng ngay, hóa đơn không kèm tiền sân.',{exact:true}).count(),0);
 assert.equal(await page.getByLabel('Số điện thoại khách hàng',{exact:true}).count(),1);
 await page.getByLabel('Số điện thoại khách hàng',{exact:true}).fill('0912 345 678');
 await page.getByLabel('Khách thuê sân',{exact:true}).check();assert.equal(await page.getByLabel('Số điện thoại khách hàng',{exact:true}).count(),0);
 await page.getByLabel('Khách vãng lai',{exact:true}).check();
 await page.getByLabel('Số điện thoại khách hàng',{exact:true}).fill('');
 assert.equal(await page.getByLabel('Mã đơn đặt sân (UUID)',{exact:true}).count(),0);
 await page.getByRole('button',{name:'Thêm +',exact:true}).click();
 const cash=page.getByRole('button',{name:'✓ Thu tiền hàng (CASH)',exact:true});
 await cash.waitFor();
 assert.equal(await cash.isDisabled(),true);
 for(const invalid of ['', 'abc', '00000000', '123', '1234567890123456']){await page.getByLabel('Số điện thoại khách hàng',{exact:true}).fill(invalid);await page.getByRole('checkbox').check();assert(await cash.isDisabled());}
 await page.getByLabel('Số điện thoại khách hàng',{exact:true}).fill('0912 345 678');
 await page.getByRole('checkbox').check();
 await page.waitForFunction(()=>[...document.querySelectorAll('button')].some(b=>b.textContent==='✓ Thu tiền hàng (CASH)'&&!b.disabled));
 await cash.click();
 await page.getByText('Bán hàng thành công! Hóa đơn chỉ gồm hàng hóa, không kèm tiền sân.',{exact:true}).waitFor();
 assert.equal(sales[1].customerPhone,'0912 345 678');assert.equal(await page.getByLabel('Số điện thoại khách hàng',{exact:true}).inputValue(),'');
 assert.equal(sales.length,2);assert.equal(sales[1].bookingId,null);assert.equal(sales[1].courtFee,0);assert.equal(sales[1].paymentConfirmed,true);assert.equal(sales[1].expectedTotal,10000);
 await page.getByLabel('Phương thức POS',{exact:true}).selectOption('VNPAY');
 await page.getByRole('button',{name:'Thêm +',exact:true}).click();
 await page.getByLabel('Số điện thoại khách hàng',{exact:true}).fill('+84 912 345 678');
 await page.getByRole('checkbox').check();await page.getByLabel('Số điện thoại khách hàng',{exact:true}).fill('+84 987 654 321');
 await page.waitForFunction(()=>!document.querySelector('.pos-receipt-confirm input').checked);
 const pay=page.getByRole('button',{name:'✓ Thu tiền hàng (VNPAY)',exact:true});assert(await pay.isDisabled());
 for(const width of [1440,1024,768,375]){await page.setViewportSize({width,height:1000});assert(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),'overflow '+width);const spacing=await page.locator('.pos-order-panel .pos-summary').evaluate(el=>{const fields=[...el.querySelectorAll(':scope > .field')].map(e=>e.getBoundingClientRect());return fields.every((b,i)=>i===0||fields[i-1].bottom<=b.top)});assert(spacing);}
 await page.getByRole('checkbox').check();await pay.click();await page.getByText('Bán hàng thành công! Hóa đơn chỉ gồm hàng hóa, không kèm tiền sân.',{exact:true}).waitFor();
 assert.equal(sales[2].paymentMethod,'VNPAY');assert.equal(sales[2].customerPhone,'+84 987 654 321');
 await page.getByRole('button',{name:'Thêm +',exact:true}).click();
 await page.getByLabel('Số điện thoại khách hàng',{exact:true}).fill('0933557799');
 const buyer=page.getByLabel('Hồ sơ khách hàng',{exact:true});await buyer.waitFor();
 assert.equal(await buyer.locator('option').count(),3);
 assert(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),'duplicate buyer selector overflows mobile viewport');
 await page.getByRole('checkbox').check();assert(await pay.isDisabled());
 await buyer.selectOption('33333333-3333-4333-8333-333333333333');
 await page.waitForFunction(()=>!document.querySelector('.pos-receipt-confirm input').checked);
 await page.getByRole('checkbox').check();await pay.click();
 await page.getByText('Bán hàng thành công! Hóa đơn chỉ gồm hàng hóa, không kèm tiền sân.',{exact:true}).waitFor();
 assert.equal(sales[3].customerId,'33333333-3333-4333-8333-333333333333');
 assert.equal(sales[3].customerPhone,'0933557799');assert.deepEqual(errors,[]);
 console.log('PASS: booking unchanged; invalid phones blocked; CASH/VNPAY and selected customer carried; duplicate phone requires selection; edits revoke confirmation; responsive 1440/1024/768/375; no JS errors.');
 }finally{await browser.close()}
})().catch(e=>{console.error(e);process.exitCode=1});
