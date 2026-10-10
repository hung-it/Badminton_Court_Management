import { useEffect, useState } from 'react';
import { Check, ClipboardList, PackagePlus, Plus, X } from 'lucide-react';
import { posApi } from './api';

const money = (value) => new Intl.NumberFormat('vi-VN', { style: 'currency', currency: 'VND', maximumFractionDigits: 0 }).format(value);
const useApi = import.meta.env.VITE_USE_API === 'true';

export default function InventoryPanel({ notify, orders, setOrders }) {
  const [showForm, setShowForm] = useState(false);
  const [supplier, setSupplier] = useState('Công ty Thể thao Minh Long');
  const [quantity, setQuantity] = useState(10);
  const [price, setPrice] = useState(85000);
  useEffect(() => {
    if (useApi) posApi.importOrders().then((response) => setOrders(response.data?.data || [])).catch(() => notify('Không tải được phiếu nhập', 'Đang hiển thị dữ liệu demo.'));
  }, [notify, setOrders]);
  const approvedValue = orders.filter((order) => order.status === 'RECEIVED').reduce((sum, order) => sum + Number(order.totalAmount || order.total || 0), 0);
  const approve = async (order) => {
    if (useApi) {
      try {
        const response = await posApi.approveImport(order.id);
        setOrders((items) => items.map((item) => item.id === order.id ? response.data?.data : item));
        notify('Phiếu nhập đã được duyệt', 'Tồn kho đã được cộng theo phiếu nhập.');
      } catch (error) {
        notify('Không thể duyệt phiếu', error.response?.data?.message || 'Kiểm tra kết nối backend.');
      }
      return;
    }
    setOrders((items) => items.map((item) => item.id === order.id ? { ...item, status: 'RECEIVED' } : item));
    notify(`${order.id} đã được duyệt`, 'Tồn kho đã được cộng ở chế độ demo.');
  };
  const createOrder = () => {
    const order = { id: `PN-${24092 + orders.length}`, supplier, totalAmount: Number(quantity) * Number(price), status: 'DRAFT' };
    setOrders((items) => [order, ...items]);
    setShowForm(false);
    notify('Đã lập phiếu nhập', `${order.id} đang chờ duyệt.`);
  };
  return <section className="content"><div className="page-intro"><div><p className="eyebrow">KHO & NHÀ CUNG CẤP</p><h2>Nhập hàng</h2></div><button className="primary-button" onClick={() => setShowForm(true)}><Plus size={18} /> Lập phiếu nhập</button></div><div className="inventory-stats"><div className="metric"><div className="metric-icon orange"><ClipboardList size={19} /></div><p>Phiếu chờ duyệt</p><strong>{orders.filter((order) => order.status !== 'RECEIVED').length}</strong></div><div className="metric"><div className="metric-icon blue"><PackagePlus size={19} /></div><p>Giá trị nhập tháng này</p><strong>{money(approvedValue)}</strong></div></div><div className="panel table-panel"><table><thead><tr><th>Mã phiếu</th><th>Nhà cung cấp</th><th>Tổng tiền</th><th>Trạng thái</th><th /></tr></thead><tbody>{orders.map((order) => <tr key={order.id}><td><strong>{order.id}</strong></td><td>{order.supplierName || order.supplier || 'Nhà cung cấp'}</td><td>{money(order.totalAmount || order.total || 0)}</td><td><span className={`status ${order.status === 'RECEIVED' ? 'success' : 'pending'}`}>{order.status === 'RECEIVED' ? 'Đã nhập kho' : 'Chờ duyệt'}</span></td><td>{order.status !== 'RECEIVED' && <button className="approve-button" onClick={() => approve(order)}><Check size={14} /> Duyệt</button>}</td></tr>)}</tbody></table></div>{showForm && <div className="modal-backdrop"><div className="modal"><div className="modal-head"><div><p className="eyebrow">NHẬP KHO</p><h3>Lập phiếu nhập</h3></div><button className="icon-button subtle" onClick={() => setShowForm(false)}><X size={18} /></button></div><label>Nhà cung cấp<select value={supplier} onChange={(event) => setSupplier(event.target.value)}><option>Công ty Thể thao Minh Long</option><option>Nước giải khát Nam Việt</option></select></label><label>Số lượng<input type="number" min="1" value={quantity} onChange={(event) => setQuantity(event.target.value)} /></label><label>Đơn giá<input type="number" min="0" value={price} onChange={(event) => setPrice(event.target.value)} /></label><button className="primary-button full" onClick={createOrder}>Lưu phiếu nhập</button></div></div>}</section>;
}
