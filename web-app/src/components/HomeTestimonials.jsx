const TESTIMONIALS = [
  {
    quote:
      '“Đặt sân rất nhanh, giao diện dễ nhìn và hỗ trợ hủy lịch linh hoạt. Sẽ tiếp tục dùng cho hội nhóm của mình.”',
    name: 'Nguyễn Tuấn',
    role: 'Hội nhóm cầu lông TP.HCM',
    initials: 'NT',
  },
  {
    quote: '“Giá hợp lý, sân sạch sẽ, bãi xe rộng rãi. Mình đã giới thiệu cho nhiều hội nhóm khác.”',
    name: 'Lê Minh',
    role: 'Khách hàng thân thiết',
    initials: 'LM',
  },
  {
    quote: '“Hỗ trợ rất tốt, có thể hủy lịch trước giờ và hoàn tiền nhanh. Sẽ tiếp tục sử dụng dịch vụ.”',
    name: 'Trần Huy',
    role: 'Đội bóng công ty',
    initials: 'TH',
  },
];

export default function HomeTestimonials() {
  return (
    <section className="section section--green">
      <div className="container">
        <div className="section-heading">
          <h2>Khách hàng nói gì về chúng tôi</h2>
          <p>Những phản hồi thực tế từ hội nhóm và khách hàng đã đặt sân qua nền tảng.</p>
        </div>

        <div className="testimonial-grid">
          {TESTIMONIALS.map((item) => (
            <figure className="testimonial" key={item.name}>
              <blockquote>{item.quote}</blockquote>
              <figcaption className="testimonial__author">
                <span className="testimonial__avatar">{item.initials}</span>
                <span>
                  <strong>{item.name}</strong>
                  <span>{item.role}</span>
                </span>
              </figcaption>
            </figure>
          ))}
        </div>
      </div>
    </section>
  );
}
