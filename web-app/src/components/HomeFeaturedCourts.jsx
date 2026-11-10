import { useMemo } from 'react';
import { Link } from 'react-router-dom';
import { ArrowRight, MapPin } from 'lucide-react';
import { courtTypeLabel, formatVND } from '../lib/format';
import courtImage1 from '../assets/court-1.png';
import courtImage2 from '../assets/court-2.png';
import courtImage3 from '../assets/court-3.png';

/** Ảnh minh hoạ tĩnh theo thứ tự; backend `courts` không có cột imageUrl. */
const PLACEHOLDER_IMAGES = [courtImage1, courtImage2, courtImage3];

function CourtCard({ court, imageUrl }) {
  return (
    <article className="court-card">
      <div className="court-card__media">
        <img src={imageUrl} alt={court.name} loading="lazy" />
        <span className="badge court-card__type">{courtTypeLabel(court.type)}</span>
      </div>

      <div className="court-card__body">
        <div className="court-card__head">
          <h3>{court.name}</h3>
        </div>

        <p className="court-card__number">Sân số {court.courtNumber}</p>

        <p className="court-card__meta">
          <MapPin size={14} style={{ verticalAlign: '-2px', marginRight: 4 }} />
          TP. Hồ Chí Minh • {courtTypeLabel(court.type)}
        </p>

        <div className="court-card__foot">
          <span className="court-card__price">
            {formatVND(court.basePrice)} <small>/ khung giờ</small>
          </span>
          <Link to={`/booking?courtId=${court.id}`} className="court-card__link">
            Đặt ngay
            <ArrowRight size={16} />
          </Link>
        </div>
      </div>
    </article>
  );
}

export default function HomeFeaturedCourts({ courts, loading, error, onRetry }) {
  const featured = useMemo(() => courts.slice(0, 3), [courts]);

  if (loading) {
    return (
      <section className="section section--green">
        <div className="container">
          <div className="section-heading">
            <h2>Sân nổi bật hôm nay</h2>
            <p>Những sân đang mở cửa với giá hợp lý, sẵn sàng đón bạn.</p>
          </div>
          <div className="court-grid">
            {[0, 1, 2].map((i) => (
              <div key={i} className="court-card">
                <div className="skeleton" style={{ height: 240 }} />
                <div className="court-card__body">
                  <div className="skeleton" style={{ height: 24, width: '70%' }} />
                  <div className="skeleton" style={{ height: 18, width: '45%' }} />
                  <div className="skeleton" style={{ height: 28, width: '55%' }} />
                </div>
              </div>
            ))}
          </div>
        </div>
      </section>
    );
  }

  if (error) {
    return (
      <section className="section section--green">
        <div className="container">
          <div className="section-heading">
            <h2>Sân nổi bật hôm nay</h2>
          </div>
          <div className="state state--error">
            <span className="state__icon">!</span>
            <h3>Không tải được danh sách sân</h3>
            <p>{error}</p>
            <button type="button" className="btn btn--secondary" onClick={onRetry}>
              Thử lại
            </button>
          </div>
        </div>
      </section>
    );
  }

  if (featured.length === 0) {
    return (
      <section className="section section--green">
        <div className="container">
          <div className="section-heading">
            <h2>Sân nổi bật hôm nay</h2>
          </div>
          <div className="state">
            <span className="state__icon">🏸</span>
            <h3>Hiện chưa có sân nào mở cửa</h3>
            <p>Vui lòng quay lại sau, hệ thống đang bổ sung thêm sân tại TP. Hồ Chí Minh.</p>
          </div>
        </div>
      </section>
    );
  }

  return (
    <section className="section section--green">
      <div className="container">
        <div className="section-heading">
          <h2>Sân nổi bật hôm nay</h2>
          <p>Những sân đang mở cửa với giá hợp lý, sẵn sàng đón bạn.</p>
        </div>

        <div className="court-grid">
          {featured.map((court, index) => (
            <CourtCard
              key={court.id}
              court={court}
              imageUrl={PLACEHOLDER_IMAGES[index % PLACEHOLDER_IMAGES.length]}
            />
          ))}
        </div>
      </div>
    </section>
  );
}
