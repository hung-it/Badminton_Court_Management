/**
 * Trang tạm cho các màn hình chưa triển khai (booking, login, profile...).
 * Giữ layout + điều hướng để link trong header/footer không gãy.
 */
export default function PlaceholderPage({ title }) {
  return (
    <div className="section section--green">
      <div className="container">
        <div className="state">
          <span className="state__icon">🚧</span>
          <h3>{title}</h3>
          <p>Màn hình này đang được phát triển. Trang chủ đã hoạt động đầy đủ.</p>
        </div>
      </div>
    </div>
  );
}
