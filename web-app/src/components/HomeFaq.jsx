import { useState } from 'react';
import { Minus, Plus } from 'lucide-react';

/**
 * Nội dung FAQ bám đúng hành vi backend hiện tại:
 *  - Booking PENDING được giữ 15 phút (booking.hold-duration) rồi chuyển EXPIRED.
 *  - Thanh toán online qua VNPay.
 *  - Không có cột amenities/rating nên không hứa tiện ích/đánh giá cụ thể.
 */
const FAQS = [
  {
    q: 'Làm thế nào để đặt sân nhanh nhất?',
    a: 'Chọn ngày chơi và khung giờ ngay trên thanh tìm kiếm, sau đó chọn sân còn trống và hoàn tất thanh toán qua VNPay để nhận mã booking.',
  },
  {
    q: 'Tôi có bao lâu để hoàn tất thanh toán?',
    a: 'Sau khi tạo booking, hệ thống giữ chỗ sân trong 15 phút. Nếu chưa thanh toán, lịch sẽ tự động được giải phóng và chuyển sang trạng thái hết hạn.',
  },
  {
    q: 'Tôi có thể thanh toán bằng hình thức nào?',
    a: 'Hiện tại nền tảng hỗ trợ thanh toán trực tuyến qua cổng VNPay. Bạn thanh toán ngay trên trang xác nhận booking, không cần trả tiền tại quầy.',
  },
  {
    q: 'Sân có tiện ích gì?',
    a: 'Danh sách tiện ích chi tiết của từng sân đang được bổ sung. Bạn có thể xem loại sàn, giá theo khung giờ và lịch trống ngay trên bảng đặt sân.',
  },
  {
    q: 'Sân nào đang mở cửa?',
    a: 'Tất cả sân hiển thị trong danh sách đều đang hoạt động. Sân đang bảo trì hoặc tạm đóng sẽ không xuất hiện trên trang đặt sân.',
  },
];

export default function HomeFaq() {
  const [openIndex, setOpenIndex] = useState(0);

  return (
    <section className="section section--white" id="faq">
      <div className="container">
        <div className="section-heading">
          <h2>Câu hỏi thường gặp</h2>
          <p>Những thông tin cần biết trước khi đặt sân và sử dụng dịch vụ.</p>
        </div>

        <div className="faq-list">
          {FAQS.map((item, index) => {
            const isOpen = openIndex === index;
            return (
              <div className="faq-item" key={item.q}>
                <button
                  type="button"
                  className="faq-item__q"
                  aria-expanded={isOpen}
                  onClick={() => setOpenIndex(isOpen ? -1 : index)}
                >
                  <span>{item.q}</span>
                  <span className="faq-item__icon">
                    {isOpen ? <Minus size={20} /> : <Plus size={20} />}
                  </span>
                </button>
                {isOpen && <p className="faq-item__a">{item.a}</p>}
              </div>
            );
          })}
        </div>
      </div>
    </section>
  );
}
