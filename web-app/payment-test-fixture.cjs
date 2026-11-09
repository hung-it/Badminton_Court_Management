// Test-only signed callback fixture. Never calls VNPay or reads merchant credentials.
const assert=require('node:assert/strict'),crypto=require('node:crypto');
function requireTestEnvironment(){
 const db=process.env.BCM_E2E_DB;
 if(!db||db==='badminton_court_db'||!/^bcm_[a-z0-9_]*(test|audit|e2e)[a-z0-9_]*$/.test(db))throw Error('Set BCM_E2E_DB to the isolated database used by this test backend');
}
async function callback(api,checkout){
 requireTestEnvironment();if(!process.env.BCM_E2E_VNPAY_HASH_SECRET)throw Error('Set the isolated backend test signing key BCM_E2E_VNPAY_HASH_SECRET; never use a real merchant key');const params=new URL(checkout).searchParams;
 const fields={vnp_TmnCode:params.get('vnp_TmnCode'),vnp_TxnRef:params.get('vnp_TxnRef'),vnp_Amount:params.get('vnp_Amount'),vnp_BankCode:'NCB',vnp_OrderInfo:'Controlled test fixture',vnp_ResponseCode:'00',vnp_TransactionStatus:'00',vnp_TransactionNo:String(Date.now()),vnp_PayDate:params.get('vnp_CreateDate')};
 const encoded=new URLSearchParams(Object.fromEntries(Object.entries(fields).sort(([a],[b])=>a.localeCompare(b)))).toString();
 fields.vnp_SecureHash=crypto.createHmac('sha512',process.env.BCM_E2E_VNPAY_HASH_SECRET).update(encoded).digest('hex');
 const response=await api.get('/api/payments/vnpay/ipn',{params:fields});assert.equal(response.status(),200);assert.equal((await response.json()).RspCode,'00');
 const repeated=await api.get('/api/payments/vnpay/ipn',{params:fields});assert.equal((await repeated.json()).RspCode,'02');
}
async function settleFixture(api,headers,bookingId){
 requireTestEnvironment();const response=await api.post(`/api/bookings/${bookingId}/payments`,{headers,data:{paymentMethod:'VNPAY'}});assert.equal(response.status(),201,await response.text());
 const attempt=(await response.json()).data;
 const retry=await api.post(`/api/bookings/${bookingId}/payments`,{headers,data:{paymentMethod:'VNPAY'}});assert.equal(retry.status(),201);assert.equal((await retry.json()).data.paymentAttemptId,attempt.paymentAttemptId);
 await callback(api,attempt.gatewayPreparation.checkoutUrl);return attempt;
}
async function fakeCheckout(page,api){
 requireTestEnvironment();const bookings=new Map();
 await page.route('**/api/bookings/*/payments',async route=>{
  const response=await route.fetch(),body=await response.json();
  if(body.data?.paymentAttemptId)bookings.set(body.data.paymentAttemptId,body.data.bookingId);
  await route.fulfill({response});
 });
 await page.route('https://sandbox.vnpayment.vn/**',async route=>{
  const checkout=route.request().url();await callback(api,checkout);
  const reference=new URL(checkout).searchParams.get('vnp_TxnRef');
  const attemptId=reference.replace(/^(.{8})(.{4})(.{4})(.{4})(.{12})$/,'$1-$2-$3-$4-$5');
  assert.ok(bookings.has(attemptId),'Expected a booking payment response before the fake provider redirect');
  await route.fulfill({status:302,headers:{location:new URL('/payment-result?bookingId='+bookings.get(attemptId)+'&paymentAttemptId='+attemptId,page.url()).href},body:''});
 });
}
module.exports={requireTestEnvironment,settleFixture,fakeCheckout};
