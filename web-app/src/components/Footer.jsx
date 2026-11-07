import { Link } from 'react-router-dom';

const COLUMNS = [
  {
    title: 'Dịch vụ',
    links: [
      { to: '/courts', label: 'Tìm sân' },
      { to: '/guide', label: 'Hướng dẫn' },
      { to: '/contact', label: 'Liên hệ' },
    ],
  },
  {
    title: 'Hỗ trợ',
    links: [
      { to: '/guide', label: 'Hướng dẫn đặt sân' },
      { to: '/guide#faq', label: 'Chính sách hủy' },
      { to: '/guide#step-4', label: 'Phương thức thanh toán' },
      { to: '/#faq', label: 'Câu hỏi thường gặp' },
    ],
  },
  {
    title: 'Kết nối',
    links: [
      { href: 'https://facebook.com', label: 'Facebook' },
      { href: 'https://instagram.com', label: 'Instagram' },
      { href: 'https://youtube.com', label: 'YouTube' },
    ],
  },
];

export default function Footer() {
  return (
    <footer className="footer">
      <div className="container">
        <div className="footer__top">
          <div className="footer__brand">
            <Link to="/" className="brand">
              <span className="brand__mark">
                <svg
                  width="20"
                  height="20"
                  viewBox="0 0 24 24"
                  fill="none"
                  stroke="currentColor"
                  strokeWidth="2.5"
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  aria-hidden="true"
                >
                  <circle cx="12" cy="12" r="9" />
                  <path d="m15 9-6 6M9 9l6 6" />
                </svg>
              </span>
              <span className="brand__text">
                <strong>Sân cầu lông</strong>
                <span>Đặt sân nhanh • Giá tốt • Gần bạn</span>
              </span>
            </Link>
            <p>
              Nền tảng đặt sân cầu lông trực tuyến giúp bạn tìm sân chất lượng cao, so sánh giá và quản lý
              lịch chơi dễ dàng.
            </p>
          </div>

          <div className="footer__cols">
            {COLUMNS.map((col) => (
              <div className="footer__col" key={col.title}>
                <h4>{col.title}</h4>
                {col.links.map((link) =>
                  link.to ? (
                    <Link key={link.label} to={link.to}>
                      {link.label}
                    </Link>
                  ) : (
                    <a key={link.label} href={link.href} target="_blank" rel="noreferrer">
                      {link.label}
                    </a>
                  ),
                )}
              </div>
            ))}

            <div className="footer__col">
              <h4>Liên hệ</h4>
              <p>Hỗ trợ: 1900 1000</p>
              <p>Email: support@sancaulong.vn</p>
              <p>TP. Hồ Chí Minh</p>
            </div>
          </div>
        </div>

        <div className="footer__bottom">
          <span>© 2026 Sân cầu lông. Tất cả quyền được bảo lưu.</span>
          <div className="footer__legal">
            <Link to="/contact#privacy-note">Chính sách bảo mật</Link>
            <Link to="/contact#privacy-note">Điều khoản sử dụng</Link>
          </div>
        </div>
      </div>
    </footer>
  );
}
