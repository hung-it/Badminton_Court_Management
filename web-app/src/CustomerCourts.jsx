import React, {useCallback, useEffect, useMemo, useRef, useState} from 'react';
import './customer-courts.css';
import {assets, GuideFooter, GuideHeader, GuideIcon, SupportBanner} from './CustomerLayout.jsx';
import client, {tokenStore, toErrorMessage} from './lib/apiClient';

export const SESSION_KEY = 'bcm-customer-session';
export const BOOKING_DRAFT_KEY = 'bcm-booking-draft';
const money = value => new Intl.NumberFormat('vi-VN', {style: 'currency', currency: 'VND', maximumFractionDigits: 0}).format(Number(value) || 0);
const localDate = date => `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`;
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
  const [session, setSession] = useState(readSession);
  const [courts, setCourts] = useState([]);
  const [slots, setSlots] = useState([]);
  const [availability, setAvailability] = useState(null);
  const [quotes, setQuotes] = useState({});
  const [bookings, setBookings] = useState({items: [], total: null, upcoming: null, error: false});
  const [date, setDate] = useState(() => {
    try { return new URLSearchParams(window.location.search).get('date') || JSON.parse(sessionStorage.getItem(BOOKING_DRAFT_KEY))?.bookingDate || localDate(new Date()); }
    catch { return localDate(new Date()); }
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
  const requestVersion = useRef(0);

  const fetchPageData = useCallback(async () => {
    const version = ++requestVersion.current;
    setLoading(true);
    setError('');
    try {
      const [courtData, slotData, availabilityData, bookingPage] = await Promise.all([
        api('/public/courts', null),
        api('/public/time-slots', null),
        session ? api(`/availability?date=${encodeURIComponent(date)}`, session.accessToken) : Promise.resolve(null),
        session ? api('/bookings?page=0&size=20', session.accessToken).catch(() => null) : Promise.resolve(null),
      ]);
      if (version !== requestVersion.current) return;
      setCourts(Array.isArray(courtData) ? courtData : []);
      setSlots(Array.isArray(slotData) ? slotData : []);
      setAvailability(availabilityData);
      setBookings({items: bookingPage?.content || [], total: bookingPage ? Number(bookingPage.totalElements) || 0 : session ? null : 0, upcoming: null, error: Boolean(session && !bookingPage)});
      setSelectedSlot(current => current || slotData?.[0]?.id || '');
      const candidates = (bookingPage?.content || []).filter(item => ['PENDING', 'PAID'].includes(item.status));
      let upcoming = null;
      for (const item of candidates.slice(0, 3)) {
        try {
          const detail = await api(`/bookings/${encodeURIComponent(item.bookingId)}`, session.accessToken);
          const now = localDate(new Date());
          const future = (detail?.details || []).find(entry => entry.bookingDate >= now);
          if (future) {
            upcoming = {header: item, detail: future};
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
  }, [date, session]);

  useEffect(() => {
    fetchPageData();
    return () => { requestVersion.current += 1; };
  }, [fetchPageData]);

  useEffect(() => {
    if (!selectedSlot || !courts.length) {
      setQuotes({});
      return;
    }
    let active = true;
    setQuoteLoading(true);
    Promise.all(courts.map(async court => {
      try {
        const quote = await api(`/public/price-quote?courtId=${encodeURIComponent(court.id)}&timeSlotId=${encodeURIComponent(selectedSlot)}`, null);
        return [court.id, quote];
      } catch {
        return [court.id, null];
      }
    })).then(entries => {
      if (active) setQuotes(Object.fromEntries(entries));
    }).finally(() => { if (active) setQuoteLoading(false); });
    return () => { active = false; };
  }, [courts, selectedSlot]);

  const availabilityByCourt = useMemo(() => new Map((availability?.courts || []).map(court => [court.courtId, court])), [availability]);
  const visibleCourts = useMemo(() => courts.filter(court => {
    const term = query.trim().toLocaleLowerCase('vi');
    const courtAvailability = availabilityByCourt.get(court.id);
    const availableSlots = courtAvailability?.slots?.filter(slot => slot.available) || [];
    const slotAvailable = !session || !selectedSlot || availableSlots.some(slot => slot.timeSlotId === selectedSlot);
    const price = quotes[court.id]?.price;
    return (!term || `${court.name} ${court.courtNumber}`.toLocaleLowerCase('vi').includes(term))
      && (!courtFilter || court.id === courtFilter)
      && (!onlyAvailable || slotAvailable)
      && (!maxPrice || (price != null && Number(price) <= Number(maxPrice)));
  }).sort((left, right) => sortOrder === 'price'
    ? Number(quotes[left.id]?.price ?? Infinity) - Number(quotes[right.id]?.price ?? Infinity)
    : Number(left.courtNumber) - Number(right.courtNumber)), [availabilityByCourt, courts, courtFilter, maxPrice, onlyAvailable, query, quotes, selectedSlot, sortOrder]);

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
            <a href="#upcoming-booking">Mã đặt {bookings.upcoming.header.bookingId.slice(0, 8)} · {bookings.upcoming.header.status}</a>
          </> : <><strong>{!session ? 'Lịch chơi sắp tới' : loading ? 'Đang tải lịch đặt…' : bookings.error ? 'Không tải được lịch đặt' : 'Chưa có lịch chơi sắp tới'}</strong><span>{!session ? 'Đăng nhập để xem lịch đặt của bạn.' : loading ? ' ' : bookings.error ? 'Thử lại để xem các lịch đặt của bạn.' : 'Các lịch đặt sắp tới của bạn sẽ hiển thị ở đây.'}</span><a href={bookings.error ? '#upcoming-booking' : '#search-courts'} onClick={bookings.error ? event => {event.preventDefault(); fetchPageData();} : undefined}>{bookings.error ? 'Tải lại lịch đặt' : 'Tìm sân phù hợp →'}</a></>}
        </aside>
      </section>

      <section className="customer-search-panel" id="search-courts" aria-label="Tìm sân">
        <div className="customer-search-fields">
          <label className="customer-search-field"><span>Địa điểm</span><span className="customer-input-display"><Icon name="pin" size={19}/><span>TP. Hồ Chí Minh</span></span></label>
          <label className="customer-search-field"><span>Ngày chơi</span><span className="customer-input-display"><Icon name="calendar" size={19}/><input aria-label="Ngày chơi" type="date" value={date} onChange={event => setDate(event.target.value)}/></span></label>
          <label className="customer-search-field"><span>Khung giờ</span><span className="customer-input-display"><Icon name="clock" size={20}/><select aria-label="Khung giờ" value={selectedSlot} onChange={event => setSelectedSlot(event.target.value)}><option value="">Tất cả khung giờ</option>{slots.map(slot => <option key={slot.id} value={slot.id}>{shortTime(slot.startTime)} – {shortTime(slot.endTime)}</option>)}</select><Icon name="chevron" size={16}/></span></label>
          <label className="customer-search-field"><span>Giá tối đa / khung giờ</span><span className="customer-input-display"><strong className="customer-dong">₫</strong><input aria-label="Giá tối đa mỗi khung giờ" type="number" min="0" step="10000" placeholder="Không giới hạn" value={maxPrice} onChange={event => setMaxPrice(event.target.value)}/></span></label>
          <button className="customer-button customer-button-primary customer-search-button" onClick={fetchPageData} disabled={loading}><Icon name="search" size={18}/> Tìm sân</button>
        </div>
        <div className="customer-quick-filters"><span>Lọc nhanh:</span><label className={onlyAvailable ? 'selected' : ''}><input type="checkbox" checked={onlyAvailable} onChange={event => setOnlyAvailable(event.target.checked)}/><Icon name="check" size={13}/> Còn sân trong giờ đã chọn</label><button onClick={() => {setQuery(''); setCourtFilter(''); setMaxPrice(''); setOnlyAvailable(true); setSelectedSlot(slots[0]?.id || '');}}>Đặt lại bộ lọc</button></div>
      </section>

      {error && <div className="customer-alert customer-alert-error" role="alert">{error}<button onClick={fetchPageData}>Thử lại</button></div>}
      {notice && <div className="customer-alert customer-alert-info" role="status">{notice}</div>}

      <section className="customer-results-layout" id="court-results" aria-label="Danh sách sân">
        <aside className="customer-filter-card" aria-labelledby="filter-title">
          <h2 id="filter-title"><span className="customer-filter-glyph">☷</span> Bộ lọc chi tiết</h2>
          <label className="customer-filter-label" htmlFor="court-search">Tên sân</label>
          <input className="customer-text-filter" id="court-search" type="search" placeholder="Tìm theo tên sân" value={query} onChange={event => setQuery(event.target.value)}/>
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
              const courtAvailability = availabilityByCourt.get(court.id);
              const availableSlots = courtAvailability?.slots?.filter(slot => slot.available) || [];
              const selectedAvailable = !selectedSlot || availableSlots.some(slot => slot.timeSlotId === selectedSlot);
              const quote = quotes[court.id];
              const priceText = quote ? money(quote.price) : quoteLoading ? 'Đang lấy giá…' : 'Giá chưa có';
              return <article className="customer-court-card" key={court.id}>
                <div className="customer-court-image"><CourtIllustration/><span className="customer-court-number">Sân {String(court.courtNumber).padStart(2, '0')}</span></div>
                <div className="customer-court-info">
                  <div className="customer-court-location"><Icon name="pin" size={15}/> TP. Hồ Chí Minh</div>
                  <h3>{court.name}</h3>
                  <p className="customer-court-type">{court.type === 'WOODEN_FLOOR' ? 'Sàn gỗ' : 'Thảm tiêu chuẩn'} <span>•</span> Mã sân {String(court.courtNumber).padStart(2, '0')}</p>
                  <div className="customer-slot-row"><span>{session ? 'Khung giờ còn:' : 'Khung giờ:'}</span>{!session ? <span>Đăng nhập để kiểm tra tình trạng trống</span> : availableSlots.length ? availableSlots.slice(0, 4).map(slot => <button key={slot.timeSlotId} className={slot.timeSlotId === selectedSlot ? 'customer-slot-chip selected' : 'customer-slot-chip'} onClick={() => setSelectedSlot(slot.timeSlotId)}>{shortTime(slot.startTime)} – {shortTime(slot.endTime)}</button>) : <span className="customer-no-slots">Không còn khung giờ trống trong ngày</span>}</div>
                  <div className="customer-court-price-row"><div><strong>{priceText}</strong><span>/ khung giờ</span><small>{quote ? `Giá cho ${shortTime(quote.startTime || slots.find(slot => slot.id === selectedSlot)?.startTime)} – ${shortTime(quote.endTime || slots.find(slot => slot.id === selectedSlot)?.endTime)}` : 'Chọn khung giờ để xem giá'}</small></div><button className="customer-button customer-button-primary customer-book-button" disabled={!selectedSlot || (session && !selectedAvailable) || !quote || quoteLoading} onClick={() => {
                    sessionStorage.setItem(BOOKING_DRAFT_KEY, JSON.stringify({
                      courtId: court.id, bookingDate: date, timeSlotId: selectedSlot,
                      courtName: court.name, courtNumber: court.courtNumber, courtType: court.type,
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
