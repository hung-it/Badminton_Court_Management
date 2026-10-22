import { useState } from 'react';
import { Link, useLocation, useNavigate } from 'react-router-dom';
import {
  ArrowLeft,
  BadgeCheck,
  CalendarDays,
  Heart,
  LockKeyhole,
  Mail,
  MapPin,
  Phone,
  Sparkles,
  Tag,
  UserRound,
} from 'lucide-react';
import AuthField from '../components/AuthField';
import { useAuth } from '../lib/authContext';
import authSideImage from '../assets/auth-side.png';

const BENEFITS = [
  { icon: CalendarDays, title: 'Quản lý lịch chơi dễ dàng', body: 'Lưu lịch đặt sân, không bỏ lỡ trận cầu nào.' },
  { icon: Heart, title: 'Lưu sân yêu thích', body: 'Tìm lại sân quen, đặt lại nhanh hơn.' },
  { icon: Tag, title: 'Ưu đãi cho hội nhóm', body: 'Cập nhật những mức giá tốt dành cho bạn.' },
];

const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
const PHONE_RE = /^[0-9]{10,20}$/;

/**
 * Validate phía client theo đúng ràng buộc của `RegisterRequest`:
 *  - email: @Email + @NotBlank
 *  - password: @Size(min = 6, max = 100)
 *  - fullName: @Size(min = 2, max = 255)
 *  - phone: @Pattern("^[0-9]{10,20}$")
 */
function validate(values) {
  const errors = {};

  if (!values.fullName.trim()) {
    errors.fullName = 'Vui lòng nhập họ và tên.';
  } else if (values.fullName.trim().length < 2) {
    errors.fullName = 'Họ và tên phải có ít nhất 2 ký tự.';
  }

  if (!values.email.trim()) {
    errors.email = 'Vui lòng nhập email.';
  } else if (!EMAIL_RE.test(values.email.trim())) {
    errors.email = 'Email không hợp lệ.';
  }

  if (!values.phone.trim()) {
    errors.phone = 'Vui lòng nhập số điện thoại.';
  } else if (!PHONE_RE.test(values.phone.replace(/\s+/g, ''))) {
    errors.phone = 'Số điện thoại phải là 10–20 chữ số, không khoảng trắng hoặc dấu +.';
  }

  if (!values.password) {
    errors.password = 'Vui lòng nhập mật khẩu.';
  } else if (values.password.length < 6) {
    errors.password = 'Mật khẩu phải có ít nhất 6 ký tự.';
  } else if (values.password.length > 100) {
    errors.password = 'Mật khẩu không được vượt quá 100 ký tự.';
  }

  if (values.confirmPassword !== values.password) {
    errors.confirmPassword = 'Mật khẩu xác nhận không khớp.';
  }

  if (!values.accepted) {
    errors.accepted = 'Bạn cần đồng ý với Điều khoản sử dụng để tiếp tục.';
  }

  return errors;
}

