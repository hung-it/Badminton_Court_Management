import React, {useEffect, useState} from "react";
import {api, readSession, clearSession} from "./CustomerCourts.jsx";
import {assets, GuideFooter, GuideHeader, GuideIcon, SupportBanner} from "./CustomerLayout.jsx";
import './customer-guide.css';
export {GuideFooter, GuideHeader} from "./CustomerLayout.jsx";

const steps = [
  {
    title: 'Tìm sân phù hợp',
    description: 'Vào Sân cầu lông, chọn ngày chơi, khung giờ và mức giá mong muốn để tìm sân phù hợp với hội bạn.',
    note: 'Kiểm tra thông tin sân và khung giờ trước khi chọn.',
    icon: 'search',
    panelTitle: 'ĐỊA ĐIỂM CỦA BẠN',
    panelValue: 'TP. Hồ Chí Minh',
    panelFooter: 'Lọc theo khu vực • Giá',
  },
  {
    title: 'Chọn ngày và khung giờ',
    description: 'Chọn ngày chơi, giờ bắt đầu và giờ kết thúc. Xem các khung giờ còn trống, sau đó chọn số sân và thời lượng bạn cần.',
    note: 'Kiểm tra kỹ ngày và thời lượng trước khi tiếp tục.',
    icon: 'calendar',
    panelTitle: 'LỊCH CHƠI ĐÃ CHỌN',
    panelValue: 'Ngày và giờ bạn chọn',
    panelFooter: 'Thời gian theo khung giờ còn trống',
  },
  {
    title: 'Kiểm tra và đặt sân',
    description: 'Kiểm tra tên sân, lịch chơi và thông tin liên hệ. Xem lại lựa chọn của bạn trước khi xác nhận.',
    note: 'Khung giờ được giữ sau khi máy chủ xác nhận tạo booking; hãy thanh toán trước khi hết hạn.',
    icon: 'clipboard',
    panelTitle: 'TÓM TẮT ĐẶT SÂN',
    panelValue: 'Sân bạn đã chọn',
    panelFooter: 'Giá và thời lượng theo lựa chọn của bạn',
  },
  {
    title: 'Thanh toán và nhận xác nhận',
    description: 'Chọn VNPay Sandbox trên trang xác nhận, làm theo hướng dẫn và chờ hệ thống xác nhận thanh toán.',
    note: 'Chỉ xem xác nhận sau khi hệ thống cập nhật trạng thái thành công.',
    icon: 'card',
    panelTitle: 'THANH TOÁN',
    panelValue: 'Làm theo hướng dẫn thanh toán',
    panelFooter: 'Mã đặt sân hiển thị sau khi xác nhận',
  },
  {
    title: 'Xem và quản lý lịch đặt',
    description: 'Mở Lịch đặt của tôi trên thanh điều hướng để xem lịch sắp tới, lịch sử và chi tiết đặt sân. Liên hệ hỗ trợ nếu bạn cần hỏi về đổi hoặc hủy lịch.',
    note: 'Khi đến sân, đưa mã đặt sân cho nhân viên để được nhận sân.',
    icon: 'calendarChecked',
    panelTitle: 'LỊCH ĐẶT CỦA TÔI',
    panelValue: 'Xem lịch và trạng thái đặt sân',
    panelFooter: 'Xem chi tiết • Đổi lịch • Yêu cầu hủy',
  },
];

const faqs = [
  {
    question: 'Tôi có thể thay đổi hoặc hủy lịch đã đặt không?',
    answer: 'Ứng dụng hiện chưa hỗ trợ đổi hoặc hủy lịch trực tuyến. Vui lòng liên hệ bộ phận hỗ trợ để được hướng dẫn.',
  },
  {
    question: 'Tôi có thể thanh toán bằng hình thức nào?',
    answer: 'Phương thức thanh toán sẽ được hiển thị khi chức năng đặt sân trực tuyến khả dụng. Hãy kiểm tra số tiền và nội dung giao dịch theo hướng dẫn trên màn hình.',
  },
  {
    question: 'Đã thanh toán nhưng chưa thấy xác nhận thì làm gì?',
    answer: 'Kiểm tra trạng thái trong Lịch đặt của tôi. Nếu giao dịch vẫn đang xử lý, không thanh toán lại. Liên hệ bộ phận hỗ trợ và cung cấp mã giao dịch để được kiểm tra.',
  },
  {
    question: 'Khi đến sân, tôi cần mang theo những gì?',
    answer: 'Chuẩn bị mã đặt sân trong tài khoản và dụng cụ chơi. Kiểm tra thông tin liên hệ hoặc hướng dẫn của sân trước khi đến.',
  },
];

