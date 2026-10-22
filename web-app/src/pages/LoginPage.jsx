import { useState } from 'react';
import { Link, useLocation, useNavigate } from 'react-router-dom';
import {
  ArrowLeft,
  ArrowRight,
  BadgeCheck,
  CalendarCheck,
  Headset,
  LockKeyhole,
  MapPin,
  ShieldCheck,
  Sparkles,
  UserRound,
} from 'lucide-react';
import AuthField from '../components/AuthField';
import { useAuth } from '../lib/authContext';
import authSideImage from '../assets/auth-side.png';

const BENEFITS = [
  {
    icon: MapPin,
    title: 'Sân chất lượng cao tại TP.HCM',
    body: 'Nhiều sân đang mở cửa với lịch trống cập nhật liên tục.',
  },
  {
    icon: CalendarCheck,
    title: 'Đặt sân chỉ trong 30 giây',
    body: 'Lịch trống rõ ràng, xác nhận nhanh chóng.',
  },
  {
    icon: Headset,
    title: 'Luôn sẵn sàng hỗ trợ',
    body: 'Đồng hành cùng bạn 24/7.',
  },
];

export default function LoginPage() {
  const navigate = useNavigate();
  const location = useLocation();
  const { login, isAuthenticated } = useAuth();

  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [remember, setRemember] = useState(true);
  const [error, setError] = useState('');
  const [submitting, setSubmitting] = useState(false);

  const redirectTo = new URLSearchParams(location.search).get('redirect') || '/';

  if (isAuthenticated) {
    navigate(redirectTo, { replace: true });
  }

  async function handleSubmit(event) {
    event.preventDefault();
    setError('');
    setSubmitting(true);
    const result = await login({ email: email.trim(), password, remember });
    setSubmitting(false);

    if (result.ok) {
      navigate(redirectTo, { replace: true });
    } else {
      setError(result.message);
    }
  }

  return (
    <div className="auth">
      <div className="container">
        <Link to="/" className="auth__back">
          <ArrowLeft size={18} />
          Về trang chủ
        </Link>

        <div className="auth__grid">
          <aside className="auth__aside">
            <div className="auth__intro">
              <span className="badge">
                <Sparkles size={16} />
                Đặt sân nhanh • Thanh toán tiện lợi
              </span>
              <h1 className="auth__title">
                Sân quen đang chờ,
                <br />
                sẵn sàng ra sân thôi!
              </h1>
              <p className="auth__lead">
                Đăng nhập để đặt sân yêu thích, quản lý lịch chơi và không bỏ lỡ những ưu đãi dành riêng cho
                bạn.
              </p>
            </div>

            <div className="auth__media">
              <img src={authSideImage} alt="Sân cầu lông trong nhà" />
              <span className="badge auth__media-badge">
                <BadgeCheck size={16} />
                Sân chất lượng cao
              </span>
              <span className="auth__media-cta">
                <UserRound size={18} />
                Hẹn hội bạn, giữ ngay sân đẹp.
              </span>
            </div>

            <ul className="auth__benefits">
              {BENEFITS.map((item) => (
                <li key={item.title}>
                  <span className="auth__benefit-icon">
                    <item.icon size={20} />
                  </span>
                  <span>
                    <strong>{item.title}</strong>
                    <span>{item.body}</span>
                  </span>
                </li>
              ))}
            </ul>
          </aside>

          <div className="auth__panel">
            <div className="auth__tabs">
              <span className="auth__tab is-active">Đăng nhập</span>
              <Link to="/register" className="auth__tab">
                Đăng ký
              </Link>
            </div>

            <div className="auth__heading">
              <h2>Chào mừng bạn trở lại!</h2>
              <p>Đăng nhập để tiếp tục đặt sân và quản lý lịch chơi của bạn.</p>
            </div>

            {error && (
              <div className="alert alert--error" role="alert">
                {error}
              </div>
            )}

            <form className="auth__form" onSubmit={handleSubmit} noValidate>
              {/* M1: backend chỉ nhận email (@Email), không nhận số điện thoại. */}
              <AuthField
                label="Email *"
                icon={UserRound}
                type="email"
                value={email}
                onChange={setEmail}
                placeholder="Nhập email của bạn"
                autoComplete="email"
                inputMode="email"
                required
              />

              <AuthField
                label="Mật khẩu *"
                icon={LockKeyhole}
                type="password"
                value={password}
                onChange={setPassword}
                placeholder="Nhập mật khẩu của bạn"
                autoComplete="current-password"
                required
              />

              <div className="auth__options">
                <label className="auth__checkbox">
                  <input
                    type="checkbox"
                    checked={remember}
                    onChange={(e) => setRemember(e.target.checked)}
                  />
                  <span>Ghi nhớ đăng nhập</span>
                </label>
                {/* M14: backend chưa có endpoint quên mật khẩu → không để link chết. */}
                <span className="auth__help">Cần trợ giúp? Gọi 1900 1000</span>
              </div>

              <button type="submit" className="btn btn--primary btn--block" disabled={submitting}>
                {submitting ? 'Đang đăng nhập…' : 'Đăng nhập'}
                {!submitting && <ArrowRight size={18} />}
              </button>
            </form>

            <p className="auth__switch">
              Chưa có tài khoản? <Link to="/register">Đăng ký ngay</Link>
            </p>

            <p className="auth__security">
              <ShieldCheck size={15} />
              Thông tin của bạn luôn được bảo mật.
            </p>
          </div>
        </div>

        <p className="auth__support">
          <Headset size={16} />
          Cần hỗ trợ? Gọi 1900 1000 (24/7)
        </p>
      </div>
    </div>
  );
}
