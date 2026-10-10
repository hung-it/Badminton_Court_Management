import { CircleDollarSign, ShoppingCart, Store } from 'lucide-react';

const money = (value) => new Intl.NumberFormat('vi-VN', { style: 'currency', currency: 'VND', maximumFractionDigits: 0 }).format(value);

function Metric({ icon: Icon, label, value, tone }) {
  return <div className="metric"><div className={`metric-icon ${tone}`}><Icon size={19} /></div><p>{label}</p><strong>{money(value)}</strong></div>;
}

export default function DashboardPanel({ history, revenue }) {
  const total = revenue.court + revenue.product;
  return <section className="content"><div className="page-intro"><div><p className="eyebrow analytics-eyebrow">DOANH THU & HIỆU SUẤT</p></div></div><div className="metric-grid metric-grid-three"><Metric icon={CircleDollarSign} label="Tổng doanh thu" value={total} tone="teal" /><Metric icon={Store} label="Tiền sân" value={revenue.court} tone="blue" /><Metric icon={ShoppingCart} label="Hàng hóa / dịch vụ" value={revenue.product} tone="orange" /></div><h3 className="section-title">Giao dịch mới nhất</h3><div className="panel table-panel"><table><thead><tr><th>Mã giao dịch</th><th>Loại</th><th>Khách hàng</th><th>Thời gian</th><th>Giá trị</th><th>Trạng thái</th></tr></thead><tbody>{history.length === 0 ? <tr><td colSpan="6">Chưa có giao dịch mới</td></tr> : history.map((item) => <tr key={item.id}><td><strong>{item.id}</strong></td><td>{item.type}</td><td>{item.customer}</td><td>{item.time}</td><td><strong>{money(item.total)}</strong></td><td><span className="status success">Đã thanh toán</span></td></tr>)}</tbody></table></div></section>;
}
