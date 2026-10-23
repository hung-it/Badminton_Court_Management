import React, {useEffect, useMemo, useRef, useState} from 'react';
import './customer-checkout.css';
import {api, readSession, clearSession, BOOKING_DRAFT_KEY} from './CustomerCourts.jsx';
import {GuideFooter, GuideHeader} from './CustomerLayout.jsx';
import {useBookingClock} from './lib/useBookingClock';
import {isFutureSlot} from './lib/bookingTime';

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
  const slots = Array.isArray(slot) ? slot : [slot];
  if (!slots.length || slots.some(item => !item?.startTime || !item?.endTime)) return 'Chưa có dữ liệu';
  const minutes = (time) => Number(String(time).slice(0, 2)) * 60 + Number(String(time).slice(3, 5));
  const total = slots.reduce((sum, item) => sum + Math.max(0, minutes(item.endTime) - minutes(item.startTime)), 0);
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
  const bookingClock = useBookingClock();
  const [session, setSession] = useState(readSession);
  const [draft, setDraft] = useState(() => {
    let saved = null;
    try { saved = JSON.parse(sessionStorage.getItem(BOOKING_DRAFT_KEY)); } catch { /* Recover an owned hold from its URL. */ }
    const bookingId = new URLSearchParams(window.location.search).get('bookingId');
    return bookingId && bookingId !== saved?.bookingId ? {bookingId} : saved;
  });
  const [court, setCourt] = useState(null);
  const [slot, setSlot] = useState(null);
  const [selectedDetails, setSelectedDetails] = useState([]);
  const [price, setPrice] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [submitError, setSubmitError] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [paymentMethod, setPaymentMethod] = useState('VNPAY');
  const [bankTransfer, setBankTransfer] = useState({bankName: '', accountHolder: '', accountNumber: '', transferContent: ''});
  const [bankTransferErrors, setBankTransferErrors] = useState({});
  const [bookingCount, setBookingCount] = useState(0);
  const [bookingLoading, setBookingLoading] = useState(true);
  const [bookingError, setBookingError] = useState(null);
  const submitted = useRef(false);
  const holdCreating = useRef(false);
  const [remaining, setRemaining] = useState(null);
  const [expired, setExpired] = useState(false);
  const [note, setNote] = useState('');
  const [savedNote, setSavedNote] = useState('');
  const [noteLoaded, setNoteLoaded] = useState(false);
  const [noteStatus, setNoteStatus] = useState('');
  const [noteSaving, setNoteSaving] = useState(false);
  const [paymentAttempts, setPaymentAttempts] = useState(0);
  const [editing, setEditing] = useState(false);
  const [editDate, setEditDate] = useState('');
  const [editSlotIds, setEditSlotIds] = useState([]);
  const [editOptions, setEditOptions] = useState([]);
  const [editOptionsDate, setEditOptionsDate] = useState('');
  const [editLoading, setEditLoading] = useState(false);
  const [editSaving, setEditSaving] = useState(false);
  const [editError, setEditError] = useState('');
  const [bookingSyncRequired, setBookingSyncRequired] = useState(false);
  const mutationStarted = useRef(false);
  const noteDirty = note !== savedNote;
  const visibleEditOptions = editOptions.filter(option => isFutureSlot(editDate, option.startTime, bookingClock.nowMs));
  const visibleEditKey = visibleEditOptions.map(option => option.timeSlotId).join('|');
  const editSelectionValid = editOptionsDate === editDate && editSlotIds.length > 0 && editSlotIds.every(id => visibleEditOptions.some(option => option.timeSlotId === id && option.available));
  const expiryMessage = 'Thời gian giữ sân 10 phút đã hết. Đơn đặt sân của bạn đã hết hạn và không thể tiếp tục thanh toán. Vui lòng quay về trang chủ để đặt sân lại.';

  useEffect(() => {
    if (!editing || !bookingClock.ready || editLoading || editOptionsDate !== editDate) return;
    const valid = new Set(visibleEditKey ? visibleEditKey.split('|') : []);
    setEditSlotIds(current => {
      const next = current.filter(id => valid.has(id));
      return current.length === next.length ? current : next;
    });
    if (editDate && editDate < bookingClock.today) { setEditDate(bookingClock.today); setEditSlotIds([]); }
  }, [editing, bookingClock.ready, bookingClock.today, visibleEditKey, editDate, editLoading, editOptionsDate]);

  function applyBooking(booking) {
    const first = booking.details?.[0];
    if (!first) return;
    setSelectedDetails(booking.details);
    setCourt({id: first.courtId, name: first.courtName, courtNumber: first.courtNumber});
    setSlot({id: first.timeSlotId, startTime: first.startTime, endTime: first.endTime});
    setPrice(Number(booking.courtFee));
    setPaymentAttempts(booking.payments?.length || 0);
    setDraft(current => {
      const latest = {...current, bookingId: booking.bookingId, courtId: first.courtId,
        timeSlotId: first.timeSlotId, bookingDate: first.bookingDate, details: booking.details};
      sessionStorage.setItem(BOOKING_DRAFT_KEY, JSON.stringify(latest));
      return latest;
    });
  }

  useEffect(() => {
    if (!session || !draft?.bookingId) return;
    let active = true;
    setNoteLoaded(false);
    api(`/bookings/${encodeURIComponent(draft.bookingId)}`, session.accessToken).then(booking => {
      if (!active) return;
      const persisted = booking.customerNote || '';
      let pending = null;
      try { pending = JSON.parse(sessionStorage.getItem(`bcm-booking-note:${booking.bookingId}`)); } catch { /* Ignore malformed drafts. */ }
      setSavedNote(persisted);
      setNote(typeof pending?.text === 'string' ? pending.text.slice(0, 1000) : persisted);
      setNoteStatus(pending?.text !== undefined && pending.text !== persisted ? 'Có ghi chú chưa lưu từ lần trước.' : 'Đã đồng bộ với máy chủ.');
      setPaymentAttempts(booking.payments?.length || 0);
      setNoteLoaded(true);
    }).catch(reason => { if (active) setNoteStatus(`Không tải được ghi chú: ${reason.message}. Vui lòng tải lại trang.`); });
    return () => { active = false; };
  }, [draft?.bookingId, session]);

  useEffect(() => {
    if (!editing || !editDate || !court?.id) return;
    let active = true;
    setEditLoading(true);
    setEditError('');
    api(`/availability?date=${encodeURIComponent(editDate)}&courtId=${encodeURIComponent(court.id)}`, session.accessToken)
      .then(async grid => {
        const options = grid.courts?.[0]?.slots || [];
        const withPrices = await Promise.all(options.map(async option => {
          const original = selectedDetails.find(detail => detail.bookingDate === editDate && detail.timeSlotId === option.timeSlotId);
          const quote = original ? {price: original.price} : await api(`/public/price-quote?courtId=${encodeURIComponent(court.id)}&timeSlotId=${encodeURIComponent(option.timeSlotId)}`, null);
          return {...option, available: option.available || Boolean(original), price: Number(quote.price)};
        }));
        if (active) { setEditOptions(withPrices); setEditOptionsDate(editDate); }
      }).catch(reason => { if (active) { setEditOptions([]); setEditError(reason.message || 'Không tải được khung giờ.'); } })
      .finally(() => { if (active) setEditLoading(false); });
    return () => { active = false; };
  }, [editing, editDate, court?.id, session, selectedDetails]);

  function showMutationError(reason) {
    if (reason.message?.includes('BOOKING_EXPIRED')) setExpired(true);
    return reason.message?.includes('PAYMENT_ATTEMPT_EXISTS')
      ? 'Đã khởi tạo giao dịch thanh toán; không thể đổi ngày hoặc khung giờ của đơn này.'
      : reason.status === 409 && !reason.message?.includes('BOOKING_EXPIRED')
        ? 'Không thể lưu thay đổi. Khung giờ có thể đã được đặt hoặc đơn không còn được phép chỉnh sửa. Vui lòng kiểm tra lại.'
        : reason.message || 'Lưu thất bại. Nội dung của bạn vẫn được giữ lại để thử lại.';
  }

  async function saveNote() {
    if (mutationStarted.current || submitted.current || !noteLoaded || expired || !draft?.bookingId) return;
    mutationStarted.current = true;
    setNoteSaving(true); setNoteStatus('Đang lưu…');
    try {
      const booking = await api(`/bookings/${encodeURIComponent(draft.bookingId)}/notes`, session.accessToken,
        {method: 'PATCH', body: JSON.stringify({note})});
      setNote(booking.customerNote || ''); setSavedNote(booking.customerNote || '');
      sessionStorage.removeItem(`bcm-booking-note:${draft.bookingId}`);
      setNoteStatus('Đã lưu ghi chú.');
    } catch (reason) { setNoteStatus(`Lưu thất bại: ${showMutationError(reason)}`); }
    finally { mutationStarted.current = false; setNoteSaving(false); }
  }

  async function saveDetails() {
    if (mutationStarted.current || submitted.current || expired || !editSelectionValid || !bookingClock.ready || editLoading) return;
    mutationStarted.current = true; setEditSaving(true); setEditError(''); setBookingSyncRequired(true);
    try {
      const booking = await api(`/bookings/${encodeURIComponent(draft.bookingId)}/details`, session.accessToken,
        {method: 'PATCH', body: JSON.stringify({bookingDate: editDate, timeSlotIds: editSlotIds})});
      applyBooking(booking); setEditing(false); setBookingSyncRequired(false);
      setSubmitError('');
    } catch (reason) {
      setEditError(showMutationError(reason));
      // A lost PATCH response may follow a successful commit. Reconcile before allowing payment.
      try {
        const latest = await api(`/bookings/${encodeURIComponent(draft.bookingId)}`, session.accessToken);
        applyBooking(latest); setBookingSyncRequired(false);
      } catch { setSubmitError('Chưa đồng bộ được đơn sau chỉnh sửa. Vui lòng tải lại trang trước khi thanh toán.'); }
    }
    finally { mutationStarted.current = false; setEditSaving(false); }
  }

  useEffect(() => {
    if (!session || !draft?.bookingId) return;
    window.history.replaceState(null, '', `/checkout?bookingId=${encodeURIComponent(draft.bookingId)}`);
    if (draft.courtId && draft.timeSlotId && draft.bookingDate) return;
    let active = true;
    api(`/bookings/${encodeURIComponent(draft.bookingId)}`, session.accessToken).then(booking => {
      if (!active) return;
      if (booking.status === 'EXPIRED') { setExpired(true); setLoading(false); return; }
      if (booking.status !== 'PENDING') {
        window.location.assign(`/payment-result?bookingId=${encodeURIComponent(draft.bookingId)}`);
        return;
      }
      const first = booking.details?.[0];
      if (!first) throw new Error('Không tìm thấy chi tiết đặt sân.');
      const restored = {...draft, courtId: first.courtId, timeSlotId: first.timeSlotId,
        bookingDate: first.bookingDate, details: booking.details};
      sessionStorage.setItem(BOOKING_DRAFT_KEY, JSON.stringify(restored));
      setDraft(restored);
    }).catch(reason => { if (active) { setError(reason.message); setLoading(false); } });
    return () => { active = false; };
  }, [draft?.bookingId, draft?.courtId, draft?.timeSlotId, draft?.bookingDate, session]);

  useEffect(() => {
    if (!session || loading || error || !price || draft?.bookingId || holdCreating.current) return;
    if (draft?.bookingCreateStarted) {
      setError('Yêu cầu đặt sân trước đó chưa có xác nhận. Vui lòng kiểm tra lịch đặt trước khi thử lại.');
      return;
    }
    holdCreating.current = true;
    const started = {...draft, bookingCreateStarted: true};
    sessionStorage.setItem(BOOKING_DRAFT_KEY, JSON.stringify(started));
    (async () => {
      const {customerId} = await api('/bookings/customer-profile', session.accessToken);
      const booking = await api('/bookings', session.accessToken, {method: 'POST', body: JSON.stringify({customerId,
        details: draft.details?.length ? draft.details : [{bookingDate: draft.bookingDate, courtId: draft.courtId, timeSlotId: draft.timeSlotId}]})});
      const saved = {...started, bookingId: booking.bookingId};
      sessionStorage.setItem(BOOKING_DRAFT_KEY, JSON.stringify(saved));
      setDraft(saved);
    })().catch(reason => setError(reason.message || 'Không tạo được thời gian giữ sân. Vui lòng kiểm tra lịch đặt.'));
  }, [session, loading, error, price, draft]);

  useEffect(() => {
    if (!draft?.bookingId || !session) return;
    let active = true;
    let anchor = null;
    const tick = () => {
      if (!anchor) return;
      const seconds = Math.max(0, Math.ceil((anchor.ms - (performance.now() - anchor.at)) / 1000));
      setRemaining(seconds);
      if (!seconds) setExpired(true);
    };
    const sync = async () => {
      try {
        const state = await api(`/bookings/${encodeURIComponent(draft.bookingId)}/checkout-clock`, session.accessToken);
        if (!active) return;
        if (!['PENDING', 'EXPIRED'].includes(state.status)) {
          window.location.assign(`/payment-result?bookingId=${encodeURIComponent(draft.bookingId)}`);
          return;
        }
        // Compare server-local timestamps on the same axis, independent of client timezone.
        const parse = value => Date.parse(value + 'Z');
        anchor = {ms: state.status === 'EXPIRED' ? 0 : Math.max(0, parse(state.expiresAt) - parse(state.serverTime)), at: performance.now()};
        tick();
      } catch (reason) { if (active) setSubmitError(reason.message || 'Không đồng bộ được thời gian giữ sân.'); }
    };
    sync();
    const timer = window.setInterval(tick, 250);
    const poll = window.setInterval(sync, 15000);
    window.addEventListener('focus', sync);
    return () => { active = false; window.clearInterval(timer); window.clearInterval(poll); window.removeEventListener('focus', sync); };
  }, [draft?.bookingId, session]);

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
    const requestedDetails = draft.details?.length ? draft.details : [{courtId: draft.courtId, timeSlotId: draft.timeSlotId, bookingDate: draft.bookingDate}];
    if (draft.bookingId) {
      api(`/bookings/${encodeURIComponent(draft.bookingId)}`, session.accessToken).then(booking => {
        if (!active) return;
        if (booking.status === 'EXPIRED') { setExpired(true); return; }
        if (booking.status !== 'PENDING') return;
        if (!booking.details?.length) throw new Error('Không tìm thấy chi tiết đặt sân.');
        applyBooking(booking);
      }).catch(reason => { if (active) setError(reason.message); })
        .finally(() => { if (active) setLoading(false); });
      return () => { active = false; };
    }
    Promise.all([
      api('/public/courts', null),
      api('/public/time-slots', null),
      Promise.all(requestedDetails.map(item => api(`/public/price-quote?courtId=${encodeURIComponent(item.courtId)}&timeSlotId=${encodeURIComponent(item.timeSlotId)}`, null))),
      Promise.all([...new Set(requestedDetails.map(item => item.bookingDate))].map(date => api(`/availability?date=${encodeURIComponent(date)}`, session.accessToken))),
    ]).then(([courtList, slotList, quotes, availabilityGrids]) => {
      if (!active) return;
      const selectedCourt = courtList.find(item => item.id === draft.courtId);
      const selectedSlot = slotList.find(item => item.id === draft.timeSlotId);
      if (!selectedCourt || !selectedSlot) throw new Error('Không còn tìm thấy sân hoặc khung giờ đã chọn. Vui lòng quay lại chọn lại.');
      if (!requestedDetails.every(item => availabilityGrids.find(grid => grid.date === item.bookingDate)?.courts?.find(court => court.courtId === item.courtId)?.slots?.some(slot => slot.timeSlotId === item.timeSlotId && slot.available))) throw new Error('Sân đã có người đặt. Vui lòng chọn khung giờ khác.');
      setSelectedDetails(requestedDetails.map((item, index) => ({...item, ...slotList.find(slot => slot.id === item.timeSlotId), courtName: courtList.find(court => court.id === item.courtId)?.name, price: quotes[index]?.price})));
      setCourt(selectedCourt);
      setSlot(selectedSlot);
      setPrice(quotes.every(quote => quote?.price != null) ? quotes.reduce((total, quote) => total + Number(quote.price), 0) : null);
    }).catch(reason => { if (active) setError(reason.message || 'Không tải được thông tin đặt sân.'); })
      .finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [draft?.bookingId, draft?.courtId, draft?.timeSlotId, draft?.bookingDate, session]);

  const duration = useMemo(() => durationText(selectedDetails.length ? selectedDetails : slot), [selectedDetails, slot]);
  const customer = session?.user || {};

  function logout() {
    clearSession();
    setSession(null);
  }

  async function continueToPayment() {
    setSubmitError('');
    if (expired || remaining === null || remaining === 0 || !draft?.bookingId || !noteLoaded || noteDirty || editing || bookingSyncRequired || mutationStarted.current) return;
    if (paymentMethod === 'BANK_TRANSFER') {
      const errors = {};
      if (!bankTransfer.bankName.trim()) errors.bankName = 'Vui lòng nhập tên ngân hàng.';
      if (!bankTransfer.accountHolder.trim()) errors.accountHolder = 'Vui lòng nhập tên chủ tài khoản.';
      if (!bankTransfer.accountNumber) errors.accountNumber = 'Vui lòng nhập số tài khoản.';
      else if (!/^\d+$/.test(bankTransfer.accountNumber)) errors.accountNumber = 'Số tài khoản chỉ được chứa chữ số.';
      if (!bankTransfer.transferContent.trim()) errors.transferContent = 'Vui lòng nhập nội dung chuyển khoản.';
      setBankTransferErrors(errors);
      if (Object.keys(errors).length) {
        document.getElementById(`checkout-${Object.keys(errors)[0]}`)?.focus();
        return;
      }
    }
    if (!session) {
      setSubmitError('Vui lòng đăng nhập để tiếp tục.');
      return;
    }
    if (!['VNPAY', 'BANK_TRANSFER'].includes(paymentMethod)) {
      setSubmitError('Không hỗ trợ thanh toán tiền mặt cho đặt sân.');
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
      const bookingId = draft.bookingId;
      const latest = await api(`/bookings/${encodeURIComponent(bookingId)}`, session.accessToken);
      if (latest.status === 'EXPIRED') { setExpired(true); throw new Error(expiryMessage); }
      if (latest.status !== 'PENDING') {
        window.location.assign(`/payment-result?bookingId=${encodeURIComponent(bookingId)}`);
        return;
      }
      const signature = details => details.map(detail => `${detail.courtId}:${detail.bookingDate}:${detail.timeSlotId}:${Number(detail.price)}`).sort().join('|');
      if (Number(latest.courtFee) !== price || signature(latest.details) !== signature(selectedDetails)) {
        applyBooking(latest);
        throw new Error('Thông tin đặt sân đã được cập nhật. Vui lòng kiểm tra ngày, khung giờ và tổng tiền mới trước khi thanh toán.');
      }
      const payment = await api(`/bookings/${encodeURIComponent(bookingId)}/payments`, session.accessToken, {
        method: 'POST', body: JSON.stringify({paymentMethod, ...(paymentMethod === 'BANK_TRANSFER' ? {bankTransfer} : {})}),
      });
      const paymentDraft = {...draft, bookingId, paymentAttemptId: payment.paymentAttemptId};
      sessionStorage.setItem(BOOKING_DRAFT_KEY, JSON.stringify(paymentDraft));
      setDraft(paymentDraft);
      if (payment.paymentMethod === 'BANK_TRANSFER' && payment.bookingStatus === 'PAID' && payment.confirmationMethod === 'CUSTOMER_DECLARED') {
        window.location.assign(`/payment-result?bookingId=${encodeURIComponent(bookingId)}&paymentAttemptId=${encodeURIComponent(payment.paymentAttemptId)}`);
        return;
      }
      if (payment?.gatewayPreparation?.checkoutReady && payment.gatewayPreparation.checkoutUrl) {
        window.location.assign(payment.gatewayPreparation.checkoutUrl);
        return;
      }
      throw new Error(payment?.gatewayPreparation?.checkoutBlocker || 'VNPay Sandbox chưa sẵn sàng. Booking vẫn chưa được xác nhận thanh toán.');
    } catch (reason) {
      if (reason.status === 409 && reason.message?.includes('BOOKING_EXPIRED')) setExpired(true);
      setSubmitError(reason.status === 409 && !draft.bookingId
        ? 'Sân đã có người đặt. Vui lòng chọn khung giờ khác.'
        : reason.message || 'Không thể khởi tạo thanh toán. Trạng thái thanh toán chưa được xác nhận.');
      submitted.current = false;
    } finally {
      setSubmitting(false);
    }
  }

  if (!session) {
    return <div className="checkout-page"><GuideHeader session={null} activePage="checkout"/><main className="checkout-empty"><h1>Đăng nhập để tiếp tục đặt sân</h1><p>Việc xác nhận đặt sân và thanh toán yêu cầu tài khoản khách hàng.</p><a className="checkout-primary-link" href="/login?redirect=%2Fcheckout">Đăng nhập</a></main><GuideFooter activePage="courts"/></div>;
  }

  if (draft?.bookingId && (!draft.courtId || !draft.timeSlotId || !draft.bookingDate)) {
    return <div className="checkout-page"><GuideHeader session={session} activePage="checkout"/><main className="checkout-empty">{expired ? <div className="checkout-alert checkout-alert-error checkout-expired" role="alert"><strong>00:00</strong><p>{expiryMessage}</p></div> : <><h1>Đang tải thông tin giữ sân</h1><p>{error || 'Đang đối chiếu thông tin từ máy chủ…'}</p><a className="checkout-primary-link" href="/">Về trang chủ</a></>}</main><GuideFooter activePage="courts"/></div>;
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
      <a className="checkout-back" href="/courts#search-courts"><CheckoutIcon name="arrow-left"/> Quay lại</a>
      <div className="checkout-heading-row">
        <div className="checkout-heading"><h1>Xác nhận đặt sân</h1><p>Kiểm tra lịch chơi và chi phí trước khi thanh toán.</p></div>
        <div className="checkout-progress" aria-label="Tiến trình đặt sân">
          <Step state="done" number="1" label="Chọn sân"/><i className="done"/>
          <Step state="current" number="2" label="Xem lại"/><i/><Step state="next" number="3" label="Thanh toán"/>
        </div>
      </div>

      {(error || loading) && <div className={error ? 'checkout-alert checkout-alert-error' : 'checkout-alert'} role={error ? 'alert' : 'status'}>{error || 'Đang kiểm tra sân, khung giờ và giá hiện tại…'}</div>}

      {remaining !== null && !expired && <div className={`checkout-alert${remaining < 60 ? ' checkout-alert-error' : ''}`} role="status">
        Thời gian giữ sân còn lại: <strong>{String(Math.floor(remaining / 60)).padStart(2, '0')}:{String(remaining % 60).padStart(2, '0')}</strong>
        {remaining < 60 && ' — Sắp hết thời gian giữ sân. Vui lòng hoàn tất thanh toán.'}
      </div>}
      {expired && <div className="checkout-alert checkout-alert-error checkout-expired" role="alert"><strong>00:00</strong><p>{expiryMessage}</p></div>}
      <div className="checkout-columns">
        <div className="checkout-details-column">
          <section className="checkout-card checkout-court-card">
            <div className="checkout-card-heading"><h2>Thông tin đặt sân</h2><button type="button" className="checkout-edit-button" disabled={loading || !bookingClock.ready || !draft?.bookingId || expired || submitting || noteSaving || editSaving || editing || paymentAttempts > 0 || new Set(selectedDetails.map(detail => detail.courtId)).size !== 1} onClick={() => {
              setEditDate(draft.bookingDate >= bookingClock.today ? draft.bookingDate : bookingClock.today); setEditSlotIds(selectedDetails.filter(detail => isFutureSlot(detail.bookingDate, detail.startTime, bookingClock.nowMs)).map(detail => detail.timeSlotId)); setEditing(true);
            }}><CheckoutIcon name="pencil"/> Chỉnh sửa thông tin</button></div>
            {paymentAttempts > 0 && <p className="checkout-note">Đã khởi tạo thanh toán; ngày và khung giờ của đơn này không thể thay đổi.</p>}
            {editing && <div className="checkout-booking-editor" aria-label="Chỉnh sửa thông tin đặt sân">
              <label htmlFor="checkout-edit-date">Ngày chơi</label>
              <input id="checkout-edit-date" type="date" min={bookingClock.today} value={editDate} disabled={editSaving} onChange={event => {setEditDate(event.target.value >= bookingClock.today ? event.target.value : bookingClock.today); setEditSlotIds([]);}}/>
              <fieldset disabled={editSaving || editLoading || expired}><legend>Khung giờ ({editSlotIds.length} đã chọn)</legend>
                {editLoading ? <p role="status">Đang kiểm tra khung giờ và giá…</p> : visibleEditOptions.map(option => <label className="checkout-edit-slot" key={option.timeSlotId}>
                  <input type="checkbox" value={option.timeSlotId} checked={editSlotIds.includes(option.timeSlotId)} disabled={!option.available} onChange={event => {setEditError(''); setEditSlotIds(current => event.target.checked ? [...current, option.timeSlotId] : current.filter(id => id !== option.timeSlotId));}}/>
                  <span>{timeText(option.startTime)} – {timeText(option.endTime)}</span><span>{formatMoney(option.price)}{!option.available && ' · Đã được đặt hoặc đã qua'}</span>
                </label>)}
                {!editLoading && bookingClock.ready && !visibleEditOptions.length && <p role="status">Ngày này không còn khung giờ bắt đầu trong tương lai. Vui lòng chọn ngày khác.</p>}
              </fieldset>
              <p>Giá sau chỉnh sửa: <strong>{editLoading || !editSlotIds.length ? '—' : formatMoney(visibleEditOptions.filter(option => editSlotIds.includes(option.timeSlotId)).reduce((sum, option) => sum + option.price, 0))}</strong></p>
              <p className="checkout-note">Giá cuối cùng do máy chủ tính. Thời gian giữ sân ban đầu được giữ nguyên.</p>
              {editError && <p className="checkout-submit-error" role="alert">{editError}</p>}
              <div className="checkout-editor-actions"><button type="button" disabled={editSaving || editLoading || expired || !editDate || !editSelectionValid} onClick={saveDetails}>{editSaving ? 'Đang lưu…' : 'Lưu thay đổi'}</button><button type="button" disabled={editSaving} onClick={() => {setEditing(false); setEditError('');}}>Hủy</button></div>
            </div>}
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
            {selectedDetails.length > 1 && <div className="checkout-note"><strong>{selectedDetails.length} khung giờ đã chọn</strong>{selectedDetails.map((detail, index) => <p key={index}>{detail.courtName} · {detail.bookingDate} · {timeText(detail.startTime)} – {timeText(detail.endTime)} · {formatMoney(detail.price)}</p>)}</div>}
          </section>

          <section className="checkout-card checkout-customer-card">
            <div className="checkout-card-heading"><h2>Thông tin người đặt</h2><button className="checkout-edit-disabled" type="button" disabled title="Ứng dụng chưa có chức năng chỉnh sửa hồ sơ."><CheckoutIcon name="pencil"/> Chỉnh sửa</button></div>
            <div className="checkout-customer-fields">
              <div><small>Họ và tên</small><strong>{customer.fullName || 'Chưa cập nhật'}</strong></div>
              <div><small>Số điện thoại</small><strong>{customer.phone || 'Chưa cập nhật'}</strong></div>
              <div><small>Email nhận xác nhận</small><strong>{customer.email || 'Chưa cập nhật'}</strong></div>
            </div>
            <div className="checkout-booking-note">
              <label htmlFor="checkout-customer-note">Ghi chú cho đơn đặt sân</label>
              <textarea id="checkout-customer-note" rows="4" maxLength={1000} placeholder="Nhập ghi chú cho sân hoặc yêu cầu thêm của bạn..." value={note} disabled={!noteLoaded || expired || submitting || noteSaving || editSaving} onChange={event => {
                const text = event.target.value; setNote(text); setNoteStatus('Chưa lưu thay đổi.');
                sessionStorage.setItem(`bcm-booking-note:${draft.bookingId}`, JSON.stringify({text}));
              }}/>
              <div className="checkout-note-status"><span>{note.length}/1000 ký tự</span><span role="status">{noteStatus}</span></div>
              <button type="button" disabled={!noteLoaded || !noteDirty || noteSaving || submitting || expired || editing} onClick={saveNote}>{noteSaving ? 'Đang lưu…' : 'Lưu ghi chú'}</button>
              {noteDirty && <p className="checkout-note">Vui lòng lưu ghi chú trước khi thanh toán.</p>}
            </div>
          </section>

          {paymentMethod === 'BANK_TRANSFER' && <section className="checkout-card checkout-bank-transfer-card" aria-labelledby="checkout-bank-transfer-heading">
            <h2 id="checkout-bank-transfer-heading">Thông tin chuyển khoản</h2>
            <p>Vui lòng điền thông tin chuyển khoản ngân hàng trước khi thanh toán.</p>
            <div className="checkout-bank-transfer-fields">
              {[
                {name: 'bankName', label: 'Ngân hàng', placeholder: 'Nhập tên ngân hàng'},
                {name: 'accountHolder', label: 'Tên chủ tài khoản', placeholder: 'Nhập tên chủ tài khoản'},
                {name: 'accountNumber', label: 'Số tài khoản', placeholder: 'Nhập số tài khoản'},
                {name: 'transferContent', label: 'Nội dung chuyển khoản', placeholder: 'Nhập nội dung chuyển khoản'},
              ].map(({name, label, placeholder}) => <div className={`checkout-bank-transfer-field checkout-bank-transfer-${name}`} key={name}>
                <label htmlFor={`checkout-${name}`}>{label}</label>
                <input id={`checkout-${name}`} name={name} type="text" placeholder={placeholder} required disabled={submitting}
                  maxLength={name === 'accountNumber' ? 34 : name === 'bankName' ? 100 : name === 'accountHolder' ? 150 : 255}
                  inputMode={name === 'accountNumber' ? 'numeric' : undefined}
                  pattern={name === 'accountNumber' ? '[0-9]+' : undefined}
                  value={bankTransfer[name]} aria-invalid={Boolean(bankTransferErrors[name])}
                  aria-describedby={bankTransferErrors[name] ? `checkout-${name}-error` : undefined}
                  onChange={event => {
                    const value = name === 'accountNumber' ? event.target.value.replace(/\D/g, '') : event.target.value;
                    setBankTransfer(previous => ({...previous, [name]: value}));
                    setBankTransferErrors(previous => ({...previous, [name]: ''}));
                  }}/>
                {bankTransferErrors[name] && <span className="checkout-bank-transfer-error" id={`checkout-${name}-error`} role="alert">{bankTransferErrors[name]}</span>}
              </div>)}
            </div>
          </section>}

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

              <fieldset className="checkout-methods" disabled={loading || Boolean(error) || submitting}>
                <legend>Phương thức thanh toán</legend>
                <label className={`checkout-method ${paymentMethod === 'VNPAY' ? 'selected' : ''}`}>
                  <span><strong>VNPAY</strong><small>Thanh toán qua cổng VNPay Sandbox</small></span><input type="radio" name="paymentMethod" value="VNPAY" checked={paymentMethod === 'VNPAY'} onChange={() => setPaymentMethod('VNPAY')}/>
                </label>
                <label className={`checkout-method ${paymentMethod === 'BANK_TRANSFER' ? 'selected' : ''}`}><span><strong>Chuyển khoản ngân hàng</strong><small>Xác nhận đặt sân theo khai báo chuyển khoản của bạn</small></span><input type="radio" name="paymentMethod" value="BANK_TRANSFER" checked={paymentMethod === 'BANK_TRANSFER'} onChange={() => setPaymentMethod('BANK_TRANSFER')}/></label>
                <label className="checkout-method unavailable"><span><strong>Tiền mặt</strong><small>Không áp dụng cho đặt sân; chỉ hỗ trợ mua sản phẩm tại quầy</small></span><input type="radio" name="paymentMethod" value="CASH" disabled aria-label="Tiền mặt không áp dụng cho đặt sân"/></label>
              </fieldset>

              <div className="checkout-payment-action"><p>Bằng việc tiếp tục, bạn đồng ý với điều khoản sử dụng và chính sách hủy của sân.</p>{submitError && <div className="checkout-submit-error" role="alert">{submitError}</div>}<button type="button" disabled={loading || Boolean(error) || !price || submitting || expired || !draft?.bookingId || remaining === null || !noteLoaded || noteDirty || noteSaving || editing || editSaving || bookingSyncRequired} onClick={continueToPayment}>{submitting ? 'Đang khởi tạo…' : 'Thanh toán'} <CheckoutIcon name="arrow-right"/></button><div className="checkout-security"><CheckoutIcon name="shield-check"/> Thanh toán an toàn, thông tin được bảo mật</div></div>
            </div>
          </section>
          <div className="checkout-support"><CheckoutIcon name="headset"/><div><span>Cần hỗ trợ về lịch đặt?</span><strong>1900 1000 • Hỗ trợ 24/7</strong></div></div>
        </aside>
      </div>
    </main>
    <footer className="checkout-footer"><span>© 2026 Sân cầu lông. Tất cả quyền được bảo lưu.</span><div><a href="/contact#privacy-note">Chính sách bảo mật</a><a href="/guide">Điều khoản sử dụng</a></div></footer>
  </div>;
}