export default function RegisterPage() {
  const navigate = useNavigate();
  const location = useLocation();
  const { register, isAuthenticated } = useAuth();

  const [values, setValues] = useState({
    fullName: '',
    email: '',
    phone: '',
    password: '',
    confirmPassword: '',
    address: '',
    accepted: false,
  });
  const [remember, setRemember] = useState(true);
  const [fieldErrors, setFieldErrors] = useState({});
  const [error, setError] = useState('');
  const [submitting, setSubmitting] = useState(false);

  const redirectTo = new URLSearchParams(location.search).get('redirect') || '/';

  if (isAuthenticated) {
    navigate(redirectTo, { replace: true });
  }

  function setField(field, value) {
    setValues((prev) => ({ ...prev, [field]: value }));
  }

  async function handleSubmit(event) {
    event.preventDefault();
    setError('');

    const errors = validate(values);
    setFieldErrors(errors);
    if (Object.keys(errors).length > 0) return;

    setSubmitting(true);
    const result = await register({
      email: values.email.trim(),
      password: values.password,
      fullName: values.fullName.trim(),
      phone: values.phone.trim(),
      address: values.address.trim() || undefined,
      remember,
    });
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
                Kết nối đam mê • Cùng nhau ra sân
              </span>
              <h1 className="auth__title">
                Thêm một tài khoản,
                <br />
                thêm nhiều trận cầu hay.
              </h1>
              <p className="auth__lead">
                Tham gia để tìm sân gần bạn, lưu lịch chơi và tận hưởng niềm vui cầu lông cùng hội nhóm.
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
              <Link to="/login" className="auth__tab">
                Đăng nhập
              </Link>
              <span className="auth__tab is-active">Đăng ký</span>
            </div>

            <div className="auth__heading">
              <h2>Tạo tài khoản miễn phí</h2>
              <p>Chỉ một phút đăng ký, sẵn sàng cho những trận cầu tiếp theo.</p>
            </div>

            {error && (
              <div className="alert alert--error" role="alert">
                {error}
              </div>
            )}

            <form className="auth__form" onSubmit={handleSubmit} noValidate>
              <AuthField
                label="Họ và tên *"
                icon={UserRound}
                value={values.fullName}
                onChange={(v) => setField('fullName', v)}
                placeholder="Nhập họ và tên của bạn"
                autoComplete="name"
                error={fieldErrors.fullName}
                required
              />

              <AuthField
                label="Email *"
                icon={Mail}
                type="email"
                value={values.email}
                onChange={(v) => setField('email', v)}
                placeholder="Nhập địa chỉ email"
                autoComplete="email"
                inputMode="email"
                error={fieldErrors.email}
                required
              />

              {/* M4: regex backend chỉ nhận 10–20 chữ số, không có + / khoảng trắng. */}
              <AuthField
                label="Số điện thoại *"
                icon={Phone}
                type="tel"
                value={values.phone}
                onChange={(v) => setField('phone', v)}
                placeholder="0912345678"
                autoComplete="tel"
                inputMode="numeric"
                maxLength={20}
                hint="10–20 chữ số, không khoảng trắng hoặc dấu +"
                error={fieldErrors.phone}
                required
              />

              {/* M3: backend yêu cầu min 6 ký tự, không bắt hoa/thường/số. */}
              <AuthField
                label="Mật khẩu *"
                icon={LockKeyhole}
                type="password"
                value={values.password}
                onChange={(v) => setField('password', v)}
                placeholder="Tạo mật khẩu của bạn"
                autoComplete="new-password"
                hint="Ít nhất 6 ký tự."
                error={fieldErrors.password}
                required
              />

              <AuthField
                label="Xác nhận mật khẩu *"
                icon={LockKeyhole}
                type="password"
                value={values.confirmPassword}
                onChange={(v) => setField('confirmPassword', v)}
                placeholder="Nhập lại mật khẩu"
                autoComplete="new-password"
                error={fieldErrors.confirmPassword}
                required
              />

              <AuthField
                label="Địa chỉ (không bắt buộc)"
                icon={MapPin}
                value={values.address}
                onChange={(v) => setField('address', v)}
                placeholder="Số nhà, đường, quận, TP. Hồ Chí Minh"
                autoComplete="street-address"
              />

              <div className="auth__options">
                <label className="auth__checkbox">
                  <input
                    type="checkbox"
                    checked={values.accepted}
                    onChange={(e) => setField('accepted', e.target.checked)}
                  />
                  <span>
                    Tôi đồng ý với Điều khoản sử dụng và Chính sách bảo mật của Sân cầu lông.
                  </span>
                </label>
              </div>
              {fieldErrors.accepted && <p className="form-field__error">{fieldErrors.accepted}</p>}

              <label className="auth__checkbox">
                <input
                  type="checkbox"
                  checked={remember}
                  onChange={(e) => setRemember(e.target.checked)}
                />
                <span>Ghi nhớ đăng nhập</span>
              </label>

              <button type="submit" className="btn btn--primary btn--block" disabled={submitting}>
                {submitting ? 'Đang tạo tài khoản…' : 'Tạo tài khoản'}
              </button>
            </form>

            <p className="auth__switch">
              Đã có tài khoản? <Link to="/login">Đăng nhập</Link>
            </p>
          </div>
        </div>
      </div>
    </div>
  );
}
