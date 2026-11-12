// Deterministic profile integration; all application API requests use fixtures.
const {chromium,expect}=require(process.env.PLAYWRIGHT_MODULE||'playwright/test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const web=process.env.BCM_WEB_TEST_URL||'http://127.0.0.1:5184';
(async()=>{
 const browser=await chromium.launch({channel:'msedge',headless:true});const errors=[];
 try{
  for(const persist of [true,false]){
   const p=await browser.newPage({viewport:{width:1440,height:1000}});p.on('pageerror',e=>errors.push(e.message));
   let profile={id:'profile-owner',email:'owner@example.test',fullName:'Original customer',phone:'0912345678',address:'Old address',roles:['CUSTOMER'],canEditProfile:true};
   let failLoad=true,failSave=false,hold=null,submitted,requests=0,password;
   let expireSave=false,holdRefresh=false,releaseRefresh,refreshSeen=false;
   await p.route('https://fonts.googleapis.com/**',r=>r.fulfill({body:'',contentType:'text/css'}));
   await p.addInitScript(({persist,profile})=>{
    if(sessionStorage.getItem('fixture-ready'))return;
    sessionStorage.setItem('fixture-ready','1');sessionStorage.setItem('bcm.accessToken','fixture-access');
    const storage=persist?localStorage:sessionStorage;
    storage.setItem('bcm.refreshToken','fixture-refresh');storage.setItem('bcm.user',JSON.stringify(profile));
    sessionStorage.setItem('bcm-booking-draft','keep-booking');
   },{persist,profile});
   await p.route('**/api/**',async route=>{
    const r=route.request(),path=new URL(r.url()).pathname;let data={content:[]};
    if(path==='/api/users/me'){
     if(r.method()==='GET'){
      if(failLoad)return route.fulfill({status:500,json:{message:'Fixture loading error'}});
      data=profile;
     }else{
      requests++;submitted=r.postDataJSON();const owner=profile;if(hold)await hold;
      if(expireSave)return route.fulfill({status:401,json:{message:'Fixture expired access'}});
      if(failSave)return route.fulfill({status:400,json:{message:'Fixture save error'}});
      data={...owner,...submitted};if(profile.id===owner.id)profile=data;
     }
    }
    if(path==='/api/users/me/password'){password=r.postDataJSON();data=null;}
    if(path==='/api/auth/login')data={accessToken:'fixture-access-new',refreshToken:'fixture-refresh-new',user:profile};
    if(path==='/api/auth/refresh'){
     const owner=profile;refreshSeen=true;
     if(holdRefresh)await new Promise(resolve=>releaseRefresh=resolve);
     data={accessToken:'fixture-refreshed-old',refreshToken:'fixture-refreshed-old',user:owner};
    }
    return route.fulfill({json:{success:true,data}});
   });
   await p.goto(web+'/profile');await expect(p.getByRole('alert')).toContainText('Fixture loading error');
   failLoad=false;await p.getByRole('button',{name:'Thử lại',exact:true}).click();await expect(p.locator('.profile-details')).toContainText('Original customer');
   await p.getByRole('button',{name:'Chỉnh sửa',exact:true}).click();await expect(p.getByLabel('Email đăng nhập')).toHaveAttribute('readonly','');
   await p.getByLabel('Họ và tên',{exact:true}).fill('Cancelled name');await p.getByRole('button',{name:'Hủy',exact:true}).click();assert.equal(requests,0);
   await p.getByRole('button',{name:'Chỉnh sửa',exact:true}).click();await p.getByLabel('Họ và tên',{exact:true}).fill('Updated customer');await p.getByLabel('Địa chỉ',{exact:true}).fill('New address');
   failSave=true;await p.getByRole('button',{name:'Lưu thay đổi',exact:true}).click();await expect(p.getByRole('alert')).toContainText('Fixture save error');await expect(p.getByLabel('Họ và tên',{exact:true})).toHaveValue('Updated customer');
   failSave=false;let release;hold=new Promise(r=>release=r);await p.getByRole('button',{name:'Lưu thay đổi',exact:true}).click();await expect(p.getByRole('button',{name:'Đang lưu…',exact:true})).toBeDisabled();release();hold=null;
   await expect(p.getByRole('status')).toContainText('Cập nhật thông tin thành công');await expect(p.locator('.header__top-actions')).toContainText('Updated customer');
   assert.deepEqual(Object.keys(submitted).sort(),['address','fullName','phone']);
   assert.equal(await p.evaluate(()=>sessionStorage.getItem('bcm-booking-draft')),'keep-booking');
   assert.equal(await p.evaluate(persist=>JSON.parse((persist?localStorage:sessionStorage).getItem('bcm.user')).fullName,persist),'Updated customer');
   await p.reload();await expect(p.locator('.profile-details')).toContainText('New address');
   await p.getByLabel('Mật khẩu hiện tại',{exact:true}).fill('OldPassword123');await p.getByLabel('Mật khẩu mới',{exact:true}).fill('NewPassword123');await p.getByLabel('Xác nhận mật khẩu mới',{exact:true}).fill('NewPassword123');await p.getByRole('button',{name:'Lưu mật khẩu',exact:true}).click();await expect(p.getByRole('status')).toContainText('Đổi mật khẩu thành công');assert.equal(password.newPassword,'NewPassword123');
   if(persist){fs.mkdirSync('.tools/profile-ui',{recursive:true});await p.screenshot({path:'.tools/profile-ui/desktop.png',fullPage:true});}
   await p.setViewportSize({width:390,height:844});if(persist)await p.screenshot({path:'.tools/profile-ui/mobile.png',fullPage:true});assert(await p.evaluate(()=>document.documentElement.scrollWidth<=innerWidth));
   profile={...profile,canEditProfile:false};await p.reload();await expect(p.locator('.profile-details')).toContainText('Updated customer');assert.equal(await p.getByRole('button',{name:'Chỉnh sửa',exact:true}).count(),0);
   profile={...profile,canEditProfile:true};await p.reload();await p.getByRole('button',{name:'Chỉnh sửa',exact:true}).click();let finish;hold=new Promise(r=>finish=r);
   if(!persist){hold=null;expireSave=true;holdRefresh=true;}
   await p.getByRole('button',{name:'Lưu thay đổi',exact:true}).click();await expect(p.getByRole('button',{name:'Đang lưu…',exact:true})).toBeDisabled();if(!persist)await expect.poll(()=>refreshSeen).toBe(true);await p.getByRole('button',{name:'Đăng xuất',exact:true}).filter({visible:true}).first().click();await p.waitForURL('**/login?redirect=*');
   profile={...profile,id:'other-owner',email:'other@example.test',fullName:'Other customer'};
   await p.locator('input[type=email]').fill(profile.email);await p.locator('input[type=password]').fill('Fixture123!');await p.locator('button[type=submit]').click();await p.waitForURL('**/profile');await expect(p.locator('.profile-details')).toContainText('Other customer');
   const completed=p.waitForResponse(r=>new URL(r.url()).pathname===(persist?'/api/users/me':'/api/auth/refresh')&&r.request().method()===(persist?'PUT':'POST'));
   if(persist)finish();else releaseRefresh();hold=null;await completed;
   await expect(p.locator('.header__top-actions')).toContainText('Other customer');assert.equal(await p.evaluate(()=>JSON.parse(localStorage.getItem('bcm.user')||sessionStorage.getItem('bcm.user')).id),'other-owner');
   await p.close();
  }
  const anonymous=await browser.newPage();await anonymous.route('https://fonts.googleapis.com/**',r=>r.fulfill({body:'',contentType:'text/css'}));await anonymous.goto(web+'/profile');await anonymous.waitForURL('**/login?redirect=*');
  assert.deepEqual(errors,[]);console.log('PASS: profile loading/retry, edit/cancel/error/save, read-only email, busy state, persisted/session user, header, reload, draft retention, password, permissions, mobile, logout during save and anonymous redirect.');
 }finally{await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
