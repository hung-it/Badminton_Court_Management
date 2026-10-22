import React, {useEffect, useState} from 'react';
import './payment-result.css';
import {api, readSession, clearSession, BOOKING_DRAFT_KEY} from './CustomerCourts.jsx';
import {GuideHeader} from './CustomerLayout.jsx';

const asset = name => `/assets/checkout-${name}`;
const formatMoney = value => `${new Intl.NumberFormat('vi-VN', {maximumFractionDigits: 0}).format(Number(value) || 0)}₫`;
const timeText = value => String(value || '').slice(0, 5);
const normalizedId = value => String(value || '').replaceAll('-', '').toLowerCase();

function formatDate(value) {
  if (!value) return 'Chưa có dữ liệu';
  const [year, month, day] = value.split('-').map(Number);
  const date = new Date(year, month - 1, day);
  const weekday = new Intl.DateTimeFormat('vi-VN', {weekday: 'long'}).format(date);
  return `${weekday.charAt(0).toLocaleUpperCase('vi-VN')}${weekday.slice(1)}, ${String(day).padStart(2, '0')}/${String(month).padStart(2, '0')}/${year}`;
}

function durationText(detail, count) {
  const toMinutes = value => Number(String(value || '').slice(0, 2)) * 60 + Number(String(value || '').slice(3, 5));
  const minutes = Math.max(0, toMinutes(detail?.endTime) - toMinutes(detail?.startTime));
  const hours = Math.floor(minutes / 60);
  const remainder = minutes % 60;
  const duration = [hours ? `${hours} giờ` : '', remainder ? `${remainder} phút` : ''].filter(Boolean).join(' ') || 'Chưa có dữ liệu';
  return count > 1 ? `${duration} / sân • ${count} sân` : duration;
}

function ResultStep({done, current, label, number}) {
  return <div className={`payment-result-step${current ? ' is-current' : ''}`} aria-current={current ? 'step' : undefined}>
    <span className={`payment-result-step-marker${done ? ' is-done' : ''}`}>{done ? <img src={asset('check.svg')} alt=""/> : number}</span>
    <span>{label}</span>
  </div>;
}

function ResultFooter() {
  return <footer className="payment-result-footer">
    <span>© {new Date().getFullYear()} Sân cầu lông. Tất cả quyền được bảo lưu.</span>
    <div><a href="/contact#privacy-note">Chính sách bảo mật</a><a href="/contact#privacy-note">Điều khoản sử dụng</a></div>
  </footer>;
}

async function findBooking(session, preferredBookingId, attemptId) {
  if (preferredBookingId) return api(`/bookings/${encodeURIComponent(preferredBookingId)}`, session.accessToken);
  if (!attemptId) return null;

  // A verified VNPay return can still be resolved after sessionStorage was cleared.
  // Search only the authenticated customer's history; every detail endpoint enforces ownership.
  const firstPage = await api('/bookings?page=0&size=100', session.accessToken);
  const pages = [firstPage];
  for (let page = 1; page < Number(firstPage?.totalPages || 0); page += 1) {
    pages.push(await api(`/bookings?page=${page}&size=100`, session.accessToken));
  }
  const ids = pages.flatMap(page => page?.content || []).map(item => item.bookingId).filter(Boolean);
  for (let offset = 0; offset < ids.length; offset += 6) {
    const details = await Promise.all(ids.slice(offset, offset + 6).map(id =>
      api(`/bookings/${encodeURIComponent(id)}`, session.accessToken).catch(() => null)));
    const match = details.find(detail => detail?.payments?.some(payment => normalizedId(payment.paymentAttemptId) === normalizedId(attemptId)));
    if (match) return match;
  }
  return null;
}

