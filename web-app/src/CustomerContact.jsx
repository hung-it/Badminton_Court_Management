import React, {useEffect, useState} from 'react';
import {api, readSession, clearSession} from './CustomerCourts.jsx';
import {assets, GuideFooter, GuideHeader, GuideIcon} from './CustomerLayout.jsx';
import './customer-contact.css';

const SUPPORT_EMAIL = 'support@sancaulong.vn';
const MAX_FILE_SIZE = 5 * 1024 * 1024;

function ContactIcon({name, size = 19}) {
  const paths = {
    phone: <><path d="M22 16.92v3a2 2 0 0 1-2.18 2A19.8 19.8 0 0 1 11.19 18a19.5 19.5 0 0 1-6-6A19.8 19.8 0 0 1 2.09 3.18 2 2 0 0 1 4.08 1h3a2 2 0 0 1 2 1.72c.12.96.36 1.9.7 2.8a2 2 0 0 1-.45 2.11L8.06 8.9a16 16 0 0 0 6 6l1.27-1.27a2 2 0 0 1 2.11-.45c.9.34 1.84.58 2.8.7A2 2 0 0 1 22 16.92Z"/></>,
    mail: <><rect x="3" y="5" width="18" height="14" rx="2"/><path d="m3 7 9 6 9-6"/></>,
    message: <><path d="M21 11.5a8.4 8.4 0 0 1-.9 3.8 8.5 8.5 0 0 1-7.6 4.7 8.4 8.4 0 0 1-3.8-.9L3 21l1.9-5.7a8.4 8.4 0 0 1-.9-3.8 8.5 8.5 0 0 1 4.7-7.6 8.4 8.4 0 0 1 3.8-.9h.5a8.5 8.5 0 0 1 8 8v.5Z"/></>,
    headset: <><path d="M3 13v-2a9 9 0 0 1 18 0v2"/><path d="M5 13h2v6H5a2 2 0 0 1-2-2v-2a2 2 0 0 1 2-2Zm14 0h-2v6h2a2 2 0 0 0 2-2v-2a2 2 0 0 0-2-2ZM17 19a5 5 0 0 1-5 2"/></>,
    clock: <><circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/></>,
    pin: <><path d="M20 10c0 5-8 11-8 11S4 15 4 10a8 8 0 1 1 16 0Z"/><circle cx="12" cy="10" r="2.5"/></>,
    arrow: <><path d="M7 17 17 7M7 7h10v10"/></>,
    clip: <><path d="m21.44 11.05-9.19 9.19a6 6 0 0 1-8.49-8.49l9.2-9.19a4 4 0 0 1 5.65 5.66l-9.2 9.19a2 2 0 1 1-2.82-2.83l8.48-8.48"/></>,
    shield: <><path d="M12 22s8-4 8-11V5l-8-3-8 3v6c0 7 8 11 8 11Z"/><path d="m9 12 2 2 4-4"/></>,
    send: <><path d="m22 2-7 20-4-9-9-4Z"/><path d="M22 2 11 13"/></>,
  };
  return <svg aria-hidden="true" width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">{paths[name]}</svg>;
}

function ContactChannel({icon, title, value, description, link, action, target}) {
  return <article className="contact-channel-card">
    <div className="contact-channel-title"><span><ContactIcon name={icon}/></span>{title}</div>
    <strong>{value}</strong>
    <p>{description}</p>
    <a href={link} target={target} rel={target ? 'noreferrer' : undefined}>{action} <span aria-hidden="true">→</span></a>
  </article>;
}

