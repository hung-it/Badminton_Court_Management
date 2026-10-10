import { useState } from 'react';
import { Check, ClipboardList, Plus, WalletCards, X } from 'lucide-react';
import { posApi } from './api';

const money = (value) => new Intl.NumberFormat('vi-VN', { style: 'currency', currency: 'VND', maximumFractionDigits: 0 }).format(value);
const useApi = import.meta.env.VITE_USE_API === 'true';

function HistoryModal({ items, onClose }) {
  return <div className="modal-backdrop"><div className="modal history-modal"><div className="modal-head"><div><p className="eyebrow">TRẢ SÂN</p><h3>Lịch sử trả sân</h3></div><button className="icon-button subtle" onClick={onClose}><X size={18} /></button></div>{items.length === 0 ? <p className="muted">Chưa có phiếu trả sân đã thanh toán.</p> : items.map((item) => <div className="history-detail" key={item.id}><div className="history-item"><div><strong>{item.id}</strong><span>Booking #{item.bookingId} · {item.paymentMethod}</span></div><strong>{money(item.totalAmount)}</strong></div><div className="history-breakdown"><div><span>Giá thuê sân</span><strong>{money(item.courtAmount)}</strong></div><div><span>Dịch vụ đã dùng</span><strong>{item.serviceName}</strong></div><div><span>Giá dịch vụ</span><strong>{money(item.productAmount)}</strong></div><div><span>Trạng thái</span><strong className="paid-label">Đã thanh toán</strong></div></div></div>)}</div></div>;
}

export default function CheckoutPanel({ notify, onPaid, history }) {
  const [bookingId, setBookingId] = useState(import.meta.env.VITE_BOOKING_ID || 'BK-2038');
  const [paymentMethod, setPaymentMethod] = useState('CASH');
  const [slip, setSlip] = useState(null);
  const [formOpen, setFormOpen] = useState(false);
  const [historyOpen, setHistoryOpen] = useState(false);
  const [courtAmount, setCourtAmount] = useState(360000);
  const [serviceName, setServiceName] = useState('Nước, thuê vợt và cầu lông');
  const [productAmount, setProductAmount] = useState(145000);

  const createSlip = () => {
    const court = Number(courtAmount) || 0;
    const product = Number(productAmount) || 0;
    setSlip({ id: `PT-${Date.now().toString().slice(-6)}`, bookingId, courtAmount: court, productAmount: product, totalAmount: court + product, serviceName, status: 'DRAFT' });
    setFormOpen(false);
    notify('Đã lập phiếu sân', 'Phiếu tạm tính đang chờ xác nhận và thu tiền.');
  };
  const confirmPayment = async () => {
    if (!slip || slip.status === 'PAID') return;
    try {
      let paidSlip = { ...slip, paymentMethod, status: 'PAID' };
      if (useApi) {
        const result = await posApi.checkout(bookingId, paymentMethod);
        const data = result.data?.data;
        paidSlip = { ...paidSlip, invoiceId: data?.invoiceId, courtAmount: data?.courtAmount || slip.courtAmount, productAmount: data?.productAmount || slip.productAmount, totalAmount: data?.totalAmount || slip.totalAmount };
      }
      setSlip(paidSlip);
      onPaid(paidSlip);
      notify('Đã thanh toán', `Phiếu ${paidSlip.id} đã được lưu vào Lịch sử trả sân.`);
    } catch (error) {
      notify('Không thể thanh toán', error.response?.data?.message || 'Kiểm tra booking và kết nối backend.');
    }
  };
  return <section className="content"><div className="page-intro"><div><p className="eyebrow">CHECK-OUT / TRẢ SÂN</p><h2>Kết toán</h2></div><div className="checkout-actions"><button className="primary-button" onClick={() => setFormOpen(true)}><Plus size={17} /> Lập phiếu sân</button><button className="outline-button" onClick={() => setHistoryOpen(true)}><ClipboardList size={16} /> Lịch sử trả sân</button></div></div>{!slip ? <div className="panel empty-checkout"><WalletCards size={30} /><h3>Chưa có phiếu sân</h3><p>Lập phiếu để nhập tiền sân và các dịch vụ phát sinh.</p></div> : <div className="checkout-layout"><section className="panel invoice-paper"><div className="invoice-top"><div><span className="invoice-label">PHIẾU TẠM TÍNH</span><h3>Phiếu {slip.id}</h3><p>Booking <strong>#{slip.bookingId}</strong> · Sân 04</p></div><span className={slip.status === 'PAID' ? 'status success' : 'status pending'}>{slip.status === 'PAID' ? 'Đã thanh toán' : 'Chờ thanh toán'}</span></div><div className="invoice-row"><span>Giá thuê sân</span><strong>{money(slip.courtAmount)}</strong></div><div className="invoice-row"><span>Dịch vụ đã dùng: {slip.serviceName}</span><strong>{money(slip.productAmount)}</strong></div><div className="invoice-total"><div><span>Tiền sân</span><strong>{money(slip.courtAmount)}</strong></div><div><span>Tiền hàng hóa / dịch vụ</span><strong>{money(slip.productAmount)}</strong></div><div className="total-line"><span>TỔNG THU</span><strong>{money(slip.totalAmount)}</strong></div></div></section><aside className="panel checkout-side"><div className="checkout-side-heading"><div className="round-icon teal-bg"><WalletCards size={20} /></div><div><h3>{slip.status === 'PAID' ? 'Phiếu đã thanh toán' : 'Xác nhận & thu tiền'}</h3><p>{slip.status === 'PAID' ? 'Đã lưu vào lịch sử trả sân' : 'Chọn phương thức thanh toán'}</p></div></div>{slip.status !== 'PAID' && <><button className={paymentMethod === 'CASH' ? 'payment selected' : 'payment'} onClick={() => setPaymentMethod('CASH')}><span className="payment-icon">₫</span><strong>Tiền mặt</strong>{paymentMethod === 'CASH' && <Check size={17} />}</button><button className={paymentMethod === 'QR' ? 'payment selected' : 'payment'} onClick={() => setPaymentMethod('QR')}><span className="payment-icon bank">QR</span><strong>QR / chuyển khoản</strong>{paymentMethod === 'QR' && <Check size={17} />}</button><button className="primary-button full" onClick={confirmPayment}><Check size={18} /> Xác nhận & thu tiền</button></>}</aside></div>}{formOpen && <div className="modal-backdrop"><div className="modal"><div className="modal-head"><div><p className="eyebrow">TRẢ SÂN</p><h3>Lập phiếu sân</h3></div><button className="icon-button subtle" onClick={() => setFormOpen(false)}><X size={18} /></button></div><label>Mã booking<input value={bookingId} onChange={(event) => setBookingId(event.target.value)} /></label><label>Giá thuê sân<input type="number" value={courtAmount} onChange={(event) => setCourtAmount(event.target.value)} /></label><label>Dịch vụ đã dùng<input value={serviceName} onChange={(event) => setServiceName(event.target.value)} /></label><label>Giá dịch vụ<input type="number" value={productAmount} onChange={(event) => setProductAmount(event.target.value)} /></label><button className="primary-button full" onClick={createSlip}>Tạo phiếu tạm tính</button></div></div>}{historyOpen && <HistoryModal items={history} onClose={() => setHistoryOpen(false)} />}</section>;
}
