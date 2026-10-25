import {test} from 'node:test';
import assert from 'node:assert/strict';
import {courtReferencePrice, compareCourts, courtBadge} from './src/lib/courtPresentation.js';

test('neutral badges vary deterministically by ID without ratings or facility claims', () => {
  const ids=Array.from({length:35},(_,i)=>`court-${i}`);
  const first=ids.map(courtBadge);
  assert.deepEqual(ids.map(courtBadge),first);
  assert.equal(new Set(first).size,5);
  assert.ok(first.every(label=>!/[0-9]|yêu thích|tiện nghi|xếp hạng/i.test(label)));
});

const slots = [{timeSlotId:'normal',price:'100000.00'},{timeSlotId:'peak',price:'150000.00'}];
test('reference price uses the first selected available hour, including peak surcharge', () => {
  assert.equal(courtReferencePrice(slots,['peak','normal'],'normal'),150000);
  assert.equal(courtReferencePrice(slots,['normal','peak'],'peak'),100000);
  assert.equal(courtReferencePrice(slots,['expired','peak'],'normal'),150000);
});
test('no selected hour uses the filtered server price or the lowest available server price', () => {
  assert.equal(courtReferencePrice(slots,[],'peak'),150000);
  assert.equal(courtReferencePrice(slots,[],''),100000);
  assert.equal(courtReferencePrice(slots,[],'occupied'),null);
});
test('missing, blank and non-finite prices do not become zero or break sorting', () => {
  const invalid=[null,undefined,'',NaN,Infinity,'NaN',-1].map((price,i)=>({timeSlotId:String(i),price}));
  assert.equal(courtReferencePrice(invalid),null);
  assert.equal(courtReferencePrice([...invalid,{timeSlotId:'free',price:0}]),0);
  assert.equal(courtReferencePrice([]),null);
});
test('both price directions use the same reference amounts and keep missing prices last', () => {
  const courts=[{id:'missing',name:'A'},{id:'mid',name:'C'},{id:'high',name:'B'},{id:'low',name:'D'}];
  const prices={missing:null,mid:150000,high:270000,low:100000};
  assert.deepEqual([...courts].sort((a,b)=>compareCourts(a,b,'price',prices)).map(c=>c.id),['low','mid','high','missing']);
  assert.deepEqual([...courts].sort((a,b)=>compareCourts(a,b,'price-desc',prices)).map(c=>c.id),['high','mid','low','missing']);
});
test('recommended and equal-price ordering are stable by Vietnamese name, then ID', () => {
  const courts=[{id:'3',name:'Sân Sao 10'},{id:'2',name:'Sân Sao 02'},{id:'1',name:'Sân Sao 02'}];
  const prices={'1':100000,'2':100000,'3':100000};
  for(const order of ['recommended','price','price-desc'])
    assert.deepEqual([...courts].sort((a,b)=>compareCourts(a,b,order,prices)).map(c=>c.id),['1','2','3']);
});
