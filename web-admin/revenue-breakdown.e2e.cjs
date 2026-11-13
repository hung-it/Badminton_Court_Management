// Dashboard browser regression on Vite with isolated HTTP fixtures; no database writes.
const {chromium}=require(process.env.PLAYWRIGHT_MODULE||'playwright');
const assert=require('node:assert/strict');
const base=process.env.ADMIN_TEST_URL||'http://127.0.0.1:5173';
(async()=>{
 const browser=await chromium.launch({headless:true,channel:'msedge'});
 try{
  for(const role of ['ADMIN','STAFF','CUSTOMER']){
   const page=await browser.newPage({viewport:{width:1440,height:1000}}),errors=[],queries=[];
   page.on('pageerror',e=>errors.push(e.message));let fail=false,empty=false;
   await page.route('https://fonts.googleapis.com/**',r=>r.fulfill({body:'',contentType:'text/css'}));
   await page.route('**/api/**',async route=>{
    const u=new URL(route.request().url());let data=[];
    if(u.pathname==='/api/pos/drafts')data={content:[],page:0,totalPages:0,totalElements:0};
    if(u.pathname==='/api/analytics/revenue-dashboard'){
     queries.push(u.search);if(fail)return route.fulfill({status:500,json:{success:false,message:'Không tải được doanh thu'}});
     const from=u.searchParams.get('from'),to=u.searchParams.get('to');
     data={from,to,previousFrom:'2025-01-01',previousTo:'2025-01-31',totalRevenue:empty?0:1000000,previousRevenue:empty?0:800000,growthPercent:empty?null:25,invoiceCount:empty?0:4,averageInvoice:empty?0:250000,trend:empty?[]:[{from,to,previousFrom:'2025-01-01',previousTo:'2025-01-31',current:1000000,previous:800000}],composition:empty?[]:[{label:'Tiền sân',amount:600000,percent:60},{label:'Hàng hóa',amount:300000,percent:30},{label:'Hàng hóa / dịch vụ chưa phân loại',amount:100000,percent:10}],payments:[{label:'CASH',amount:empty?0:400000,percent:40},{label:'VNPAY',amount:empty?0:600000,percent:60}],paymentUnallocated:0,topCourts:empty?[]:[{label:'Sân cầu lông chi nhánh Bình Thạnh có tên dài',amount:400000,percent:66.67},{label:'Sân 2',amount:200000,percent:33.33}],warnings:['Phần chưa phân loại được giữ riêng, không tự suy đoán lịch sử.']};
    }
    return route.fulfill({json:{success:true,data}});
   });
   await page.addInitScript(role=>sessionStorage.setItem('bcm-session',JSON.stringify({accessToken:'fixture',user:{id:'test-reader',roles:[role],fullName:role}})),role);
   await page.goto(base+'/?tab=revenue-breakdown');
   if(role==='CUSTOMER'){await page.getByRole('heading',{name:'Đăng nhập quản trị'}).waitFor();assert.equal(queries.length,0);await page.close();continue;}
   const nav=page.locator('.sidebar').getByRole('button',{name:'Doanh thu',exact:true});
   if(role==='STAFF'){await page.waitForURL('**/?tab=pos');assert(await nav.isDisabled());assert.equal(queries.length,0);assert.equal(await page.locator('.revenue-dashboard').count(),0);await page.close();continue;}
   await page.getByRole('heading',{name:'Tổng quan doanh thu',exact:true}).waitFor();await page.locator('.rd-kpis article').first().waitFor();
   assert.equal(await page.locator('.rd-kpis article').count(),4);assert.equal(await page.locator('.revenue-dashboard table').count(),0);assert.equal(await page.locator('.revenue-dashboard .rd-panel').count(),4);assert.match(await page.locator('.rd-kpis').innerText(),/1.000.000/);assert.match(await page.locator('.rd-kpis').innerText(),/25%/);assert.equal(await page.locator('.topbar strong').innerText(),'Doanh thu');
   const before=queries.length;await page.getByRole('button',{name:'7 ngày',exact:true}).click();assert.equal(queries.length,before);
   const from=await page.getByLabel('Từ ngày',{exact:true}).inputValue(),to=await page.getByLabel('Đến ngày',{exact:true}).inputValue();assert.equal((new Date(to)-new Date(from))/86400000,6);
   let response=page.waitForResponse(r=>r.url().includes('/revenue-dashboard?'));await page.getByRole('button',{name:'Xem doanh thu',exact:true}).click();await response;await page.locator('.rd-kpis article').first().waitFor();const q=new URLSearchParams(queries.at(-1));assert.equal(q.get('from'),from);assert.equal(q.get('to'),to);
   for(const quick of ['Hôm nay','30 ngày','Tháng này','Năm nay']){await page.getByRole('button',{name:quick,exact:true}).click();response=page.waitForResponse(r=>r.url().includes('/revenue-dashboard?'));await page.getByRole('button',{name:'Xem doanh thu',exact:true}).click();await response;await page.locator('.rd-kpis article').first().waitFor();}
   await page.locator('.rd-line [role=button]').first().focus();await page.getByRole('checkbox',{name:'So sánh kỳ trước',exact:true}).uncheck();assert.equal(await page.locator('.rd-line polyline').count(),1);await page.getByRole('checkbox',{name:'So sánh kỳ trước',exact:true}).check();assert.equal(await page.locator('.rd-line polyline').count(),2);
   await page.locator('.rd-donut [role=button]').first().focus();assert.equal(await page.locator('.rd-donut [role=button]').first().evaluate(e=>getComputedStyle(e).outlineStyle),'none');await page.locator('.rd-donut [role=button]').first().press('Enter');assert.notEqual(await page.locator('.rd-donut [role=button]').first().evaluate(e=>getComputedStyle(e).filter),'none');const donut=await page.locator('.rd-donut').boundingBox();const radius=Math.min(donut.width,donut.height)*62/180;await page.mouse.move(donut.x+donut.width/2+radius,donut.y+donut.height/2);await page.mouse.click(donut.x+donut.width/2+radius,donut.y+donut.height/2);assert.equal(await page.locator('.rd-donut [role=button]:focus').evaluate(e=>getComputedStyle(e).outlineStyle),'none');assert.match(await page.locator('.rd-composition .rd-tooltip').innerText(),/600.000/);await page.locator('.rd-payments [role=button]').first().click();assert.match(await page.locator('.rd-payments .rd-tooltip').innerText(),/400.000/);await page.locator('.rd-bars button').first().click();assert.match(await page.locator('.rd-top .rd-tooltip').innerText(),/400.000/);
   for(const width of [1440,1024,768,375]){await page.setViewportSize({width,height:1000});assert(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),'overflow '+width);assert.equal(await page.locator('.rd-kpis article').count(),4);}
   empty=true;response=page.waitForResponse(r=>r.url().includes('/revenue-dashboard?'));await page.getByRole('button',{name:'Xem doanh thu',exact:true}).click();await response;await page.locator('.rd-kpis article').first().waitFor();assert.match(await page.locator('.rd-kpis').innerText(),/chưa có cơ sở tính/);assert.doesNotMatch(await page.locator('.revenue-dashboard').innerText(),/NaN|Infinity/);
   fail=true;response=page.waitForResponse(r=>r.url().includes('/revenue-dashboard?'));await page.getByRole('button',{name:'Xem doanh thu',exact:true}).click();await response;await page.getByRole('alert').getByText('Không tải được doanh thu',{exact:true}).waitFor();assert.equal(await page.locator('.rd-kpis').count(),0);
   fail=false;empty=false;response=page.waitForResponse(r=>r.url().includes('/revenue-dashboard?'));await page.getByRole('button',{name:'Xem doanh thu',exact:true}).click();await response;await page.locator('.rd-kpis').waitFor();
   await page.getByLabel('Từ ngày',{exact:true}).fill('2027-12-31');await page.getByLabel('Đến ngày',{exact:true}).fill('2026-01-01');const count=queries.length;await page.getByRole('button',{name:'Xem doanh thu',exact:true}).click();await page.getByRole('alert').filter({hasText:'Từ ngày không sau Đến ngày'}).waitFor();assert.equal(queries.length,count);
   assert.deepEqual(errors,[]);await page.close();
  }
  console.log('PASS revenue dashboard: 4 KPI/4 panels, shared dates/quick filters, interactions, comparison, empty/error/recovery, ADMIN/STAFF/CUSTOMER, 1440/1024/768/375, no JS errors.');
 }finally{await browser.close()}
})().catch(e=>{console.error(e);process.exitCode=1});
