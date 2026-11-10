import React,{useState} from 'react';
const money=n=>new Intl.NumberFormat('vi-VN',{style:'currency',currency:'VND'}).format(n||0);
export default function CounterReceipt({booking,api,onClose,onReceived}){
 const [method,setMethod]=useState('CASH'),[reference,setReference]=useState(''),[confirmed,setConfirmed]=useState(false),[busy,setBusy]=useState(false),[error,setError]=useState('');
 async function submit(e){
  e.preventDefault();if(!confirmed||busy)return;setBusy(true);setError('');
  try{await api('/pos/bookings/'+booking.bookingId+'/receive','POST',{paymentMethod:method,paymentConfirmed:true,reference:reference.trim()||null,expectedAmount:Number(booking.finalAmount??booking.courtFee)});onReceived();}
  catch(e){setError(e.message);setBusy(false);}
 }
 return <div className="overlay"><section className="dialog small" role="dialog" aria-modal="true" aria-labelledby="counter-receipt-title">
  <div className="dialog-head"><h2 id="counter-receipt-title">Thu tiền sân tại quầy</h2><button aria-label="Đóng thu tiền" disabled={busy} onClick={onClose}>✕</button></div>
  <p>Đơn <code>{booking.shortId||booking.bookingId.slice(0,8)}</code> · {booking.customerName}</p><p>Số tiền cần thu: <strong>{money(booking.finalAmount??booking.courtFee)}</strong></p>
  <form onSubmit={submit}><fieldset disabled={busy} className="form-grid">
   <label className="field">Phương thức<select aria-label="Phương thức" value={method} onChange={e=>{setMethod(e.target.value);setConfirmed(false)}}><option value="CASH">Tiền mặt</option><option value="VNPAY">VNPay tại quầy</option></select></label>
   <label className="field">Mã tham chiếu (nếu có)<input value={reference} maxLength={255} onChange={e=>setReference(e.target.value)}/></label>
   <label><input type="checkbox" checked={confirmed} onChange={e=>setConfirmed(e.target.checked)}/> Tôi xác nhận đã nhận đủ tiền sân</label>
  </fieldset>{error&&<p className="alert error" role="alert">{error}</p>}<div className="dialog-foot"><button type="button" className="secondary" disabled={busy} onClick={onClose}>Hủy</button><button className="primary" disabled={busy||!confirmed}>{busy?'Đang xác nhận…':'Xác nhận thu tiền'}</button></div></form>
 </section></div>;
}
