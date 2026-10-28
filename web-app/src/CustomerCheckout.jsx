import React, {useEffect, useMemo, useRef, useState} from 'react';
import './customer-checkout.css';
import {api, readSession, clearSession, BOOKING_DRAFT_KEY, CHECKOUT_REPLACEMENT_KEY} from './CustomerCourts.jsx';
import {GuideFooter, GuideHeader} from './CustomerLayout.jsx';
import {useBookingClock} from './lib/useBookingClock';
import {isFutureSlot} from './lib/bookingTime';
import {courtImageSource, COURT_FALLBACK_IMAGE} from './lib/courtImages';
import {sortBookingDetails, bookingCourtGroups, bookingTimeLabel, bookingDuration, courtLabel, compareCourtNumbers, surfaceLabel, rangesLabel} from './lib/bookingPresentation';

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
  const [imageCatalog, setImageCatalog] = useState([]);
  const [slot, setSlot] = useState(null);
  const [selectedDetails, setSelectedDetails] = useState([]);
  const [price, setPrice] = useState(null);
  const [promotion, setPromotion] = useState(null);
  const [voucher, setVoucher] = useState('');
  const [promotionBusy, setPromotionBusy] = useState(false);
  const [promotionMessage, setPromotionMessage] = useState('');
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
  const resumeRequest = useRef(null);
  const [checkoutReady, setCheckoutReady] = useState(false);
  const [remaining, setRemaining] = useState(null);
  const [expired, setExpired] = useState(false);
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
  const [contact, setContact] = useState(null);
  const [contactReady, setContactReady] = useState(false);
  const [contactEditing, setContactEditing] = useState(false);
  const [contactSaving, setContactSaving] = useState(false);
  const [contactFields, setContactFields] = useState({fullName: '', phone: ''});
  const [contactErrors, setContactErrors] = useState({});
  const [contactMessage, setContactMessage] = useState('');
  const [contactError, setContactError] = useState('');
  const [contactReload, setContactReload] = useState(0);
  const mutationStarted = useRef(false);
  const visibleEditOptions = editOptions.filter(option => isFutureSlot(editDate, option.startTime, bookingClock.nowMs));
  const visibleEditKey = visibleEditOptions.map(option => option.timeSlotId).join('|');
  const editSelectionValid = editOptionsDate === editDate && editSlotIds.length > 0 && editSlotIds.every(id => visibleEditOptions.some(option => option.timeSlotId === id && option.available));
  const expiryMessage = 'Thời gian giữ sân 10 phút đã hết. Đơn đặt sân của bạn đã hết hạn và không thể tiếp tục thanh toán. Vui lòng quay về trang chủ để đặt sân lại.';

  useEffect(() => {
    setContactReady(false); setContact(null); setContactEditing(false); setContactError('');
    if (!session || !draft?.bookingId) return;
    let active = true;
    api(`/bookings/${encodeURIComponent(draft.bookingId)}/contact`, session.accessToken)
      .then(value => { if (active) { setContact(value); setContactReady(true); } })
      .catch(reason => { if (active) setContactError(reason.message || 'Không tải được thông tin người đặt.'); });
    return () => { active = false; };
  }, [draft?.bookingId, session, contactReload]);

  async function saveContact(event) {
    event.preventDefault();
    if (mutationStarted.current || submitted.current || expired || !contactReady) return;
    const body = {fullName: contactFields.fullName.trim(), phone: contactFields.phone.trim()};
    const errors = {};
    if (body.fullName.length < 2 || body.fullName.length > 255) errors.fullName = 'Họ và tên phải từ 2 đến 255 ký tự.';
    if (!/^[0-9]{10,20}$/.test(body.phone)) errors.phone = 'Số điện thoại phải gồm 10–20 chữ số.';
    setContactErrors(errors); setContactError(''); setContactMessage('');
    if (Object.keys(errors).length) return;
    mutationStarted.current = true; setContactSaving(true);
    try {
      const saved = await api(`/bookings/${encodeURIComponent(draft.bookingId)}/contact`, session.accessToken,
        {method: 'PATCH', body: JSON.stringify(body)});
      setContact(saved); setContactEditing(false); setContactMessage('Đã lưu.');
    } catch (reason) {
      if (reason.message?.includes('BOOKING_EXPIRED')) setExpired(true);
      setContactError(reason.status === 409 ? 'Đơn đã hết hạn hoặc đã khởi tạo thanh toán; không thể sửa thông tin người đặt.' : reason.message || 'Chưa lưu được thông tin. Vui lòng thử lại.');
      // A lost response may follow a committed PATCH. Reload before allowing payment.
      setContactReady(false);
      try {
        const latest = await api(`/bookings/${encodeURIComponent(draft.bookingId)}/contact`, session.accessToken);
        setContact(latest); setContactReady(true);
      } catch { setContactError('Chưa đồng bộ được thông tin người đặt. Vui lòng tải lại trước khi thanh toán.'); }
    } finally { mutationStarted.current = false; setContactSaving(false); }
  }

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
    const sorted = sortBookingDetails(booking.details);
    const first = sorted[0];
    if (!first) return;
    setSelectedDetails(sorted);
    setCourt({...first, id: first.courtId, name: first.courtName});
    setSlot({id: first.timeSlotId, startTime: first.startTime, endTime: first.endTime});
    setPrice(Number(booking.courtFee));
    setPromotion({subtotal: booking.courtFee, discountAmount: booking.discountAmount, finalAmount: booking.finalAmount,
      promotionCode: booking.promotionCode, requestedVoucher: booking.requestedVoucher});
    setVoucher(booking.requestedVoucher || '');
    setPaymentAttempts(booking.payments?.length || 0);
    setDraft(current => {
      const latest = {...current, bookingId: booking.bookingId, courtId: first.courtId,
        timeSlotId: first.timeSlotId, bookingDate: first.bookingDate, details: sorted};
      sessionStorage.setItem(BOOKING_DRAFT_KEY, JSON.stringify(latest));
      return latest;
    });
  }


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
    if (!session || !draft?.bookingId) return;
    let active = true;
    setCheckoutReady(false);
    // Reuse the in-flight request across StrictMode setup/cleanup so an older resume
    // cannot arrive after leave and silently cancel the newly scheduled release.
    if (resumeRequest.current?.bookingId !== draft.bookingId) {
      resumeRequest.current = {bookingId: draft.bookingId,
        promise: api(`/bookings/${encodeURIComponent(draft.bookingId)}/checkout/resume`, session.accessToken, {method: 'POST'})};
    }
    const request = resumeRequest.current.promise;
    request.then(state => {
      if (!active) return;
      if (state.status === 'EXPIRED') setExpired(true);
      else {
        if (sessionStorage.getItem(CHECKOUT_REPLACEMENT_KEY) === draft.bookingId) sessionStorage.removeItem(CHECKOUT_REPLACEMENT_KEY);
        setCheckoutReady(true);
      }
    }).catch(reason => { if (active) setError(reason.message); });
    return () => { active = false; };
  }, [draft?.bookingId, session]);

  useEffect(() => {
    const restore = event => { if (event.persisted) window.location.reload(); };
    window.addEventListener('pageshow', restore);
    return () => window.removeEventListener('pageshow', restore);
  }, []);

  async function leaveCheckout(event) {
    event.preventDefault();
    if (mutationStarted.current || submitting) return;
    mutationStarted.current = true;
    try {
      if (draft?.bookingId && session) {
        await resumeRequest.current?.promise;
        await api(`/bookings/${encodeURIComponent(draft.bookingId)}/checkout/leave`, session.accessToken, {method: 'POST'});
        sessionStorage.removeItem(CHECKOUT_REPLACEMENT_KEY);
      }
      window.location.assign('/courts#search-courts');
    } catch (reason) { setSubmitError(reason.message); }
    finally { mutationStarted.current = false; }
  }

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
        ...(draft.branchId ? {branchId: draft.branchId} : {}),
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
      Promise.all([api(`/bookings/${encodeURIComponent(draft.bookingId)}`, session.accessToken), api('/public/courts', null).catch(() => [])]).then(([booking, catalog]) => {
        if (!active) return;
        setImageCatalog(catalog);
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
      setImageCatalog(courtList);
      const selectedCourt = courtList.find(item => item.id === draft.courtId);
      const selectedSlot = slotList.find(item => item.id === draft.timeSlotId);
      if (requestedDetails.some(item => !courtList.some(court => court.id === item.courtId && court.branchId === selectedCourt?.branchId))) throw new Error('Chỉ chọn nhiều sân trong cùng một địa điểm.');
      if (!selectedCourt || !selectedSlot) throw new Error('Không còn tìm thấy sân hoặc khung giờ đã chọn. Vui lòng quay lại chọn lại.');
      if (!requestedDetails.every(item => availabilityGrids.find(grid => grid.date === item.bookingDate)?.courts?.find(court => court.courtId === item.courtId)?.slots?.some(slot => slot.timeSlotId === item.timeSlotId && slot.available))) throw new Error('Sân đã có người đặt. Vui lòng chọn khung giờ khác.');
      setSelectedDetails(sortBookingDetails(requestedDetails.map((item, index) => ({...courtList.find(court => court.id === item.courtId), ...item, ...slotList.find(slot => slot.id === item.timeSlotId), courtName: courtList.find(court => court.id === item.courtId)?.name, price: quotes[index]?.price}))));
      setCourt(selectedCourt);
      setSlot(selectedSlot);
      setPrice(quotes.every(quote => quote?.price != null) ? quotes.reduce((total, quote) => total + Number(quote.price), 0) : null);
    }).catch(reason => { if (active) setError(reason.message || 'Không tải được thông tin đặt sân.'); })
      .finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [draft?.bookingId, draft?.courtId, draft?.timeSlotId, draft?.bookingDate, session]);

  const duration = useMemo(() => bookingDuration(selectedDetails), [selectedDetails]);
  const courtGroups = useMemo(() => bookingCourtGroups(selectedDetails), [selectedDetails]);
  const multiCourt = courtGroups.length > 1;
  const customer = contact || session?.user || {};

  function logout() {
    clearSession();
    setSession(null);
  }

  async function continueToPayment() {
    setSubmitError('');
    if (!checkoutReady || expired || remaining === null || remaining === 0 || !draft?.bookingId || editing || contactEditing || !contactReady || bookingSyncRequired || mutationStarted.current) return;
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
      if (Number(latest.courtFee) !== price || Number(latest.finalAmount) !== Number(promotion?.finalAmount)
          || latest.promotionCode !== promotion?.promotionCode || signature(latest.details) !== signature(selectedDetails)) {
        applyBooking(latest);
        throw new Error('Thông tin đặt sân đã được cập nhật. Vui lòng kiểm tra ngày, khung giờ và tổng tiền mới trước khi thanh toán.');
      }
      const latestContact = await api(`/bookings/${encodeURIComponent(bookingId)}/contact`, session.accessToken);
      if (['fullName', 'phone', 'email'].some(key => latestContact[key] !== contact?.[key])) {
        setContact(latestContact);
        throw new Error('Thông tin người đặt đã được cập nhật. Vui lòng kiểm tra lại trước khi thanh toán.');
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

  const courtName = court?.branchName || court?.name || 'Sân đã chọn';
  const selectedCourtLabel = courtGroups.map(group => group.details[0]).sort(compareCourtNumbers).map(courtLabel).join(', ') || 'Đang tải…';
  const selectedDates = [...new Set(selectedDetails.map(detail => detail.bookingDate))].map(formattedDate).join('; ') || formattedDate(draft.bookingDate);
  const quotePrice = promotion?.finalAmount;

  async function applyVoucher(remove = false) {
    if (mutationStarted.current || submitted.current || expired || !draft?.bookingId) return;
    mutationStarted.current = true; setPromotionBusy(true); setPromotionMessage('');
    try {
      const quote = await api(`/bookings/${encodeURIComponent(draft.bookingId)}/promotion${remove ? '' : '/apply'}`, session.accessToken,
        remove ? {method: 'DELETE'} : {method: 'POST', body: JSON.stringify({code: voucher})});
      setPromotion(quote); setPrice(Number(quote.subtotal));
      setPromotionMessage(quote.message || (remove ? 'Đã xóa mã khuyến mãi.' : 'Đã cập nhật ưu đãi.'));
      if (remove) setVoucher('');
    } catch (reason) {
      setPromotionMessage(reason.message || 'Không kiểm tra được mã. Vui lòng thử lại.');
      // Recover a committed apply if its response was lost; block payment if recovery fails.
      try { applyBooking(await api(`/bookings/${encodeURIComponent(draft.bookingId)}`, session.accessToken)); }
      catch { setBookingSyncRequired(true); }
    } finally { mutationStarted.current = false; setPromotionBusy(false); }
  }

  return <div className="checkout-page">
    <GuideHeader session={session} bookingCount={bookingCount} bookingError={bookingError} bookingLoading={bookingLoading} onLogout={logout} activePage="checkout"/>
    <main className="checkout-main">
      <a className="checkout-back" href="/courts#search-courts" onClick={leaveCheckout}><CheckoutIcon name="arrow-left"/> Quay lại</a>
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
            <div className="checkout-card-heading"><h2>Thông tin đặt sân</h2><button type="button" className="checkout-edit-button" disabled={loading || !bookingClock.ready || !draft?.bookingId || expired || submitting || editSaving || editing || contactEditing || contactSaving || paymentAttempts > 0 || new Set(selectedDetails.map(detail => detail.courtId)).size !== 1} onClick={() => {
              setEditDate(draft.bookingDate >= bookingClock.today ? draft.bookingDate : bookingClock.today); setEditSlotIds(selectedDetails.filter(detail => isFutureSlot(detail.bookingDate, detail.startTime, bookingClock.nowMs)).map(detail => detail.timeSlotId)); setEditing(true);
            }}><CheckoutIcon name="pencil"/> Chỉnh sửa thông tin</button></div>
            {paymentAttempts > 0 && <p className="checkout-note">Đã khởi tạo thanh toán; ngày và khung giờ của đơn này không thể thay đổi.</p>}
            {multiCourt && <p className="checkout-note">Đơn gồm {courtGroups.length} sân. Kiểm tra giờ của từng sân bên dưới; thanh toán một lần cho toàn bộ đơn.</p>}
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
              {editError && <p className="checkout-submit-error" role="alert">{editError}</p>}
              <div className="checkout-editor-actions"><button type="button" disabled={editSaving || editLoading || expired || !editDate || !editSelectionValid} onClick={saveDetails}>{editSaving ? 'Đang lưu…' : 'Lưu thay đổi'}</button><button type="button" disabled={editSaving} onClick={() => {setEditing(false); setEditError('');}}>Hủy</button></div>
            </div>}
            <div className="checkout-court-summary">
              {court && courtImageSource(court, imageCatalog) ? <img key={court.id} className="checkout-court-photo" src={courtImageSource(court, imageCatalog)} alt={court.name || 'Sân đã chọn'} onError={event => {if (event.currentTarget.getAttribute('src') !== COURT_FALLBACK_IMAGE) event.currentTarget.src = COURT_FALLBACK_IMAGE; else event.currentTarget.hidden = true;}}/> : <div className="checkout-court-photo" role="img" aria-label="Chưa có ảnh sân">Chưa có ảnh sân</div>}
              <div className="checkout-court-copy">
                <span className="checkout-region">{court?.district || 'Chưa có dữ liệu khu vực'}</span>
                <h3>{courtName}</h3>
                <p className="checkout-address"><CheckoutIcon name="map-pin"/> {court?.address || 'Chưa có địa chỉ sân'}</p>
                <div className="checkout-tags">{[...new Set(selectedDetails.map(detail => surfaceLabel(detail.type)))].map(label => <span key={label}>{label}</span>)}</div>
              </div>
            </div>
            <div className="checkout-divider"/>
            <div className="checkout-booking-facts">
              <div className="checkout-fact"><span className="checkout-fact-icon"><CheckoutIcon name="calendar-days"/></span><div><small>Ngày chơi</small><strong>{selectedDates}</strong></div></div>
              <div className="checkout-fact"><span className="checkout-fact-icon"><CheckoutIcon name="clock-3"/></span><div><small>Khung giờ</small><strong>{bookingTimeLabel(selectedDetails) || 'Đang tải…'}</strong></div></div>
              <div className="checkout-fact"><span className="checkout-fact-icon"><CheckoutIcon name="layout-grid"/></span><div><small>Sân cụ thể</small><strong>{selectedCourtLabel}</strong></div></div>
              <div className="checkout-fact"><span className="checkout-fact-icon"><CheckoutIcon name="timer"/></span><div><small>{multiCourt ? 'Tổng thời lượng sân đặt' : 'Thời lượng'}</small><strong>{duration}{multiCourt ? ' (giờ-sân)' : ''}</strong></div></div>
            </div>
            {multiCourt ? <div className="checkout-multi-courts"><strong>{selectedDetails.length} khung giờ đã chọn</strong>{courtGroups.map(group => <section key={group.courtId} data-checkout-court-id={group.courtId}>
              <h4>{courtLabel(group.details[0])} · {group.details[0].courtName}</h4><p>{surfaceLabel(group.details[0].type)} · {rangesLabel(group.ranges)}</p>{group.details.map(detail => <p key={`${detail.bookingDate}:${detail.timeSlotId}`}>{detail.bookingDate} · {timeText(detail.startTime)} – {timeText(detail.endTime)} · {formatMoney(detail.price)}</p>)}
            </section>)}</div> : selectedDetails.length > 1 && <div className="checkout-note"><strong>{selectedDetails.length} khung giờ đã chọn</strong>{selectedDetails.map((detail, index) => <p key={index}>{detail.courtName} · {detail.bookingDate} · {timeText(detail.startTime)} – {timeText(detail.endTime)} · {formatMoney(detail.price)}</p>)}</div>}
          </section>

          <section className="checkout-card checkout-customer-card">
            <div className="checkout-card-heading"><h2>Thông tin người đặt</h2><button className="checkout-edit-button" type="button" disabled={loading || !contactReady || expired || submitting || editing || editSaving || contactEditing || contactSaving || paymentAttempts > 0} onClick={() => {
              setContactFields({fullName: customer.fullName || '', phone: customer.phone || ''});
              setContactErrors({}); setContactError(''); setContactMessage(''); setContactEditing(true);
            }}><CheckoutIcon name="pencil"/> Chỉnh sửa</button></div>
            {contactEditing ? <form className="checkout-contact-editor" aria-label="Chỉnh sửa thông tin người đặt" noValidate onSubmit={saveContact}>
              <div className="checkout-customer-fields">
                <label>Họ và tên<input autoComplete="name" maxLength={255} value={contactFields.fullName} disabled={contactSaving || expired} aria-invalid={Boolean(contactErrors.fullName)} onChange={event => setContactFields(current => ({...current, fullName: event.target.value}))}/>{contactErrors.fullName && <small role="alert">{contactErrors.fullName}</small>}</label>
                <label>Số điện thoại<input type="tel" inputMode="numeric" autoComplete="tel" maxLength={20} value={contactFields.phone} disabled={contactSaving || expired} aria-invalid={Boolean(contactErrors.phone)} onChange={event => setContactFields(current => ({...current, phone: event.target.value}))}/>{contactErrors.phone && <small role="alert">{contactErrors.phone}</small>}</label>
                <label>Email nhận xác nhận<input type="email" value={customer.email || ''} readOnly aria-readonly="true"/></label>
              </div>
              <div className="checkout-editor-actions"><button type="submit" disabled={contactSaving || expired || !contactReady}>{contactSaving ? 'Đang lưu…' : 'Lưu thông tin người đặt'}</button><button type="button" disabled={contactSaving} onClick={() => {setContactEditing(false); setContactErrors({});}}>Hủy</button></div>
            </form> : <div className="checkout-customer-fields">
              <div><small>Họ và tên</small><strong>{customer.fullName || 'Chưa cập nhật'}</strong></div>
              <div><small>Số điện thoại</small><strong>{customer.phone || 'Chưa cập nhật'}</strong></div>
              <div><small>Email nhận xác nhận</small><strong>{customer.email || 'Chưa cập nhật'}</strong></div>
            </div>}
            {!contactReady && !contactError && <p role="status">Đang tải thông tin người đặt…</p>}
            {contactMessage && <p role="status" className="checkout-contact-status">{contactMessage}</p>}
            {contactError && <div role="alert" className="checkout-submit-error">{contactError}{!contactReady && <button type="button" onClick={() => setContactReload(value => value + 1)}>Thử lại</button>}</div>}
          </section>

          {paymentMethod === 'BANK_TRANSFER' && <section className="checkout-card checkout-bank-transfer-card" aria-labelledby="checkout-bank-transfer-heading">
            <h2 id="checkout-bank-transfer-heading">Thông tin chuyển khoản</h2>
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

        </div>

        <aside className="checkout-invoice-column">
          <section className="checkout-card checkout-invoice-card">
            <div className="checkout-invoice-accent"/>
            <div className="checkout-invoice-content">
              <div className="checkout-invoice-heading"><div><CheckoutIcon name="receipt-text"/><h2>Chi tiết hóa đơn</h2></div><span className="checkout-payment-status"><CheckoutIcon name="clock-4"/> Chưa thanh toán</span><p>{courtGroups.length} sân • {duration}{multiCourt ? ' (giờ-sân)' : ''} • {selectedDates}</p></div>
              <div className="checkout-divider"/>
              <div className="checkout-price-table">
                <div className="checkout-price-head"><span>CHI PHÍ THUÊ SÂN</span><span>THÀNH TIỀN</span></div>
                <div className="checkout-price-item"><div><strong>{selectedCourtLabel}</strong><small>{price == null ? 'Đang lấy báo giá từ máy chủ…' : `Giá theo khung giờ · ${duration}${multiCourt ? ' (giờ-sân)' : ''}`}</small></div><strong>{price == null ? '—' : formatMoney(price)}</strong></div>
              </div>
              <div className="checkout-divider"/>
              <div className="checkout-totals"><div><span>Tạm tính</span><strong>{price == null ? '—' : formatMoney(price)}</strong></div><div><span>Phí đặt sân</span><strong>{formatMoney(0)}</strong></div></div>
              <fieldset className="checkout-promotions" disabled={loading || submitting || promotionBusy || expired || editing || contactEditing || paymentAttempts > 0 || !promotion}>
                <legend>KHUYẾN MÃI</legend>
                <form onSubmit={event => { event.preventDefault(); applyVoucher(); }}>
                  <input aria-label="Mã khuyến mãi" placeholder="Mã khuyến mãi" maxLength={50} value={voucher} onChange={event => setVoucher(event.target.value)}/>
                  <button type="submit" disabled={!voucher.trim()}>{promotionBusy ? 'Đang kiểm tra…' : 'Áp dụng'}</button>
                </form>
                {promotion?.requestedVoucher && <button type="button" className="checkout-remove-voucher" onClick={() => applyVoucher(true)}>Xóa mã {promotion.requestedVoucher}</button>}
                <p role="status" aria-live="polite">{promotionMessage}</p>
              </fieldset>
              {Number(promotion?.discountAmount) > 0 && <div className="checkout-totals checkout-discount">
                <div><span>Giảm giá</span><strong>-{formatMoney(promotion.discountAmount)}</strong></div>
                <div><span>Ưu đãi áp dụng</span><strong>{promotion.promotionCode === 'INVOICE_TOTAL' ? 'Ưu đãi hóa đơn' : promotion.promotionCode}</strong></div>
              </div>}
              <div className="checkout-grand-total"><strong>Tổng thanh toán</strong><b>{quotePrice == null ? '—' : formatMoney(quotePrice)}</b></div>

              <fieldset className="checkout-methods" disabled={loading || Boolean(error) || submitting}>
                <legend>Phương thức thanh toán</legend>
                <label className={`checkout-method ${paymentMethod === 'VNPAY' ? 'selected' : ''}`}>
                  <span><strong>VNPAY</strong><small>Thanh toán qua cổng VNPay Sandbox</small></span><input type="radio" name="paymentMethod" value="VNPAY" checked={paymentMethod === 'VNPAY'} onChange={() => setPaymentMethod('VNPAY')}/>
                </label>
                <label className={`checkout-method ${paymentMethod === 'BANK_TRANSFER' ? 'selected' : ''}`}><span><strong>Chuyển khoản ngân hàng</strong><small>Xác nhận đặt sân theo khai báo chuyển khoản của bạn</small></span><input type="radio" name="paymentMethod" value="BANK_TRANSFER" checked={paymentMethod === 'BANK_TRANSFER'} onChange={() => setPaymentMethod('BANK_TRANSFER')}/></label>
                <label className="checkout-method unavailable"><span><strong>Tiền mặt</strong><small>Không áp dụng cho đặt sân; chỉ hỗ trợ mua sản phẩm tại quầy</small></span><input type="radio" name="paymentMethod" value="CASH" disabled aria-label="Tiền mặt không áp dụng cho đặt sân"/></label>
              </fieldset>

              <div className="checkout-payment-action"><p>Bằng việc tiếp tục, bạn đồng ý với điều khoản sử dụng và chính sách hủy của sân.</p>{submitError && <div className="checkout-submit-error" role="alert">{submitError}</div>}<button type="button" disabled={!checkoutReady || loading || Boolean(error) || !price || submitting || expired || !draft?.bookingId || remaining === null || editing || editSaving || contactEditing || contactSaving || !contactReady || bookingSyncRequired || promotionBusy || !promotion} onClick={continueToPayment}>{submitting ? 'Đang khởi tạo…' : 'Thanh toán'} <CheckoutIcon name="arrow-right"/></button></div>
            </div>
          </section>
          <div className="checkout-support"><CheckoutIcon name="headset"/><div><span>Cần hỗ trợ về lịch đặt?</span><strong>1900 1000 • Hỗ trợ 24/7</strong></div></div>
        </aside>
      </div>
    </main>
    <footer className="checkout-footer"><span>© 2026 Sân cầu lông. Tất cả quyền được bảo lưu.</span><div><a href="/contact#privacy-note">Chính sách bảo mật</a><a href="/guide">Điều khoản sử dụng</a></div></footer>
  </div>;
}
