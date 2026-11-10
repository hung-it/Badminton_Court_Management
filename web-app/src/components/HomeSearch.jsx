import { useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Calendar, Clock, MapPin, Search, Users } from 'lucide-react';
import { formatDateLabel, formatTime, todayISO } from '../lib/format';

/**
 * Form tìm sân của trang chủ.
 *
 * Theo PLAN.md:
 *  - BỎ bộ lọc "Khu vực" (backend không có cột tỉnh/thành) → hiển thị tĩnh "TP. Hồ Chí Minh".
 *  - BỎ chip tiện ích ("Có bãi xe", "Có phòng thay đồ") vì `courts` không có cột amenities.
 *  - Giữ chip "Giá tốt" như một toggle sắp xếp theo giá tăng dần (client-side, không cần API).
 */
export default function HomeSearch({ courts, timeSlots, loading }) {
  const navigate = useNavigate();
  const [date, setDate] = useState(todayISO());
  const [slotId, setSlotId] = useState('');
  const [courtCount, setCourtCount] = useState('1');
  const [sortByPrice, setSortByPrice] = useState(false);

  const minDate = todayISO();

  const selectedSlot = useMemo(
    () => timeSlots.find((slot) => slot.id === slotId),
    [timeSlots, slotId],
  );

  function handleSubmit(event) {
    event.preventDefault();
    const params = new URLSearchParams({ date, courts: courtCount });
    if (slotId) params.set('timeSlotId', slotId);
    if (sortByPrice) params.set('sort', 'price-asc');
    navigate(`/courts?${params.toString()}`);
  }

  return (
    <section className="section section--green" id="tim-san">
      <div className="container">
        <div className="section-heading">
          <h2>Tìm sân cầu lông phù hợp</h2>
          <p>Chọn ngày chơi và khung giờ để tìm sân nhanh nhất.</p>
        </div>

        <form className="search-card" onSubmit={handleSubmit}>
          <div className="search-card__fields">
            <div className="field">
              <label htmlFor="search-area">Khu vực</label>
              <div className="field__control">
                <MapPin size={20} />
                <span className="field__control--static" id="search-area">
                  TP. Hồ Chí Minh
                </span>
              </div>
            </div>

            <div className="field">
              <label htmlFor="search-date">Ngày chơi</label>
              <div className="field__control">
                <Calendar size={20} />
                <input
                  id="search-date"
                  type="date"
                  value={date}
                  min={minDate}
                  onChange={(e) => setDate(e.target.value)}
                  required
                />
              </div>
              <p className="field__hint">{formatDateLabel(date)}</p>
            </div>

            <div className="field">
              <label htmlFor="search-slot">Khung giờ</label>
              <div className="field__control">
                <Clock size={20} />
                <select
                  id="search-slot"
                  value={slotId}
                  onChange={(e) => setSlotId(e.target.value)}
                  disabled={loading}
                >
                  <option value="">Bất kỳ khung giờ</option>
                  {timeSlots.map((slot) => (
                    <option key={slot.id} value={slot.id}>
                      {formatTime(slot.startTime)} – {formatTime(slot.endTime)}
                    </option>
                  ))}
                </select>
              </div>
              <p className="field__hint">
                {selectedSlot
                  ? `Hệ số giá ×${selectedSlot.priceMultiplier}`
                  : `${timeSlots.length} khung giờ mở cửa`}
              </p>
            </div>

            <div className="field">
              <label htmlFor="search-count">Số sân</label>
              <div className="field__control">
                <Users size={20} />
                <select
                  id="search-count"
                  value={courtCount}
                  onChange={(e) => setCourtCount(e.target.value)}
                  disabled
                >
                  {[1].map((n) => (
                    <option key={n} value={n}>
                      {n} sân
                    </option>
                  ))}
                </select>
              </div>
              <p className="field__hint">
                Mỗi lượt đặt chọn một sân
              </p>
            </div>
          </div>

          <div className="search-card__actions">
            <div className="search-card__filters">
              <button
                type="button"
                className="chip"
                aria-pressed={sortByPrice}
                onClick={() => setSortByPrice((v) => !v)}
                style={
                  sortByPrice
                    ? { background: 'var(--green-600)', color: 'var(--white)' }
                    : undefined
                }
              >
                {sortByPrice ? '✓ Giá tốt' : 'Giá tốt'}
              </button>
            </div>

            <button type="submit" className="btn btn--primary" disabled={loading}>
              <Search size={18} />
              Tìm sân phù hợp
            </button>
          </div>
        </form>
      </div>
    </section>
  );
}
