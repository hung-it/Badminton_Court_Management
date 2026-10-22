import { Link } from 'react-router-dom';
import { Calendar, MapPin, ShieldCheck } from 'lucide-react';
import heroImage from '../assets/hero.png';

/**
 * Stat card đầu tiên lấy số sân thật từ GET /api/public/courts.
 * Hai stat còn lại là thông tin tĩnh của dịch vụ.
 */
export default function HomeHero({ courtCount, loading }) {
  return (
    <section className="hero">
      <div className="container hero__inner">
        <div className="hero__copy">
          <span className="badge">Đặt sân nhanh • Thanh toán tiện lợi</span>

          <h1 className="hero__title">Đặt sân cầu lông dễ dàng, giá tốt, gần bạn</h1>

          <p className="hero__lead">
            Tìm sân chất lượng cao, so sánh giá, đặt lịch nhanh và quản lý booking mọi lúc mọi nơi. Hỗ trợ
            24/7, thanh toán tiện lợi qua VNPay.
          </p>

          <div className="hero__actions">
            <Link to="/booking" className="btn btn--primary">
              Đặt sân ngay
            </Link>
            <Link to="/courts" className="btn btn--secondary">
              Xem tất cả sân
            </Link>
          </div>

          <div className="hero__stats">
            <div className="stat-card">
              <strong>{loading || courtCount === 0 ? '—' : `${courtCount}+`}</strong>
              <span>Sân tại TP. Hồ Chí Minh</span>
            </div>
            <div className="stat-card">
              <strong>24/7</strong>
              <span>Hỗ trợ đặt sân và hủy lịch</span>
            </div>
            <div className="stat-card">
              <strong>0đ</strong>
              <span>Phí đặt sân khi hủy trước giờ</span>
            </div>
          </div>
        </div>

        <div className="hero__visual">
          <div className="hero__image">
            <img src={heroImage} alt="Sân cầu lông trong nhà tại TP. Hồ Chí Minh" />
            <span className="badge">Sân chất lượng cao</span>
            <span className="badge badge--solid">Đặt ngay trong 30 giây</span>
          </div>

          <div className="hero__features">
            <div className="feature-card">
              <span className="feature-card__icon">
                <MapPin size={20} />
              </span>
              <h3>Tìm sân gần bạn</h3>
              <p>Xem lịch trống theo ngày và mức giá phù hợp.</p>
            </div>
            <div className="feature-card">
              <span className="feature-card__icon">
                <Calendar size={20} />
              </span>
              <h3>Lịch trống rõ ràng</h3>
              <p>Chọn khung giờ còn trống ngay trên bảng lịch.</p>
            </div>
            <div className="feature-card">
              <span className="feature-card__icon">
                <ShieldCheck size={20} />
              </span>
              <h3>Thanh toán an toàn</h3>
              <p>Đặt giữ chỗ nhanh, thanh toán qua VNPay sandbox.</p>
            </div>
          </div>
        </div>
      </div>
    </section>
  );
}
