export const BOOKING_TIME_ZONE = 'Asia/Ho_Chi_Minh';

const formatter = new Intl.DateTimeFormat('en-CA', {
  timeZone: BOOKING_TIME_ZONE, year: 'numeric', month: '2-digit', day: '2-digit',
  hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23',
});

function localParts(nowMs) {
  if (!Number.isFinite(nowMs)) return null;
  return Object.fromEntries(formatter.formatToParts(new Date(nowMs)).map(part => [part.type, part.value]));
}

export function bookingToday(nowMs) {
  const parts = localParts(nowMs);
  return parts ? `${parts.year}-${parts.month}-${parts.day}` : '';
}

export function isFutureSlot(date, startTime, nowMs) {
  const parts = localParts(nowMs);
  const time = /^(\d{2}):(\d{2})(?::(\d{2})(\.\d+)?)?$/.exec(String(startTime || ''));
  if (!parts || !/^\d{4}-\d{2}-\d{2}$/.test(String(date)) || !time) return false;
  const [hour, minute, second] = [Number(time[1]), Number(time[2]), Number(time[3] || 0)];
  if (hour > 23 || minute > 59 || second > 59) return false;
  const today = `${parts.year}-${parts.month}-${parts.day}`;
  if (date !== today) return date > today;
  const start = hour * 3600 + minute * 60 + second + Number(time[4] || 0);
  const now = Number(parts.hour) * 3600 + Number(parts.minute) * 60 + Number(parts.second)
    + ((nowMs % 1000 + 1000) % 1000) / 1000;
  return start > now; // Equality means already started, exactly like the backend.
}

export function nextBookingDate(date) {
  const [year, month, day] = date.split('-').map(Number);
  // Calendar arithmetic only; current time is always obtained from the backend and Intl timezone API.
  const next = new Date(Date.UTC(year, month - 1, day + 1));
  return `${next.getUTCFullYear()}-${String(next.getUTCMonth() + 1).padStart(2, '0')}-${String(next.getUTCDate()).padStart(2, '0')}`;
}

export function anchorBookingClock(snapshot, monotonicMs) {
  const serverMs = Date.parse(snapshot?.serverTime);
  if (snapshot?.timeZone !== BOOKING_TIME_ZONE || !Number.isFinite(serverMs)) throw new Error('Invalid booking clock');
  return elapsedMs => serverMs + Math.max(0, elapsedMs - monotonicMs);
}
