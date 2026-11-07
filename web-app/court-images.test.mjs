import {test} from 'node:test';
import assert from 'node:assert/strict';
import {existsSync} from 'node:fs';
import {courtImageSource} from './src/lib/courtImages.js';

const catalog=[{id:'representative-a',branchId:'a',branchCourtNumber:1,courtNumber:1},
  {id:'child-a',branchId:'a',branchCourtNumber:2,courtNumber:1000012},
  {id:'representative-b',branchId:'b',branchCourtNumber:1,courtNumber:3}];
test('a child inherits only its own branch representative image, independent of list order',()=>{
  assert.equal(courtImageSource(catalog[1],catalog),'/assets/courts/court-00001.webp');
  assert.equal(courtImageSource(catalog[1],[...catalog].reverse()),courtImageSource(catalog[1],catalog));
});
test('a directly assigned local image takes priority over branch fallback',()=>{
  assert.equal(courtImageSource({...catalog[1],imageUrl:'/assets/checkout-court-star-city.png'},catalog),'/assets/checkout-court-star-city.png');
});
test('existing direct court mappings remain unchanged',()=>{
  assert.equal(courtImageSource(catalog[2],catalog),'/assets/courts/court-00003.webp');
  assert.equal(courtImageSource({courtNumber:2}),'/assets/checkout-court-star-city.png');
});
test('unknown branch or missing catalog uses the same existing generic image',()=>{
  assert.equal(courtImageSource({...catalog[1],branchId:'unknown'},catalog),'/assets/guide-court.jpg');
  assert.equal(courtImageSource(catalog[1]),'/assets/guide-court.jpg');
  assert.equal(courtImageSource(undefined),'/assets/guide-court.jpg');
});
test('all fixture paths resolve to existing project assets',()=>{
  for(const court of catalog)assert.ok(existsSync(new URL('./public'+courtImageSource(court,catalog),import.meta.url)));
});
test('normalized local numbers never select another court photo by their numeric value',()=>{
  const normalized=[{id:'venue',branchId:'branch',courtNumber:1,branchCourtNumber:1,courtCode:10001},
    {id:'child',branchId:'branch',courtNumber:3,branchCourtNumber:3,courtCode:1100013}];
  assert.equal(courtImageSource(normalized[1],normalized),'/assets/courts/court-10001.webp');
  assert.notEqual(courtImageSource(normalized[1],normalized),'/assets/courts/court-00003.webp');
});
