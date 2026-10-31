// Isolated UI fixtures: never writes runtime invoices.
const {chromium}=require(process.env.PLAYWRIGHT_MODULE||'playwright');
const assert=require('node:assert/strict');
(async()=>{
 const browser=await chromium.launch({headless:true,channel:'msedge'});
 try{
 const page=await browser.newPage({viewport:{width:1366,height:768}});
 const invoice={id:'11111111-1111-4111-8111-111111111111',bookingId:'22222222-2222-4222-8222-222222222222',customerName:'Khách kiểm thử',createdAt:'2026-10-30T10:00:00',paymentMethod:'CASH',courtFee:200000,productFee:30000,discountAmount:5000,totalAmount:225000};
 let historyError=false;
 await page.route('**/api/**',route=>{
  const url=new URL(route.request().url());let data=[];
  if(url.pathname==='/api/analytics/revenue')data={total_amount:225000,court_fee:200000,product_fee:30000,from:'2026-10-30',to:'2026-10-30'};
  if(url.pathname==='/api/analytics/invoices'){
   if(historyError)return route.fulfill({status:500,json:{success:false,message:'Không tải được lịch sử'}});
   data=url.searchParams.get('period')==='WEEK'?{invoices:[],page:0,totalPages:0,totalElements:0}:{invoices:[invoice],page:Number(url.searchParams.get('page')),totalPages:2,totalElements:21};
  }
  if(url.pathname==='/api/analytics/invoices/'+invoice.id)data={invoice,items:[{name:'Nước suối',quantity:3,unitPrice:10000,discountAmount:5000,promotionName:'Ưu đãi nước',totalAmount:25000}],courts:[{name:'Sân A',date:'2026-10-30',startTime:'10:00:00',endTime:'11:00:00',price:200000}]};
  return route.fulfill({json:{success:true,data}});
 });
 await page.addInitScript(()=>sessionStorage.setItem('bcm-session',JSON.stringify({accessToken:'fixture',user:{roles:['ADMIN'],fullName:'Admin Test'}})));
 await page.goto('http://127.0.0.1:5173');
 await page.getByRole('button',{name:'Doanh thu',exact:true}).click();
 await page.getByRole('button',{name:'Chi tiết',exact:true}).waitFor();
 await page.getByRole('button',{name:'Chi tiết',exact:true}).click();
 const dialog=page.getByRole('dialog');
 await dialog.getByText('Nước suối',{exact:false}).waitFor();
 assert.match(await dialog.innerText(),/Sân A/);assert.match(await dialog.innerText(),/225\.000/);assert.match(await dialog.innerText(),/25\.000/);
 await page.keyboard.press('Escape');assert.equal(await page.getByRole('dialog').count(),0);
 await page.getByRole('button',{name:'Trang sau',exact:true}).click();await page.getByText('Trang 2 / 2',{exact:true}).waitFor();
 await page.getByRole('button',{name:'Tuần này',exact:true}).click();await page.getByText('Chưa có hóa đơn đã thanh toán trong kỳ này.',{exact:true}).waitFor();
 await page.getByRole('button',{name:'Hôm nay',exact:true}).click();await page.getByText('Trang 1 / 2',{exact:true}).waitFor();
 await page.setViewportSize({width:390,height:844});
 await page.getByRole('button',{name:'Chi tiết',exact:true}).click();await page.getByRole('dialog').getByText('Nước suối',{exact:false}).waitFor();
 assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true);
 await page.getByRole('button',{name:'Đóng',exact:true}).click();
 historyError=true;await page.getByRole('button',{name:'Làm mới',exact:true}).click();await page.getByText('Không tải được lịch sử',{exact:true}).waitFor();
 console.log('PASS: persistent invoice UI, details, pagination, period reset, empty/error states, mobile.');
 }finally{await browser.close()}
})().catch(e=>{console.error(e);process.exitCode=1});
