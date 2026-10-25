import React, {useCallback, useEffect, useMemo, useRef, useState} from 'react';
import './customer-courts.css';
import {assets, GuideFooter, GuideHeader, GuideIcon, SupportBanner} from './CustomerLayout.jsx';
import client, {tokenStore, toErrorMessage} from './lib/apiClient';
import {useBookingClock} from './lib/useBookingClock';
import {isFutureSlot, nextBookingDate} from './lib/bookingTime';
import {courtReferencePrice, compareCourts, courtBadge} from './lib/courtPresentation';
import BranchCourtPicker from './BranchCourtPicker';
import {matchingHours, filterHours, reconcileVenue, toggleCourt, toggleFilterHour, selectedPairs} from './lib/courtSelection';
import {courtImageSource} from './lib/courtImages';
import {sortBookingDetails, bookingTimeLabel, courtLabel} from './lib/bookingPresentation';

export const SESSION_KEY = 'bcm-customer-session';
export const BOOKING_DRAFT_KEY = 'bcm-booking-draft';
export const CHECKOUT_REPLACEMENT_KEY = 'bcm-checkout-replacement';
const money = value => new Intl.NumberFormat('vi-VN', {style: 'currency', currency: 'VND', maximumFractionDigits: 0}).format(Number(value) || 0);
const shortTime = value => String(value || '').slice(0, 5);

export function readSession() {
  const user = tokenStore.user;
  return tokenStore.access && user?.roles?.includes('CUSTOMER') ? {user, accessToken: tokenStore.access} : null;
}

export function clearSession() {
  tokenStore.clear();
  sessionStorage.removeItem(BOOKING_DRAFT_KEY);
  sessionStorage.removeItem(CHECKOUT_REPLACEMENT_KEY);
  window.dispatchEvent(new CustomEvent('bcm:auth-expired'));
}

export async function api(path, token, options = {}) {
  try {
    const response = await client.request({url: path, method: options.method || 'GET',
      data: options.body, headers: options.headers});
    if (!response.data?.success) throw new Error(response.data?.message || 'Phản hồi không hợp lệ.');
    return response.data.data;
  } catch (reason) {
    const error = new Error(reason.response ? toErrorMessage(reason) : reason.message);
    error.status = reason.response?.status;
    error.fields = reason.response?.data?.data;
    throw error;
  }
}

export function Icon({name, size = 18}) {
  const paths = {
    headset: <><path d="M3 13v-2a9 9 0 0 1 18 0v2"/><path d="M5 13h2v6H5a2 2 0 0 1-2-2v-2a2 2 0 0 1 2-2Zm14 0h-2v6h2a2 2 0 0 0 2-2v-2a2 2 0 0 0-2-2ZM17 19a5 5 0 0 1-5 2"/></>,
    calendar: <><rect x="3" y="5" width="18" height="16" rx="2"/><path d="M16 3v4M8 3v4M3 10h18"/></>,
    clock: <><circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/></>,
    pin: <><path d="M20 10c0 5-8 11-8 11S4 15 4 10a8 8 0 1 1 16 0Z"/><circle cx="12" cy="10" r="2.5"/></>,
    search: <><circle cx="10.8" cy="10.8" r="6.8"/><path d="m16 16 5 5"/></>,
    chevron: <path d="m7 10 5 5 5-5"/>,
    arrow: <><path d="M5 12h14M13 6l6 6-6 6"/></>,
    check: <path d="m5 12 4 4L19 6"/>,
    logo: <><path d="M12 3v2M12 19v2M3 12h2M19 12h2M5.64 5.64l1.42 1.42m9.88 9.88 1.42 1.42m0-12.72-1.42 1.42m-9.88 9.88-1.42 1.42"/><circle cx="12" cy="12" r="7"/><path d="m10 10 4 4m0-4-4 4"/></>,
  };
  return <svg aria-hidden="true" width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">{paths[name]}</svg>;
}

function CourtIllustration({court, catalog}) {
  const src = courtImageSource(court, catalog);
  const [failedSrc, setFailedSrc] = useState(null);
  if (src && failedSrc !== src) {
    return <img key={src} className="customer-court-photo" src={src}
      alt={`Ảnh minh họa sân cầu lông ${court.name}`} title="Ảnh minh họa, chưa xác minh là ảnh thực tế của sân"
      loading="lazy" decoding="async" onError={() => setFailedSrc(src)}/>;
  }
  return <div className="customer-court-illustration" aria-label="Ảnh sân chưa được cung cấp" role="img"><div className="court-lines"><i/><b/></div><span>Chưa có ảnh sân</span></div>;
}

