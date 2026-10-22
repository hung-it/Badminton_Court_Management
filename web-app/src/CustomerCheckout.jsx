import React, {useEffect, useMemo, useRef, useState} from 'react';
import './customer-checkout.css';
import {api, readSession, clearSession, BOOKING_DRAFT_KEY} from './CustomerCourts.jsx';
import {GuideFooter, GuideHeader} from './CustomerLayout.jsx';

const asset = name => `/assets/checkout-${name}`;
const formatMoney = value => new Intl.NumberFormat('vi-VN', {style: 'currency', currency: 'VND', maximumFractionDigits: 0}).format(Number(value) || 0);
const timeText = value => String(value || '').slice(0, 5);

function formattedDate(value) {
  if (!value) return 'Chưa chọn ngày';
  const [year, month, day] = value.split('-').map(Number);
  const date = new Date(year, month - 1, day);
  const weekday = new Intl.DateTimeFormat('vi-VN', {weekday: 'long'}).format(date);
  return `${weekday.charAt(0).toLocaleUpperCase('vi-VN')}${weekday.slice(1)}, ${String(day).padStart(2, '0')}/${String(month).padStart(2, '0')}/${year}`;
}

function durationText(slot) {
  if (!slot?.startTime || !slot?.endTime) return 'Chưa có dữ liệu';
  const minutes = (time) => Number(String(time).slice(0, 2)) * 60 + Number(String(time).slice(3, 5));
  const total = Math.max(0, minutes(slot.endTime) - minutes(slot.startTime));
  const hours = Math.floor(total / 60);
  const rest = total % 60;
  return [hours ? `${hours} giờ` : '', rest ? `${rest} phút` : ''].filter(Boolean).join(' ') || '0 phút';
}

function Step({state, number, label}) {
  return <div className={`checkout-step checkout-step-${state}`} aria-current={state === 'current' ? 'step' : undefined}>
    <span className="checkout-step-marker">{state === 'done' ? <img src={asset('check.svg')} alt=""/> : number}</span>
    <span>{label}</span>
  </div>;
}

function CheckoutIcon({name, className = ''}) {
  return <img className={className} src={asset(`${name}.svg`)} alt="" aria-hidden="true"/>;
}

