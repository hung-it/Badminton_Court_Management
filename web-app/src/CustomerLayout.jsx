import React, {useState} from 'react';
import './customer-layout.css';

export const assets = {
  headset: '/assets/d88dd2c0-45d7-43a6-b62c-d972671aaa50.svg',
  calendarCheck: '/assets/3bb73ea5-e16d-4ed7-8740-a8f2cf2f0cb0.svg',
  chevronDown: '/assets/a9ba3193-e3a5-4959-92c1-2988dd048811.svg',
  brand: '/assets/fff0f6cb-49a7-420c-9230-baad179c53e0.svg',
  chevronRight: '/assets/f91edecd-977e-40c3-8ec6-c6baadeb6143.svg',
  badgeCheck: '/assets/6ba3fe49-2a20-4ba3-8954-9d5fbd0e43e1.svg',
  calendar: '/assets/af3d2096-a777-4183-b416-568b5a34ebbd.svg',
  calendarChecked: '/assets/1154b71c-609e-4dd0-bb43-bf47cc95ce0b.svg',
  clipboard: '/assets/6acf23f0-1154-4db8-9057-6efe9d7efdf8.svg',
  card: '/assets/b8a3c7d8-684e-4d08-8a7d-341395ff5429.svg',
  info: '/assets/463b70ce-7f90-4703-849a-be6b865ca5b5.svg',
  search: '/assets/1e9d019c-2933-49bd-8f54-03d3a54e6c91.svg',
  minus: '/assets/c0cb0f54-cb52-4387-8fc1-94f3c3001c60.svg',
  help: '/assets/f690203b-fb5b-4047-b00f-b0e248dd28be.svg',
  arrow: '/assets/877939c4-8deb-456f-b163-a776a12041d9.svg',
};

export function GuideIcon({src, className = ''}) {
  return <img className={`guide-icon ${className}`} src={src} alt="" aria-hidden="true" />;
}

export function SupportBanner({id = 'support-banner'}) {
  return <section className="customer-support-banner" id={id} aria-label="Hỗ trợ khách hàng">
    <GuideIcon src={assets.help} className="customer-support-icon"/>
    <div className="customer-support-copy">
      <strong>Cần một chút trợ giúp?</strong>
      <span>Gọi 1900 1000 (24/7) hoặc gửi tin nhắn cho chúng tôi.</span>
    </div>
    <a className="customer-support-action" href="tel:19001000">
      <svg aria-hidden="true" width="20" height="20" viewBox="0 0 20 20" fill="none"><path d="M4.166 10H15.834M10 15.834L15.834 10L10 4.166" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"/></svg>
      Liên hệ hỗ trợ
    </a>
  </section>;
}

