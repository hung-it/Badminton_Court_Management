const {chromium}=require(process.env.PLAYWRIGHT_MODULE||'playwright');
const assert=require('node:assert/strict');
(async()=>{
 const browser=await chromium.launch({headless:true,channel:'msedge'});
 try{
 const page=await browser.newPage({viewport:{width:1366,height:768}});
 const supplier='11111111-1111-4111-8111-111111111111',product='22222222-2222-4222-8222-222222222222',orderId='33333333-3333-4333-8333-333333333333';
 let orders=[],payload,approveCount=0;
 await page.route('**/api/**',route=>{
  const req=route.request(),path=new URL(req.url()).pathname;let data=[];
  if(path==='/api/admin/products')data=[{id:product,name:'Cầu nhập kho',unit:'hộp',type:'GOODS'},{id:'service',name:'Dịch vụ',type:'SERVICE'}];
  if(path==='/api/suppliers')data=[{id:supplier,name:'Nhà cung cấp A'}];
  if(path==='/api/import-orders'||path==='/api/import-orders/manual'){
   if(req.method()==='POST'){
    payload=req.postDataJSON();
    orders=[{id:orderId,supplierId:supplier,importDate:'2026-10-30',details:[{productId:product,quantity:payload.quantity,importPrice:Number(payload.totalAmount)/payload.quantity}],totalAmount:Number(payload.totalAmount),status:'DRAFT'}];data=orders[0];
   }else data=orders;
  }
  if(path==='/api/import-orders/'+orderId)data=orders[0];
  if(path==='/api/import-orders/'+orderId+'/approve'){approveCount++;orders[0]={...orders[0],status:'RECEIVED'};data=orders[0];}
  return route.fulfill({json:{success:true,data}});
 });
 await page.addInitScript(()=>sessionStorage.setItem('bcm-session',JSON.stringify({accessToken:'fixture',user:{roles:['ADMIN'],fullName:'Admin'}})));
 await page.goto('http://127.0.0.1:5173');await page.getByRole('button',{name:'Nhập kho',exact:true}).click();
 assert.equal(await page.locator('.stats>div').count(),3);
 for(const title of ['Số phiếu nhập kho','Số nhà cung cấp','Tổng giá trị nhập'])assert.equal(await page.getByText(title,{exact:true}).count(),1);
 assert.deepEqual(await page.locator('.workspace table th').allTextContents(),['Mã phiếu','Ngày nhập','Tổng tiền','Trạng thái']);
 await page.getByRole('button',{name:'＋ Tạo phiếu nhập',exact:true}).click();
 const dialog=page.getByRole('dialog'),submit=dialog.getByRole('button',{name:'Tạo phiếu nhập',exact:true});
 assert.equal(await submit.isDisabled(),true);
 assert.equal(await dialog.getByLabel('Nhà cung cấp',{exact:true}).inputValue(),'');
 await dialog.getByLabel('Nhà cung cấp',{exact:true}).fill('Nhà cung cấp nhập tay');
 assert.equal(await dialog.locator('select').count(),0);
 await dialog.getByLabel('Loại sản phẩm',{exact:true}).fill('Sản phẩm nhập tay');
 await dialog.getByLabel('Số lượng',{exact:true}).fill('0');await dialog.getByLabel('Tổng tiền',{exact:true}).fill('100000');
 await submit.click();assert.equal(payload,undefined);
 await dialog.getByLabel('Số lượng',{exact:true}).fill('3');await submit.click();
 await page.getByText('Lập phiếu nhập thành công.',{exact:true}).waitFor();
 assert.equal(payload.supplierName,'Nhà cung cấp nhập tay');assert.equal(payload.productName,'Sản phẩm nhập tay');
 assert.equal(payload.quantity,3);
 assert.equal(payload.totalAmount,'100000');
 await page.getByText('Chưa duyệt',{exact:true}).waitFor();
 await page.getByRole('button',{name:'Chi tiết',exact:true}).click();
 await page.getByRole('heading',{name:'Chi tiết phiếu nhập',exact:true}).waitFor();
 assert.match(await page.getByRole('dialog').innerText(),/100\.000/);
 await page.getByRole('button',{name:'Duyệt nhập kho',exact:true}).click();await page.getByText('Trạng thái: Đã nhập kho',{exact:true}).waitFor();
 assert.equal(approveCount,1);assert.equal(await page.getByRole('button',{name:'Duyệt nhập kho',exact:true}).count(),0);
 await page.getByRole('button',{name:'Đóng',exact:true}).click();
 await page.reload();await page.getByRole('button',{name:'Nhập kho',exact:true}).click();
 await page.getByText('Đã duyệt',{exact:true}).waitFor();
 await page.getByRole('button',{name:'Chi tiết',exact:true}).click();
 await page.getByText('Trạng thái: Đã nhập kho',{exact:true}).waitFor();
 assert.match(await page.getByRole('dialog').innerText(),/100\.000/);
 assert.equal(await page.getByRole('button',{name:'Duyệt nhập kho',exact:true}).count(),0);
 await page.getByRole('button',{name:'Đóng',exact:true}).click();await page.setViewportSize({width:390,height:844});
 await page.getByRole('button',{name:'＋ Tạo phiếu nhập',exact:true}).click();
 assert.equal(await page.getByLabel('Số lượng',{exact:true}).inputValue(),'');
 assert.equal(await page.getByLabel('Tổng tiền',{exact:true}).inputValue(),'');
 assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true);
 console.log('PASS: 3 statistics, 4 columns, required fields, exact total allocation, receipt details, approval, reset.');
 }finally{await browser.close()}
})().catch(e=>{console.error(e);process.exitCode=1});
