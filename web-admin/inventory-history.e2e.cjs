// All APIs are mocked; no operational data is written.
const {chromium}=require(process.env.PLAYWRIGHT_MODULE||'playwright');
const assert=require('node:assert/strict');
(async()=>{
 const browser=await chromium.launch({headless:true,channel:'msedge'});
 try{
  const page=await browser.newPage();
  const day=d=>`${d.getFullYear()}-${String(d.getMonth()+1).padStart(2,'0')}-${String(d.getDate()).padStart(2,'0')}`;
  const now=new Date(), yesterday=new Date(now);yesterday.setDate(yesterday.getDate()-1);
  const orders=[{id:'previous-day-import',supplierId:'supplier',status:'DRAFT',importDate:day(yesterday),totalAmount:390000},
   {id:'today-import',supplierId:'supplier',status:'RECEIVED',importDate:day(now),totalAmount:234000}];
  await page.addInitScript(()=>sessionStorage.setItem('bcm-session',JSON.stringify({accessToken:'fixture',user:{id:'admin',roles:['ADMIN'],fullName:'Admin'}})));
  await page.route('**/api/**',route=>{
   const path=new URL(route.request().url()).pathname;
   const data=path==='/api/import-orders'?orders:path==='/api/suppliers'?[{id:'supplier',name:'Test supplier'}]:[];
   return route.fulfill({json:{success:true,data}});
  });
  await page.goto((process.env.ADMIN_TEST_URL||'http://127.0.0.1:5173/')+'?tab=inventory');
  await page.getByText('previous-day-import',{exact:true}).waitFor();
  await page.getByText('today-import',{exact:true}).waitFor();
  assert.equal(await page.getByRole('button',{name:'Tất cả',exact:true}).getAttribute('aria-pressed'),'true');
  await page.getByRole('button',{name:'Hôm nay',exact:true}).click();
  assert.equal(await page.getByText('previous-day-import',{exact:true}).count(),0);
  assert.equal(await page.getByText('today-import',{exact:true}).count(),1);
  await page.getByRole('button',{name:'Tất cả',exact:true}).click();
  await page.getByText('previous-day-import',{exact:true}).waitFor();
  await page.reload();
  await page.getByText('previous-day-import',{exact:true}).waitFor();
  await page.getByText('today-import',{exact:true}).waitFor();
  console.log('PASS: inventory defaults to all dates after reload; Today filter remains exact');
 }finally{await browser.close()}
})().catch(e=>{console.error(e);process.exitCode=1});
