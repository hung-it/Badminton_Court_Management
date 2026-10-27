import React, {useCallback, useEffect, useState} from 'react';
import './customer-history.css';
import {assets, GuideFooter, GuideHeader, GuideIcon, SupportBanner} from './CustomerLayout.jsx';
import {api, clearSession, readSession} from './CustomerCourts.jsx';
import {formatVND} from './lib/format';

const PAGE_SIZE = 10;

const STATUS_OPTIONS = [
  {value: '', label: 'Tất cả trạng thái'},
  {value: 'PENDING', label: 'Chờ thanh toán'},
  {value: 'PAID', label: 'Đã thanh toán'},
  {value: 'CHECKED_IN', label: 'Đang sử dụng'},
  {value: 'COMPLETED', label: 'Hoàn thành'},
  {value: 'NO_SHOW', label: 'Không đến'},
  {value: 'EXPIRED', label: 'Hết hạn'},
];

const STATUS_LABELS = Object.fromEntries(STATUS_OPTIONS.map(({value, label}) => [value, label]));

function formatDateTime(value) {
  if (!value) return '—';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return String(value);
  return new Intl.DateTimeFormat('vi-VN', {dateStyle: 'medium', timeStyle: 'short'}).format(date);
}

function formatDate(value) {
  if (!value) return '—';
  const date = new Date(`${value}T00:00:00`);
  if (Number.isNaN(date.getTime())) return String(value);
  return new Intl.DateTimeFormat('vi-VN', {dateStyle: 'full'}).format(date);
}

function timeLabel(value) {
  return value ? String(value).slice(0, 5) : '—';
}

function statusLabel(status) {
  return STATUS_LABELS[status] || status || 'Không xác định';
}

function BookingDetail({detail, loading, error, onRetry}) {
  if (loading) return <div className="history-detail-state">Đang tải chi tiết đặt sân…</div>;
  if (error) {
    return <div className="history-detail-state history-detail-state--error">
      <span>{error}</span>
      <button type="button" className="history-inline-button" onClick={onRetry}>Thử lại</button>
    </div>;
  }
  if (!detail) return null;

  return <div className="history-detail">
    <div className="history-detail__columns">
      <section>
        <h3>Khung giờ đã đặt</h3>
        {detail.details?.length ? <div className="history-slots">
          {detail.details.map(slot => <div className="history-slot" key={slot.detailId}>
            <div>
              <strong>{slot.courtName || `Sân ${slot.courtNumber ?? '—'}`}</strong>
              <span>Sân {slot.courtNumber ?? '—'} · {formatDate(slot.bookingDate)}</span>
            </div>
            <span className="history-slot__time">{timeLabel(slot.startTime)} – {timeLabel(slot.endTime)}</span>
            <strong>{formatVND(slot.price)}</strong>
          </div>)}
        </div> : <p className="history-muted">Booking này không còn chi tiết khung giờ. Có thể lịch đã hết hạn.</p>}
      </section>
      <section>
        <h3>Lịch sử thanh toán</h3>
        {detail.payments?.length ? <div className="history-payments">
          {detail.payments.map(payment => <div className="history-payment" key={payment.paymentAttemptId}>
            <div>
              <strong>{payment.paymentMethod || '—'}</strong>
              <span>{formatDateTime(payment.createdAt)}</span>
            </div>
            <span className={`history-payment__status history-payment__status--${String(payment.paymentStatus || '').toLowerCase()}`}>
              {payment.paymentStatus || '—'}
            </span>
            <strong>{formatVND(payment.amount)}</strong>
          </div>)}
        </div> : <p className="history-muted">Chưa có giao dịch thanh toán.</p>}
      </section>
    </div>
  </div>;
}

