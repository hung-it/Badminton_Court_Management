import { useEffect, useState } from 'react';
import { Link, NavLink, useLocation } from 'react-router-dom';
import { CircleX, Menu, X } from 'lucide-react';
import { tokenStore } from '../lib/apiClient';
import { useAuth } from '../lib/authContext';
import { fetchBookingCount } from '../lib/publicApi';

const NAV_LINKS = [
  { to: '/', label: 'Trang chủ', end: true },
  { to: '/courts', label: 'Sân cầu lông' },
  { to: '/guide', label: 'Hướng dẫn' },
  { to: '/contact', label: 'Liên hệ' },
];

export default function Header() {
  const [menuOpen, setMenuOpen] = useState(false);
  const [bookingCount, setBookingCount] = useState(0);
  const location = useLocation();
  const { user, isAuthenticated } = useAuth();

  useEffect(() => {
    setMenuOpen(false);
  }, [location.pathname]);

  // Badge "Lịch đặt của tôi" lấy totalElements từ GET /api/bookings (cần token).
  useEffect(() => {
    let cancelled = false;
    if (!isAuthenticated || !tokenStore.access) {
      setBookingCount(0);
      return undefined;
    }
    fetchBookingCount()
      .then((count) => {
        if (!cancelled) setBookingCount(count);
      })
      .catch(() => {
        /* badge là thông tin phụ: im lặng khi lỗi */
      });
    return () => {
      cancelled = true;
    };
  }, [location.pathname, isAuthenticated]);

  return (
    <header className="header">
      <div className="header__top">
        <div className="container header__top-inner">
          <span>Hỗ trợ: 1900 1000</span>
          <div className="header__top-actions">
            {user ? (
              <>
                <Link to="/my-bookings" className="header__link-with-badge">
                  Lịch đặt của tôi
                  {bookingCount > 0 && (
                    <span className="header__badge">{bookingCount > 99 ? '99+' : bookingCount}</span>
                  )}
                </Link>
                <Link to="/profile">{user.fullName || user.email}</Link>
              </>
            ) : (
              <>
                <span>Tài khoản</span>
                <Link to="/login">Đăng nhập</Link>
                <Link to="/register" className="badge">
                  Đăng ký
                </Link>
              </>
            )}
          </div>
        </div>
      </div>

      <div className="container header__nav">
        <Link to="/" className="brand">
          <span className="brand__mark">
            <CircleX size={22} strokeWidth={2.5} />
          </span>
          <span className="brand__text">
            <strong>Sân cầu lông</strong>
            <span>Đặt sân nhanh • Giá tốt • Gần bạn</span>
          </span>
        </Link>

        <nav className="nav-links">
          {NAV_LINKS.map((link) => (
            <NavLink
              key={link.to}
              to={link.to}
              end={link.end}
              className={({ isActive }) => (isActive ? 'is-active' : undefined)}
            >
              {link.label}
            </NavLink>
          ))}
        </nav>

        <Link to="/booking" className="btn btn--primary header__cta">
          Đặt sân ngay
        </Link>

        <button
          type="button"
          className="header__menu-toggle"
          aria-label={menuOpen ? 'Đóng menu' : 'Mở menu'}
          aria-expanded={menuOpen}
          onClick={() => setMenuOpen((open) => !open)}
        >
          {menuOpen ? <X size={20} /> : <Menu size={20} />}
        </button>
      </div>

      <div className={`header__mobile-menu${menuOpen ? ' is-open' : ''}`}>
        {NAV_LINKS.map((link) => (
          <NavLink key={link.to} to={link.to} end={link.end}>
            {link.label}
          </NavLink>
        ))}
        {user && (
          <NavLink to="/my-bookings">
            Lịch đặt của tôi{bookingCount > 0 ? ` (${bookingCount})` : ''}
          </NavLink>
        )}
        {user ? (
          <NavLink to="/profile">Hồ sơ ({user.email})</NavLink>
        ) : (
          <>
            <NavLink to="/login">Đăng nhập</NavLink>
            <NavLink to="/register">Đăng ký</NavLink>
          </>
        )}
        <Link to="/booking" className="btn btn--primary btn--block">
          Đặt sân ngay
        </Link>
      </div>
    </header>
  );
}