export default function CustomerGuide() {
  const [session, setSession] = useState(readSession);
  const [bookingCount, setBookingCount] = useState(0);
  const [bookingLoading, setBookingLoading] = useState(true);
  const [bookingError, setBookingError] = useState(null);
  const [bookingRetry, setBookingRetry] = useState(0);

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
        if (!Number.isFinite(total) || !Number.isInteger(total) || total < 0) {
          throw new Error('Phản hồi lịch đặt không đúng định dạng.');
        }
        if (active) setBookingCount(total);
      })
      .catch(reason => {
        if (active) setBookingError({
          status: reason.status,
          message: [401, 403].includes(reason.status)
            ? 'Phiên đăng nhập khách hàng đã hết hạn. Vui lòng đăng nhập lại.'
            : reason.message || 'Không tải được lịch đặt.',
        });
      })
      .finally(() => { if (active) setBookingLoading(false); });
    return () => { active = false; };
  }, [session, bookingRetry]);

  function logout() {
    clearSession();
    setSession(null);
  }



  return <div className="guide-page" id="guide-top">
    <GuideHeader session={session} bookingCount={bookingCount} bookingError={bookingError} bookingLoading={bookingLoading} onLogout={logout}/>
    {bookingError && <div className="guide-booking-alert" role="alert"><span>{bookingError.message}</span><div><button onClick={() => setBookingRetry(value => value + 1)}>Thử lại</button>{[401, 403].includes(bookingError.status) && <button onClick={logout}>Đăng nhập lại</button>}</div></div>}
    <main className="customer-content guide-main">
      <section className="guide-hero" aria-labelledby="guide-title">
        <div className="guide-hero-copy">
          <div className="guide-breadcrumb"><a href="/courts">Trang chủ</a><GuideIcon src={assets.chevronRight}/><strong>Hướng dẫn</strong></div>
          <h1 id="guide-title">Từ tìm sân đến ra sân,<br/>chỉ vài bước đơn giản.</h1>
          <p>Mọi điều bạn cần để đặt sân tự tin, thanh toán thuận tiện và chủ động quản lý lịch chơi của mình.</p>
          <div className="guide-pills"><span>5 bước dễ thực hiện</span><span>Hỗ trợ xuyên suốt 24/7</span></div>
        </div>
        <figure className="guide-hero-image">
          <img src="/assets/guide-court.jpg" alt="Sân cầu lông trong nhà sáng rộng" />
          <figcaption><GuideIcon src={assets.badgeCheck}/> Sẵn sàng cho trận cầu tiếp theo</figcaption>
        </figure>
      </section>

      <section className="guide-process" aria-label="Quy trình đặt sân">
        <aside className="guide-toc-column">
          <nav className="guide-toc" aria-label="Trong hướng dẫn này">
            <h2>Trong hướng dẫn này</h2>
            {steps.map((step, index) => <a key={step.title} href={`#step-${index + 1}`}><span>{String(index + 1).padStart(2, '0')}</span>{step.title}</a>)}
            <a className="guide-toc-faq" href="#faq">Câu hỏi thường gặp <span aria-hidden="true">↓</span></a>
          </nav>
          <aside className="guide-bookings-promo">
            <GuideIcon src={assets.calendar}/>
            <h2>Đã có lịch hẹn ra sân?</h2>
            <p>Lịch chơi và mã đặt sân luôn có sẵn trong tài khoản của bạn.</p>
            <a href="/my-bookings">Lịch đặt của tôi <span aria-hidden="true">→</span></a>
          </aside>
        </aside>

        <div className="guide-steps">
          {steps.map((step, index) => <article className="guide-step-card" id={`step-${index + 1}`} key={step.title}>
            <span className="guide-step-number">{String(index + 1).padStart(2, '0')}</span>
            <div className="guide-step-copy">
              <h2>{step.title}</h2>
              <p>{step.description}</p>
              <span className="guide-step-note"><GuideIcon src={assets.info}/>{step.note}</span>
            </div>
            <aside className="guide-step-example" aria-label={`Thông tin minh họa: ${step.panelTitle}`}>
              <span className="guide-example-title"><GuideIcon src={assets[step.icon]}/>{step.panelTitle}</span>
              <strong>{step.panelValue}</strong>
              <span className="guide-example-footer">{step.panelFooter}</span>
            </aside>
          </article>)}
          <p className="guide-footnote">Thông tin sân, lịch và giá sẽ được lấy từ lựa chọn thực tế của bạn.</p>
        </div>
      </section>

      <section className="guide-faq" id="faq" aria-labelledby="faq-title">
        <div className="guide-section-heading"><div><h2 id="faq-title">Bạn hỏi, chúng tôi giải đáp</h2><p>Những điều thường gặp trước và sau khi đặt sân.</p></div><span>Câu hỏi thường gặp</span></div>
        <div className="guide-faq-grid">{faqs.map(item => <details className="guide-faq-card" key={item.question} open>
          <summary><span>{item.question}</span><GuideIcon src={assets.minus}/></summary>
          <p>{item.answer}</p>
        </details>)}</div>
      </section>

      <SupportBanner/>
    </main>
    <GuideFooter />
  </div>;
}
