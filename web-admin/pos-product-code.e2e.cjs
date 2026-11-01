const {chromium}=require(process.env.PLAYWRIGHT_MODULE||'playwright');
const assert=require('node:assert/strict');
(async()=>{
 const browser=await chromium.launch({headless:true,channel:'msedge'});
 try{
 const page=await browser.newPage({viewport:{width:1366,height:768}});
 const product={id:'22222222-2222-4222-8222-222222222222',name:'Nước kiểm thử',type:'GOODS',price:10000,stockQuantity:10,unit:'chai'};
 const today=new Date().toLocaleDateString('en-CA');let saved,sale;
 const promotion={id:'33333333-3333-4333-8333-333333333333',name:'Giảm nước',code:'WATER10',productId:product.id,productName:product.name,minQuantity:1,discountPercent:10,validFrom:today,validTo:today,active:true};
 await page.route('**/api/**',route=>{
  const req=route.request(),path=new URL(req.url()).pathname;let data=[];
  if(path==='/api/admin/products')data=[product];
  if(path==='/api/admin/product-promotions'){
   if(req.method()==='POST'){saved=req.postDataJSON();data={...promotion,...saved};}else data=[promotion];
  }
  if(path==='/api/pos/quote'){
   const body=req.postDataJSON();
   if(body.promotionCode==='BAD')return route.fulfill({status:400,json:{success:false,message:'Mã khuyến mãi sản phẩm không tồn tại'}});
   data={productFee:10000,discountAmount:1000,totalAmount:9000,lines:[{productId:product.id,unitPrice:10000,discountAmount:1000,promotionName:promotion.name}]};
  }
  if(path==='/api/pos/sales'){sale=req.postDataJSON();data={id:'test-sale',status:'PAID',totalAmount:9000,discountAmount:1000};}
  return route.fulfill({json:{success:true,data}});
 });
 await page.addInitScript(()=>sessionStorage.setItem('bcm-session',JSON.stringify({accessToken:'fixture',user:{roles:['ADMIN'],fullName:'Admin'}})));
 await page.goto('http://127.0.0.1:5173');
 await page.getByRole('button',{name:'Khuyến mãi',exact:true}).click();
 await page.getByText('WATER10',{exact:true}).waitFor();
 await page.getByRole('button',{name:'＋ Thêm khuyến mãi',exact:true}).click();
 await page.getByLabel('Tên chương trình',{exact:true}).fill('Mã mới');
 await page.getByLabel('Mã giảm giá sản phẩm',{exact:true}).fill('SAVE10');
 await page.getByRole('button',{name:'Lưu khuyến mãi',exact:true}).click();
 await page.getByText('Đã lưu khuyến mãi sản phẩm.',{exact:true}).waitFor();assert.equal(saved.code,'SAVE10');
 await page.getByRole('button',{name:'Bán tại quầy',exact:true}).click();
 await page.getByLabel('Khách vãng lai',{exact:true}).check();
 await page.getByRole('button',{name:'Thêm +',exact:true}).click();
 await page.getByLabel('Mã giảm giá',{exact:true}).fill('BAD');
 await page.getByText('Mã khuyến mãi sản phẩm không tồn tại',{exact:true}).waitFor();
 const pay=page.getByRole('button',{name:'✓ Thu tiền hàng (CASH)',exact:true});assert.equal(await pay.isDisabled(),true);
 await page.getByLabel('Mã giảm giá',{exact:true}).fill('water10');
 await page.getByText('Mã hợp lệ. Đã tính ưu đãi tốt nhất cho sản phẩm trong tổng tiền.',{exact:true}).waitFor();
 assert.match(await page.locator('.pos-summary-total').innerText(),/9\.000/);
 await pay.click();await page.getByText('Bán hàng thành công! Hóa đơn chỉ gồm hàng hóa, không kèm tiền sân.',{exact:true}).waitFor();
 assert.equal(sale.promotionCode,'WATER10');assert.equal(sale.bookingId,null);
 assert.equal(await page.getByLabel('Mã giảm giá',{exact:true}).inputValue(),'');
 console.log('PASS: create/display product code, invalid POS code blocked, valid code quoted and sold, cleared after sale.');
 }finally{await browser.close()}
})().catch(e=>{console.error(e);process.exitCode=1});
