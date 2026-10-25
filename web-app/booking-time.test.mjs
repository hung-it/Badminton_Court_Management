import test from 'node:test';
import assert from 'node:assert/strict';
import {bookingToday, isFutureSlot, nextBookingDate, anchorBookingClock} from './src/lib/bookingTime.js';

const date = '2031-02-20';
const cases = ['00:00', '06:59', '07:00', '11:59', '12:00', '12:30', '17:59', '18:00', '23:59'];
for (const time of cases) {
  test(`Vietnam clock ${time}: yesterday/today/tomorrow and exact start boundary`, () => {
    const now = Date.parse(`${date}T${time}:00+07:00`); // Fixed test fixture, never the host clock.
    assert.equal(bookingToday(now), date);
    for (const start of ['00:00:00', '06:00:00', '07:00:00', '08:00:00', '12:00:00', '13:00:00', '17:00:00', '18:00:00', '19:00:00', '23:00:00']) {
      assert.equal(isFutureSlot('2031-02-19', start, now), false);
      assert.equal(isFutureSlot('2031-02-21', start, now), true);
      assert.equal(isFutureSlot(date, start, now), start > `${time}:00`);
    }
  });
}
test('12 AM is midnight, 12 PM is noon; a slot in progress is excluded', () => {
  assert.equal(isFutureSlot(date, '12:00', Date.parse(`${date}T00:00:00+07:00`)), true);
  assert.equal(isFutureSlot(date, '12:00', Date.parse(`${date}T12:30:00+07:00`)), false);
  assert.equal(isFutureSlot(date, '12:00', Date.parse(`${date}T12:00:00+07:00`)), false);
  assert.equal(isFutureSlot(date, '13:00', Date.parse(`${date}T12:30:00+07:00`)), true);
});
test('anchored monotonic clock removes a slot while the UI remains open', () => {
  const anchor = anchorBookingClock({serverTime: `${date}T06:59:59+07:00`, timeZone: 'Asia/Ho_Chi_Minh'}, 100);
  const templates = [{id: 'court-a:07', startTime: '07:00'}, {id: 'court-a:08', startTime: '08:00'}, {id: 'court-b:08', startTime: '08:00'}];
  const visible = ms => templates.filter(slot => isFutureSlot(date, slot.startTime, anchor(ms))).map(slot => slot.id);
  assert.equal(visible(100).length, 3);
  assert.deepEqual(visible(1100), ['court-a:08', 'court-b:08']);
  assert.equal(templates.length, 3, 'Never remove master/history data');
});
test('midnight changes today in Vietnam even while the UTC calendar is still yesterday', () => {
  const anchor = anchorBookingClock({serverTime: '2031-02-20T16:59:59Z', timeZone: 'Asia/Ho_Chi_Minh'}, 0);
  assert.equal(bookingToday(anchor(0)), '2031-02-20');
  assert.equal(bookingToday(anchor(1000)), '2031-02-21');
  assert.equal(isFutureSlot('2031-02-20', '23:59:59', anchor(1000)), false);
  assert.equal(isFutureSlot('2031-02-21', '00:00:00', anchor(1000)), false);
  assert.equal(nextBookingDate('2031-02-28'), '2031-03-01');
  assert.equal(nextBookingDate('2032-02-28'), '2032-02-29');
});
test('clock not synchronized and malformed slots fail closed', () => {
  assert.equal(isFutureSlot(date, '07:00', null), false);
  assert.equal(isFutureSlot(date, '25:00', Date.parse(`${date}T00:00:00Z`)), false);
  assert.throws(() => anchorBookingClock({serverTime: 'bad', timeZone: 'Asia/Ho_Chi_Minh'}, 0));
  assert.throws(() => anchorBookingClock({serverTime: `${date}T00:00:00Z`, timeZone: 'UTC'}, 0));
});
