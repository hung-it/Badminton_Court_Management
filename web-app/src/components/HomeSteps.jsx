const STEPS = [
  {
    title: 'Chọn ngày và khung giờ',
    body: 'Xem lịch trống theo ngày, chọn khung giờ phù hợp với lịch chơi của bạn.',
  },
  {
    title: 'Xem giá và chọn sân',
    body: 'Giá hiển thị theo khung giờ, so sánh giữa các sân đang mở cửa tại TP.HCM.',
  },
  {
    title: 'Thanh toán và nhận xác nhận',
    body: 'Thanh toán online qua VNPay, nhận mã booking và quản lý lịch trên tài khoản.',
  },
];

export default function HomeSteps() {
  return (
    <section className="section section--white">
      <div className="container">
        <div className="section-heading">
          <h2>Đặt sân chỉ trong 3 bước</h2>
          <p>Quy trình đơn giản, minh bạch và hỗ trợ xuyên suốt quá trình đặt sân.</p>
        </div>

        <div className="steps">
          {STEPS.map((step, index) => (
            <div className="step" key={step.title}>
              <span className="step__num">{index + 1}</span>
              <h3>{step.title}</h3>
              <p>{step.body}</p>
            </div>
          ))}
        </div>
      </div>
    </section>
  );
}