export default function PaymentResult() {
  const [session, setSession] = useState(readSession);
  const [bookingCount, setBookingCount] = useState(0);
  const [bookingCountLoading, setBookingCountLoading] = useState(true);
  const [bookingCountError, setBookingCountError] = useState(null);
  const [state, setState] = useState('checking');
  const [booking, setBooking] = useState(null);
  const [payment, setPayment] = useState(null);
  const [notice, setNotice] = useState('Đang xác minh giao dịch với máy chủ…');
  const [retry, setRetry] = useState(0);

  useEffect(() => {
    if (!session?.accessToken) return undefined;
    let active = true;
    setBookingCountLoading(true);
    setBookingCountError(null);
    api('/bookings?page=0&size=1', session.accessToken)
      .then(page => { if (active) setBookingCount(Number(page?.totalElements) || 0); })
      .catch(error => { if (active) setBookingCountError(error); })
      .finally(() => { if (active) setBookingCountLoading(false); });
    return () => { active = false; };
  }, [session]);

  useEffect(() => {
    if (!session?.accessToken) return undefined;
    let active = true;
    let draft = null;
    try { draft = JSON.parse(sessionStorage.getItem(BOOKING_DRAFT_KEY)); } catch { /* Treat malformed local data as unavailable. */ }
    const params = new URLSearchParams(window.location.search);
    const hasProviderReturn = [...params.keys()].some(key => key.startsWith('vnp_'));
    const draftAttempt = draft?.paymentAttemptId;

    setState('checking');
    setBooking(null);
    setPayment(null);
    setNotice('Đang xác minh giao dịch với máy chủ…');

    (async () => {
      let providerResult = null;
      if (hasProviderReturn) {
        providerResult = await api(`/payments/vnpay/return?${params.toString()}`, null);
        if (draftAttempt && normalizedId(draftAttempt) !== normalizedId(providerResult?.attemptId)) {
          throw new Error('Thông tin giao dịch không khớp với lượt thanh toán đã bắt đầu.');
        }
      }
      const attemptId = providerResult?.attemptId || draftAttempt;
      const bookingId = draft?.bookingId;
      if (!attemptId && !bookingId) {
        if (active) {
          setState('unverified');
          setNotice('Không có thông tin giao dịch để đối chiếu. Hãy mở trang này từ luồng thanh toán hoặc kiểm tra lịch đặt của bạn.');
        }
        return;
      }

      const attempts = providerResult?.providerSuccess ? 6 : 1;
      let foundBooking = null;
      let foundPayment = null;
      for (let index = 0; index < attempts; index += 1) {
        foundBooking = await findBooking(session, bookingId, attemptId);
        foundPayment = foundBooking?.payments?.find(item => normalizedId(item.paymentAttemptId) === normalizedId(attemptId)) || null;
        if (!providerResult && !foundPayment && !bookingId) break;
        if (foundBooking?.status === 'PAID' && foundPayment?.paymentStatus === 'SUCCESS') break;
        if (index + 1 < attempts) await new Promise(resolve => window.setTimeout(resolve, 1200));
        if (!active) return;
      }
      if (!active) return;
      if (!foundBooking || !foundPayment) {
        setState('unverified');
        setNotice('Không tìm thấy giao dịch này trong lịch đặt của tài khoản. Vui lòng mở lịch đặt của tôi hoặc liên hệ hỗ trợ.');
        return;
      }
      if (providerResult && Number(foundPayment.amount) !== Number(providerResult.amount)) {
        setState('unverified');
        setNotice('Số tiền giao dịch chưa khớp với thông tin máy chủ lưu. Vui lòng liên hệ hỗ trợ để được kiểm tra.');
        return;
      }
      setBooking(foundBooking);
      setPayment(foundPayment);
      if (foundBooking.status === 'EXPIRED') {
        setState('expired');
        setNotice('Thời gian giữ sân đã hết hạn. Trạng thái thanh toán cần được bộ phận hỗ trợ kiểm tra.');
      } else if (foundBooking.status === 'PAID' && foundPayment.paymentStatus === 'SUCCESS') {
        setState('success');
        setNotice('Thanh toán thành công');
      } else if (foundPayment.paymentStatus === 'FAILED' || providerResult?.providerSuccess === false) {
        setState('failed');
        setNotice('Cổng thanh toán báo giao dịch chưa thành công. Booking chưa được xác nhận đã thanh toán.');
      } else {
        setState('pending');
        setNotice('Giao dịch đang được xác minh. Đừng thanh toán lại; hãy kiểm tra lịch đặt sau ít phút.');
      }
    })().catch(error => {
      if (!active) return;
      setState('unverified');
      setNotice(error.status === 401 || error.status === 403
        ? 'Phiên đăng nhập không còn hợp lệ. Hãy đăng nhập lại để xác minh lịch đặt của tài khoản.'
        : error.status === 404
          ? 'Không tìm thấy lịch đặt trong tài khoản này.'
          : error.message || 'Chưa xác minh được giao dịch. Vui lòng kiểm tra lịch đặt hoặc thử lại.');
    });
    return () => { active = false; };
  }, [retry, session]);

  function logout() {
    clearSession();
    setSession(null);
  }

  if (!session) return <div className="payment-result-page"><GuideHeader session={null} activePage="checkout"/><main className="payment-result-main"><section className="payment-result-state-card payment-result-signed-out"><div className="payment-result-signed-out-content"><span className="payment-result-notice-icon" aria-hidden="true">i</span><div className="payment-result-signed-out-copy"><h1>Đăng nhập để xem trạng thái thanh toán</h1><p>Trạng thái giao dịch được đối chiếu với tài khoản đã đặt sân. Hiện chưa thể xác minh giao dịch khi bạn chưa đăng nhập.</p></div></div><a className="payment-result-home-link" href="/courts">Về trang chủ</a></section><div className="payment-result-support">Cần hỗ trợ? <a href="tel:19001000">1900 1000</a></div></main><ResultFooter/></div>;

  const details = booking?.details || [];
  const firstDetail = details[0];
  const uniqueCourts = [...new Map(details.map(detail => [detail.courtId, detail])).values()];
  const uniqueSlots = [...new Map(details.map(detail => [`${detail.startTime}-${detail.endTime}`, detail])).values()];
  const courtLabel = uniqueCourts.map(detail => `Sân ${String(detail.courtNumber ?? '').padStart(2, '0')}`).join(' và ') || 'Không có chi tiết sân';
  const slotLabel = uniqueSlots.map(detail => `${timeText(detail.startTime)} – ${timeText(detail.endTime)}`).join(', ') || 'Không có dữ liệu';
  const isSuccess = state === 'success';
  const pageTitle = isSuccess ? 'Thanh toán thành công' : state === 'checking' ? 'Đang xác minh thanh toán' : state === 'failed' ? 'Thanh toán chưa thành công' : state === 'expired' ? 'Đặt sân đã hết hạn' : 'Chưa xác minh được thanh toán';

  return <div className="payment-result-page">
    <GuideHeader session={session} bookingCount={bookingCount} bookingError={bookingCountError} bookingLoading={bookingCountLoading} onLogout={logout} activePage="checkout"/>
    <main className="payment-result-main">
      <div className="payment-result-topline">
        <span aria-hidden="true" />
        <div className="payment-result-progress" aria-label="Tiến trình đặt sân">
          <ResultStep done label="Chọn sân"/><i/>
          <ResultStep done label="Xem lại"/><i/>
          <ResultStep current number="3" label="Thanh toán"/>
        </div>
      </div>

      <section className={`payment-result-hero${isSuccess ? '' : ' is-not-success'}`} aria-live="polite">
        <div className="payment-result-success-icon" aria-hidden="true"><img src={asset('check-selected.svg')} alt=""/></div>
        <div className="payment-result-message">
          <h1>{pageTitle}</h1>
          {isSuccess ? <p>Cảm ơn {session.user?.fullName?.trim().split(/\s+/)[0] || 'bạn'}! Lịch đặt sân của bạn đã được xác nhận.<br className="payment-result-desktop-break"/> Hẹn gặp bạn trên sân và chúc bạn có một buổi chơi thật vui!</p> : <p>{notice}</p>}
        </div>
      </section>

      {isSuccess && booking && payment && <>
        <section className="payment-result-summary" aria-label="Tóm tắt đặt sân">
          <div className="payment-result-summary-details">
            <div className="payment-result-court">
              <div className="payment-result-photo-wrap">
                <img src="/assets/checkout-court-star-city.png" alt="Sân cầu lông trong nhà" onError={event => {event.currentTarget.hidden = true; event.currentTarget.nextElementSibling.hidden = false;}}/>
                <span hidden aria-hidden="true">Sân cầu lông</span>
              </div>
              <div className="payment-result-court-copy">
                <h2>{uniqueCourts.map(detail => detail.courtName).filter(Boolean).filter((name, index, all) => all.indexOf(name) === index).join(' · ') || 'Sân cầu lông'}</h2>
                <p>Địa chỉ sân chưa được cung cấp trong dữ liệu đặt sân.</p>
                <strong>Đã xác nhận đặt sân</strong>
              </div>
            </div>
            <div className="payment-result-divider"/>
            <div className="payment-result-facts">
              <div><span>Ngày chơi</span><strong>{formatDate(firstDetail?.bookingDate)}</strong></div>
              <div><span>Khung giờ</span><strong>{slotLabel}</strong></div>
              <div><span>Sân cụ thể</span><strong>{courtLabel}</strong></div>
              <div><span>Thời lượng</span><strong>{durationText(firstDetail, uniqueCourts.length)}</strong></div>
            </div>
          </div>
          <div className="payment-result-total">
            <div><strong>Đã thanh toán toàn bộ</strong><span>{payment.paymentMethod === 'VNPAY' ? 'VNPay • Không phát sinh phí đặt sân' : `${payment.paymentMethod} • Không phát sinh phí đặt sân`}</span></div>
            <b>{formatMoney(payment.amount)}</b>
          </div>
        </section>

        <section className="payment-result-actions">
          <div className="payment-result-email">
            <span>Email tài khoản (chưa gửi tự động)</span>
            <strong>{session.user?.email || 'Chưa có email trong hồ sơ tài khoản'}</strong>
          </div>
          <div className="payment-result-buttons">
            <a className="payment-result-primary" href="/courts#upcoming-booking"><img src={asset('calendar-days.svg')} alt=""/> Xem lịch đặt sân</a>
            <a className="payment-result-secondary" href="/courts">Về trang chủ</a>
          </div>
          <p>Vui lòng đến trước 10 phút và mang giày thể thao đế mềm.</p>
        </section>
      </>}

      {state !== 'success' && <section className="payment-result-state-card">
        {booking?.bookingId && <p>Mã đặt sân: <strong>{booking.bookingId}</strong></p>}
        <div className="payment-result-state-actions">
          <button type="button" onClick={() => setRetry(value => value + 1)}>Kiểm tra lại</button>
          <a href="/courts#upcoming-booking">Xem lịch đặt sân</a>
        </div>
      </section>}

      <div className="payment-result-support">Cần hỗ trợ về lịch đặt? <a href="tel:19001000">1900 1000 • Hỗ trợ 24/7</a></div>
    </main>
    <ResultFooter/>
  </div>;
}
