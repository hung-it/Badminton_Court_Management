import React, {useEffect, useRef, useState} from 'react';
import './payment-result.css';
import {api, readSession, clearSession, BOOKING_DRAFT_KEY} from './CustomerCourts.jsx';
import {GuideHeader} from './CustomerLayout.jsx';
import {courtImageSource, COURT_FALLBACK_IMAGE} from './lib/courtImages';
import {sortBookingDetails, bookingCourtGroups, bookingTimeLabel, bookingDuration, courtLabel as labelCourt, compareCourtNumbers, surfaceLabel} from './lib/bookingPresentation';

const asset = name => `/assets/checkout-${name}`;
const formatMoney = value => `${new Intl.NumberFormat('vi-VN', {maximumFractionDigits: 0}).format(Number(value) || 0)}₫`;
const normalizedId = value => String(value || '').replaceAll('-', '').toLowerCase();

function formatDate(value) {
  if (!value) return 'Chưa có dữ liệu';
  const [year, month, day] = value.split('-').map(Number);
  const date = new Date(year, month - 1, day);
  const weekday = new Intl.DateTimeFormat('vi-VN', {weekday: 'long'}).format(date);
  return `${weekday.charAt(0).toLocaleUpperCase('vi-VN')}${weekday.slice(1)}, ${String(day).padStart(2, '0')}/${String(month).padStart(2, '0')}/${year}`;
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
  const [imageCatalog, setImageCatalog] = useState([]);
  const [payment, setPayment] = useState(null);
  const [notice, setNotice] = useState('Đang xác minh giao dịch với máy chủ…');
  const [retry, setRetry] = useState(0);
  const [paying, setPaying] = useState(false);
  const paymentStarted = useRef(false);

  useEffect(() => {
    let active = true;
    api('/public/courts', null).then(catalog => { if (active) setImageCatalog(catalog); }).catch(() => {});
    return () => { active = false; };
  }, []);

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
        params.set('displayOnly', 'true');
        providerResult = await api(`/payments/vnpay/return?${params.toString()}`, null);
      }
      const explicitBookingId = params.get('bookingId');
      const attemptId = providerResult?.attemptId || params.get('paymentAttemptId')
        || (explicitBookingId && explicitBookingId !== draft?.bookingId ? null : draftAttempt);
      const bookingId = explicitBookingId || (providerResult?.attemptId && normalizedId(draftAttempt) !== normalizedId(providerResult.attemptId) ? null : draft?.bookingId);
      if (!attemptId && !bookingId) {
        if (active) {
          setState('unverified');
          setNotice('Không có thông tin giao dịch để đối chiếu. Hãy mở trang này từ luồng thanh toán hoặc kiểm tra lịch đặt của bạn.');
        }
        return;
      }

      const attempts = 6;
      let foundBooking = null;
      let foundPayment = null;
      for (let index = 0; index < attempts; index += 1) {
        foundBooking = await findBooking(session, bookingId, attemptId);
        foundPayment = foundBooking?.payments?.find(item => normalizedId(item.paymentAttemptId) === normalizedId(attemptId)) || (!attemptId ? foundBooking?.payments?.find(item => item.paymentStatus === 'SUCCESS') || foundBooking?.payments?.find(item => item.confirmationMethod === 'CUSTOMER_DECLARED') : null) || null;
        if (!providerResult && !foundPayment && !bookingId) break;
        if (foundBooking?.status === 'EXPIRED' || foundPayment?.paymentStatus === 'SUCCESS'
          || ['FAILED', 'REFUNDED'].includes(foundPayment?.paymentStatus) || foundPayment?.paymentMethod === 'BANK_TRANSFER') break;
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
      } else if (['PAID', 'CHECKED_IN', 'COMPLETED', 'NO_SHOW'].includes(foundBooking.status) && (foundPayment.paymentStatus === 'SUCCESS' || (foundPayment.paymentStatus === 'PENDING' && foundPayment.confirmationMethod === 'CUSTOMER_DECLARED'))) {
        setState('success');
        setNotice(foundPayment.paymentStatus === 'SUCCESS' ? 'Thanh toán thành công' : 'Thanh toán chờ xác nhận');
      } else if (foundPayment.paymentStatus === 'REFUNDED') {
        setState('refunded');
        setNotice('Máy chủ ghi nhận giao dịch đã được hoàn tiền. Vui lòng kiểm tra lịch đặt hoặc liên hệ hỗ trợ.');
      } else if (foundPayment.paymentStatus === 'FAILED') {
        setState('failed');
        setNotice('Cổng thanh toán báo giao dịch chưa thành công. Booking chưa được xác nhận đã thanh toán.');
      } else if (foundPayment.paymentMethod === 'BANK_TRANSFER' && foundPayment.paymentStatus === 'PENDING') {
        setState('bank-pending');
        setNotice('Thông tin đặt sân của bạn đã được lưu. Thanh toán đang chờ xác nhận. Yêu cầu chưa đối soát sẽ hết hạn theo thời gian giữ sân; vui lòng liên hệ hỗ trợ nếu đã chuyển tiền.');
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

  async function retryPayment() {
    if (paymentStarted.current || !booking || payment?.paymentMethod !== 'VNPAY') return;
    paymentStarted.current = true;
    setPaying(true);
    try {
      const attempt = await api(`/bookings/${encodeURIComponent(booking.bookingId)}/payments`, session.accessToken,
        {method: 'POST', body: JSON.stringify({paymentMethod: 'VNPAY'})});
      if (!attempt.gatewayPreparation?.checkoutReady || !attempt.gatewayPreparation.checkoutUrl) throw new Error('Cổng thanh toán chưa sẵn sàng.');
      let draft = {};
      try { draft = JSON.parse(sessionStorage.getItem(BOOKING_DRAFT_KEY)) || {}; } catch { /* Result can resume from URL alone. */ }
      sessionStorage.setItem(BOOKING_DRAFT_KEY, JSON.stringify({...draft, bookingId: booking.bookingId, paymentAttemptId: attempt.paymentAttemptId}));
      window.location.assign(attempt.gatewayPreparation.checkoutUrl);
    } catch (error) {
      setNotice(error.message || 'Không thể thử lại thanh toán.');
      paymentStarted.current = false;
      setPaying(false);
    }
  }

  if (!session) return <div className="payment-result-page"><GuideHeader session={null} activePage="checkout"/><main className="payment-result-main"><section className="payment-result-state-card payment-result-signed-out"><div className="payment-result-signed-out-content"><span className="payment-result-notice-icon" aria-hidden="true">i</span><div className="payment-result-signed-out-copy"><h1>Đăng nhập để xem trạng thái thanh toán</h1><p>Trạng thái giao dịch được đối chiếu với tài khoản đã đặt sân. Hiện chưa thể xác minh giao dịch khi bạn chưa đăng nhập.</p></div></div><a className="payment-result-home-link" href="/courts">Về trang chủ</a></section><div className="payment-result-support">Cần hỗ trợ? <a href="tel:19001000">1900 1000</a></div></main><ResultFooter/></div>;

  const details = sortBookingDetails(booking?.details);
  const firstDetail = details[0];
  const groups = bookingCourtGroups(details);
  const uniqueCourts = groups.map(group => group.details[0]);
  const courtLabel = [...uniqueCourts].sort(compareCourtNumbers).map(labelCourt).join(', ') || 'Không có chi tiết sân';
  const slotLabel = bookingTimeLabel(details) || 'Không có dữ liệu';
  const isSuccess = state === 'success';
  const declared = payment?.confirmationMethod === 'CUSTOMER_DECLARED' && payment?.paymentStatus !== 'SUCCESS';
  const pageTitle = isSuccess ? (declared ? 'Đã xác nhận đặt sân' : 'Thanh toán thành công') : state === 'bank-pending' ? 'Đã ghi nhận yêu cầu chuyển khoản' : state === 'refunded' ? 'Giao dịch đã được hoàn tiền' : state === 'checking' || state === 'pending' ? 'Đang xác minh kết quả thanh toán' : state === 'failed' ? 'Thanh toán không thành công' : state === 'expired' ? 'Đơn đặt sân đã hết thời gian thanh toán' : 'Chưa xác minh được thanh toán';

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

      {booking && payment && <>
        <section className="payment-result-summary" aria-label="Tóm tắt đặt sân">
          <div className="payment-result-summary-details">
            <div className="payment-result-court">
              <div className="payment-result-photo-wrap">
                {firstDetail && courtImageSource(firstDetail, imageCatalog) && <img src={courtImageSource(firstDetail, imageCatalog)} alt={firstDetail.courtName || 'Sân đã chọn'} onError={event => {if (event.currentTarget.getAttribute('src') !== COURT_FALLBACK_IMAGE) event.currentTarget.src = COURT_FALLBACK_IMAGE; else {event.currentTarget.hidden = true; event.currentTarget.nextElementSibling.hidden = false;}}}/>}
                <span hidden={Boolean(firstDetail && courtImageSource(firstDetail, imageCatalog))}>Chưa có ảnh sân</span>
              </div>
              <div className="payment-result-court-copy">
                <h2>{firstDetail?.branchName || uniqueCourts.map(detail => detail.courtName).filter(Boolean).filter((name, index, all) => all.indexOf(name) === index).join(' · ') || 'Sân cầu lông'}</h2>
                <p>{[...new Set(uniqueCourts.map(detail => detail.address).filter(Boolean))].join(' · ') || 'Chưa có địa chỉ sân'}</p>
                <p>{uniqueCourts.map(detail => `${labelCourt(detail)} · ${surfaceLabel(detail.type)}`).join('; ')}</p>
                <strong>{isSuccess ? declared ? 'Đã xác nhận đặt sân' : 'Đặt sân thành công' : 'Chưa xác nhận thanh toán'}</strong>
              </div>
            </div>
            <div className="payment-result-divider"/>
            <div className="payment-result-facts">
              <div><span>Ngày chơi</span><strong>{[...new Set(details.map(detail => detail.bookingDate))].map(formatDate).join('; ')}</strong></div>
              <div><span>Khung giờ</span><strong>{slotLabel}</strong></div>
              <div><span>Sân cụ thể</span><strong>{courtLabel}</strong></div>
              <div><span>{uniqueCourts.length > 1 ? 'Tổng thời lượng sân đặt' : 'Thời lượng'}</span><strong>{bookingDuration(details)}{uniqueCourts.length > 1 ? ' (giờ-sân)' : ''}</strong></div>
            </div>
          </div>
          <div className="payment-result-total">
            <div><strong>Tổng tiền đặt sân</strong><span>{payment.paymentMethod === 'BANK_TRANSFER' ? 'Chuyển khoản ngân hàng' : 'VNPay'}</span><small>Mã đặt sân: {booking.bookingId}</small>{payment.transactionDate && <small>Thời điểm giao dịch: {payment.transactionDate}</small>}</div>
            <b>{formatMoney(payment.amount)}</b>
          </div>
        </section>

        <section className="payment-result-actions">
          <div className="payment-result-email">
            <span>Email tài khoản: <strong>{session.user?.email || 'Chưa có email trong hồ sơ tài khoản'}</strong></span>
          </div>
          <div className="payment-result-buttons">
            <a className="payment-result-primary" href="/my-bookings"><img src={asset('calendar-days.svg')} alt=""/> Xem lịch đặt sân</a>
            <a className="payment-result-secondary" href="/courts">Về trang chủ</a>
          </div>
          <p>Vui lòng đến trước 10 phút và mang giày thể thao đế mềm.</p>
        </section>
      </>}

      {state !== 'success' && <section className="payment-result-state-card">
        {booking?.bookingId && <p>Mã đặt sân: <strong>{booking.bookingId}</strong></p>}
        <div className="payment-result-state-actions">
          {state === 'failed' && booking?.status === 'PENDING' && payment?.paymentMethod === 'VNPAY' && <button type="button" disabled={paying} onClick={retryPayment}>{paying ? 'Đang khởi tạo…' : 'Thử lại thanh toán'}</button>}
          <button type="button" onClick={() => setRetry(value => value + 1)}>Kiểm tra lại</button>
          <a href="/my-bookings">Xem lịch đặt sân</a>
        </div>
      </section>}

      <div className="payment-result-support">Cần hỗ trợ về lịch đặt? <a href="tel:19001000">1900 1000 • Hỗ trợ 24/7</a></div>
    </main>
    <ResultFooter/>
  </div>;
}