export default function CustomerCheckout() {
  const [session, setSession] = useState(readSession);
  const [draft, setDraft] = useState(() => {
    try { return JSON.parse(sessionStorage.getItem(BOOKING_DRAFT_KEY)); }
    catch { return null; }
  });
  const [court, setCourt] = useState(null);
  const [slot, setSlot] = useState(null);
  const [price, setPrice] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [submitError, setSubmitError] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [paymentMethod, setPaymentMethod] = useState('VNPAY');
  const [bookingCount, setBookingCount] = useState(0);
  const [bookingLoading, setBookingLoading] = useState(true);
  const [bookingError, setBookingError] = useState(null);
  const submitted = useRef(false);

  useEffect(() => {
    if (!session) {
      setBookingCount(0);
      setBookingLoading(false);
      setBookingError(null);
      return;
    }
    let active = true;
    setBookingLoading(true);
    setBookingError(null);
    api('/bookings?page=0&size=1', session.accessToken)
      .then(page => { if (active) setBookingCount(Number(page?.totalElements) || 0); })
      .catch(reason => { if (active) setBookingError({message: reason.message || 'Không tải được lịch đặt.'}); })
      .finally(() => { if (active) setBookingLoading(false); });
    return () => { active = false; };
  }, [session]);

  useEffect(() => {
    if (!session || !draft?.courtId || !draft?.timeSlotId || !draft?.bookingDate) {
      setLoading(false);
      return;
    }
    let active = true;
    setLoading(true);
    setError('');
    if (draft.bookingId) {
      api(`/bookings/${encodeURIComponent(draft.bookingId)}`, session.accessToken).then(booking => {
        if (!active) return;
        if (booking.status !== 'PENDING') throw new Error('Booking này không còn chờ thanh toán. Vui lòng kiểm tra lịch đặt của bạn.');
        const detail = booking.details?.find(item => item.courtId === draft.courtId
          && item.timeSlotId === draft.timeSlotId && item.bookingDate === draft.bookingDate);
        if (!detail || booking.details.length !== 1) throw new Error('Thông tin booking không khớp với lựa chọn. Vui lòng kiểm tra lịch đặt.');
        setCourt({id: detail.courtId, name: detail.courtName, courtNumber: detail.courtNumber});
        setSlot({id: detail.timeSlotId, startTime: detail.startTime, endTime: detail.endTime});
        setPrice(Number(booking.courtFee));
      }).catch(reason => { if (active) setError(reason.message); })
        .finally(() => { if (active) setLoading(false); });
      return () => { active = false; };
    }
    Promise.all([
      api('/public/courts', null),
      api('/public/time-slots', null),
      api(`/public/price-quote?courtId=${encodeURIComponent(draft.courtId)}&timeSlotId=${encodeURIComponent(draft.timeSlotId)}`, null),
      api(`/availability?date=${encodeURIComponent(draft.bookingDate)}`, session.accessToken),
    ]).then(([courtList, slotList, quote, availability]) => {
      if (!active) return;
      const selectedCourt = courtList.find(item => item.id === draft.courtId);
      const selectedSlot = slotList.find(item => item.id === draft.timeSlotId);
      const courtAvailability = availability?.courts?.find(item => item.courtId === draft.courtId);
      const slotAvailability = courtAvailability?.slots?.find(item => item.timeSlotId === draft.timeSlotId);
      if (!selectedCourt || !selectedSlot) throw new Error('Không còn tìm thấy sân hoặc khung giờ đã chọn. Vui lòng quay lại chọn lại.');
      if (!slotAvailability?.available) throw new Error('Khung giờ này hiện không còn trống. Vui lòng quay lại chọn khung giờ khác.');
      setCourt(selectedCourt);
      setSlot(selectedSlot);
      setPrice(quote?.price == null ? null : Number(quote.price));
    }).catch(reason => { if (active) setError(reason.message || 'Không tải được thông tin đặt sân.'); })
      .finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [draft?.bookingId, draft?.courtId, draft?.timeSlotId, draft?.bookingDate, session]);

  const duration = useMemo(() => durationText(slot), [slot]);
  const customer = session?.user || {};

  function logout() {
    clearSession();
    setSession(null);
  }

  async function continueToPayment() {
    setSubmitError('');
    if (!session) {
      setSubmitError('Vui lòng đăng nhập để tiếp tục.');
      return;
    }
    if (paymentMethod !== 'VNPAY') {
      setSubmitError('Hiện hệ thống chỉ hỗ trợ thanh toán đặt sân qua VNPAY.');
      return;
    }
    if (!price || !court || !slot || !draft) {
      setSubmitError('Thông tin giá hoặc khung giờ chưa sẵn sàng. Vui lòng thử tải lại trang.');
      return;
    }
    if (submitted.current || submitting) return;
    submitted.current = true;
    setSubmitting(true);
    try {
      let bookingId = draft.bookingId;
      if (!bookingId) {
        if (draft.bookingCreateStarted) {
          throw new Error('Yêu cầu đặt sân trước đó chưa nhận được xác nhận. Hãy kiểm tra Lịch đặt của tôi trước khi thử lại để tránh tạo booking trùng.');
        }
        const {customerId} = await api('/bookings/customer-profile', session.accessToken);
        if (!customerId) throw new Error('Không tìm thấy hồ sơ khách hàng để đặt sân.');
        const startedDraft = {...draft, bookingCreateStarted: true};
        sessionStorage.setItem(BOOKING_DRAFT_KEY, JSON.stringify(startedDraft));
        setDraft(startedDraft);
        try {
          const booking = await api('/bookings', session.accessToken, {
            method: 'POST',
            body: JSON.stringify({customerId, details: [{bookingDate: draft.bookingDate, courtId: draft.courtId, timeSlotId: draft.timeSlotId}]}),
          });
          bookingId = booking.bookingId;
          const createdDraft = {...startedDraft, bookingId};
          sessionStorage.setItem(BOOKING_DRAFT_KEY, JSON.stringify(createdDraft));
          setDraft(createdDraft);
        } catch (reason) {
          if (reason.status && reason.status < 500) {
            const resetDraft = {...startedDraft, bookingCreateStarted: false};
            sessionStorage.setItem(BOOKING_DRAFT_KEY, JSON.stringify(resetDraft));
            setDraft(resetDraft);
          }
          throw reason;
        }
      }
      const payment = await api(`/bookings/${encodeURIComponent(bookingId)}/payments`, session.accessToken, {
        method: 'POST', body: JSON.stringify({paymentMethod}),
      });
      if (payment?.gatewayPreparation?.checkoutReady && payment.gatewayPreparation.checkoutUrl) {
        const paymentDraft = {...draft, bookingId, paymentAttemptId: payment.paymentAttemptId};
        sessionStorage.setItem(BOOKING_DRAFT_KEY, JSON.stringify(paymentDraft));
        setDraft(paymentDraft);
        window.location.assign(payment.gatewayPreparation.checkoutUrl);
        return;
      }
      throw new Error(payment?.gatewayPreparation?.checkoutBlocker || 'VNPay Sandbox chưa sẵn sàng. Booking vẫn chưa được xác nhận thanh toán.');
    } catch (reason) {
      setSubmitError(reason.message || 'Không thể khởi tạo thanh toán. Trạng thái thanh toán chưa được xác nhận.');
      submitted.current = false;
    } finally {
      setSubmitting(false);
    }
  }

  if (!session) {
    return <div className="checkout-page"><GuideHeader session={null} activePage="checkout"/><main className="checkout-empty"><h1>Đăng nhập để tiếp tục đặt sân</h1><p>Việc xác nhận đặt sân và thanh toán yêu cầu tài khoản khách hàng.</p><a className="checkout-primary-link" href="/login?redirect=%2Fcheckout">Đăng nhập</a></main><GuideFooter activePage="courts"/></div>;
  }

  if (!draft?.courtId || !draft?.timeSlotId || !draft?.bookingDate) {
    return <div className="checkout-page"><GuideHeader session={session} bookingCount={bookingCount} bookingError={bookingError} bookingLoading={bookingLoading} onLogout={logout} activePage="checkout"/><main className="checkout-empty"><h1>Chưa có sân để xác nhận</h1><p>Hãy chọn sân, ngày và khung giờ còn trống trước khi xem lại đặt sân.</p><a className="checkout-primary-link" href="/courts#search-courts">Tìm sân cầu lông</a></main><GuideFooter activePage="courts"/></div>;
  }

  const courtName = court?.name || draft.courtName || 'Sân đã chọn';
  const courtNumber = court?.courtNumber ?? draft.courtNumber;
  const quotePrice = price ?? (Number(draft.price) || 0);

  return <div className="checkout-page">
    <GuideHeader session={session} bookingCount={bookingCount} bookingError={bookingError} bookingLoading={bookingLoading} onLogout={logout} activePage="checkout"/>
    <main className="checkout-main">
      <a className="checkout-back" href="/courts#search-courts"><CheckoutIcon name="arrow-left"/> Quay lại chỉnh sửa đặt sân</a>
      <div className="checkout-heading-row">
        <div className="checkout-heading"><h1>Xác nhận đặt sân</h1><p>Kiểm tra lịch chơi và chi phí trước khi thanh toán.</p></div>
        <div className="checkout-progress" aria-label="Tiến trình đặt sân">
          <Step state="done" number="1" label="Chọn sân"/><i className="done"/>
          <Step state="current" number="2" label="Xem lại"/><i/><Step state="next" number="3" label="Thanh toán"/>
        </div>
      </div>

      {(error || loading) && <div className={error ? 'checkout-alert checkout-alert-error' : 'checkout-alert'} role={error ? 'alert' : 'status'}>{error || 'Đang kiểm tra sân, khung giờ và giá hiện tại…'}</div>}

      <div className="checkout-columns">
        <div className="checkout-details-column">
          <section className="checkout-card checkout-court-card">
            <div className="checkout-card-heading"><h2>Thông tin sân đặt</h2><a href="/courts#search-courts"><CheckoutIcon name="pencil"/> Chỉnh sửa</a></div>
            <div className="checkout-court-summary">
              <img className="checkout-court-photo" src="/assets/checkout-court-star-city.png" alt="Sân cầu lông trong nhà" onError={event => {event.currentTarget.hidden = true;}}/>
              <div className="checkout-court-copy">
                <span className="checkout-region">KHU VỰC CHƯA CÓ DỮ LIỆU</span>
                <h3>{courtName}</h3>
                <p className="checkout-address"><CheckoutIcon name="map-pin"/> Địa chỉ chưa được cung cấp bởi dữ liệu sân.</p>
                <div className="checkout-tags"><span>{court?.type === 'WOODEN_FLOOR' ? 'Sàn gỗ' : court?.type ? 'Thảm tiêu chuẩn' : 'Loại sân chưa có dữ liệu'}</span></div>
              </div>
            </div>
            <div className="checkout-divider"/>
            <div className="checkout-booking-facts">
              <div className="checkout-fact"><span className="checkout-fact-icon"><CheckoutIcon name="calendar-days"/></span><div><small>Ngày chơi</small><strong>{formattedDate(draft.bookingDate)}</strong></div></div>
              <div className="checkout-fact"><span className="checkout-fact-icon"><CheckoutIcon name="clock-3"/></span><div><small>Khung giờ</small><strong>{slot ? `${timeText(slot.startTime)} – ${timeText(slot.endTime)}` : 'Đang tải…'}</strong></div></div>
              <div className="checkout-fact"><span className="checkout-fact-icon"><CheckoutIcon name="layout-grid"/></span><div><small>Sân cụ thể</small><strong>{courtNumber != null ? `Sân ${String(courtNumber).padStart(2, '0')}` : 'Chưa có dữ liệu'}</strong></div></div>
              <div className="checkout-fact"><span className="checkout-fact-icon"><CheckoutIcon name="timer"/></span><div><small>Thời lượng</small><strong>{duration}</strong></div></div>
            </div>
          </section>

          <section className="checkout-card checkout-customer-card">
            <div className="checkout-card-heading"><h2>Thông tin người đặt</h2><button className="checkout-edit-disabled" type="button" disabled title="Ứng dụng chưa có chức năng chỉnh sửa hồ sơ."><CheckoutIcon name="pencil"/> Chỉnh sửa</button></div>
            <div className="checkout-customer-fields">
              <div><small>Họ và tên</small><strong>{customer.fullName || 'Chưa cập nhật'}</strong></div>
              <div><small>Số điện thoại</small><strong>{customer.phone || 'Chưa cập nhật'}</strong></div>
              <div><small>Email nhận xác nhận</small><strong>{customer.email || 'Chưa cập nhật'}</strong></div>
            </div>
            <div className="checkout-note"><span>Ghi chú:</span> API đặt sân hiện không nhận ghi chú riêng cho sân.</div>
          </section>

            <div className="checkout-arrival-note"><CheckoutIcon name="info"/><div><strong>Sẵn sàng cho buổi chơi của bạn</strong><p>Vui lòng đến trước 10 phút và mang giày thể thao đế mềm. Kiểm tra kết quả thanh toán trong lịch đặt; hệ thống hiện chưa gửi email tự động.</p></div></div>
        </div>

        <aside className="checkout-invoice-column">
          <section className="checkout-card checkout-invoice-card">
            <div className="checkout-invoice-accent"/>
            <div className="checkout-invoice-content">
              <div className="checkout-invoice-heading"><div><CheckoutIcon name="receipt-text"/><h2>Chi tiết hóa đơn</h2></div><span className="checkout-payment-status"><CheckoutIcon name="clock-4"/> Chưa thanh toán</span><p>{courtNumber != null ? '1 sân' : 'Sân đã chọn'} • {duration} • {formattedDate(draft.bookingDate)}</p></div>
              <div className="checkout-divider"/>
              <div className="checkout-price-table">
                <div className="checkout-price-head"><span>CHI PHÍ THUÊ SÂN</span><span>THÀNH TIỀN</span></div>
                <div className="checkout-price-item"><div><strong>{courtNumber != null ? `Sân ${String(courtNumber).padStart(2, '0')}` : courtName}</strong><small>{price == null ? 'Đang lấy báo giá từ máy chủ…' : `Giá theo khung giờ · ${duration}`}</small></div><strong>{price == null ? '—' : formatMoney(price)}</strong></div>
              </div>
              <div className="checkout-divider"/>
              <div className="checkout-totals"><div><span>Tạm tính</span><strong>{price == null ? '—' : formatMoney(price)}</strong></div><div><span>Phí đặt sân</span><strong>{formatMoney(0)}</strong></div></div>
              <div className="checkout-grand-total"><strong>Tổng thanh toán</strong><b>{price == null ? '—' : formatMoney(quotePrice)}</b><small>Số tiền được xác nhận lại từ máy chủ khi tạo booking.</small></div>

              <fieldset className="checkout-methods" disabled={loading || Boolean(error)}>
                <legend>Phương thức thanh toán</legend>
                <label className={`checkout-method ${paymentMethod === 'VNPAY' ? 'selected' : ''}`}>
                  <span><strong>VNPAY</strong><small>Thanh toán qua cổng VNPay Sandbox</small></span><input type="radio" name="paymentMethod" value="VNPAY" checked={paymentMethod === 'VNPAY'} onChange={() => setPaymentMethod('VNPAY')}/>
                </label>
                <label className="checkout-method unavailable"><span><strong>Chuyển khoản ngân hàng</strong><small>Chưa được hỗ trợ cho đặt sân trực tuyến</small></span><input type="radio" disabled aria-label="Chuyển khoản ngân hàng chưa được hỗ trợ"/></label>
                <label className="checkout-method unavailable"><span><strong>Tiền mặt</strong><small>Chưa được hỗ trợ cho đặt sân trực tuyến</small></span><input type="radio" disabled aria-label="Tiền mặt chưa được hỗ trợ"/></label>
              </fieldset>

              <div className="checkout-payment-action"><p>Bằng việc tiếp tục, bạn đồng ý với điều khoản sử dụng và chính sách hủy của sân.</p>{submitError && <div className="checkout-submit-error" role="alert">{submitError}</div>}<button type="button" disabled={loading || Boolean(error) || !price || submitting} onClick={continueToPayment}>{submitting ? 'Đang khởi tạo…' : 'Thanh toán'} <CheckoutIcon name="arrow-right"/></button><div className="checkout-security"><CheckoutIcon name="shield-check"/> Thanh toán an toàn, thông tin được bảo mật</div></div>
            </div>
          </section>
          <div className="checkout-support"><CheckoutIcon name="headset"/><div><span>Cần hỗ trợ về lịch đặt?</span><strong>1900 1000 • Hỗ trợ 24/7</strong></div></div>
        </aside>
      </div>
    </main>
    <footer className="checkout-footer"><span>© 2026 Sân cầu lông. Tất cả quyền được bảo lưu.</span><div><a href="/contact#privacy-note">Chính sách bảo mật</a><a href="/guide">Điều khoản sử dụng</a></div></footer>
  </div>;
}
