import { useEffect, useMemo, useState } from 'react';
import { Boxes, Check, Clock3, Plus, Search, Trash2, X } from 'lucide-react';
import { posApi } from './api';

const demoProducts = [{ id: 'water', name: 'Nước suối Aquafina', type: 'Đồ uống', price: 10000, stockQuantity: 42 }, { id: 'shuttle', name: 'Cầu lông Yonex AS-30', type: 'Dụng cụ', price: 85000, stockQuantity: 18 }, { id: 'racket', name: 'Thuê vợt ProAce', type: 'Dịch vụ', price: 50000, stockQuantity: 9 }];
const money = (value) => new Intl.NumberFormat('vi-VN', { style: 'currency', currency: 'VND', maximumFractionDigits: 0 }).format(value);
const useApi = import.meta.env.VITE_USE_API === 'true';

function SaleHistoryItem({ item }) {
  return <div className="history-detail"><div className="history-item"><div><strong>{item.id}</strong><span>{item.customer} · {item.time}</span></div><strong>{money(item.total)}</strong></div><div className="history-breakdown">{(item.items || []).map((product, index) => <div key={`${product.id}-${index}`}><span>{product.name}</span><strong>{product.quantity} × {money(product.price)} = {money(product.quantity * product.price)}</strong></div>)}<div><span>Trạng thái</span><strong className="paid-label">Đã thanh toán</strong></div></div></div>;
}

export default function PosPanel({ notify, onPaid, history }) {
  const [products, setProducts] = useState(demoProducts);
  const [cart, setCart] = useState([{ ...demoProducts[0], quantity: 2 }]);
  const [showHistory, setShowHistory] = useState(false);
  const total = useMemo(() => cart.reduce((sum, item) => sum + Number(item.price) * item.quantity, 0), [cart]);
  useEffect(() => { if (useApi) posApi.products().then((response) => setProducts(response.data?.data || [])).catch(() => notify('Không tải được sản phẩm', 'Đang hiển thị dữ liệu demo.')); }, [notify]);
  const add = (product) => setCart((items) => [...items, { ...product, quantity: 1 }]);
  const change = (id, amount) => setCart((items) => items.map((item) => item.id === id ? { ...item, quantity: Math.max(1, item.quantity + amount) } : item));
  const pay = async () => {
    if (useApi) {
      const customerId = import.meta.env.VITE_POS_CUSTOMER_ID;
      if (!customerId) return notify('Thiếu khách hàng POS', 'Đặt VITE_POS_CUSTOMER_ID trước khi dùng API thật.');
      try {
        const response = await posApi.sell({ customerId, paymentMethod: 'CASH', items: cart.map((item) => ({ productId: item.id, quantity: item.quantity })) });
        onPaid({ amount: total, items: cart });
        notify('Bán hàng thành công', `Invoice ${response.data?.data?.id || ''} đã được lưu.`);
        setCart([]);
      } catch (error) { notify('Không thể bán hàng', error.response?.data?.message || 'Kiểm tra tồn kho và kết nối backend.'); }
      return;
    }
    onPaid({ amount: total, items: cart });
    notify('Đơn bán lẻ đã thanh toán', `Tổng thu ${money(total)} đã được ghi nhận ở chế độ demo.`);
    setCart([]);
  };
  return <section className="content"><div className="page-intro"><div><p className="eyebrow">POS BÁN LẺ</p><h2>Tạo đơn</h2></div><button className="outline-button" onClick={() => setShowHistory(true)}><Clock3 size={16} /> Lịch sử giao dịch</button></div><div className="pos-layout"><section className="panel catalog-panel"><div className="search-box"><Search size={17} /><input placeholder="Tìm sản phẩm hoặc dịch vụ..." /></div><div className="product-grid">{products.map((product) => <button className="product-card" key={product.id} onClick={() => add(product)}><div className="product-art blue"><Boxes size={25} /></div><div className="product-info"><span>{product.type}</span><strong>{product.name}</strong><b>{money(product.price)}</b><small>Còn {product.stockQuantity ?? 0} sản phẩm</small></div><div className="add-product"><Plus size={17} /></div></button>)}</div></section><aside className="panel cart-panel"><div className="cart-heading"><h3>Đơn hiện tại</h3><button className="icon-button" onClick={() => setCart([])}><Trash2 size={17} /></button></div><div className="cart-items">{cart.map((item, index) => <div className="cart-item" key={`${item.id}-${index}`}><div className="cart-item-main"><strong>{item.name}</strong><span>{money(item.price)}</span><div className="stepper"><button onClick={() => change(item.id, -1)}>−</button><b>{item.quantity}</b><button onClick={() => change(item.id, 1)}>+</button></div></div><strong>{money(item.price * item.quantity)}</strong></div>)}</div><div className="cart-summary"><div><span>Tổng thanh toán</span><strong>{money(total)}</strong></div><button className="primary-button" disabled={!cart.length} onClick={pay}><Check size={18} /> Thanh toán ngay</button></div></aside></div>{showHistory && <div className="modal-backdrop"><div className="modal history-modal"><div className="modal-head"><div><p className="eyebrow">POS BÁN LẺ</p><h3>Lịch sử giao dịch</h3></div><button className="icon-button subtle" onClick={() => setShowHistory(false)}><X size={18} /></button></div>{history.length === 0 ? <p className="muted">Chưa có giao dịch nào.</p> : history.map((item) => <SaleHistoryItem item={item} key={item.id} />)}</div></div>}</section>;
}
