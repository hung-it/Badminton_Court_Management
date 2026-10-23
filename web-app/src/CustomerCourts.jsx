import React, {useCallback, useEffect, useMemo, useRef, useState} from 'react';
import './customer-courts.css';
import {assets, GuideFooter, GuideHeader, GuideIcon, SupportBanner} from './CustomerLayout.jsx';
import client, {tokenStore, toErrorMessage} from './lib/apiClient';
import {useBookingClock} from './lib/useBookingClock';
import {isFutureSlot, nextBookingDate} from './lib/bookingTime';

export const SESSION_KEY = 'bcm-customer-session';
export const BOOKING_DRAFT_KEY = 'bcm-booking-draft';
const money = value => new Intl.NumberFormat('vi-VN', {style: 'currency', currency: 'VND', maximumFractionDigits: 0}).format(Number(value) || 0);
const shortTime = value => String(value || '').slice(0, 5);

export function readSession() {
  const user = tokenStore.user;
  return tokenStore.access && user?.roles?.includes('CUSTOMER') ? {user, accessToken: tokenStore.access} : null;
}

export function clearSession() {
  tokenStore.clear();
  sessionStorage.removeItem(BOOKING_DRAFT_KEY);
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

function CourtIllustration() {
  return <div className="customer-court-illustration" aria-label="Ảnh sân chưa được cung cấp" role="img"><div className="court-lines"><i/><b/></div><span>Chưa có ảnh sân</span></div>;
}

export default function CustomerCourts() {
  const bookingClock = useBookingClock();
  const [session, setSession] = useState(readSession);
  const [courts, setCourts] = useState([]);
  const [slots, setSlots] = useState([]);
  const [availability, setAvailability] = useState(null);
  const [quotes, setQuotes] = useState({});
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
  const [sortOrder, setSortOrder] = useState(() => new URLSearchParams(window.location.search).get('sort') === 'price-asc' ? 'price' : 'number');
  const [courtFilter, setCourtFilter] = useState(() => new URLSearchParams(window.location.search).get('courtId') || '');
  const [loading, setLoading] = useState(false);
  const [quoteLoading, setQuoteLoading] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [extraSlots, setExtraSlots] = useState({});
  const [courtSlots, setCourtSlots] = useState({});
  const [districts, setDistricts] = useState([]);
  const [districtSearch, setDistrictSearch] = useState('');
  const requestVersion = useRef(0);
  const initializedSlot = useRef(false);
  const eligibleSlots = useMemo(() => slots.filter(slot => isFutureSlot(date, slot.startTime, bookingClock.nowMs)), [slots, date, bookingClock.nowMs]);
  const eligibleKey = eligibleSlots.map(slot => slot.id).join('|');

  useEffect(() => {
    if (bookingClock.ready && (!date || date < bookingClock.today)) setDate(bookingClock.today);
  }, [bookingClock.ready, bookingClock.today, date]);

  const fetchPageData = useCallback(async () => {
    if (!date || !bookingClock.ready) return;
    const version = ++requestVersion.current;
    setLoading(true);
    setError('');
    try {
      const [courtData, slotData, availabilityData, bookingPage] = await Promise.all([
        api('/public/courts', null),
        api('/public/time-slots', null),
        session ? api(`/availability?date=${encodeURIComponent(date)}`, session.accessToken) : Promise.resolve(null),
        session ? api(`/bookings?page=0&size=20`, session.accessToken).catch(() => null) : Promise.resolve(null),
      ]);
      if (version !== requestVersion.current) return;
      setCourts(Array.isArray(courtData) ? courtData : []);
      setSlots(Array.isArray(slotData) ? slotData : []);
      setAvailability(availabilityData);
      setBookings({items: bookingPage?.content || [], total: bookingPage ? Number(bookingPage.totalElements) || 0 : session ? null : 0, upcoming: null, error: Boolean(session && !bookingPage)});
      const candidates = (bookingPage?.content || []).filter(item => ['PENDING', 'PAID'].includes(item.status));
      let upcoming = null;
      for (const item of candidates.slice(0, 3)) {
        try {
          const detail = await api(`/bookings/${encodeURIComponent(item.bookingId)}`, session.accessToken);
          const now = bookingClock.today;
          const future = (detail?.details || []).find(entry => entry.bookingDate >= now);
          if (future) {
            upcoming = {header: item, detail: future, customerNote: detail.customerNote, declared: detail.payments?.some(payment => payment.confirmationMethod === 'CUSTOMER_DECLARED' && payment.paymentStatus === 'PENDING')};
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
  }, [date, session, bookingClock.ready, bookingClock.today]);

  // A date change resets the grid. Time advancing only prunes expired IDs, preserving other selections/filters.
  useEffect(() => { initializedSlot.current = false; setExtraSlots({}); setCourtSlots({}); }, [date]);
  useEffect(() => {
    if (!bookingClock.ready || !slots.length) return;
    const valid = new Set(eligibleKey ? eligibleKey.split('|') : []);
    const first = eligibleSlots[0]?.id || '';
    const initialize = !initializedSlot.current;
    setSelectedSlot(current => initialize || (current && !valid.has(current)) ? (valid.has(current) ? current : first) : current);
    initializedSlot.current = true;
    setCourtSlots(current => {
      const kept = Object.fromEntries(Object.entries(current).filter(([, id]) => valid.has(id)));
      return Object.keys(kept).length === Object.keys(current).length ? current : kept;
    });
    setExtraSlots(current => {
      const kept = Object.fromEntries(Object.entries(current).map(([id, values]) => [id, values.filter(value => valid.has(value))]));
      return Object.keys(kept).every(id => kept[id].length === current[id].length) ? current : kept;
    });
  }, [eligibleKey, bookingClock.ready, date, slots.length]);

  useEffect(() => {
    fetchPageData();
    return () => { requestVersion.current += 1; };
  }, [fetchPageData]);

  useEffect(() => {
    if (!session || !date || !bookingClock.ready) return;
    let active = true;
    const refresh = () => api(`/availability?date=${encodeURIComponent(date)}`, session.accessToken)
      .then(grid => { if (active) setAvailability(grid); }).catch(() => { /* Retain last grid; backend rechecks all writes. */ });
    const poll = window.setInterval(refresh, 15000);
    window.addEventListener('focus', refresh);
    return () => { active = false; window.clearInterval(poll); window.removeEventListener('focus', refresh); };
  }, [session, date, bookingClock.ready]);

  useEffect(() => {
    if (!courts.length) {
      setQuotes({});
      return;
    }
    let active = true;
    setQuoteLoading(true);
    Promise.all(courts.map(async court => {
      if (!courtSlots[court.id] && !selectedSlot) return [court.id, null];
      try {
        const quote = await api(`/public/price-quote?courtId=${encodeURIComponent(court.id)}&timeSlotId=${encodeURIComponent(courtSlots[court.id] || selectedSlot)}`, null);
        return [court.id, quote];
      } catch {
        return [court.id, null];
      }
    })).then(entries => {
      if (active) setQuotes(Object.fromEntries(entries));
    }).finally(() => { if (active) setQuoteLoading(false); });
    return () => { active = false; };
  }, [courts, selectedSlot, courtSlots]);

  // /public/courts returns the complete active catalog, without pagination.
  const districtOptions = useMemo(() => [...new Set(courts.map(court => court.district).filter(Boolean))]
    .sort((a, b) => a.localeCompare(b, 'vi')), [courts]);

  const availabilityByCourt = useMemo(() => new Map((availability?.courts || []).map(court => [court.courtId, court])), [availability]);
  const visibleCourts = useMemo(() => courts.filter(court => {
    const term = query.trim().toLocaleLowerCase('vi');
    const courtAvailability = availabilityByCourt.get(court.id);
    const availableSlots = courtAvailability?.slots?.filter(slot => slot.available && isFutureSlot(date, slot.startTime, bookingClock.nowMs)) || [];
    const slotAvailable = !session || !selectedSlot || availableSlots.some(slot => slot.timeSlotId === selectedSlot);
    const price = quotes[court.id]?.price;
    return (!term || `${court.name} ${court.courtNumber}`.toLocaleLowerCase('vi').includes(term))
      && (!courtFilter || court.id === courtFilter)
      && (!districts.length || districts.includes(court.district))
      && (!onlyAvailable || slotAvailable)
      && (!maxPrice || (price != null && Number(price) <= Number(maxPrice)));
  }).sort((left, right) => sortOrder === 'price'
    ? Number(quotes[left.id]?.price ?? Infinity) - Number(quotes[right.id]?.price ?? Infinity)
    : Number(left.courtNumber) - Number(right.courtNumber)), [availabilityByCourt, courts, courtFilter, districts, maxPrice, onlyAvailable, query, quotes, selectedSlot, session, sortOrder, date, bookingClock.nowMs]);

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
            <span>{bookings.upcoming.detail.bookingDate} • {shortTime(bookings.upcoming.detail.startTime)} – {shortTime(bookings.upcoming.detail.endTime)}</span>
            {bookings.upcoming.customerNote && <span className="customer-upcoming-note">Ghi chú: {bookings.upcoming.customerNote.slice(0, 140)}{bookings.upcoming.customerNote.length > 140 && '…'}</span>}
            <a href={`/payment-result?bookingId=${encodeURIComponent(bookings.upcoming.header.bookingId)}`}>Mã đặt {bookings.upcoming.header.bookingId.slice(0, 8)} · {bookings.upcoming.declared ? 'Đã xác nhận đặt sân — chuyển khoản do khách tự khai báo, chưa xác minh nhận tiền' : bookings.upcoming.header.status}</a>
          </> : <><strong>{!session ? 'Lịch chơi sắp tới' : loading ? 'Đang tải lịch đặt…' : bookings.error ? 'Không tải được lịch đặt' : 'Chưa có lịch chơi sắp tới'}</strong><span>{!session ? 'Đăng nhập để xem lịch đặt của bạn.' : loading ? ' ' : bookings.error ? 'Thử lại để xem các lịch đặt của bạn.' : 'Các lịch đặt sắp tới của bạn sẽ hiển thị ở đây.'}</span><a href={bookings.error ? '#upcoming-booking' : '#search-courts'} onClick={bookings.error ? event => {event.preventDefault(); fetchPageData();} : undefined}>{bookings.error ? 'Tải lại lịch đặt' : 'Tìm sân phù hợp →'}</a></>}
        </aside>
      </section>

      <section className="customer-search-panel" id="search-courts" aria-label="Tìm sân">
        <div className="customer-search-fields">
          <label className="customer-search-field"><span>Địa điểm</span><span className="customer-input-display"><Icon name="pin" size={19}/><span>TP. Hồ Chí Minh</span></span></label>
          <label className="customer-search-field"><span>Ngày chơi</span><span className="customer-input-display"><Icon name="calendar" size={19}/><input aria-label="Ngày chơi" type="date" min={bookingClock.today} value={date} disabled={!bookingClock.ready} onChange={event => setDate(event.target.value >= bookingClock.today ? event.target.value : bookingClock.today)}/></span></label>
          <label className="customer-search-field"><span>Khung giờ</span><span className="customer-input-display"><Icon name="clock" size={20}/><select aria-label="Khung giờ" value={selectedSlot} disabled={!bookingClock.ready || !eligibleSlots.length} onChange={event => {setSelectedSlot(event.target.value); setExtraSlots({}); setCourtSlots({});}}><option value="">Tất cả khung giờ</option>{eligibleSlots.map(slot => <option key={slot.id} value={slot.id}>{shortTime(slot.startTime)} – {shortTime(slot.endTime)}</option>)}</select><Icon name="chevron" size={16}/></span></label>
          <label className="customer-search-field"><span>Giá tối đa / khung giờ</span><span className="customer-input-display"><strong className="customer-dong">₫</strong><input aria-label="Giá tối đa mỗi khung giờ" type="number" min="0" step="10000" placeholder="Không giới hạn" value={maxPrice} onChange={event => setMaxPrice(event.target.value)}/></span></label>
          <button className="customer-button customer-button-primary customer-search-button" onClick={fetchPageData} disabled={loading}><Icon name="search" size={18}/> Tìm sân</button>
        </div>
        <div className="customer-quick-filters"><span>Lọc nhanh:</span><label className={onlyAvailable ? 'selected' : ''}><input type="checkbox" checked={onlyAvailable} onChange={event => setOnlyAvailable(event.target.checked)}/><Icon name="check" size={13}/> Còn sân trong giờ đã chọn</label><button onClick={() => {setQuery(''); setCourtFilter(''); setDistricts([]); setDistrictSearch(''); setMaxPrice(''); setOnlyAvailable(true); setSelectedSlot(eligibleSlots[0]?.id || '');}}>Đặt lại bộ lọc</button></div>
      </section>

      {error && <div className="customer-alert customer-alert-error" role="alert">{error}<button onClick={fetchPageData}>Thử lại</button></div>}
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
            {!districtOptions.length && <p className="customer-filter-hint">Chưa có dữ liệu quận / huyện được xác minh.</p>}
            {courts.some(court => !court.district) && <p className="customer-filter-hint">{courts.filter(court => !court.district).length} sân chưa cập nhật quận / huyện; vẫn hiển thị khi không lọc quận.</p>}
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
          <div className="customer-results-heading"><div><h2>{loading ? 'Đang tìm sân…' : `${visibleCourts.length} sân phù hợp với bạn`}</h2><p>{date} {selectedSlot && slots.find(slot => slot.id === selectedSlot) ? `• ${shortTime(slots.find(slot => slot.id === selectedSlot).startTime)} – ${shortTime(slots.find(slot => slot.id === selectedSlot).endTime)}` : '• Mọi khung giờ'} • TP. Hồ Chí Minh</p></div><label className="customer-sort">Sắp xếp<select aria-label="Sắp xếp sân" value={sortOrder} onChange={event => setSortOrder(event.target.value)}><option value="number">Số sân</option><option value="price">Giá thấp đến cao</option></select></label></div>

          {loading && courts.length === 0 ? <div className="customer-state-card" role="status">Đang tải sân và khung giờ…</div> : !loading && !visibleCourts.length ? <div className="customer-state-card"><strong>Chưa tìm thấy sân phù hợp</strong><span>Thử đổi ngày, khung giờ hoặc bộ lọc giá.</span><button className="customer-button customer-button-outline" onClick={() => {setOnlyAvailable(false); setMaxPrice('');}}>Xem tất cả sân</button></div> : <div className="customer-court-list">
            {visibleCourts.map(court => {
              const cardSlot = courtSlots[court.id] || selectedSlot;
              const courtAvailability = availabilityByCourt.get(court.id);
              const availableSlots = courtAvailability?.slots?.filter(slot => slot.available && isFutureSlot(date, slot.startTime, bookingClock.nowMs)) || [];
              const selectedAvailable = !cardSlot || availableSlots.some(slot => slot.timeSlotId === cardSlot);
              const quote = quotes[court.id];
              const priceText = quote ? money(quote.price) : quoteLoading ? 'Đang lấy giá…' : 'Giá chưa có';
              return <article className="customer-court-card" data-court-id={court.id} key={court.id}>
                <div className="customer-court-image"><CourtIllustration/><span className="customer-court-number">Sân {String(court.courtNumber).padStart(2, '0')}</span></div>
                <div className="customer-court-info">
                  <div className="customer-court-location"><Icon name="pin" size={15}/> {court.district || 'Chưa cập nhật quận / huyện'}</div>
                  <h3>{court.name}</h3>
                  <p className="customer-court-type">{court.type === 'WOODEN_FLOOR' ? 'Sàn gỗ' : 'Thảm tiêu chuẩn'} <span>•</span> Mã sân {String(court.courtNumber).padStart(2, '0')}</p>
                  <div className="customer-slot-row"><span>{session ? 'Khung giờ còn:' : 'Khung giờ:'}</span>{!session ? <span>Đăng nhập để kiểm tra tình trạng trống</span> : availableSlots.length ? availableSlots.slice(0, 4).map(slot => <button type="button" key={`${court.id}:${slot.timeSlotId}`} aria-pressed={slot.timeSlotId === cardSlot} className={slot.timeSlotId === cardSlot ? 'customer-slot-chip selected' : 'customer-slot-chip'} onClick={() => {
                    setCourtSlots(current => ({...current, [court.id]: slot.timeSlotId}));
                    setExtraSlots(current => ({...current, [court.id]: (current[court.id] || []).filter(id => id !== slot.timeSlotId)}));
                  }}>{shortTime(slot.startTime)} – {shortTime(slot.endTime)}</button>) : <span className="customer-no-slots">Không còn khung giờ trống trong ngày</span>}</div>
                  {session && selectedAvailable && cardSlot && <div className="customer-slot-row customer-extra-slots"><span>Đặt thêm khung giờ:</span>{availableSlots.filter(slot => slot.timeSlotId !== cardSlot).map(slot => <label className="customer-slot-chip" key={`${court.id}:${slot.timeSlotId}`}><input type="checkbox" value={slot.timeSlotId} checked={(extraSlots[court.id] || []).includes(slot.timeSlotId)} onChange={event => setExtraSlots(current => ({...current, [court.id]: event.target.checked ? [...(current[court.id] || []), slot.timeSlotId] : (current[court.id] || []).filter(id => id !== slot.timeSlotId)}))}/>{shortTime(slot.startTime)} – {shortTime(slot.endTime)}</label>)}</div>}
                  <div className="customer-court-price-row"><div><strong>{priceText}</strong><span>/ khung giờ</span><small>{quote ? `Giá cho ${shortTime(quote.startTime || slots.find(slot => slot.id === cardSlot)?.startTime)} – ${shortTime(quote.endTime || slots.find(slot => slot.id === cardSlot)?.endTime)}` : 'Chọn khung giờ để xem giá'}</small></div><button className="customer-button customer-button-primary customer-book-button" disabled={!bookingClock.ready || !eligibleSlots.some(slot => slot.id === cardSlot) || (session && !selectedAvailable) || !quote || quoteLoading} onClick={() => {
                    sessionStorage.setItem(BOOKING_DRAFT_KEY, JSON.stringify({
                      courtId: court.id, bookingDate: date, timeSlotId: cardSlot,
                      courtName: court.name, courtNumber: court.courtNumber, courtType: court.type,
                      details: [...new Set([cardSlot, ...(extraSlots[court.id] || []).filter(id => availableSlots.some(slot => slot.timeSlotId === id))])].map(timeSlotId => ({courtId: court.id, bookingDate: date, timeSlotId})),
                    }));
                    window.location.assign(session ? '/checkout' : '/login?redirect=%2Fcheckout');
                  }} aria-describedby={`booking-disabled-${court.id}`}><Icon name="arrow" size={17}/> Đặt sân</button><span className="customer-sr-only" id={`booking-disabled-${court.id}`}>Chọn ngày và khung giờ còn trống để xem lại thông tin đặt sân.</span></div>
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
