// UI-only API fixtures; no runtime database requests or writes.
const {chromium}=require(process.env.PLAYWRIGHT_MODULE||'playwright');
const assert=require('node:assert/strict');
(async()=>{
 const browser=await chromium.launch({headless:true,channel:'msedge'});
 try{
  for(const role of ['ADMIN','STAFF','CUSTOMER']){
   const page=await browser.newPage({viewport:{width:1366,height:900}});
   const requests=[];let fail=false,failLock=false,failUnlock=false,holdMutation=null;
   const invoice={id:'11111111-1111-4111-8111-111111111111',customerName:'Khách kiểm thử',createdAt:'2026-11-11T10:00:00',courtFee:200000,productFee:30000,discountAmount:5000,totalAmount:225000,invoiceStatus:'PAID'};
   const customer={id:'22222222-2222-4222-8222-222222222222',fullName:'Khách kiểm thử',email:'customer@ui.test',phone:'0912345678',address:'Test',roles:['CUSTOMER'],createdAt:'2026-11-11T10:00:00',deletedAt:null,canLock:true,canUnlock:false,customerId:'customer-profile',bookingCount:0,totalSpent:0,bookings:[],invoices:[]};
   await page.route('**/api/**',async route=>{
    const r=route.request(),u=new URL(r.url());requests.push({method:r.method(),path:u.pathname,body:r.postDataJSON()});let data=[];
    if(u.pathname==='/api/admin/accounts')data={accounts:[customer],totalPages:1,totalElements:1};
    if(u.pathname==='/api/admin/accounts/'+customer.id&&r.method()==='DELETE'){
     if(holdMutation)await holdMutation;
     if(failLock)return route.fulfill({status:409,json:{success:false,message:'Không thể khóa lúc này'}});
     customer.deletedAt='2026-11-11T10:05:00';customer.canLock=false;customer.canUnlock=true;
    }else if(u.pathname==='/api/admin/accounts/'+customer.id)data=customer;
    if(u.pathname==='/api/admin/accounts/'+customer.id+'/unlock'&&r.method()==='POST'){
     if(holdMutation)await holdMutation;
     if(failUnlock)return route.fulfill({status:409,json:{success:false,message:'Không thể mở khóa lúc này'}});
     customer.deletedAt=null;customer.canLock=true;customer.canUnlock=false;
    }
    if(u.pathname==='/api/admin/customers')data=customer.customerId;
    if(u.pathname==='/api/analytics/invoices'){
     if(fail)return route.fulfill({status:500,json:{success:false,message:'Không tải được lịch sử'}});
     data=u.searchParams.get('search')==='none'?{invoices:[],page:0,totalPages:0,totalElements:0}:{invoices:[invoice],page:Number(u.searchParams.get('page')),totalPages:2,totalElements:21};
    }
    if(u.pathname==='/api/analytics/invoices/'+invoice.id)data={invoice,items:[{name:'Nước suối',quantity:3,unitPrice:10000,discountAmount:5000,totalAmount:25000}],courts:[],receipts:[{id:'receipt',target:'INVOICE',source:'MANUAL',method:'CASH',amount:225000,actorName:'Thu ngân',status:'SUCCESS'}]};
    return route.fulfill({json:{success:true,data}});
   });
   await page.addInitScript(role=>sessionStorage.setItem('bcm-session',JSON.stringify({accessToken:'ui-fixture',user:{id:'admin-fixture',roles:[role],fullName:role+' Test'}})),role);
   await page.goto('http://127.0.0.1:5173/?tab=accounts');
   if(role==='CUSTOMER'){await page.getByRole('heading',{name:'Đăng nhập quản trị'}).waitFor();assert.equal(await page.locator('.sidebar').count(),0);console.log('PASS CUSTOMER: admin UI denied');await page.close();continue;}
   assert(await page.getByRole('button',{name:'Đổi mật khẩu',exact:true}).isVisible());
   const nav=page.locator('.sidebar button.nav');assert.equal(await nav.count(),12);
   if(role==='STAFF'){
    for(const name of ['Tài khoản','Thống kê','Doanh thu theo đối tượng','Nhập kho','Sân cầu']){
     const b=nav.filter({hasText:name});assert(await b.isDisabled());assert.match(await b.innerText(),/🔒/);assert.equal(await b.getAttribute('title'),'Chức năng chỉ dành cho Quản lý');
    }
    for(const name of ['Bán tại quầy','Trả sân','Hóa đơn','Khuyến mãi'])assert(await page.getByRole('button',{name,exact:true}).isEnabled());
    for(const url of ['/?tab=statistics','/accounts','/?tab=revenue-breakdown']){
     await page.goto('http://127.0.0.1:5173'+url);await page.waitForURL('**/?tab=pos');assert.equal(await page.getByRole('heading',{name:'Tài khoản',exact:true}).count(),0);
    }
    await page.evaluate(()=>{history.pushState(null,'','/?tab=accounts');dispatchEvent(new PopStateEvent('popstate'));});
    assert.equal(await page.getByRole('heading',{name:'Tài khoản',exact:true}).count(),0);
   }else{
    assert.equal(await nav.locator(':disabled').count(),0);
    assert.equal(await page.getByRole('button',{name:'Sửa',exact:true}).count(),0);
    await page.getByRole('button',{name:'Chi tiết',exact:true}).click();await page.getByRole('dialog').getByText('Lịch sử đặt sân',{exact:true}).waitFor();await page.getByRole('button',{name:'X',exact:true}).click();
    await page.getByRole('button',{name:'+ Thêm khách hàng',exact:true}).click();
    const d=page.getByRole('dialog');for(const [name,value] of [['Họ tên','Khách mới'],['Email','new@ui.test'],['Số điện thoại','0987654321'],['Địa chỉ','Test'],['Mật khẩu','Password123!']])await d.getByLabel(name,{exact:true}).fill(value);
    await d.getByRole('button',{name:'Lưu',exact:true}).click();await d.waitFor({state:'hidden'});
    const sent=requests.find(r=>r.path==='/api/admin/customers'&&r.method==='POST');assert(sent&&sent.body.password==='Password123!');assert.equal(sent.body.roles,undefined);
    await page.getByRole('button',{name:'Khóa tài khoản',exact:true}).click();assert.equal(requests.filter(r=>r.method==='DELETE').length,0);
    await page.getByRole('dialog').getByRole('button',{name:'Hủy',exact:true}).click();assert.equal(requests.filter(r=>r.method==='DELETE').length,0);
    assert.equal(await page.locator('tbody tr .record-actions button').count(),2);
    failLock=true;await page.getByRole('button',{name:'Khóa tài khoản',exact:true}).click();
    let dialog=page.getByRole('dialog');await dialog.getByRole('button',{name:'Khóa tài khoản',exact:true}).click();
    await dialog.getByText('Không thể khóa lúc này',{exact:true}).waitFor();
    assert.equal(customer.deletedAt,null);assert.equal(await page.getByText('Hoạt động',{exact:true}).count(),1);
    failLock=false;let release;holdMutation=new Promise(resolve=>{release=resolve});
    await dialog.getByRole('button',{name:'Khóa tài khoản',exact:true}).click();
    assert(await dialog.getByRole('button',{name:'Khóa tài khoản',exact:true}).isDisabled());
    assert(await dialog.getByRole('button',{name:'Hủy',exact:true}).isDisabled());
    assert(await page.getByRole('button',{name:'+ Thêm khách hàng',exact:true}).isDisabled());
    release();holdMutation=null;await dialog.waitFor({state:'hidden'});await page.getByText('Đã khóa',{exact:true}).waitFor();
    assert(await page.getByRole('button',{name:'Mở khóa tài khoản',exact:true}).isEnabled());
    await page.getByRole('button',{name:'Mở khóa tài khoản',exact:true}).click();
    dialog=page.getByRole('dialog');await dialog.getByRole('heading',{name:'Mở khóa tài khoản',exact:true}).waitFor();
    const unlockCount=()=>requests.filter(r=>r.path.endsWith('/unlock')).length;
    assert.equal(unlockCount(),0);await dialog.getByRole('button',{name:'Hủy',exact:true}).click();assert.equal(unlockCount(),0);
    await page.getByRole('button',{name:'Mở khóa tài khoản',exact:true}).click();dialog=page.getByRole('dialog');
    failUnlock=true;await dialog.getByRole('button',{name:'Mở khóa tài khoản',exact:true}).click();
    await dialog.getByText('Không thể mở khóa lúc này',{exact:true}).waitFor();
    assert.equal(customer.deletedAt,'2026-11-11T10:05:00');assert.equal(await page.getByText('Đã khóa',{exact:true}).count(),1);
    failUnlock=false;holdMutation=new Promise(resolve=>{release=resolve});
    await dialog.getByRole('button',{name:'Mở khóa tài khoản',exact:true}).click();
    assert(await dialog.getByRole('button',{name:'Mở khóa tài khoản',exact:true}).isDisabled());
    assert(await dialog.getByRole('button',{name:'Hủy',exact:true}).isDisabled());
    release();holdMutation=null;await dialog.waitFor({state:'hidden'});await page.getByText('Hoạt động',{exact:true}).waitFor();
    await page.getByText('Đã mở khóa tài khoản.',{exact:true}).waitFor();
    assert(await page.getByRole('button',{name:'Khóa tài khoản',exact:true}).isEnabled());
    assert.equal(await page.locator('tbody tr .record-actions button').count(),2);
    assert.equal(unlockCount(),2);
    assert(requests.filter(r=>r.path.endsWith('/unlock')).every(r=>r.method==='POST'));

   }
   await page.getByRole('button',{name:'Hóa đơn',exact:true}).click();await page.getByRole('button',{name:'Chi tiết',exact:true}).click();
   const dialog=page.getByRole('dialog');await dialog.getByText('Nước suối',{exact:true}).waitFor();assert.match(await dialog.innerText(),/225.000/);assert.match(await dialog.innerText(),/Thu ngân/);assert.equal(await dialog.getByRole('button').count(),2);
   await page.keyboard.press('Escape');await page.getByRole('button',{name:'Trang sau',exact:true}).click();await page.getByText('Trang 2 / 2',{exact:true}).waitFor();
   await page.getByLabel('Tìm hóa đơn',{exact:true}).fill('none');await page.getByText('Chưa có hóa đơn đã thanh toán trong kỳ này.',{exact:true}).waitFor();
   await page.getByLabel('Tìm hóa đơn',{exact:true}).fill('');await page.getByRole('button',{name:'Chi tiết',exact:true}).waitFor();
   await page.setViewportSize({width:390,height:844});await page.getByRole('button',{name:'Chi tiết',exact:true}).click();await page.getByRole('dialog').getByText('Nước suối',{exact:true}).waitFor();assert(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth));await page.getByRole('button',{name:'Đóng',exact:true}).click();
   fail=true;await page.getByRole('button',{name:'Làm mới',exact:true}).click();await page.getByText('Không tải được lịch sử',{exact:true}).waitFor();
   assert(requests.filter(r=>r.path.startsWith('/api/analytics/invoices')).every(r=>r.method==='GET'));
   if(role==='STAFF')assert(!requests.some(r=>r.path.startsWith('/api/admin/')||r.path==='/api/analytics/statistics'||r.path==='/api/analytics/revenue-breakdown'));
   console.log('PASS '+role+': menu/URL guards, read-only invoices, pagination, details, payment information, errors and mobile'+(role==='ADMIN'?'; create/view/lock/unlock customer; one toggle button/modal; cancel/busy/error states preserve server state':''));await page.close();
  }
 }finally{await browser.close()}
})().catch(e=>{console.error(e);process.exitCode=1});
