import test from 'node:test';
import assert from 'node:assert/strict';
import {unionSlots,matchingHours,filterCourts,filterHours,reconcileVenue,toggleCourt,toggleCourtHour,toggleFilterHour,prunePairs,selectedPairs} from './src/lib/courtSelection.js';
const courts=[{id:'a'},{id:'b'},{id:'c'}];
const slot=(id,hour)=>({timeSlotId:id,startTime:hour,price:100000});
const available=new Map([['a',[slot('six','06:00'),slot('seven','07:00')]],['b',[slot('seven','07:00'),slot('eight','08:00')]],['c',[]]]);

test('initial hours are the union; neither courts nor hours are selected by deriving availability',()=>{
 const selections={};assert.deepEqual(unionSlots(courts,available).map(s=>s.timeSlotId),['six','seven','eight']);
 assert.deepEqual(selectedPairs(courts,selections,available,'2031-02-20'),[]);assert.deepEqual(selections,{});
});
test('court first creates no hours until an explicit hour click',()=>{
 let selected=toggleCourt({},'a',true,[],available);assert.deepEqual(selected,{a:[]});
 selected=toggleCourtHour(selected,'a','six',true,available);assert.deepEqual(selected,{a:['six']});
 assert.deepEqual(toggleCourtHour({},'a','six',true,available),{});
});
test('hours first requires every selected hour and never invents a court choice',()=>{
 assert.deepEqual(toggleFilterHour({},courts,'six',true,available),{});
 let selected=toggleCourt({},'a',true,['six','eight'],available);
 selected=toggleCourt(selected,'b',true,['six','eight'],available);
 assert.deepEqual(selected,{});assert.deepEqual(matchingHours('b',['six','eight'],available),['eight']);
});
test('each explicit global hour adds only available pairs on already selected courts',()=>{
 let selected={a:['six'],b:[],elsewhere:['keep']};
 selected=toggleFilterHour(selected,courts,'eight',true,available);
 assert.deepEqual(selected,{a:['six'],b:['eight'],elsewhere:['keep']});
 selected=toggleFilterHour(selected,courts,'seven',true,available);
 assert.deepEqual(selected,{a:['six','seven'],b:['eight','seven'],elsewhere:['keep']});
});
test('deselecting a court or hour preserves every other valid choice, including other branches',()=>{
 const selected={a:['six','seven'],b:['seven','eight'],elsewhere:['keep']};
 assert.deepEqual(toggleCourt(selected,'a',false,[],available),{b:['seven','eight'],elsewhere:['keep']});
 assert.deepEqual(toggleFilterHour(selected,courts,'seven',false,available),{a:['six'],b:['eight'],elsewhere:['keep']});
});
test('duplicate clicks do not create duplicate pairs and busy hours are rejected',()=>{
 let selected=toggleCourt({},'a',true,['six','six'],available);
 selected=toggleCourtHour(selected,'a','six',true,available);selected=toggleCourtHour(selected,'a','eight',true,available);
 assert.deepEqual(selected,{a:['six']});assert.equal(selectedPairs(courts,selected,available,'2031-02-20').length,1);
});

test('court list excludes sold-out courts and requires all selected hours',()=>{
 assert.deepEqual(filterCourts(courts,[],available).map(c=>c.id),['a','b']);
 assert.deepEqual(filterCourts(courts,['six'],available).map(c=>c.id),['a']);
 assert.deepEqual(filterCourts(courts,['six','seven'],available).map(c=>c.id),['a']);
 assert.deepEqual(filterCourts(courts,['six','eight'],available),[]);
});
test('hour list uses the union initially and intersection of every selected court',()=>{
 assert.deepEqual(filterHours(courts,{},available).map(s=>s.timeSlotId),['six','seven','eight']);
 assert.deepEqual(filterHours(courts,{a:[]},available).map(s=>s.timeSlotId),['six','seven']);
 assert.deepEqual(filterHours(courts,{a:[],b:[]},available).map(s=>s.timeSlotId),['seven']);
});
test('refresh unchecks incompatible courts and preserves choices in another branch',()=>{
 const changed=new Map(available);changed.set('a',[slot('seven','07:00')]);
 const result=reconcileVenue(courts,{a:['six'],elsewhere:['keep']},['six'],changed);
 assert.deepEqual(result,{selections:{a:[],elsewhere:['keep']},hours:[]});
 assert.deepEqual(reconcileVenue(courts,result.selections,result.hours,available),result);
});
test('a court losing one requested hour is unselected when that hour remains available elsewhere',()=>{
 const changed=new Map(available);changed.set('a',[slot('six','06:00')]);
 const result=reconcileVenue(courts,{a:['seven'],b:['seven']},['seven'],changed);
 assert.deepEqual(result,{selections:{b:['seven']},hours:['seven']});
 assert.deepEqual(reconcileVenue(courts,result.selections,result.hours,available),result);
});
test('availability loss removes only affected pairs; a released slot is not silently reselected',()=>{
 const changed=new Map(available);changed.set('a',[slot('seven','07:00')]);
 const result=prunePairs({a:['six','seven'],b:['eight']},changed);
 assert.deepEqual(result.removed,[{courtId:'a',hourId:'six'}]);assert.deepEqual(result.selections,{a:['seven'],b:['eight']});
 assert.deepEqual(prunePairs(result.selections,available).selections,result.selections);
});
test('a selected court without hours does not block another valid pair from checkout',()=>{
 assert.deepEqual(selectedPairs(courts,{a:[],b:['seven','eight']},available,'2031-02-20'),[
  {courtId:'b',timeSlotId:'seven',bookingDate:'2031-02-20'},
  {courtId:'b',timeSlotId:'eight',bookingDate:'2031-02-20'}]);
});
