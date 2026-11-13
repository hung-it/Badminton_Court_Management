const {chromium}=require(process.env.PLAYWRIGHT_MODULE||'playwright');
const assert=require('node:assert/strict');
(async()=>{
 const browser=await chromium.launch({headless:true,channel:'msedge'});
 try {
  const page=await browser.newPage();const requests=[],errors=[];
  page.on('pageerror',e=>errors.push(e.message));
  await page.route('https://fonts.googleapis.com/**',r=>r.fulfill({body:'',contentType:'text/css'}));
  await page.route('**/api/**',r=>{
   const url=new URL(r.request().url());let data=[];
   if(url.pathname==='/api/analytics/statistics/matrix'){
    requests.push(url.search);data={type:'TIME',columns:[],rows:[],totals:[],total:0,note:''};
   }
   return r.fulfill({json:{success:true,data}});
  });
  await page.addInitScript(()=>sessionStorage.setItem('bcm-session',JSON.stringify({accessToken:'fixture',user:{roles:['ADMIN'],fullName:'Range test'}})));
  await page.goto((process.env.ADMIN_TEST_URL||'http://127.0.0.1:5173')+'/?tab=statistics');
  await page.getByRole('button',{name:'Xem thống kê',exact:true}).waitFor();
  await page.getByLabel('Kỳ thống kê',{exact:true}).selectOption('DATE');
  await page.getByLabel('Từ ngày',{exact:true}).fill('2024-01-01');
  await page.getByLabel('Đến ngày',{exact:true}).fill('2025-01-01');
  const count=requests.length;
  await page.getByRole('button',{name:'Xem thống kê',exact:true}).click();
  await page.getByRole('alert').filter({hasText:'tối đa 366 ngày'}).waitFor();
  assert.equal(requests.length,count,'invalid daily range must not call the API');
  await page.getByLabel('Đến ngày',{exact:true}).fill('2024-12-31');
  await page.getByRole('button',{name:'Xem thống kê',exact:true}).click();
  await page.waitForFunction(()=>!document.querySelector('[role="alert"]'));
  assert.equal(requests.length,count+1);assert.match(requests.at(-1),/to=2024-12-31/);assert.deepEqual(errors,[]);
  console.log('PASS statistics: 367 days blocked locally; leap-year 366-day range accepted; no JS errors');
 } finally {await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1});
