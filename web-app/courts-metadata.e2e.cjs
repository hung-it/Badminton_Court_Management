// Real API/PostgreSQL/browser. Main runtime checks are read-only.
// Admin writes run ONLY against the dedicated 5176 -> 8088 audit runtime.
const {chromium,request,expect}=require('playwright/test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const baseURL=process.env.BCM_E2E_WEB_URL||'http://127.0.0.1:5174';
const artifacts=path.resolve(process.env.BCM_E2E_ARTIFACTS||'backups.local/court-metadata-20261024');
const pass=name=>console.log('PASS '+name);
(async()=>{
 fs.mkdirSync(artifacts,{recursive:true});
 const api=await request.newContext({baseURL});
 const catalog=(await(await api.get('/api/public/courts')).json()).data;
 if(process.env.BCM_E2E_ADMIN_WRITE!=='1')assert.equal(catalog.length,35);
 const projectCatalog=catalog.filter(c=>(c.courtCode??c.courtNumber)>=1&&(c.courtCode??c.courtNumber)<=8||(c.courtCode??c.courtNumber)>=10001&&(c.courtCode??c.courtNumber)<=10007);
 assert.equal(projectCatalog.length,15);assert.equal(new Set(projectCatalog.map(c=>c.type)).size,2);
 for(const c of projectCatalog){assert.ok(['STANDARD_MAT','WOODEN_FLOOR'].includes(c.type));assert.ok(c.address?.includes(c.district));}
 const clock=(await(await api.get('/api/public/booking-clock')).json()).data;
 const {nextBookingDate}=await import('./src/lib/bookingTime.js');
 const date=nextBookingDate(clock.today);
 const browser=await chromium.launch({channel:'msedge',headless:true});
 try{
  const page=await browser.newPage({viewport:{width:1440,height:1100}});const errors=[];
  page.on('pageerror',e=>errors.push(e.message));
  await page.goto(`${baseURL}/courts?date=${date}`);
  await expect(page.locator('.customer-court-card')).toHaveCount(catalog.length);
  await expect(page.locator('.customer-branch-card')).toHaveCount(0);
  for(const c of projectCatalog){const card=page.locator(`[data-court-id="${c.id}"]`);
   await expect(card.locator('.customer-court-type')).toContainText(c.type==='STANDARD_MAT'?'Thảm tiêu chuẩn':'Sàn gỗ');
   await expect(card.locator('.customer-court-address')).toHaveText('Địa chỉ: '+c.address);
   assert.equal((await card.locator('.customer-court-address').innerText()).split(c.district).length-1,1);
  }
  await expect(page.locator('.customer-district-options input')).toHaveCount(new Set(catalog.map(c=>c.district).filter(Boolean)).size);
  await expect(page.getByText('Tổng dự kiến',{exact:false})).toHaveCount(0);
  await expect(page.getByText('sân chưa cập nhật quận / huyện',{exact:false})).toHaveCount(0);
  await page.screenshot({path:path.join(artifacts,'courts-desktop.png'),fullPage:true});
  pass('All original-layout court cards: Vietnamese surfaces, stored addresses once, 7 districts, removed UI stays absent');
  for(const width of [820,375,320]){
   await page.setViewportSize({width,height:1000});
   assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),'No horizontal overflow at '+width);
   await expect(page.locator('.customer-court-address').first()).toBeVisible();
   await page.screenshot({path:path.join(artifacts,`courts-${width}.png`),fullPage:true});
  }
  assert.deepEqual(errors,[]);pass('Responsive layout 1440/820/375/320px, no browser errors');
  const admin=await browser.newPage({viewport:{width:1440,height:1000}});
  const adminURL=process.env.BCM_E2E_ADMIN_URL||'http://127.0.0.1:5173';
  await admin.goto(adminURL);
  await admin.getByLabel('Email',{exact:true}).fill('admin@bcm.com');
  // Existing local fixture credential, never logged. Override through environment if changed.
  await admin.getByLabel('Mật khẩu',{exact:true}).fill(process.env.BCM_E2E_ADMIN_PASSWORD||'admin123');
  await admin.getByRole('button',{name:'Đăng nhập →',exact:true}).click();
  await expect(admin.locator('.court-card')).toHaveCount(catalog.length);
  await expect(admin.locator('.court-address').first()).toContainText(catalog[0].address);
  await admin.locator('.court-card').first().getByRole('button',{name:'Sửa',exact:true}).click();
  let dialog=admin.getByRole('dialog');
  await expect(dialog.getByLabel('Địa chỉ sân',{exact:true})).toHaveValue(catalog[0].address);
  await expect(dialog.getByLabel('Quận / Huyện',{exact:true})).toHaveValue(catalog[0].district);
  assert.deepEqual(await dialog.getByLabel('Loại sân',{exact:true}).locator('option').allTextContents(),['Thảm tiêu chuẩn','Sàn gỗ']);
  await admin.screenshot({path:path.join(artifacts,'admin-edit.png'),fullPage:true});
  pass('Admin real login/catalog/edit preload and exactly two surface choices');
  if(process.env.BCM_E2E_ADMIN_WRITE==='1'){
   assert.ok(adminURL.includes(':5176'),'Admin writes require isolated audit runtime');
   const address='Khu thể thao mô phỏng E2E, Quận 1, TP. Hồ Chí Minh';
   await dialog.getByLabel('Loại sân',{exact:true}).selectOption('STANDARD_MAT');
   await dialog.getByLabel('Địa chỉ sân',{exact:true}).fill(address);
   await dialog.getByRole('button',{name:'Lưu thay đổi',exact:true}).click();
   await expect(admin.getByRole('dialog')).toHaveCount(0);
   await admin.reload();await expect(admin.locator('.court-card').first().locator('.court-address')).toHaveText('Địa chỉ: '+address);
   await admin.locator('.court-card').first().getByRole('button',{name:'Sửa',exact:true}).click();dialog=admin.getByRole('dialog');
   await expect(dialog.getByLabel('Loại sân',{exact:true})).toHaveValue('STANDARD_MAT');
   await dialog.getByLabel('Địa chỉ sân',{exact:true}).fill('Khu mô phỏng, Quận 7, TP. Hồ Chí Minh');
   await dialog.getByRole('button',{name:'Lưu thay đổi',exact:true}).click();
   await expect(dialog.getByRole('alert')).toContainText('không khớp');
   await dialog.getByLabel('Địa chỉ sân',{exact:true}).fill('');
   await dialog.getByRole('button',{name:'Lưu thay đổi',exact:true}).click();
   await expect(admin.getByRole('dialog')).toHaveCount(0);
   await expect(admin.locator('.court-card').first().locator('.court-address')).toHaveText('Địa chỉ: '+address);
   await admin.getByRole('button',{name:'Thêm sân',exact:false}).click();dialog=admin.getByRole('dialog');
   await dialog.getByLabel('Số sân',{exact:true}).fill('908002');
   await dialog.getByLabel('Tên sân',{exact:true}).fill('Metadata E2E fixture');
   await dialog.getByLabel('Quận / Huyện',{exact:true}).fill('Quận 3');
   const longAddress='Khu mô phỏng E2E '+ 'vị trí '.repeat(60)+', Quận 3, TP. Hồ Chí Minh';
   await dialog.getByLabel('Địa chỉ sân',{exact:true}).fill(longAddress);
   await dialog.getByLabel('Loại sân',{exact:true}).selectOption('WOODEN_FLOOR');
   await dialog.getByRole('button',{name:'Lưu thay đổi',exact:true}).click();
   await expect(admin.getByRole('dialog')).toHaveCount(0);await admin.reload();
   const created=admin.locator('.court-card').filter({hasText:'Metadata E2E fixture'});
   await expect(created).toContainText('Sàn gỗ');await expect(created).toContainText('Quận 3');
   await page.setViewportSize({width:320,height:1000});await page.goto(`${baseURL}/courts?date=${date}`);
   const longCard=page.locator('.customer-court-card').filter({hasText:'Metadata E2E fixture'});
   await expect(longCard.locator('.customer-court-address')).toHaveText('Địa chỉ: '+longAddress);
   assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth));
   await longCard.screenshot({path:path.join(artifacts,'long-address-320.png')});
   pass('Isolated admin UPDATE/reload, district mismatch rejection, blank preservation and CREATE/reload persisted');
  }
 }finally{await browser.close();await api.dispose();}
})().catch(e=>{console.error(e);process.exitCode=1});
