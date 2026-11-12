import test from 'node:test';
import assert from 'node:assert/strict';
import {canResumeBooking,holdDurationLabel} from './src/lib/bookingPolicy.mjs';

test('resume uses server time and only a pending future deadline',()=>{
 const b={status:'PENDING',expiresAt:'2030-01-02T10:00:00'};
 const now=Date.parse('2030-01-02T02:59:59Z');
 assert.equal(canResumeBooking(b,now),true);
 assert.equal(canResumeBooking(b,now+1000),false);
 for(const status of ['PAID','EXPIRED','COMPLETED'])assert.equal(canResumeBooking({...b,status},now),false);
 assert.equal(canResumeBooking(b,null),false);
 assert.equal(canResumeBooking({...b,expiresAt:null},now),false);
 assert.equal(canResumeBooking({...b,expiresAt:'invalid'},now),false);
});
test('FAQ formats the configured duration without rounding up or guessing',()=>{
 assert.equal(holdDurationLabel(600),'10 phút');
 assert.equal(holdDurationLabel(900),'15 phút');
 assert.equal(holdDurationLabel(90),'1 phút 30 giây');
 for(const v of [null,undefined,0,-1,'600',NaN])assert.equal(holdDurationLabel(v),null);
});
