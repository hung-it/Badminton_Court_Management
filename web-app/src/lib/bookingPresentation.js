const names = new Intl.Collator('vi', {numeric: true, sensitivity: 'base'});
const timeSeconds = value => String(value || '').split(':').reduce((total, part, index) => total + Number(part) * [3600, 60, 1][index], 0);
export const shortTime = value => String(value || '').slice(0, 5);
export const surfaceLabel = type => ({WOODEN_FLOOR: 'Sàn gỗ', STANDARD_MAT: 'Thảm tiêu chuẩn'}[type] || 'Loại sân chưa có dữ liệu');
export const displayCourtNumber = court => court.branchId ? court.branchCourtNumber : court.courtNumber;
export const courtLabel = court => displayCourtNumber(court) != null ? `Sân ${String(displayCourtNumber(court)).padStart(2, '0')}` : 'Chưa có số sân';
export const compareCourtNumbers = (a, b) => names.compare(a.branchName || '', b.branchName || '')
  || (displayCourtNumber(a) ?? Infinity) - (displayCourtNumber(b) ?? Infinity) || String(a.courtId || a.id).localeCompare(String(b.courtId || b.id));

// Local date/time values already represent Asia/Ho_Chi_Minh. Do not reinterpret them in the browser timezone.
export function sortBookingDetails(details = []) {
  return [...details].sort((a, b) => String(a.bookingDate).localeCompare(String(b.bookingDate))
    || timeSeconds(a.startTime) - timeSeconds(b.startTime) || compareCourtNumbers(a, b)
    || timeSeconds(a.endTime) - timeSeconds(b.endTime));
}

export function bookingCourtGroups(details = []) {
  const groups = new Map();
  for (const detail of sortBookingDetails(details)) {
    if (!groups.has(detail.courtId)) groups.set(detail.courtId, {courtId: detail.courtId, details: [], ranges: []});
    const group = groups.get(detail.courtId);
    group.details.push(detail);
    const previous = group.ranges.at(-1);
    if (previous && previous.bookingDate === detail.bookingDate && timeSeconds(previous.endTime) === timeSeconds(detail.startTime)) {
      previous.endTime = detail.endTime;
    } else group.ranges.push({...detail});
  }
  return [...groups.values()];
}

export function rangesLabel(ranges = [], multipleDates = new Set(ranges.map(range => range.bookingDate)).size > 1) {
  return ranges.map(range => `${multipleDates ? `${range.bookingDate} · ` : ''}${shortTime(range.startTime)} – ${shortTime(range.endTime)}`).join(', ');
}

export function bookingTimeLabel(details = []) {
  const groups = bookingCourtGroups(details);
  const multipleDates = new Set(details.map(detail => detail.bookingDate)).size > 1;
  return groups.map(group => `${groups.length > 1 ? `${courtLabel(group.details[0])}: ` : ''}${rangesLabel(group.ranges, multipleDates)}`).join('; ');
}

export function bookingDuration(details = []) {
  if (!details.length || details.some(detail => !detail.startTime || !detail.endTime)) return 'Chưa có dữ liệu';
  const minutes = details.reduce((sum, detail) => sum + Math.max(0, timeSeconds(detail.endTime) - timeSeconds(detail.startTime)) / 60, 0);
  return [Math.floor(minutes / 60) ? `${Math.floor(minutes / 60)} giờ` : '', minutes % 60 ? `${minutes % 60} phút` : ''].filter(Boolean).join(' ') || '0 phút';
}
