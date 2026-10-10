import axios from 'axios';

export const api = axios.create({
  baseURL: import.meta.env.VITE_API_URL || '/api',
  headers: { 'Content-Type': 'application/json' },
});

export const posApi = {
  products: () => api.get('/products'),
  sell: (payload) => api.post('/pos/sales', payload),
  checkout: (bookingId, paymentMethod = 'CASH') => api.post(`/checkout/${bookingId}`, { paymentMethod }),
  suppliers: () => api.get('/suppliers'),
  importOrders: () => api.get('/import-orders'),
  approveImport: (id) => api.post(`/import-orders/${id}/approve`),
  revenue: (period, date) => api.get('/analytics/revenue', { params: { period, date } }),
};
