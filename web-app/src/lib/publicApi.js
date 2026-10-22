import { apiGet } from './apiClient';

/**
 * Endpoint public của backend (`/api/public/**` được permitAll trong SecurityConfig).
 * Chỉ dùng cho trang chủ — các màn hình cần token dùng `apiGet` trực tiếp.
 */

/** GET /api/public/courts — chỉ trả về court có status AVAILABLE. */
export async function fetchCourts() {
  const data = await apiGet('/public/courts');
  return Array.isArray(data) ? data : [];
}

/** GET /api/public/time-slots — toàn bộ khung giờ master data, đã sắp xếp theo startTime. */
export async function fetchTimeSlots() {
  const data = await apiGet('/public/time-slots');
  return Array.isArray(data) ? data : [];
}

/** GET /api/public/price-quote?courtId=&timeSlotId= — giá = basePrice × priceMultiplier. */
export async function fetchPriceQuote(courtId, timeSlotId) {
  return apiGet('/public/price-quote', { courtId, timeSlotId });
}

/** GET /api/availability?date= — cần Bearer token. */
export async function fetchAvailability(date, courtId) {
  return apiGet('/availability', { date, courtId });
}

/** GET /api/bookings?page=&size= — cần Bearer token, dùng cho badge header. */
export async function fetchBookingCount() {
  const data = await apiGet('/bookings', { page: 0, size: 1 });
  return data?.totalElements ?? 0;
}
