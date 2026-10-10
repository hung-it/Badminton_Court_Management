import { useMemo, useState } from 'react';
import { Bell, Boxes, Check, CircleDollarSign, ClipboardList, LayoutDashboard, PackagePlus, Plus, ShoppingCart, Store, Trash2, WalletCards } from 'lucide-react';
import { Search } from 'lucide-react';
import { Bar, CartesianGrid, ComposedChart, Line, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import CheckoutPanel from './CheckoutPanel';
import PosPanel from './PosPanel';
import InventoryPanel from './InventoryPanel';
import DashboardPanel from './DashboardPanel';

const money = (value) => new Intl.NumberFormat('vi-VN', { style: 'currency', currency: 'VND', maximumFractionDigits: 0 }).format(value);
const products = [
  { id: 'water', name: 'Nước suối Aquafina', type: 'Đồ uống', price: 10000 },
  { id: 'shuttle', name: 'Cầu lông Yonex AS-30', type: 'Dụng cụ', price: 85000 },
  { id: 'racket', name: 'Thuê vợt ProAce', type: 'Dịch vụ', price: 50000 },
];
const chartData = ['T2', 'T3', 'T4', 'T5', 'T6', 'T7', 'CN'].map((name, index) => ({
  name,
  court: [3100000, 4250000, 3890000, 5140000, 6280000, 8450000, 7920000][index],
  product: [840000, 1260000, 990000, 1570000, 2180000, 3120000, 2880000][index],
}));

function App() {
  const [page, setPage] = useState('dashboard');
  const [cart, setCart] = useState([{ ...products[0], quantity: 2 }]);
  const [history, setHistory] = useState([]);
  const [checkoutHistory, setCheckoutHistory] = useState([]);
  const [revenue, setRevenue] = useState({ court: 0, product: 0 });
  const [orders, setOrders] = useState([{ id: 'PN-24091', supplier: 'Công ty Thể thao Minh Long', totalAmount: 4850000, status: 'DRAFT' }]);
  const [messages, setMessages] = useState([{ id: 1, title: 'Hệ thống sẵn sàng', detail: 'Bạn có thể bắt đầu vận hành.', time: 'Vừa xong' }]);
  const [openMessages, setOpenMessages] = useState(false);
  const [toast, setToast] = useState('');
  const total = useMemo(() => cart.reduce((sum, item) => sum + item.price * item.quantity, 0), [cart]);
  const notify = (title, detail) => {
    setToast(title);
    setMessages((items) => [{ id: Date.now(), title, detail: detail || 'Thao tác đã được ghi nhận.', time: 'Vừa xong' }, ...items]);
    window.setTimeout(() => setToast(''), 2400);
  };
  const recordSale = ({ amount, items }) => {
    const sale = { id: `#HD-${1084 + history.length}`, type: 'Hàng hóa / dịch vụ', customer: 'Khách vãng lai', time: new Date().toLocaleTimeString('vi-VN', { hour: '2-digit', minute: '2-digit' }), total: amount, items };
    setHistory((items) => [sale, ...items]);
    setRevenue((items) => ({ ...items, product: items.product + amount }));
  };
  const recordCheckout = (slip) => {
    const { courtAmount, productAmount, totalAmount } = slip;
    const sale = { id: `#HD-${1084 + history.length}`, type: 'Trả sân', customer: 'Nguyễn Minh Anh', time: new Date().toLocaleTimeString('vi-VN', { hour: '2-digit', minute: '2-digit' }), total: totalAmount };
    setHistory((items) => [sale, ...items]);
    setCheckoutHistory((items) => [{ ...slip, ...sale, status: 'PAID' }, ...items]);
    setRevenue((items) => ({ court: items.court + courtAmount, product: items.product + productAmount }));
  };
  const pages = [['dashboard', 'Tổng quan', LayoutDashboard], ['pos', 'Bán tại quầy', ShoppingCart], ['checkout', 'Trả sân', WalletCards], ['inventory', 'Nhập kho', PackagePlus]];
  return <div className="shell">
    <aside className="sidebar"><div className="brand"><div className="brand-mark">BC</div><div><strong>BCM</strong><span>operations desk</span></div></div><div className="workspace-label">VẬN HÀNH CLB</div><nav>{pages.map(([id, label, Icon]) => <button key={id} className={page === id ? 'nav-item active' : 'nav-item'} onClick={() => setPage(id)}><Icon size={18} /><span>{label}</span></button>)}</nav></aside>
    <main className="main"><header className="topbar"><div><p className="eyebrow">THỨ SÁU, 09 THÁNG 10 2026</p><h1>{pages.find(([id]) => id === page)[1]}</h1></div><div className="top-actions"><div className="notification-wrap"><button className="icon-button" onClick={() => setOpenMessages((value) => !value)} title="Thông báo"><Bell size={18} /><span className="notification-dot" /></button>{openMessages && <div className="notification-panel"><div className="notification-heading"><strong>Thông báo gần đây</strong><span>{messages.length}</span></div>{messages.map((item) => <div className="notification-item" key={item.id}><div className="notification-icon"><Check size={14} /></div><div><strong>{item.title}</strong><p>{item.detail}</p><small>{item.time}</small></div></div>)}</div>}</div></div></header>
    {page === 'dashboard' && <DashboardPanel history={history} revenue={revenue} />}
    {page === 'pos' && <PosPanel notify={notify} onPaid={recordSale} history={history} />}
    {page === 'checkout' && <CheckoutPanel notify={notify} onPaid={recordCheckout} history={checkoutHistory} />}
    {page === 'inventory' && <InventoryPanel notify={notify} orders={orders} setOrders={setOrders} />}
    {toast && <div className="toast"><Check size={17} /> {toast}</div>}
    </main>
  </div>;
}

function Intro({ eyebrow, title, action }) { return <div className="page-intro"><div><p className="eyebrow">{eyebrow}</p>{title && <h2>{title}</h2>}</div>{action}</div>; }
function Metric({ icon: Icon, label, value, tone }) { return <div className="metric"><div className={`metric-icon ${tone}`}><Icon size={19} /></div><p>{label}</p><strong>{value}</strong></div>; }
function Dashboard({ history }) { const court = chartData.reduce((sum, item) => sum + item.court, 0); const product = chartData.reduce((sum, item) => sum + item.product, 0); return <section className="content"><Intro eyebrow="DOANH THU & HIỆU SUẤT" action={<select className="select-control" defaultValue="Tuần này"><option>Hôm nay</option><option>Tuần này</option><option>Tháng này</option></select>} /><div className="metric-grid"><Metric icon={CircleDollarSign} label="Tổng doanh thu" value={money(court + product)} tone="teal" /><Metric icon={Store} label="Tiền sân" value={money(court)} tone="blue" /><Metric icon={ShoppingCart} label="Hàng hóa / dịch vụ" value={money(product)} tone="orange" /></div><section className="panel chart-panel"><div className="panel-heading"><div><p className="eyebrow">PHÂN TÍCH DOANH THU</p><h3>Sân và quầy theo ngày</h3></div></div><div className="chart-wrap"><ResponsiveContainer width="100%" height="100%"><ComposedChart data={chartData}><CartesianGrid vertical={false} stroke="#e7eeec" /><XAxis dataKey="name" /><YAxis tickFormatter={(value) => `${Math.round(value / 1000000)}tr`} /><Tooltip formatter={(value) => money(value)} /><Bar dataKey="court" name="Tiền sân" fill="#0f766e" /><Bar dataKey="product" name="Hàng hóa" fill="#f2ad46" /><Line dataKey={(item) => item.court + item.product} name="Tổng" stroke="#193c31" /></ComposedChart></ResponsiveContainer></div></section><h3 className="section-title">Giao dịch mới nhất</h3><History history={history} /></section>; }
function History({ history }) { return <div className="panel table-panel"><table><thead><tr><th>Mã giao dịch</th><th>Khách hàng</th><th>Thời gian</th><th>Giá trị</th><th>Trạng thái</th></tr></thead><tbody>{history.length === 0 ? <tr><td colSpan="5">Chưa có giao dịch mới</td></tr> : history.map((item) => <tr key={item.id}><td><strong>{item.id}</strong></td><td>{item.customer}</td><td>{item.time}</td><td>{money(item.total)}</td><td><span className="status success">Đã thanh toán</span></td></tr>)}</tbody></table></div>; }
function Pos({ cart, setCart, total, onPaid }) { const add = (product) => setCart((items) => [...items, { ...product, quantity: 1 }]); const change = (id, amount) => setCart((items) => items.map((item) => item.id === id ? { ...item, quantity: Math.max(1, item.quantity + amount) } : item)); return <section className="content"><Intro eyebrow="POS BÁN LẺ" title="Tạo đơn trong vài chạm." /><div className="pos-layout"><section className="panel catalog-panel"><div className="search-box"><Search size={17} /><input placeholder="Tìm sản phẩm hoặc dịch vụ..." /></div><div className="product-grid">{products.map((product) => <button className="product-card" key={product.id} onClick={() => add(product)}><div className="product-art blue"><Boxes size={25} /></div><div className="product-info"><span>{product.type}</span><strong>{product.name}</strong><b>{money(product.price)}</b></div><div className="add-product"><Plus size={17} /></div></button>)}</div></section><aside className="panel cart-panel"><div className="cart-heading"><h3>Đơn hiện tại</h3><button className="icon-button" onClick={() => setCart([])}><Trash2 size={17} /></button></div><div className="cart-items">{cart.map((item, index) => <div className="cart-item" key={`${item.id}-${index}`}><div className="cart-item-main"><strong>{item.name}</strong><span>{money(item.price)}</span><div className="stepper"><button onClick={() => change(item.id, -1)}>−</button><b>{item.quantity}</b><button onClick={() => change(item.id, 1)}>+</button></div></div><strong>{money(item.price * item.quantity)}</strong></div>)}</div><div className="cart-summary"><div><span>Tổng thanh toán</span><strong>{money(total)}</strong></div><button className="primary-button" disabled={!cart.length} onClick={onPaid}><Check size={18} /> Thanh toán ngay</button></div></aside></div></section>; }
function Checkout({ notify }) { const court = 360000; const product = 145000; return <section className="content"><Intro eyebrow="CHECK-OUT / TRẢ SÂN" title="Kết toán một lượt chơi." /><section className="panel invoice-paper"><h3>Nguyễn Minh Anh · Booking #BK-2038</h3><div className="invoice-row"><span>Tiền sân</span><strong>{money(court)}</strong></div><div className="invoice-row"><span>Tiền hàng hóa / dịch vụ</span><strong>{money(product)}</strong></div><div className="invoice-total"><div className="total-line"><span>TỔNG THU</span><strong>{money(court + product)}</strong></div></div><button className="primary-button full" onClick={() => notify('Check-out thành công', 'Hóa đơn gộp đã được lưu.')}>Xác nhận và thu tiền</button></section></section>; }
function Inventory({ notify }) { const [approved, setApproved] = useState(false); return <section className="content"><Intro eyebrow="KHO & NHÀ CUNG CẤP" title="Nhập hàng, không nhập nhằng." action={<button className="primary-button"><Plus size={18} /> Lập phiếu nhập</button>} /><div className="inventory-stats"><Metric icon={ClipboardList} label="Phiếu chờ duyệt" value={approved ? '00' : '01'} tone="orange" /><Metric icon={PackagePlus} label="Giá trị nhập tháng này" value={money(14850000)} tone="blue" /></div><div className="panel table-panel"><table><thead><tr><th>Mã phiếu</th><th>Nhà cung cấp</th><th>Tổng tiền</th><th>Trạng thái</th><th /></tr></thead><tbody><tr><td><strong>PN-24091</strong></td><td>Công ty Thể thao Minh Long</td><td>{money(4850000)}</td><td><span className={`status ${approved ? 'success' : 'pending'}`}>{approved ? 'Đã nhập kho' : 'Chờ duyệt'}</span></td><td>{!approved && <button className="approve-button" onClick={() => { setApproved(true); notify('PN-24091 đã được duyệt', 'Tồn kho đã được cộng theo phiếu nhập.'); }}><Check size={14} /> Duyệt</button>}</td></tr></tbody></table></div></section>; }

export default App;
