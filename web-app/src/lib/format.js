const VND = new Intl.NumberFormat('vi-VN', {
  style: 'currency',
  currency: 'VND',
  maximumFractionDigits: 0,
});

/** 180000 → "180.000₫" (khớp thiết kế Figma). */
export function formatVND(value) {
  const n = Number(value);
  if (!Number.isFinite(n)) return '—';
  return VND.format(n).replace(/\s/g, '');
}

/** "18:00:00" → "18:00" */
export function formatTime(value) {
  if (!value) return '';
  return String(value).slice(0, 5);
}

/** "2026-10-05" → "Thứ Bảy, 05/10" */
const WEEKDAYS = ['Chủ Nhật', 'Thứ Hai', 'Thứ Ba', 'Thứ Tư', 'Thứ Năm', 'Thứ Sáu', 'Thứ Bảy'];

export function formatDateLabel(isoDate) {
  if (!isoDate) return '';
  const d = new Date(`${isoDate}T00:00:00`);
  if (Number.isNaN(d.getTime())) return isoDate;
  return `${WEEKDAYS[d.getDay()]}, ${String(d.getDate()).padStart(2, '0')}/${String(
    d.getMonth() + 1,
  ).padStart(2, '0')}`;
}

/** Ngày hôm nay dạng YYYY-MM-DD theo giờ địa phương (không dùng toISOString để tránh lệch múi giờ). */
export function todayISO() {
  const d = new Date();
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(
    d.getDate(),
  ).padStart(2, '0')}`;
}

/** Court type: STANDARD_MAT | WOODEN_FLOOR → nhãn tiếng Việt. */
export function courtTypeLabel(type) {
  switch (type) {
    case 'WOODEN_FLOOR':
      return 'Sàn gỗ';
    case 'STANDARD_MAT':
      return 'Sàn thảm';
    default:
      return 'Sân cầu lông';
  }
}
