import {test} from 'node:test';
import assert from 'node:assert/strict';
import {bookingTimeLabel, bookingDuration, bookingCourtGroups, sortBookingDetails, courtLabel} from './src/lib/bookingPresentation.js';

const slot = (startTime, endTime, courtId = 'one', branchCourtNumber = 1, bookingDate = '2035-01-02') =>
  ({startTime, endTime, courtId, branchId: 'branch', branchCourtNumber, courtNumber: 1203036, bookingDate});

test('reverse clicks merge adjacent times and preserve actual duration', () => {
  const details = [slot('10:00:00','11:00:00'),slot('08:00:00','09:00:00'),slot('09:00:00','10:00:00')];
  assert.equal(bookingTimeLabel(details),'08:00 – 11:00');
  assert.equal(bookingDuration(details),'3 giờ');
  assert.equal(courtLabel(details[0]),'Sân 01');
});
test('gaps remain separate and do not inflate paid court hours', () => {
  const details = [slot('10:00','11:00'),slot('07:00','08:00')];
  assert.equal(bookingTimeLabel(details),'07:00 – 08:00, 10:00 – 11:00');
  assert.equal(bookingDuration(details),'2 giờ');
});
test('overlapping schedules on two courts remain separate and total four court hours', () => {
  const details = [slot('09:00','10:00','two',2),slot('08:00','09:00'),slot('07:00','08:00'),slot('08:00','09:00','two',2)];
  assert.equal(bookingTimeLabel(details),'Sân 01: 07:00 – 09:00; Sân 02: 08:00 – 10:00');
  assert.equal(bookingDuration(details),'4 giờ');
  assert.equal(bookingCourtGroups(details).length,2);
  assert.deepEqual(sortBookingDetails(details).map(item=>item.branchCourtNumber),[1,1,2,2]);
});
test('days do not merge and numeric times sort independently of click order', () => {
  const details = [slot('08:00','09:00','one',1,'2035-01-03'),slot('07:00','08:00')];
  assert.equal(bookingTimeLabel(details),'2035-01-02 · 07:00 – 08:00, 2035-01-03 · 08:00 – 09:00');
  assert.equal(bookingDuration(details),'2 giờ');
});
test('court numbers come from branch metadata, never a global identifier or array index', () => {
  assert.equal(courtLabel(slot('07:00','08:00','four',4)),'Sân 04');
  assert.equal(courtLabel({branchId:'branch',courtNumber:1203036}),'Chưa có số sân');
});
test('half hour slots keep their exact duration', () => {
  assert.equal(bookingDuration([slot('07:00','07:30'),slot('07:30','08:30')]),'1 giờ 30 phút');
});
test('different courts on different dates retain the date beside each range', () => {
  assert.equal(bookingTimeLabel([slot('08:00','09:00','two',2,'2035-01-03'),slot('07:00','08:00')]),
    'Sân 01: 2035-01-02 · 07:00 – 08:00; Sân 02: 2035-01-03 · 08:00 – 09:00');
});