export default function CustomerCourts() {
  const bookingClock = useBookingClock();
  const [session, setSession] = useState(readSession);
  const bookingSubmit = useRef(false);
  const availabilitySequence = useRef(0);
  const loadAvailability = useCallback(async selectedDate => {
    const sequence = ++availabilitySequence.current;
    try {
      const grid = await api(`/public/availability?date=${encodeURIComponent(selectedDate)}`, null);
      // Every customer uses the same persisted grid. Ignore responses superseded by
      // a newer refresh/date request rather than restoring an older occupied snapshot.
      if (sequence === availabilitySequence.current) {
        setAvailability(grid); setAvailabilityError('');
      }
      return grid;
    } catch (reason) {
      if (sequence === availabilitySequence.current) {
        setAvailability(null);
        setAvailabilityError('Không tải được tình trạng trống và giá. Bấm Tìm sân để thử lại.');
      }
      throw reason;
    }
  }, []);
  const [courts, setCourts] = useState([]);
  const [slots, setSlots] = useState([]);
  const [availability, setAvailability] = useState(null);
  const [bookings, setBookings] = useState({items: [], total: null, upcoming: null, error: false});
  const [date, setDate] = useState(() => {
    try { return new URLSearchParams(window.location.search).get('date') || JSON.parse(sessionStorage.getItem(BOOKING_DRAFT_KEY))?.bookingDate || ''; }
    catch { return ''; }
  });
  const [selectedSlot, setSelectedSlot] = useState(() => {
    try { return new URLSearchParams(window.location.search).get('timeSlotId') || JSON.parse(sessionStorage.getItem(BOOKING_DRAFT_KEY))?.timeSlotId || ''; }
    catch { return ''; }
  });
  const [query, setQuery] = useState('');
  const [maxPrice, setMaxPrice] = useState('');
  const [onlyAvailable, setOnlyAvailable] = useState(true);
  const [sortOrder, setSortOrder] = useState(() => {
    const sort = new URLSearchParams(window.location.search).get('sort');
    return sort === 'price-asc' ? 'price' : sort === 'price-desc' ? 'price-desc' : 'recommended';
  });
  const [courtFilter, setCourtFilter] = useState(() => new URLSearchParams(window.location.search).get('courtId') || '');
  const [loading, setLoading] = useState(false);
  const [checkingCourt, setCheckingCourt] = useState('');
  const [availabilityError, setAvailabilityError] = useState('');
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [courtSelections, setCourtSelections] = useState({});
  const [hourFilters, setHourFilters] = useState({});
  const [districts, setDistricts] = useState([]);
  const [districtSearch, setDistrictSearch] = useState('');
  const requestVersion = useRef(0);
  const eligibleSlots = useMemo(() => slots.filter(slot => isFutureSlot(date, slot.startTime, bookingClock.nowMs)), [slots, date, bookingClock.nowMs]);
  const eligibleKey = eligibleSlots.map(slot => slot.id).join('|');
  const activeFilterSlot = eligibleSlots.some(slot => slot.id === selectedSlot) ? selectedSlot : '';

  useEffect(() => {
    if (bookingClock.ready && (!date || date < bookingClock.today)) setDate(bookingClock.today);
  }, [bookingClock.ready, bookingClock.today, date]);

  const fetchPageData = useCallback(async () => {
    if (!date || !bookingClock.ready) return;
    const version = ++requestVersion.current;
    setLoading(true);
    setError('');
    try {
      const [courtData, slotData, , bookingPage] = await Promise.all([
        api('/public/courts', null),
        api('/public/time-slots', null),
        loadAvailability(date),
        session ? api(`/bookings?page=0&size=20`, session.accessToken).catch(() => null) : Promise.resolve(null),
      ]);
      if (version !== requestVersion.current) return;
      setCourts(Array.isArray(courtData) ? courtData : []);
      setSlots(Array.isArray(slotData) ? slotData : []);
      setAvailabilityError('');
      setBookings({items: bookingPage?.content || [], total: bookingPage ? Number(bookingPage.totalElements) || 0 : session ? null : 0, upcoming: null, error: Boolean(session && !bookingPage)});
      const candidates = (bookingPage?.content || []).filter(item => ['PENDING', 'PAID'].includes(item.status));
      let upcoming = null;
      for (const item of candidates.slice(0, 3)) {
        try {
          const detail = await api(`/bookings/${encodeURIComponent(item.bookingId)}`, session.accessToken);
          const now = bookingClock.today;
          const futureDetails = sortBookingDetails(detail?.details).filter(entry => entry.bookingDate >= now);
          const future = futureDetails[0];
          if (future) {
            upcoming = {header: item, detail: future, details: futureDetails, declared: detail.payments?.some(payment => payment.confirmationMethod === 'CUSTOMER_DECLARED' && payment.paymentStatus === 'PENDING')};
            break;
          }
        } catch {
          // A history detail failure should not prevent browsing courts.
        }
      }
      if (version === requestVersion.current) setBookings(current => ({...current, upcoming}));
    } catch (reason) {
      if (version !== requestVersion.current) return;
      setError(reason.message);
      setAvailability(null);
    } finally {
      if (version === requestVersion.current) setLoading(false);
    }
  }, [date, session, bookingClock.ready, bookingClock.today, loadAvailability]);

  // A date change resets the grid. Time advancing only prunes expired IDs, preserving other selections/filters.
  useEffect(() => { setCourtSelections({}); setHourFilters({}); }, [date]);
  useEffect(() => {
    if (!bookingClock.ready || !slots.length) return;
    const valid = new Set(eligibleKey ? eligibleKey.split('|') : []);
    setSelectedSlot(current => current && !valid.has(current) ? '' : current);
  }, [eligibleKey, bookingClock.ready, date, slots.length]);

  useEffect(() => {
    fetchPageData();
    return () => { requestVersion.current += 1; availabilitySequence.current += 1; };
  }, [fetchPageData]);

  useEffect(() => {
    if (!date || !bookingClock.ready) return;
    let active = true, refreshing = false;
    const refresh = async () => {
      if (!active || refreshing) return;
      refreshing = true;
      try { await loadAvailability(date); } catch { /* Loader fails closed for every customer. */ }
      finally { refreshing = false; }
    };
    const visible = () => { if (document.visibilityState === 'visible') refresh(); };
    const poll = window.setInterval(refresh, 2000);
    window.addEventListener('focus', refresh);
    window.addEventListener('pageshow', refresh);
    document.addEventListener('visibilitychange', visible);
    return () => { active = false; availabilitySequence.current += 1; window.clearInterval(poll); window.removeEventListener('focus', refresh); window.removeEventListener('pageshow', refresh); document.removeEventListener('visibilitychange', visible); };
  }, [session, date, bookingClock.ready, loadAvailability]);

  // /public/courts returns the complete active catalog, without pagination.
  const districtOptions = useMemo(() => [...new Set(courts.map(court => court.district?.trim()).filter(Boolean))]
    .sort((a, b) => a.localeCompare(b, 'vi')), [courts]);

  const validAvailableSlots = useMemo(() => new Map((availability?.date === date ? availability.courts : []).map(court =>
    [court.courtId, court.slots.filter(slot => slot.available && isFutureSlot(date, slot.startTime, bookingClock.nowMs))])), [availability, date, bookingClock.nowMs]);
  const referencePrices = useMemo(() => Object.fromEntries(courts.map(court => [court.id,
    courtReferencePrice(validAvailableSlots.get(court.id) || [], courtSelections[court.id], activeFilterSlot)])), [courts, validAvailableSlots, courtSelections, activeFilterSlot]);
  const availableKey = JSON.stringify([...validAvailableSlots].map(([id, values]) => [id, values.map(slot => slot.timeSlotId)]));
  useEffect(() => {
    if (availability?.date !== date) return;
    let nextSelections = Object.fromEntries(Object.entries(courtSelections).filter(([id]) => courts.some(court => court.id === id)));
    const nextFilters = {...hourFilters};
    for (const venue of courts.filter(court => !court.branchId || court.branchCourtNumber === 1)) {
      const siblings = courts.filter(court => venue.branchId ? court.branchId === venue.branchId : court.id === venue.id);
      const result = reconcileVenue(siblings, nextSelections, hourFilters[venue.id] || [], validAvailableSlots);
      nextSelections = result.selections;
      if (Object.hasOwn(hourFilters, venue.id)) nextFilters[venue.id] = result.hours;
    }
    if (JSON.stringify(nextSelections) !== JSON.stringify(courtSelections)) setCourtSelections(nextSelections);
    if (JSON.stringify(nextFilters) !== JSON.stringify(hourFilters)) setHourFilters(nextFilters);
  }, [availableKey, date, availability, courtSelections, hourFilters, courts]);
  const visibleCourts = useMemo(() => courts.filter(court => !court.branchId || court.branchCourtNumber === 1).filter(court => {
    const term = query.trim().toLocaleLowerCase('vi');
    const availableSlots = courts.filter(item => item.id === court.id || (court.branchId && item.branchId === court.branchId)).flatMap(item => validAvailableSlots.get(item.id) || []);
    const slotAvailable = activeFilterSlot ? availableSlots.some(slot => slot.timeSlotId === activeFilterSlot) : availableSlots.length > 0;
    const price = referencePrices[court.id];
    return (!term || `${court.name} ${court.courtNumber}`.toLocaleLowerCase('vi').includes(term))
      && (!courtFilter || court.id === courtFilter)
      && (!districts.length || districts.includes(court.district))
      && (!onlyAvailable || slotAvailable)
      && (!maxPrice || (price != null && Number(price) <= Number(maxPrice)));
  }).sort((left, right) => compareCourts(left, right, sortOrder, referencePrices)), [validAvailableSlots, courts, courtFilter, districts, maxPrice, onlyAvailable, query, referencePrices, activeFilterSlot, sortOrder]);

  async function startCheckout(court, selected) {
    if (checkingCourt || bookingSubmit.current) return;
    if (!Object.hasOwn(courtSelections, court.id)) { setNotice('Vui lòng chọn sân'); return; }
    if (!selected.length) { setNotice('Vui lòng chọn khung giờ'); return; }
    bookingSubmit.current = true;
    setCheckingCourt(court.id); setNotice('');
    try {
      const grid = await loadAvailability(date);
      const fresh = grid.courts.find(item => item.courtId === court.id)?.slots || [];
      if (selected.some(slot => !fresh.some(next => next.timeSlotId === slot.timeSlotId && next.available
        && isFutureSlot(date, next.startTime, bookingClock.nowMs)))) throw new Error('Khung giờ vừa hết chỗ hoặc đã bắt đầu. Vui lòng chọn lại.');
      if (selected.some(slot => Number(fresh.find(next => next.timeSlotId === slot.timeSlotId)?.price) !== Number(slot.price)))
        throw new Error('Giá vừa thay đổi. Vui lòng kiểm tra giá mới và bấm Đặt sân lại.');
      await createCheckout({branchId: court.branchId, courtId: court.id, bookingDate: date,
        timeSlotId: selected[0].timeSlotId, courtName: court.name, courtNumber: court.courtNumber, courtType: court.type,
        details: selected.map(slot => ({courtId: court.id, bookingDate: date, timeSlotId: slot.timeSlotId}))});
      window.location.assign(session ? '/checkout' : '/login?redirect=%2Fcheckout');
    } catch (reason) { setNotice(reason.message); }
    finally { bookingSubmit.current = false; setCheckingCourt(''); }
  }

  async function startVenueCheckout(venue) {
    const chosen = courts.filter(court => court.branchId === venue.branchId && Object.hasOwn(courtSelections, court.id));
    if (checkingCourt || bookingSubmit.current) return;
    if (!chosen.length) { setNotice('Vui lòng chọn sân'); return; }
    const selected = selectedPairs(chosen, courtSelections, validAvailableSlots, date);
    if (!selected.length) { setNotice('Vui lòng chọn ít nhất một cặp sân–giờ còn trống'); return; }
    bookingSubmit.current = true;
    setCheckingCourt(venue.id); setNotice('');
    try {
      const grid = await loadAvailability(date);
      const details = selected;
      for (const item of details) {
        const fresh = grid.courts.find(court => court.courtId === item.courtId)?.slots.find(slot => slot.timeSlotId === item.timeSlotId);
        const old = validAvailableSlots.get(item.courtId)?.find(slot => slot.timeSlotId === item.timeSlotId);
        if (!fresh?.available || !isFutureSlot(date, fresh.startTime, bookingClock.nowMs)) throw new Error('Khung giờ vừa hết chỗ hoặc đã bắt đầu. Vui lòng chọn lại.');
        if (fresh.price == null || Number(fresh.price) !== Number(old?.price)) throw new Error('Giá vừa thay đổi. Vui lòng kiểm tra lại.');
      }
      await createCheckout({branchId: venue.branchId, courtId: details[0].courtId,
        bookingDate: date, timeSlotId: details[0].timeSlotId, courtName: venue.name, details});
      window.location.assign(session ? '/checkout' : '/login?redirect=%2Fcheckout');
    } catch (reason) { setNotice(reason.message); }
    finally { bookingSubmit.current = false; setCheckingCourt(''); }
  }

  async function createCheckout(nextDraft) {
    if (session) {
      const {customerId} = await api('/bookings/customer-profile', session.accessToken);
      const booking = await api('/bookings', session.accessToken, {method: 'POST',
        body: JSON.stringify({customerId, branchId: nextDraft.branchId, details: nextDraft.details})});
      nextDraft = {...nextDraft, bookingId: booking.bookingId};
      sessionStorage.removeItem(CHECKOUT_REPLACEMENT_KEY);
    }
    sessionStorage.setItem(BOOKING_DRAFT_KEY, JSON.stringify(nextDraft));
  }

  function logout() {
    clearSession();
    setSession(null);
    setAvailability(null);
    setBookings({items: [], total: null, upcoming: null, error: false});
    setError('');
  }

  return <div className="customer-page">
    <GuideHeader session={session} bookingCount={bookings.total ?? 0} bookingError={bookings.error ? {message: 'Không tải được lịch đặt.'} : null} bookingLoading={Boolean(session && bookings.total === null && !bookings.error)} onLogout={logout} activePage="courts"/>

    <main className="customer-content customer-main">
      <section className="customer-welcome-row" aria-labelledby="welcome-title">
        <div className="customer-welcome">
          <div className="customer-breadcrumb"><a href="#page-top">Trang chủ</a><GuideIcon src={assets.chevronRight}/><strong>Sân cầu lông</strong></div>
          <h1 id="welcome-title">Hôm nay, mình ra sân nhé!</h1>
          <p>{session ? `Chào ${session.user?.fullName?.split(/\s+/)[0] || 'bạn'}! Tìm sân yêu thích và giữ ngay khung giờ cho hội bạn.` : 'Tìm sân yêu thích và xem giá theo khung giờ. Đăng nhập sẽ được yêu cầu khi bạn bắt đầu đặt sân.'}</p>
        </div>
        <aside className="customer-upcoming-card" id="upcoming-booking" aria-live="polite">
          <div className="customer-card-kicker"><Icon name="calendar" size={17}/> LỊCH CHƠI SẮP TỚI</div>
          {bookings.upcoming ? <>
            <strong>{bookings.upcoming.detail.courtName}</strong>
            <span>{bookings.upcoming.detail.bookingDate} • {bookingTimeLabel(bookings.upcoming.details)}</span>
            <a href={`/payment-result?bookingId=${encodeURIComponent(bookings.upcoming.header.bookingId)}`}>Mã đặt {bookings.upcoming.header.bookingId.slice(0, 8)} · {bookings.upcoming.declared ? 'Đã xác nhận đặt sân · Thanh toán chờ xác nhận' : bookings.upcoming.header.status}</a>
          </> : <><strong>{!session ? 'Lịch chơi sắp tới' : loading ? 'Đang tải lịch đặt…' : bookings.error ? 'Không tải được lịch đặt' : 'Chưa có lịch chơi sắp tới'}</strong><span>{!session ? 'Đăng nhập để xem lịch đặt của bạn.' : loading ? ' ' : bookings.error ? 'Thử lại để xem các lịch đặt của bạn.' : 'Các lịch đặt sắp tới của bạn sẽ hiển thị ở đây.'}</span><a href={bookings.error ? '#upcoming-booking' : '#search-courts'} onClick={bookings.error ? event => {event.preventDefault(); fetchPageData();} : undefined}>{bookings.error ? 'Tải lại lịch đặt' : 'Tìm sân phù hợp →'}</a></>}
        </aside>
      </section>

      <section className="customer-search-panel" id="search-courts" aria-label="Tìm sân">
        <div className="customer-search-fields">
          <label className="customer-search-field"><span>Địa điểm</span><span className="customer-input-display"><Icon name="pin" size={19}/><span>TP. Hồ Chí Minh</span></span></label>
          <label className="customer-search-field"><span>Ngày chơi</span><span className="customer-input-display"><Icon name="calendar" size={19}/><input aria-label="Ngày chơi" type="date" min={bookingClock.today} value={date} disabled={!bookingClock.ready} onChange={event => setDate(event.target.value >= bookingClock.today ? event.target.value : bookingClock.today)}/></span></label>
          <label className="customer-search-field"><span>Khung giờ</span><span className="customer-input-display"><Icon name="clock" size={20}/><select aria-label="Khung giờ" value={selectedSlot} disabled={!bookingClock.ready || !eligibleSlots.length} onChange={event => setSelectedSlot(event.target.value)}><option value="">Tất cả khung giờ</option>{eligibleSlots.map(slot => <option key={slot.id} value={slot.id}>{shortTime(slot.startTime)} – {shortTime(slot.endTime)}</option>)}</select><Icon name="chevron" size={16}/></span></label>
          <label className="customer-search-field"><span>Giá tối đa / khung giờ</span><span className="customer-input-display"><strong className="customer-dong">₫</strong><input aria-label="Giá tối đa mỗi khung giờ" type="number" min="0" step="10000" placeholder="Không giới hạn" value={maxPrice} onChange={event => setMaxPrice(event.target.value)}/></span></label>
          <button className="customer-button customer-button-primary customer-search-button" onClick={fetchPageData} disabled={loading}><Icon name="search" size={18}/> Tìm sân</button>
        </div>
        <div className="customer-quick-filters"><span>Lọc nhanh:</span><label className={onlyAvailable ? 'selected' : ''}><input type="checkbox" checked={onlyAvailable} onChange={event => setOnlyAvailable(event.target.checked)}/><Icon name="check" size={13}/> Còn sân trong giờ đã chọn</label><button onClick={() => {setQuery(''); setCourtFilter(''); setDistricts([]); setDistrictSearch(''); setMaxPrice(''); setOnlyAvailable(true); setSelectedSlot('');}}>Đặt lại bộ lọc</button></div>
      </section>

      {(error || availabilityError) && <div className="customer-alert customer-alert-error" role="alert">{error || availabilityError}<button onClick={fetchPageData}>Thử lại</button></div>}
      {notice && <div className="customer-alert customer-alert-info" role="status">{notice}</div>}
      {(!bookingClock.ready || bookingClock.error) && <div className="customer-alert" role="status">{bookingClock.error || 'Đang đồng bộ giờ Việt Nam từ máy chủ…'}</div>}
      {bookingClock.ready && date && !eligibleSlots.length && <div className="customer-alert customer-alert-info" role="status">{date === bookingClock.today ? 'Hôm nay không còn khung giờ bắt đầu trong tương lai.' : 'Không còn khung giờ hợp lệ cho ngày này.'} <button type="button" onClick={() => setDate(nextBookingDate(bookingClock.today))}>Chọn ngày mai</button></div>}

      <section className="customer-results-layout" id="court-results" aria-label="Danh sách sân">
        <aside className="customer-filter-card" aria-labelledby="filter-title">
          <h2 id="filter-title"><span className="customer-filter-glyph">☷</span> Bộ lọc chi tiết</h2>
          <label className="customer-filter-label" htmlFor="court-search">Tên sân</label>
          <input className="customer-text-filter" id="court-search" type="search" placeholder="Tìm theo tên sân" value={query} onChange={event => setQuery(event.target.value)}/>
          <div className="customer-filter-separator"/>
          <fieldset className="customer-district-filter">
            <legend className="customer-filter-label">Quận / Huyện{districts.length > 0 && ` (${districts.length})`}</legend>
            {districtOptions.length > 8 && <input className="customer-text-filter" type="search" aria-label="Tìm quận / huyện" placeholder="Tìm quận / huyện" value={districtSearch} onChange={event => setDistrictSearch(event.target.value)}/>}
            <div className="customer-district-options">
              {districtOptions.filter(value => value.toLocaleLowerCase('vi').includes(districtSearch.trim().toLocaleLowerCase('vi'))).map(value =>
                <label className="customer-check-row" key={value}><input type="checkbox" checked={districts.includes(value)} onChange={event => setDistricts(current => event.target.checked ? [...current, value] : current.filter(item => item !== value))}/>{value}</label>)}
            </div>
            {!districtOptions.length && <p className="customer-filter-hint">{loading ? 'Đang tải quận / huyện…' : error ? 'Không tải được dữ liệu quận / huyện.' : 'Chưa có dữ liệu quận / huyện được xác minh.'}</p>}
            <button type="button" className="customer-clear-districts" disabled={!districts.length && !districtSearch} onClick={() => {setDistricts([]); setDistrictSearch('');}}>Xóa lựa chọn</button>
          </fieldset>
          <div className="customer-filter-separator"/>
          <label className="customer-filter-label" htmlFor="max-price-range">Giá mỗi khung giờ</label>
          <input id="max-price-range" className="customer-range" type="range" min="0" max="1000000" step="10000" value={maxPrice || 1000000} onChange={event => setMaxPrice(Number(event.target.value) >= 1000000 ? '' : event.target.value)} aria-label="Giá tối đa mỗi khung giờ"/>
          <div className="customer-filter-hint">{maxPrice ? `Tối đa ${money(maxPrice)}` : 'Không giới hạn giá'}</div>
          <div className="customer-filter-separator"/>
          <label className="customer-check-row"><input type="checkbox" checked={onlyAvailable} onChange={event => setOnlyAvailable(event.target.checked)}/> Chỉ hiện sân còn khung giờ</label>
          <p className="customer-filter-note">Danh sách chỉ gồm sân đang hoạt động. Giá được lấy theo khung giờ đã chọn.</p>
        </aside>

        <div className="customer-results-column">
          <div className="customer-results-heading"><div><h2>{loading ? 'Đang tìm sân…' : `${visibleCourts.length} sân phù hợp với bạn`}</h2><p>{date} {selectedSlot && slots.find(slot => slot.id === selectedSlot) ? `• ${shortTime(slots.find(slot => slot.id === selectedSlot).startTime)} – ${shortTime(slots.find(slot => slot.id === selectedSlot).endTime)}` : '• Mọi khung giờ'} • TP. Hồ Chí Minh</p></div><label className="customer-sort">Sắp xếp<select title="Đề xuất theo tên sân, chưa cá nhân hóa. Giá tham chiếu: giờ chọn đầu tiên, giờ đang lọc, hoặc giá thấp nhất còn trống." aria-label="Sắp xếp sân" value={sortOrder} onChange={event => setSortOrder(event.target.value)}><option value="recommended">Đề xuất cho bạn</option><option value="price">Giá thấp đến cao</option><option value="price-desc">Giá cao đến thấp</option></select></label></div>

          {loading && courts.length === 0 ? <div className="customer-state-card" role="status">Đang tải sân và khung giờ…</div> : !loading && !visibleCourts.length ? <div className="customer-state-card"><strong>Chưa tìm thấy sân phù hợp</strong><span>Thử đổi ngày, khung giờ hoặc bộ lọc giá.</span><button className="customer-button customer-button-outline" onClick={() => {setOnlyAvailable(false); setMaxPrice('');}}>Xem tất cả sân</button></div> : <div className="customer-court-list">
            {visibleCourts.map(cardCourt => {
              const siblings = courts.filter(item => cardCourt.branchId ? item.branchId === cardCourt.branchId : item.id === cardCourt.id);
              const chosen = siblings.filter(item => Object.hasOwn(courtSelections, item.id));
              const court = chosen[0] || cardCourt;
              const requestedHours = hourFilters[cardCourt.id] || [];
              const pairs = selectedPairs(siblings, courtSelections, validAvailableSlots, date);
              const ready = pairs.length > 0;
              const cardSlot = (courtSelections[court.id] ?? [])[0] || '';
              const availableSlots = filterHours(siblings, courtSelections, validAvailableSlots);
              const quote = (validAvailableSlots.get(court.id) || []).find(slot => slot.timeSlotId === cardSlot);
              const priceText = quote?.price != null && Number.isFinite(Number(quote.price)) ? money(quote.price) : loading ? 'Đang lấy giá…' : 'Chọn giờ còn trống';
              const selectedIds = new Set(requestedHours);
              const selected = matchingHours(court.id, courtSelections[court.id] || [], validAvailableSlots)
                .map(id => validAvailableSlots.get(court.id).find(slot => slot.timeSlotId === id));
              const pairSlots = pairs.map(pair => validAvailableSlots.get(pair.courtId)?.find(slot => slot.timeSlotId === pair.timeSlotId));
              const total = pairSlots.every(slot => slot?.price != null && Number.isFinite(Number(slot.price)))
                ? pairSlots.reduce((sum, slot) => sum + Math.round(Number(slot.price) * 100), 0) / 100 : null;
              return <article className="customer-court-card" data-court-id={cardCourt.id} data-active-court-id={chosen[0]?.id || ''} data-branch-id={cardCourt.branchId} key={cardCourt.id}>
                <div className="customer-court-image"><CourtIllustration court={court} catalog={courts}/><span className="customer-court-number">{courtBadge(cardCourt.id)}</span></div>
                <div className="customer-court-info">
                  <div className="customer-court-location"><Icon name="pin" size={15}/> {cardCourt.district || 'Chưa cập nhật quận / huyện'}</div>
                  <h3>{cardCourt.name}</h3>
                  <p className="customer-court-type">Loại sân: {{WOODEN_FLOOR: 'Sàn gỗ', STANDARD_MAT: 'Thảm tiêu chuẩn'}[court.type] || 'Chưa xác minh'} <span>•</span> {courtLabel(court)}</p>
                  <p className="customer-court-address">Địa chỉ: {court.address || 'Chưa cập nhật địa chỉ'}</p>
                  <div className="customer-slot-row customer-extra-slots"><span>Khung giờ còn:</span>{availableSlots.length ? availableSlots.map(slot => {
                    const freeCourts = (chosen.length ? chosen : siblings).filter(item => matchingHours(item.id, [slot.timeSlotId], validAvailableSlots).length);
                    const prices = freeCourts.map(item => validAvailableSlots.get(item.id).find(next => next.timeSlotId === slot.timeSlotId)?.price).filter(price => price != null && Number.isFinite(Number(price))).map(Number);
                    const priceLabel = !prices.length ? 'Giá chưa có' : Math.min(...prices) === Math.max(...prices) ? money(prices[0]) : `${money(Math.min(...prices))} – ${money(Math.max(...prices))}`;
                    return <label className={selectedIds.has(slot.timeSlotId) ? 'customer-slot-chip selected' : 'customer-slot-chip'} key={`${cardCourt.id}:${slot.timeSlotId}`}><input type="checkbox" value={slot.timeSlotId} disabled={!bookingClock.ready || loading || Boolean(checkingCourt) || Boolean(availabilityError)} checked={selectedIds.has(slot.timeSlotId)} onChange={event => {
                    const checked = event.target.checked;
                    setNotice('');
                    setHourFilters(current => ({...current, [cardCourt.id]: checked ? [...new Set([...(current[cardCourt.id] || []), slot.timeSlotId])] : (current[cardCourt.id] || []).filter(id => id !== slot.timeSlotId)}));
                    setCourtSelections(current => toggleFilterHour(current, siblings, slot.timeSlotId, checked, validAvailableSlots));
                  }}/>{shortTime(slot.startTime)} – {shortTime(slot.endTime)} · {priceLabel}</label>;
                  }) : <span className="customer-no-slots">Không còn khung giờ trống trong ngày</span>}</div>
                  <BranchCourtPicker courts={siblings} availableSlots={validAvailableSlots} selections={courtSelections} requestedHours={requestedHours} disabled={!bookingClock.ready || loading || Boolean(checkingCourt) || Boolean(availabilityError)} onToggleCourt={(id, checked) => { setNotice(''); setCourtSelections(current => toggleCourt(current, id, checked, requestedHours, validAvailableSlots)); }} />
                  <div className="customer-court-price-row"><div><strong>{priceText}</strong><span>/ giờ / sân</span><small>{quote ? `Sân ${String(court.branchCourtNumber || 1).padStart(2, '0')} · Giá cho ${shortTime(quote.startTime)} – ${shortTime(quote.endTime)}` : 'Chọn khung giờ để xem giá'}</small></div><button className="customer-button customer-button-primary customer-book-button" disabled={!bookingClock.ready || loading || Boolean(checkingCourt) || Boolean(availabilityError) || !ready || total === null} onClick={() => cardCourt.branchId ? startVenueCheckout(cardCourt) : startCheckout(court, selected)} aria-describedby={`booking-disabled-${cardCourt.id}`}><Icon name="arrow" size={17}/> {checkingCourt === cardCourt.id ? 'Đang kiểm tra…' : 'Đặt sân'}</button><span className="customer-sr-only" id={`booking-disabled-${cardCourt.id}`}>Chọn ngày và khung giờ còn trống để xem lại thông tin đặt sân.</span></div>
                </div>
              </article>;
            })}
          </div>}
          {!loading && courts.length > 0 && <p className="customer-results-footnote">Giá hiển thị theo khung giờ đã chọn. Khung giờ chỉ được giữ sau khi máy chủ xác nhận đặt sân.</p>}
        </div>
      </section>


      <SupportBanner/>
    </main>

    <GuideFooter activePage="courts"/>
  </div>;
}
