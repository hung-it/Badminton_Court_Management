// Real HTTP/JWT/browser/PostgreSQL. Run against the dedicated promotion audit runtime only.
// VNPay is a locally signed contract test, not a real provider transaction.
const {chromium, request, expect}=require('playwright/test');
const {execFileSync}=require('node:child_process');
const crypto=require('node:crypto');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const baseURL=process.env.BCM_E2E_WEB_URL || 'http://127.0.0.1:5175';
const database=process.env.BCM_E2E_DB || 'bcm_promotions_e2e_20261024';
const sql=query=>execFileSync('docker',['exec',process.env.BCM_E2E_DB_CONTAINER || 'bcm-postgres','psql','-U',process.env.BCM_E2E_DB_USER || 'bcm_admin','-d',database,'-v','ON_ERROR_STOP=1','-tAc',query],{encoding:'utf8'}).trim();
const id=()=>crypto.randomUUID();
const money=value=>new Intl.NumberFormat('vi-VN',{style:'currency',currency:'VND',maximumFractionDigits:0}).format(value);
const sign=fields=>{
  const sorted=Object.fromEntries(Object.entries(fields).sort(([a],[b])=>a.localeCompare(b)));
  return {...fields,vnp_SecureHash:crypto.createHmac('sha512','promotion-e2e-test-only').update(new URLSearchParams(sorted).toString()).digest('hex')};
};
(async()=>{
  const api=await request.newContext({baseURL});
  const register=async()=>{
    const password=crypto.randomBytes(18).toString('hex');
    const response=await api.post('/api/auth/register',{data:{email:`promotion-${id()}@example.test`,password,fullName:'Promotion E2E',phone:'0900000000'}});
    assert.equal(response.status(),201);return {...(await response.json()).data,testPassword:password};
  };
  const owner=await register(),foreign=await register();
  const headers={Authorization:`Bearer ${owner.accessToken}`};
  const get=async path=>{const r=await api.get(`/api${path}`,{headers});assert.equal(r.status(),200);return (await r.json()).data;};
  const {customerId}=await get('/bookings/customer-profile');
  const courts=[id(),id()],slots=[id(),id()],branch=id();
  sql(`INSERT INTO branches(id,code,name,address,district) VALUES ('${branch}','PROMO-${branch}','Promotion E2E','Audit only','Audit');
    INSERT INTO courts(id,court_code,name,base_price,branch_id,court_number) VALUES ('${courts[0]}',${800000+Math.floor(Math.random()*10000)},'Promotion E2E A',180000,'${branch}',1),('${courts[1]}',${820000+Math.floor(Math.random()*10000)},'Promotion E2E B',180000,'${branch}',2);
    INSERT INTO time_slots(id,start_time,end_time,price_multiplier) VALUES ('${slots[0]}','08:00','09:00',1),('${slots[1]}','09:00','10:00',1);`);
  const create=async(details,date='2035-08-20')=>{
    const r=await api.post('/api/bookings',{headers,data:{customerId,branchId:branch,details:details.map(([court,slot])=>({courtId:courts[court],timeSlotId:slots[slot],bookingDate:date}))}});
    assert.equal(r.status(),201,await r.text());return (await r.json()).data;
  };
  const apply=async(bookingId,code)=>{
    const r=await api.post(`/api/bookings/${bookingId}/promotion/apply`,{headers,data:{code}});assert.equal(r.status(),200,await r.text());return (await r.json()).data;
  };
  const hold=await create([[0,0],[0,1],[1,0],[1,1]]);
  const browser=await chromium.launch({channel:'msedge',headless:true});
  try {
    const context=await browser.newContext({viewport:{width:1440,height:1100}});
    await context.addInitScript(s=>{sessionStorage.setItem('bcm.accessToken',s.accessToken);sessionStorage.setItem('bcm.user',JSON.stringify(s.user));},owner);
    const page=await context.newPage();const errors=[];page.on('pageerror',e=>errors.push(e.message));
    await page.goto(`${baseURL}/checkout?bookingId=${hold.bookingId}`);
    await expect(page.locator('.checkout-grand-total')).toContainText(money(670000));
    await expect(page.locator('.checkout-totals').first()).toContainText(money(720000));
    await expect(page.locator('.checkout-discount')).toContainText(money(50000));
    await expect(page.getByPlaceholder('Mã khuyến mãi')).toBeVisible();
    await page.getByPlaceholder('Mã khuyến mãi').fill(' newmember ');
    await page.getByRole('button',{name:'Áp dụng',exact:true}).click();
    await expect(page.locator('.checkout-promotions')).toContainText('Mã hợp lệ, nhưng ưu đãi theo hóa đơn đang giúp bạn tiết kiệm hơn.');
    await expect(page.locator('.checkout-grand-total')).toContainText(money(670000));
    await page.getByRole('button',{name:'Áp dụng',exact:true}).click();
    await expect(page.locator('.checkout-grand-total')).toContainText(money(670000));
    await page.reload();await expect(page.getByPlaceholder('Mã khuyến mãi')).toHaveValue('NEWMEMBER');
    await page.getByRole('button',{name:'Xóa mã NEWMEMBER'}).click();
    await expect(page.getByPlaceholder('Mã khuyến mãi')).toHaveValue('');
    await page.getByPlaceholder('Mã khuyến mãi').fill('KMKH10');await page.getByRole('button',{name:'Áp dụng',exact:true}).click();
    await expect(page.locator('.checkout-promotions')).toContainText('Bạn chưa đủ điều kiện sử dụng mã KMKH10');
    await expect(page.locator('.checkout-grand-total')).toContainText(money(670000));
    const path=`/api/bookings/${hold.bookingId}/promotion/apply`;
    assert.equal((await api.post(path,{data:{code:'NEWMEMBER'}})).status(),401);
    assert.equal((await api.post(path,{headers:{Authorization:`Bearer ${foreign.accessToken}`},data:{code:'NEWMEMBER'}})).status(),403);
    assert.equal((await api.post(path,{headers,data:{code:'NEWMEMBER',discountAmount:999999}})).status(),400);
    console.log('PASS automatic discount, best offer, repeat apply, remove, reload, invalid voucher, JWT ownership and amount tampering');
    // Dedicated synthetic history fixture: each header has multiple details/payments; count remains one.
    for(let n=0;n<15;n++) {
      const b=id();sql(`INSERT INTO bookings(id,customer_id,status,court_fee) VALUES ('${b}','${customerId}','COMPLETED',180000);
        INSERT INTO payment_transactions(booking_id,payment_method,status,amount,transaction_id,transaction_date) VALUES ('${b}','VNPAY','SUCCESS',180000,'${id()}',CURRENT_TIMESTAMP),('${b}','VNPAY','FAILED',180000,NULL,NULL);`);
    }
    await page.getByRole('button',{name:'Áp dụng',exact:true}).click();
    await expect(page.locator('.checkout-grand-total')).toContainText(money(648000));
    await expect(page.locator('.checkout-discount')).toContainText('KMKH10 · 10%');
    await page.reload();await expect(page.locator('.checkout-grand-total')).toContainText(money(648000));
    assert.equal(sql(`SELECT court_fee||'|'||discount_amount||'|'||final_amount FROM bookings WHERE id='${hold.bookingId}'`),'720000.00|72000.00|648000.00');
    for(const width of [1440,375,320]) {
      await page.setViewportSize({width,height:1100});
      assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false);
      await expect(page.getByPlaceholder('Mã khuyến mãi')).toBeVisible();
      await page.screenshot({path:`backend/target/promotions-checkout-${width}.png`,fullPage:true});
    }
    assert.equal(await page.locator('textarea').count(),0);
    console.log('PASS KMKH10, PostgreSQL snapshot, desktop/mobile 1440/375/320, no customer note');
    await page.setViewportSize({width:1440,height:1100});
    await page.locator('input[value=BANK_TRANSFER]').check();
    await page.getByLabel('Ngân hàng',{exact:true}).fill('Test bank');
    await page.getByLabel('Tên chủ tài khoản',{exact:true}).fill('Promotion E2E');
    await page.getByLabel('Số tài khoản',{exact:true}).fill('001234');
    await page.getByLabel('Nội dung chuyển khoản',{exact:true}).fill('Promotion test');
    await page.getByRole('button',{name:'Thanh toán',exact:true}).click();
    await expect(page).toHaveURL(/payment-result/);
    const paid=await get(`/bookings/${hold.bookingId}`);
    assert.equal(Number(paid.payments[0].amount),648000);assert.equal(paid.payments[0].paymentStatus,'PENDING');
    assert.equal(paid.payments[0].confirmationMethod,'CUSTOMER_DECLARED');
    console.log('PASS browser BANK_TRANSFER pays final amount and retains unverified declaration policy');
    const vn=await create([[0,0]],'2035-08-21');const q=await apply(vn.bookingId,'KMKH10');assert.equal(Number(q.finalAmount),162000);
    const pay=await api.post(`/api/bookings/${vn.bookingId}/payments`,{headers,data:{paymentMethod:'VNPAY'}});
    assert.equal(pay.status(),201,await pay.text());const p=(await pay.json()).data;
    assert.equal(Number(p.amount),162000);assert.equal(new URL(p.gatewayPreparation.checkoutUrl).searchParams.get('vnp_Amount'),'16200000');
    assert.equal((await api.post(`/api/bookings/${vn.bookingId}/promotion/apply`,{headers,data:{code:'NEWMEMBER'}})).status(),409);
    const params={vnp_TmnCode:process.env.BCM_E2E_TMN_CODE||'TEST1234',vnp_TxnRef:p.paymentAttemptId.replaceAll('-',''),vnp_Amount:'16200000',vnp_BankCode:'NCB',vnp_OrderInfo:'Promotion test',vnp_ResponseCode:'00',vnp_TransactionStatus:'00',vnp_TransactionNo:String(Date.now()),vnp_PayDate:'20261024180000'};
    const wrong=await api.get('/api/payments/vnpay/ipn',{params:sign({...params,vnp_Amount:'18000000'})});
    assert.equal((await wrong.json()).RspCode,'04');
    const ipn=await api.get('/api/payments/vnpay/ipn',{params:sign(params)});assert.equal((await ipn.json()).RspCode,'00');
    const dup=await api.get('/api/payments/vnpay/ipn',{params:sign(params)});assert.equal((await dup.json()).RspCode,'02');
    assert.equal((await get(`/bookings/${vn.bookingId}`)).status,'PAID');
    console.log('PASS VNPay signed URL final amount, amount mismatch rejection, valid IPN and duplicate IPN (test signer only)');
    const exp=await create([[0,1]],'2035-08-22');
    sql(`UPDATE bookings SET expires_at=CURRENT_TIMESTAMP-INTERVAL '1 minute' WHERE id='${exp.bookingId}' AND customer_id='${customerId}'`);
    assert.equal((await api.post(`/api/bookings/${exp.bookingId}/promotion/apply`,{headers,data:{code:'KMKH10'}})).status(),409);
    assert.notEqual((await api.post(`/api/bookings/${exp.bookingId}/payments`,{headers,data:{paymentMethod:'VNPAY'}})).status(),201);
    const newcomer=await register(),newHeaders={Authorization:`Bearer ${newcomer.accessToken}`};
    const newProfile=await api.get('/api/bookings/customer-profile',{headers:newHeaders});
    const newcomerId=(await newProfile.json()).data.customerId;
    const newHold=await api.post('/api/bookings',{headers:newHeaders,data:{customerId:newcomerId,branchId:branch,details:[{courtId:courts[0],timeSlotId:slots[0],bookingDate:'2035-08-23'}]}});
    assert.equal(newHold.status(),201);const newBooking=(await newHold.json()).data.bookingId;
    const freshContext=await browser.newContext();
    await freshContext.addInitScript(s=>{sessionStorage.setItem('bcm.accessToken',s.accessToken);sessionStorage.setItem('bcm.user',JSON.stringify(s.user));},newcomer);
    const freshPage=await freshContext.newPage();
    await freshPage.goto(`${baseURL}/checkout?bookingId=${newBooking}`);
    await freshPage.getByPlaceholder('Mã khuyến mãi').fill('NEWMEMBER');await freshPage.getByRole('button',{name:'Áp dụng',exact:true}).click();
    await expect(freshPage.locator('.checkout-grand-total')).toContainText(money(171000));
    await expect(freshPage.locator('.checkout-discount')).toContainText('NEWMEMBER · 5%');
    await freshContext.close();
    const login=await api.post('/api/auth/login',{data:{email:newcomer.user.email,password:newcomer.testPassword}});
    assert.equal(login.status(),200);const relogged=(await login.json()).data;
    const afterLogin=await api.get(`/api/bookings/${newBooking}`,{headers:{Authorization:`Bearer ${relogged.accessToken}`}});
    assert.equal(afterLogin.status(),200);assert.equal(Number((await afterLogin.json()).data.finalAmount),171000);
    console.log('PASS NEWMEMBER 5% in browser and persisted quote after fresh login/token');
    assert.deepEqual(errors,[]);console.log('PASS expired hold rejects apply/payment; no browser JavaScript errors');
  } finally {await browser.close();await api.dispose();}
})().catch(e=>{console.error(e);process.exitCode=1;});