export function GuideHeader({session, bookingCount, bookingError, bookingLoading, onLogout, activePage = 'guide'}) {
  const [profileOpen, setProfileOpen] = useState(false);
  const hasBookingError = Boolean(session && bookingError);
  const isBookingLoading = Boolean(session && bookingLoading);
  const fullName = session?.user?.fullName || 'Khách';
  const initials = fullName.split(/\s+/).map(part => part[0]).slice(-2).join('').toUpperCase();

  return <header className="guide-header" id="page-top">
    <div className="guide-utility-row">
      <a className="guide-support-link" href="tel:19001000"><GuideIcon src={assets.headset}/> Hỗ trợ: 1900 1000 <span>• 24/7</span></a>
      <div className="guide-account-area">
        <a className="guide-bookings-link" href="/my-bookings" aria-label={hasBookingError ? 'Không tải được lịch đặt của tôi' : isBookingLoading ? 'Đang tải lịch đặt của tôi' : `Lịch đặt của tôi, ${session ? Number(bookingCount) || 0 : 0} lịch`}><GuideIcon src={assets.calendarCheck}/> Lịch đặt của tôi <b title={hasBookingError ? bookingError.message : undefined} aria-live="polite">{!session ? 0 : hasBookingError ? '!' : isBookingLoading ? '…' : Number(bookingCount) || 0}</b></a>
        {!session && <><a href={`/login?redirect=${encodeURIComponent(window.location.pathname + window.location.search)}`}>Đăng nhập</a><a href="/register">Đăng ký</a></>}
        <span className="guide-divider" aria-hidden="true" />
        <div className="guide-profile-wrap">
          <button className="guide-profile-button" aria-expanded={session ? profileOpen : undefined} onClick={() => session && setProfileOpen(value => !value)} disabled={!session}>
            <span className="guide-avatar">{initials || 'KH'}</span><span>{fullName}</span><GuideIcon src={assets.chevronDown} />
          </button>
          {profileOpen && session && <div className="guide-profile-menu"><span>{session.user?.email}</span><button onClick={onLogout}>Đăng xuất</button></div>}
        </div>
      </div>
    </div>
    <div className="guide-main-nav">
      <a className="guide-brand" href="/"><GuideIcon src={assets.brand} className="guide-brand-mark"/><span><strong>Sân cầu lông</strong><small>Đặt sân nhanh • Giá tốt • Gần bạn</small></span></a>
      <nav className="guide-nav-links" aria-label="Điều hướng chính">
        <a href="/">Trang chủ</a><a className={activePage === 'courts' ? 'active' : ''} href="/courts" aria-current={activePage === 'courts' ? 'page' : undefined}>Sân cầu lông</a><a className={activePage === 'guide' ? 'active' : ''} href="/guide" aria-current={activePage === 'guide' ? 'page' : undefined}>Hướng dẫn</a><a className={activePage === 'contact' ? 'active' : ''} href="/contact" aria-current={activePage === 'contact' ? 'page' : undefined}>Liên hệ</a>
      </nav>
        <a className="guide-cta" href="/courts#search-courts">Đặt sân ngay</a>
    </div>
  </header>;
}

export function GuideFooter({activePage = 'guide'}) {
  const contactTarget = activePage === 'contact' ? '#contact-top' : '/contact';
  const faqTarget = activePage === 'guide' ? '#faq' : '/guide#faq';
  const paymentTarget = activePage === 'guide' ? '#step-4' : '/guide#step-4';
  const privacyTarget = activePage === 'contact' ? '#privacy-note' : activePage === 'courts' ? '/contact#privacy-note' : '#support-banner';
  return <footer className="guide-footer">
    <div className="guide-footer-grid">
      <div className="guide-footer-brand">
        <a className="guide-brand" href="/"><GuideIcon src={assets.brand} className="guide-brand-mark"/><span><strong>Sân cầu lông</strong><small>Đặt sân nhanh • Giá tốt • Gần bạn</small></span></a>
        <p>Tìm sân phù hợp, hẹn hội bạn và quản lý lịch chơi dễ dàng. Mỗi trận cầu, một niềm vui.</p>
      </div>
      <div><strong>Khám phá</strong><a href="/courts">Tìm sân cầu lông</a><a href="/my-bookings">Lịch đặt của tôi</a><a href="/guide">Hướng dẫn</a><a href={contactTarget}>Liên hệ</a></div>
      <div><strong>Hỗ trợ</strong><a href={faqTarget}>Câu hỏi thường gặp</a><a href={faqTarget}>Chính sách hủy</a><a href={paymentTarget}>Phương thức thanh toán</a><a href={privacyTarget}>Bảo mật tài khoản</a></div>
      <div><strong>Liên hệ</strong><a href="tel:19001000">Hỗ trợ: 1900 1000</a><a href="mailto:support@sancaulong.vn">support@sancaulong.vn</a><span>TP. Hồ Chí Minh</span><span>Đồng hành cùng bạn 24/7</span></div>
    </div>
    <div className="guide-footer-bottom"><span>© {new Date().getFullYear()} Sân cầu lông. Tất cả quyền được bảo lưu.</span><div><a href={privacyTarget}>Chính sách bảo mật</a><a href={privacyTarget}>Điều khoản sử dụng</a></div></div>
  </footer>;
}

