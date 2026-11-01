const {chromium}=require(process.env.PLAYWRIGHT_MODULE||'playwright');
const assert=require('node:assert/strict');
(async()=>{
 const browser=await chromium.launch({headless:true,channel:'msedge'});
 try{
 const page=await browser.newPage();const category='11111111-1111-4111-8111-111111111111';
 let product={id:'22222222-2222-4222-8222-222222222222',categoryId:category,name:'Hàng chờ duyệt',type:'GOODS',unit:'cái',price:0,stockQuantity:3,version:0,priceApprovalManaged:true};let submitted;
 await page.route('**/api/**',route=>{
  const req=route.request(),path=new URL(req.url()).pathname;let data=[];
  if(path==='/api/admin/categories')data=[{id:category,categoryName:'Hàng nhập kho'}];
  if(path==='/api/admin/products')data=[product];
  if(path==='/api/admin/products/'+product.id+'/approve-price'){
   submitted=req.postDataJSON();product={...product,price:Number(submitted.price),version:1};data=product;
  }
  return route.fulfill({json:{success:true,data}});
 });
 await page.addInitScript(()=>sessionStorage.setItem('bcm-session',JSON.stringify({accessToken:'fixture',user:{roles:['ADMIN'],fullName:'Admin'}})));
 await page.goto('http://127.0.0.1:5173');await page.getByRole('button',{name:'Bán tại quầy',exact:true}).click();
 assert.equal(await page.getByText('Hàng chờ duyệt',{exact:true}).count(),0);
 await page.getByRole('button',{name:'Hàng hóa & dịch vụ',exact:true}).click();
 await page.getByText('Chờ duyệt giá',{exact:true}).waitFor();
 await page.getByRole('button',{name:'Duyệt giá niêm yết',exact:true}).click();
 const dialog=page.getByRole('dialog');
 assert.equal(await dialog.getByRole('button',{name:'Duyệt & cho phép bán',exact:true}).isDisabled(),true);
 await page.getByLabel('Giá niêm yết cần duyệt',{exact:true}).fill('25000');
 await dialog.getByRole('button',{name:'Duyệt & cho phép bán',exact:true}).click();
 await page.getByText('Đã duyệt giá niêm yết. Sản phẩm được phép bán tại quầy.',{exact:true}).waitFor();
 await page.getByText('Đã duyệt',{exact:true}).waitFor();
 assert.equal(Number(submitted.price),25000);assert.equal(submitted.version,0);assert.equal(product.stockQuantity,3);
 await page.getByRole('button',{name:'Bán tại quầy',exact:true}).click();
 await page.getByText('Hàng chờ duyệt',{exact:true}).waitFor();
 assert.match(await page.locator('.pos-product-card').innerText(),/25\.000/);
 console.log('PASS: imported goods hidden before price approval, Admin approves positive price, POS shows approved product.');
 }finally{await browser.close()}
})().catch(e=>{console.error(e);process.exitCode=1});
