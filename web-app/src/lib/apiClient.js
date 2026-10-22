import axios from 'axios';

export const API_BASE = import.meta.env.VITE_API_BASE_URL ?? '/api';

const ACCESS_KEY = 'bcm.accessToken';
const REFRESH_KEY = 'bcm.refreshToken';
const USER_KEY = 'bcm.user';

export const tokenStore = {
  get access() {
    return sessionStorage.getItem(ACCESS_KEY);
  },
  get refresh() {
    return localStorage.getItem(REFRESH_KEY) ?? sessionStorage.getItem(REFRESH_KEY);
  },
  get user() {
    const raw = localStorage.getItem(USER_KEY);
    if (!raw) return null;
    try {
      return JSON.parse(raw);
    } catch {
      return null;
    }
  },
  save({ accessToken, refreshToken, user }, { persist = true } = {}) {
    if (accessToken) sessionStorage.setItem(ACCESS_KEY, accessToken);
    if (refreshToken) {
      if (persist) {
        localStorage.setItem(REFRESH_KEY, refreshToken);
      } else {
        sessionStorage.setItem(REFRESH_KEY, refreshToken);
        localStorage.removeItem(REFRESH_KEY);
      }
    }
    if (user) localStorage.setItem(USER_KEY, JSON.stringify(user));
  },
  setAccess(accessToken) {
    if (accessToken) sessionStorage.setItem(ACCESS_KEY, accessToken);
  },
  clear() {
    sessionStorage.removeItem(ACCESS_KEY);
    sessionStorage.removeItem(REFRESH_KEY);
    localStorage.removeItem(REFRESH_KEY);
    localStorage.removeItem(USER_KEY);
  },
};

const client = axios.create({
  baseURL: API_BASE,
  headers: { 'Content-Type': 'application/json' },
  timeout: 15000,
});

client.interceptors.request.use((config) => {
  const token = tokenStore.access;
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

/** Đang refresh: mọi request 401 khác phải chờ, không gọi refresh lặp lại. */
let refreshPromise = null;

async function refreshAccessToken() {
  const refreshToken = tokenStore.refresh;
  if (!refreshToken) throw new Error('No refresh token');

  const { data } = await axios.post(
    `${API_BASE}/auth/refresh`,
    { refreshToken },
    { headers: { 'Content-Type': 'application/json' } },
  );

  const payload = data?.data;
  if (!payload?.accessToken) throw new Error('Refresh response missing accessToken');

  tokenStore.setAccess(payload.accessToken);
  if (payload.refreshToken) {
    // Giữ đúng nơi lưu ban đầu: localStorage nếu đã "ghi nhớ", sessionStorage nếu không.
    if (localStorage.getItem(REFRESH_KEY)) {
      localStorage.setItem(REFRESH_KEY, payload.refreshToken);
    } else {
      sessionStorage.setItem(REFRESH_KEY, payload.refreshToken);
    }
  }
  if (payload.user) {
    localStorage.setItem(USER_KEY, JSON.stringify(payload.user));
  }
  return payload.accessToken;
}

client.interceptors.response.use(
  (response) => response,
  async (error) => {
    const original = error.config;
    const status = error.response?.status;

    if (status === 401 && original && !original._retried && !original.url?.includes('/auth/')) {
      original._retried = true;
      try {
        refreshPromise = refreshPromise ?? refreshAccessToken();
        const token = await refreshPromise;
        original.headers.Authorization = `Bearer ${token}`;
        return client(original);
      } catch {
        tokenStore.clear();
        window.dispatchEvent(new CustomEvent('bcm:auth-expired'));
        return Promise.reject(error);
      } finally {
        refreshPromise = null;
      }
    }
    return Promise.reject(error);
  },
);

/**
 * Backend trả về ApiResponse<T>: { success, message, data, timestamp }.
 * Mọi lời gọi public đi qua đây để luôn trả `data` đã bóc vỏ.
 */
export async function apiGet(path, params) {
  const res = await client.get(path, { params });
  return res.data?.data;
}

export async function apiPost(path, body) {
  const res = await client.post(path, body);
  return res.data?.data;
}

/** Chuẩn hoá lỗi thành message tiếng Việt để đưa ra UI. */
export function toErrorMessage(error) {
  const status = error?.response?.status;
  const body = error?.response?.data;
  const serverMessage = body?.message;

  if (status === 409) {
    if (typeof serverMessage === 'string' && /slot|booked|constraint/i.test(serverMessage)) {
      return 'Sân đã có người đặt. Vui lòng chọn khung giờ khác.';
    }
    return 'Dữ liệu đã tồn tại. Vui lòng thử lại.';
  }
  if (status === 401) return 'Phiên đăng nhập đã hết hạn. Vui lòng đăng nhập lại.';
  if (status === 403) return 'Bạn không có quyền thực hiện thao tác này.';
  if (status === 404) return 'Không tìm thấy dữ liệu yêu cầu.';
  if (!status) return 'Không thể kết nối máy chủ. Vui lòng thử lại.';

  if (body?.data && typeof body.data === 'object' && !Array.isArray(body.data)) {
    const fieldErrors = Object.values(body.data).filter((v) => typeof v === 'string');
    if (fieldErrors.length) return fieldErrors.join(' · ');
  }
  return serverMessage || 'Đã có lỗi xảy ra. Vui lòng thử lại.';
}

export default client;
