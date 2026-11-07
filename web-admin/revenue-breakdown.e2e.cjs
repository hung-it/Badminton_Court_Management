const {chromium}=require(process.env.PLAYWRIGHT_MODULE||'playwright');
const assert=require('node:assert/strict');
(async()=>{
 const browser=await chromium.launch({headless:true,channel:'msedge'});
 try{
  const page=await browser.newPage({viewport:{width:1366,height:768}});
  const requests=[];let fail=false;let empty=false;
  const periods=[
   {key:'Q1/2025',label:'Quý 1/2025',from:'2025-01-01',to:'2025-03-31'},
   {key:'Q2/2025',label:'Quý 2/2025',from:'2025-04-01',to:'2025-06-30'},
   {key:'Q3/2025',label:'Quý 3/2025',from:'2025-07-01',to:'2025-09-30'},
   {key:'Q4/2025',label:'Quý 4/2025',from:'2025-10-01',to:'2025-12-31'}];
  const response={period:'QUARTER',year:2025,availableYears:[2025,2024],periods,
   staff:[{staffId:'staff-1',staffName:'Thu ngân mẫu',values:{'Q1/2025':155000,'Q2/2025':230000,'Q3/2025':0,'Q4/2025':160000},total:545000},{staffId:null,staffName:'Chưa gán nhân viên',values:{'Q1/2025':0,'Q2/2025':0,'Q3/2025':40000,'Q4/2025':0},total:40000}],
   customers:[{customerId:'customer-1',customerName:'Nguyễn An',values:{'Q1/2025':155000,'Q2/2025':0,'Q3/2025':40000,'Q4/2025':0},total:195000}],
   products:[{productId:'product-1',productName:'Cầu lông mẫu',quantities:{'Q1/2025':2,'Q2/2025':1,'Q3/2025':0,'Q4/2025':1},values:{'Q1/2025':55000,'Q2/2025':30000,'Q3/2025':0,'Q4/2025':20000},totalQuantity:4,total:105000}],
   productDetails:[],
   courts:[{courtId:'court-1',courtName:'Sân 1',values:{'Q1/2025':100000,'Q2/2025':200000,'Q3/2025':0,'Q4/2025':150000},total:450000}],
   details:[]};
  const detailResponse={...response,details:[
   {periodKey:'Q1/2025',label:'Quý 1/2025',products:[
     {productId:'product-1',productName:'Cầu lông mẫu',staffId:'staff-1',staffName:'Thu ngân mẫu',customerId:'customer-1',customerName:'Nguyễn An',quantities:{},values:{},totalQuantity:2,total:55000}],
    productTotal:55000,courts:[{courtId:'court-1',courtName:'Sân 1',values:{},total:100000}],courtTotal:100000,invoiceTotal:155000},
   {periodKey:'Q2/2025',label:'Quý 2/2025',products:[
     {productId:'product-1',productName:'Cầu lông mẫu',staffId:'staff-1',staffName:'Thu ngân mẫu',customerId:'customer-1',customerName:'Nguyễn An',quantities:{},values:{},totalQuantity:1,total:30000}],
    productTotal:30000,courts:[{courtId:'court-1',courtName:'Sân 1',values:{},total:200000}],courtTotal:200000,invoiceTotal:230000},
   {periodKey:'Q3/2025',label:'Quý 3/2025',products:[],productTotal:0,courts:[],courtTotal:0,invoiceTotal:40000},
   {periodKey:'Q4/2025',label:'Quý 4/2025',products:[],productTotal:0,courts:[],courtTotal:0,invoiceTotal:160000}]};
  await page.route('**/api/**',route=>{
   const url=new URL(route.request().url());
   if(url.pathname==='/api/analytics/revenue-breakdown'){
    requests.push(url.searchParams.toString());
    if(fail)return route.fulfill({status:500,json:{success:false,message:'Không tải được báo cáo'}});
    if(empty)return route.fulfill({json:{success:true,data:{...detailResponse,staff:[],customers:[],products:[],courts:[],details:[]}}});
    return route.fulfill({json:{success:true,data:detailResponse}});
   }
   return route.fulfill({json:{success:true,data:[]}});
  });
  await page.addInitScript(()=>sessionStorage.setItem('bcm-session',JSON.stringify({accessToken:'fixture',user:{roles:['ADMIN'],fullName:'Admin Test'}})));
  await page.goto('http://127.0.0.1:5173');
  await page.getByRole('button',{name:'Doanh thu theo đối tượng',exact:true}).click();
  await page.getByRole('rowheader',{name:'Thu ngân mẫu',exact:true}).waitFor();
  // The first load must already ask for a valid year, without an "invalid year" error.
  assert.equal(requests[0],'period=QUARTER&year='+new Date().getFullYear()+'&detail=true');
  assert.equal(await page.getByText('Năm không hợp lệ',{exact:true}).count(),0);
  assert.match(await page.locator('.breakdown-table').first().innerText(),/545\.000/);
  assert.match(await page.locator('.breakdown-table').first().innerText(),/Chưa gán nhân viên/);

  // The year is typed into a number input and sent when the report is requested.
  await page.getByLabel('Năm báo cáo').fill('2024');
  await page.getByRole('button',{name:'Xem báo cáo',exact:true}).click();
  assert.ok(requests.at(-1).includes('year=2024'));
  assert.ok(requests.at(-1).includes('detail=true'));
  await page.getByLabel('Năm báo cáo').fill('2025');
  await page.getByRole('button',{name:'Xem báo cáo',exact:true}).click();

  // Picking a quarter loads its detail table.
  await page.getByRole('button',{name:'Quý 2/2025',exact:true}).click();
  assert.ok(requests.at(-1).includes('quarter=2'));
  const detailText=await page.locator('.breakdown-detail-table').innerText();
  assert.match(detailText,/Cầu lông mẫu/);
  assert.match(detailText,/Thu ngân mẫu/);
  assert.match(detailText,/Nguyễn An/);
  const totals=await page.locator('.breakdown-totals').innerText();
  assert.match(totals,/Tổng tiền sản phẩm/);
  assert.match(totals,/30\.000/);
  assert.match(totals,/\+ Tiền sân/);
  assert.match(totals,/200\.000/);
  assert.match(totals,/= Tổng doanh thu hóa đơn/);
  assert.match(totals,/230\.000/);

  // The court slide lists courts and only court revenue.
  await page.getByRole('tab',{name:'Tiền sân',exact:true}).click();
  await page.locator('.breakdown-table').getByText('Sân 1',{exact:true}).waitFor();
  assert.match(await page.locator('.breakdown-table').innerText(),/450\.000/);

  await page.getByRole('tab',{name:'Sản phẩm',exact:true}).click();
  await page.locator('.breakdown-table').getByText('Cầu lông mẫu',{exact:true}).waitFor();
  assert.match(await page.locator('.breakdown-table').innerText(),/105\.000/);
  await page.getByRole('tab',{name:'Khách hàng',exact:true}).click();
  await page.getByRole('rowheader',{name:'Nguyễn An',exact:true}).waitFor();
  await page.getByRole('searchbox').fill('không tồn tại');
  await page.getByText('Không tìm thấy kết quả phù hợp.',{exact:true}).waitFor();
  fail=true;await page.getByRole('button',{name:'Xem báo cáo',exact:true}).click();
  await page.getByText('Không tải được báo cáo',{exact:true}).waitFor();
  fail=false;empty=true;await page.getByRole('button',{name:'Xem báo cáo',exact:true}).click();
  await page.getByText('Chưa có dữ liệu trong kỳ này.',{exact:true}).waitFor();
  await page.setViewportSize({width:390,height:844});
  assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true);
  console.log('PASS: revenue breakdown year input, quarter detail, court slide, totals reconciliation, search, error/empty states, responsive layout.');
 }finally{await browser.close()}
})().catch(e=>{console.error(e);process.exitCode=1});