export default function CustomerBookingHistory() {
  const [session, setSession] = useState(readSession);
  const [page, setPage] = useState(0);
  const [status, setStatus] = useState('');
  const [history, setHistory] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [reload, setReload] = useState(0);
  const [expandedId, setExpandedId] = useState(null);
  const [details, setDetails] = useState({});
  const [bookingCount, setBookingCount] = useState(0);

  const loadHistory = useCallback(async () => {
    if (!session) { setLoading(false); return; }
    setLoading(true);
    setError('');
    setExpandedId(null);
    const params = {page, size: PAGE_SIZE};
    if (status) params.status = status;
    try {
      const data = await api(`/bookings?${new URLSearchParams(params).toString()}`, session.accessToken);
      setHistory(data || {content: [], page, size: PAGE_SIZE, totalElements: 0, totalPages: 0});
    } catch (reason) {
      setError(reason.message || 'Không tải được lịch đặt.');
    } finally {
      setLoading(false);
    }
  }, [page, session, status]);

  useEffect(() => { loadHistory(); }, [loadHistory, reload]);

  useEffect(() => {
    if (!session) return undefined;
    let active = true;
    api('/bookings?page=0&size=1', session.accessToken)
      .then(page => { if (active) setBookingCount(Number(page?.totalElements) || 0); })
      .catch(() => { if (active) setBookingCount(0); });
    return () => { active = false; };
  }, [session]);

  async function toggleDetails(bookingId, force = false) {
    if (!force && expandedId === bookingId) { setExpandedId(null); return; }
    setExpandedId(bookingId);
    if (!force && (details[bookingId]?.data || details[bookingId]?.loading)) return;
    setDetails(current => ({...current, [bookingId]: {loading: true, error: ''}}));
    try {
      const data = await api(`/bookings/${encodeURIComponent(bookingId)}`, session.accessToken);
      setDetails(current => ({...current, [bookingId]: {loading: false, error: '', data}}));
    } catch (reason) {
      setDetails(current => ({...current, [bookingId]: {loading: false, error: reason.message || 'Không tải được chi tiết.'}}));
    }
  }

  function logout() {
    clearSession();
    setSession(null);
    setHistory(null);
    setDetails({});
    setError('');
  }

  if (!session) {
    return <div className="customer-page">
      <GuideHeader session={null} activePage="courts"/>
      <main className="customer-content customer-main">
        <div className="history-state-card">
          <strong>Đăng nhập để xem lịch đặt</strong>
          <span>Lịch đặt sân chỉ hiển thị với tài khoản khách hàng đã đăng nhập.</span>
          <a className="customer-button customer-button-primary" href="/login?redirect=%2Fmy-bookings">Đăng nhập</a>
        </div>
      </main>
      <GuideFooter activePage="courts"/>
    </div>;
  }

  const items = history?.content || [];
  const totalPages = history?.totalPages || 0;
  const currentPage = history?.page ?? page;

  return <div className="customer-page">
    <GuideHeader session={session} bookingCount={bookingCount} bookingError={null} bookingLoading={false} onLogout={logout} activePage="courts"/>

    <main className="customer-content customer-main">
      <section className="customer-welcome-row" aria-labelledby="history-title">
        <div className="customer-welcome">
          <div className="customer-breadcrumb"><a href="#page-top">Trang chủ</a><GuideIcon src={assets.chevronRight}/><strong>Lịch đặt của tôi</strong></div>
          <h1 id="history-title">Lịch đặt của tôi</h1>
          <p>Xem lại toàn bộ lịch đặt sân và trạng thái thanh toán của bạn.</p>
        </div>
      </section>

      <section className="history-toolbar" aria-label="Lọc lịch đặt">
        <label className="history-toolbar__field">
          <span>Trạng thái</span>
          <select value={status} onChange={event => {setStatus(event.target.value); setPage(0);}} aria-label="Lọc theo trạng thái">
            {STATUS_OPTIONS.map(option => <option key={option.value} value={option.value}>{option.label}</option>)}
          </select>
        </label>
        <span className="history-toolbar__count">{history ? `${history.totalElements || 0} lịch đặt` : 'Đang tải…'}</span>
        <button type="button" className="customer-button customer-button-outline" onClick={() => setReload(value => value + 1)} disabled={loading}>Tải lại</button>
      </section>

      {error && <div className="customer-alert customer-alert-error" role="alert">{error}
        <button type="button" onClick={() => setReload(value => value + 1)}>Thử lại</button>
      </div>}

      {loading ? <div className="history-state-card" role="status">Đang tải lịch đặt…</div>
      : !items.length ? <div className="history-state-card">
        <strong>Chưa có lịch đặt nào</strong>
        <span>Các lịch đặt sân của bạn sẽ hiển thị ở đây.</span>
        <a className="customer-button customer-button-primary" href="/courts">Tìm sân ngay</a>
      </div>
      : <div className="history-list">
        {items.map(booking => {
          const detailState = details[booking.bookingId];
          const expanded = expandedId === booking.bookingId;
          return <article className={`history-card${expanded ? ' is-expanded' : ''}`} key={booking.bookingId}>
            <button type="button" className="history-card__summary" aria-expanded={expanded} onClick={() => toggleDetails(booking.bookingId)}>
              <span className="history-card__identity">
                <span className="history-card__icon"><GuideIcon src={assets.calendar}/></span>
                <span>
                  <strong>Booking {String(booking.bookingId).slice(0, 8)}…</strong>
                  <small>Đặt ngày {formatDateTime(booking.createdAt)}</small>
                </span>
              </span>
              <span className="history-card__meta">
                <span className={`history-status history-status--${String(booking.status || '').toLowerCase()}`}>{statusLabel(booking.status)}</span>
                <strong>{formatVND(booking.courtFee)}</strong>
              </span>
            </button>
            {expanded && <BookingDetail detail={detailState?.data} loading={Boolean(detailState?.loading)} error={detailState?.error}
              onRetry={() => {
                setDetails(current => {const next = {...current}; delete next[booking.bookingId]; return next;});
                toggleDetails(booking.bookingId, true);
              }}/>}
          </article>;
        })}
      </div>}

      {totalPages > 1 && <nav className="history-pagination" aria-label="Phân trang lịch đặt">
        <button type="button" className="customer-button customer-button-outline" onClick={() => setPage(value => value - 1)} disabled={currentPage <= 0 || loading}>Trước</button>
        <span>Trang {currentPage + 1} / {totalPages}</span>
        <button type="button" className="customer-button customer-button-outline" onClick={() => setPage(value => value + 1)} disabled={currentPage >= totalPages - 1 || loading}>Sau</button>
      </nav>}

      <SupportBanner/>
    </main>

    <GuideFooter activePage="courts"/>
  </div>;
}
