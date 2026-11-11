// Mutating smoke test, deliberately restricted to the isolated QA API port.
import assert from 'node:assert/strict';
const base=process.env.DEMO_CHECKOUT_TEST_API||'http://127.0.0.1:58098/api';
assert.equal(base,'http://127.0.0.1:58098/api','Use only the dedicated demo QA backend');
let token;
async function api(path,body){
 const response=await fetch(base+path,{method:body===undefined?'GET':'POST',headers:{'Content-Type':'application/json',...(token?{Authorization:`Bearer ${token}`}:{})},...(body===undefined?{}:{body:JSON.stringify(body)})});
 const result=await response.json();
 assert.equal(response.status,200,`${path}: ${result.message}`);
 assert.equal(result.success,true);
 return result.data;
}
token=(await api('/auth/login',{email:'admin.demo@bcm.test',password:'Demo@123'})).accessToken;
const active=await api('/checkout/active-bookings');
assert.equal(active.length,3);
assert.equal(active.filter(b=>b.status==='PAID').length,2);
assert.equal(active.filter(b=>b.status==='PENDING').length,1);
const waterBefore=(await api('/admin/products')).find(p=>p.name==='Nước suối 500ml').stockQuantity;
for(const booking of active){
 if(booking.status==='PENDING'){
  const body={paymentMethod:'CASH',paymentConfirmed:true,expectedAmount:booking.finalAmount,reference:'QA-COUNTER-DEMO'};
  await Promise.all([api(`/pos/bookings/${booking.bookingId}/receive`,body),api(`/pos/bookings/${booking.bookingId}/receive`,body)]);
 }
 const preview=await api(`/checkout/${booking.bookingId}`);
 assert.equal(preview.checkoutEligible,true);
 const body={paymentMethod:'CASH',paymentConfirmed:true,expectedAmountDue:preview.amountDue,reference:'QA-CHECKOUT-DEMO'};
 const [first,retry]=await Promise.all([api(`/checkout/${booking.bookingId}`,body),api(`/checkout/${booking.bookingId}`,body)]);
 assert.equal(first.remainingDue,0);
 assert.deepEqual(first,retry);
}
assert.equal((await api('/checkout/active-bookings')).length,0);
assert.equal((await api('/admin/products')).find(p=>p.name==='Nước suối 500ml').stockQuantity,waterBefore,'Checkout must not deduct the draft stock twice');
console.log('PASS: all three demo scenarios, concurrent receipt/checkout retries and no double stock deduction');