export default function CustomerContact() {
  const [session, setSession] = useState(readSession);
  const [bookingCount, setBookingCount] = useState(0);
  const [bookingLoading, setBookingLoading] = useState(true);
  const [bookingError, setBookingError] = useState(null);
  const [bookingRetry, setBookingRetry] = useState(0);
  const [file, setFile] = useState(null);
  const [fileError, setFileError] = useState('');
  const [notice, setNotice] = useState(null);

  useEffect(() => {
    if (!session?.accessToken) {
      setBookingCount(0);
      setBookingLoading(false);
      setBookingError(null);
      return undefined;
    }
    let active = true;
    setBookingLoading(true);
    setBookingError(null);
    api('/bookings?page=0&size=1', session.accessToken)
      .then(data => {
        const total = Number(data?.totalElements);
        if (!Number.isInteger(total) || total < 0) throw new Error('Phản hồi lịch đặt không đúng định dạng.');
        if (active) setBookingCount(total);
      })
      .catch(reason => {
        if (active) setBookingError({
          status: reason.status,
          message: [401, 403].includes(reason.status) ? 'Phiên đăng nhập khách hàng đã hết hạn. Vui lòng đăng nhập lại.' : reason.message || 'Không tải được lịch đặt.',
        });
      })
      .finally(() => { if (active) setBookingLoading(false); });
    return () => { active = false; };
  }, [session, bookingRetry]);

  function logout() {
    clearSession();
    setSession(null);
  }

  function chooseFile(event) {
    const selected = event.target.files?.[0] || null;
    if (selected && !['image/jpeg', 'image/png'].includes(selected.type)) {
      setFile(null);
      setFileError('Chỉ chấp nhận hình ảnh JPG hoặc PNG.');
      event.target.value = '';
      return;
    }
    if (selected && selected.size > MAX_FILE_SIZE) {
      setFile(null);
      setFileError('Tệp đính kèm phải có dung lượng không quá 5 MB.');
      event.target.value = '';
      return;
    }
    setFile(selected);
    setFileError('');
  }

  function submitRequest(event) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    const subject = `[Hỗ trợ đặt sân] ${form.get('topic')}`;
    const body = [
      `Họ và tên: ${form.get('name')}`,
      `Số điện thoại: ${form.get('phone')}`,
      `Email phản hồi: ${form.get('email')}`,
      `Chủ đề: ${form.get('topic')}`,
      `Mã đặt sân: ${form.get('bookingCode') || 'Không cung cấp'}`,
      '',
      'Nội dung cần hỗ trợ:',
      form.get('message'),
      file ? `\nTệp cần đính kèm: ${file.name} (hãy đính kèm thủ công trong ứng dụng email)` : '',
    ].filter(Boolean).join('\n');
    window.location.href = `mailto:${SUPPORT_EMAIL}?subject=${encodeURIComponent(subject)}&body=${encodeURIComponent(body)}`;
    setNotice('Website chưa gửi yêu cầu đến máy chủ. Hãy kiểm tra ứng dụng email, gửi thư và đính kèm tệp thủ công nếu có.');
  }



  return <div className="guide-page contact-page" id="contact-top">
    <GuideHeader session={session} bookingCount={bookingCount} bookingError={bookingError} bookingLoading={bookingLoading} onLogout={logout} activePage="contact"/>
    {bookingError && <div className="guide-booking-alert" role="alert"><span>{bookingError.message}</span><div><button onClick={() => setBookingRetry(value => value + 1)}>Thử lại</button>{[401, 403].includes(bookingError.status) && <button onClick={logout}>Đăng nhập lại</button>}</div></div>}

    <main className="contact-main">
      <section className="contact-hero" aria-labelledby="contact-title">
        <div>
          <div className="guide-breadcrumb"><a href="/courts">Trang chủ</a><GuideIcon src={assets.chevronRight}/><strong>Liên hệ</strong></div>
          <h1 id="contact-title">Luôn bên bạn,<br/>trước và sau mỗi trận cầu.</h1>
          <p>Bạn cần hỗ trợ đặt sân, thanh toán hay góp ý? Chọn kênh liên hệ phù hợp, chúng tôi sẵn sàng đồng hành.</p>
        </div>
        <aside className="contact-hours-highlight"><ContactIcon name="headset" size={30}/><strong>24/7</strong><span>Hỗ trợ đặt sân mỗi ngày, kể cả cuối tuần và ngày lễ.</span></aside>
      </section>

      <section className="contact-channels" aria-label="Các kênh liên hệ">
        <ContactChannel icon="phone" title="Gọi tổng đài" value="1900 1000" description="Hỗ trợ đặt sân và xử lý lịch chơi 24/7" link="tel:19001000" action="Gọi hỗ trợ"/>
        <ContactChannel icon="mail" title="Gửi email" value={SUPPORT_EMAIL} description="Gửi chi tiết vấn đề và mã đặt sân của bạn" link={`mailto:${SUPPORT_EMAIL}`} action="Soạn email"/>
        <ContactChannel icon="message" title="Gửi yêu cầu trực tuyến" value="Chúng tôi luôn lắng nghe" description="Điền biểu mẫu bên dưới để được hỗ trợ" link="#contact-form" action="Viết lời nhắn"/>
      </section>

      <div className="contact-content-grid">
        <section className="contact-form-card" id="contact-form" aria-labelledby="contact-form-title">
          <h2 id="contact-form-title">Gửi lời nhắn cho chúng tôi</h2>
          <p className="contact-form-intro">Mô tả điều bạn cần hỗ trợ để chúng tôi giúp bạn tốt hơn. Các trường có dấu <b>*</b> là bắt buộc.</p>
          {notice && <p className="contact-form-notice" role="status">{notice}</p>}
          <form onSubmit={submitRequest}>
            <div className="contact-form-grid">
              <label className="contact-field">Họ và tên <b>*</b><input name="name" autoComplete="name" required maxLength={255} defaultValue={session?.user?.fullName || ''} placeholder="Nhập họ và tên"/></label>
              <label className="contact-field">Số điện thoại <b>*</b><input name="phone" type="tel" autoComplete="tel" required pattern="[0-9+() .-]{10,20}" maxLength={20} placeholder="Nhập số điện thoại"/></label>
              <label className="contact-field contact-field-wide">Email <b>*</b><input name="email" type="email" autoComplete="email" required maxLength={254} placeholder="Nhập email để nhận phản hồi" defaultValue={session?.user?.email || ''}/></label>
              <label className="contact-field">Chủ đề <b>*</b><select name="topic" defaultValue="Hỗ trợ đặt sân" required><option>Hỗ trợ đặt sân</option><option>Thanh toán</option><option>Thông tin tài khoản</option><option>Góp ý dịch vụ</option><option>Khác</option></select></label>
              <label className="contact-field">Mã đặt sân <span>(không bắt buộc)</span><input name="bookingCode" maxLength={64} placeholder="VD: SC-241026"/></label>
              <label className="contact-field contact-field-wide">Nội dung cần hỗ trợ <b>*</b><textarea name="message" required minLength={10} maxLength={3000} placeholder="Bạn đang gặp vấn đề gì? Hãy chia sẻ thông tin sân, ngày chơi và nội dung cần hỗ trợ…"/></label>
              <div className="contact-field contact-field-wide">
                <label className="contact-file-label" htmlFor="contact-file"><ContactIcon name="clip"/><strong>Đính kèm hình ảnh</strong><span>Không bắt buộc • JPG, PNG • Tối đa 5 MB</span><input id="contact-file" type="file" accept="image/jpeg,image/png" onChange={chooseFile}/></label>
                {file && <span className="contact-file-name">Tệp đã chọn: {file.name}</span>}
                {fileError && <span className="contact-file-error" role="alert">{fileError}</span>}
              </div>
              <label className="contact-consent contact-field-wide"><input name="consent" type="checkbox" required/><span>Tôi đồng ý để Sân cầu lông sử dụng thông tin trên nhằm phản hồi yêu cầu này theo <a href="#privacy-note">thông tin bảo mật</a>.</span></label>
            </div>
            <div className="contact-form-bottom"><p><ContactIcon name="shield" size={16}/> Không chia sẻ mật khẩu hoặc mã OTP.</p><button type="submit" className="contact-submit"><ContactIcon name="send"/> Gửi yêu cầu</button></div>
          </form>
          <p className="contact-privacy-note" id="privacy-note">Khi gửi, trình duyệt chỉ mở ứng dụng email của bạn với nội dung đã điền. Website chưa có dịch vụ nhận yêu cầu; bạn cần tự gửi email. Tệp đính kèm cần được thêm thủ công trong ứng dụng email.</p>
        </section>

        <aside className="contact-sidebar">
          <section className="contact-side-card contact-hours-card"><h2><ContactIcon name="clock"/> Khi nào bạn có thể liên hệ?</h2><div><span>Tổng đài hỗ trợ</span><strong>24/7</strong></div><div><span>Ngày hỗ trợ</span><strong>Thứ Hai – Chủ Nhật</strong></div><p>Nếu lịch chơi sắp bắt đầu hoặc thanh toán gặp lỗi, hãy gọi 1900 1000 để được hỗ trợ trực tiếp.</p></section>
          <section className="contact-side-card contact-location-card"><h2><ContactIcon name="pin"/> Sân ở gần bạn</h2><p>Kết nối sân cầu lông tại TP. Hồ Chí Minh:</p><a href="/courts"><ContactIcon name="pin" size={16}/><strong>TP. Hồ Chí Minh</strong><ContactIcon name="arrow" size={16}/></a><small>Thông tin địa chỉ cụ thể sẽ hiển thị cùng sân và lịch đặt khi có dữ liệu.</small></section>
          <section className="contact-links-card"><h2>Có thể bạn đang tìm…</h2><a href="/guide">Hướng dẫn đặt sân &amp; thanh toán <span aria-hidden="true">→</span></a><a href="/guide#faq">Câu hỏi thường gặp <span aria-hidden="true">→</span></a><a href="/my-bookings">Xem và quản lý lịch đặt của tôi <span aria-hidden="true">→</span></a></section>
        </aside>
      </div>
    </main>
    <GuideFooter activePage="contact"/>
  </div>;
}
