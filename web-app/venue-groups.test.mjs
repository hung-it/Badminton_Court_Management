import test from 'node:test';
import assert from 'node:assert/strict';
import {venueRepresentatives, venueKey, selectedPairs} from './src/lib/courtSelection.js';

const court=(id,branchId,number)=>({id,branchId,branchCourtNumber:number,courtCode:number});
test('a branch with no court one still appears exactly once',()=>{
 const courts=[court('three','branch',3),court('two','branch',2)];
 assert.deepEqual(venueRepresentatives(courts).map(c=>c.id),['two']);
 assert.deepEqual(selectedPairs(courts,{two:['slot']},new Map([['two',[{timeSlotId:'slot'}]]]),'2090-01-01'),
  [{courtId:'two',timeSlotId:'slot',bookingDate:'2090-01-01'}]);
});
test('representative changes preserve the branch selection key',()=>{
 const one=court('one','branch',1),two=court('two','branch',2);
 assert.equal(venueKey(venueRepresentatives([two,one])[0]),venueKey(venueRepresentatives([two])[0]));
});
test('unassigned courts stay separate and branches never duplicate',()=>{
 const courts=[court('a','first',2),court('b','first',3),court('c','second',4),court('legacy',null,1)];
 assert.deepEqual(venueRepresentatives(courts).map(venueKey),['legacy','first','second']);
});
test('an empty catalog has no branch cards',()=>assert.deepEqual(venueRepresentatives([]),[]));
