import React,{useEffect,useState} from 'react';
const money=n=>new Intl.NumberFormat('vi-VN',{style:'currency',currency:'VND'}).format(n||0);
const methods={CASH:'Tiền mặt',BANK_TRANSFER:'Chuyển khoản',VNPAY:'VNPay'};
export default function RevenueInvoices({api,params}){
 const [page,setPage]=useState(0),[refresh,setRefresh]=useState(0),[history,setHistory]=useState(null),[busy,setBusy]=useState(true),[error,setError]=useState('');
 const [selected,setSelected]=useState(null),[detail,setDetail]=useState(null),[detailError,setDetailError]=useState('');
 useEffect(()=>{let active=true;setBusy(true);setError('');setHistory(null);
  api('/analytics/invoices?'+params+'&page='+page).then(v=>{if(active)setHistory(v)}).catch(e=>{if(active)setError(e.message)}).finally(()=>{if(active)setBusy(false)});
  return()=>{active=false};
 },[params,page,refresh]);
 useEffect(()=>{let active=true;setDetail(null);setDetailError('');if(!selected)return;
  api('/analytics/invoices/'+selected).then(v=>{if(active)setDetail(v)}).catch(e=>{if(active)setDetailError(e.message)});
  const escape=e=>{if(e.key==='Escape')setSelected(null)};document.addEventListener('keydown',escape);
  return()=>{active=false;document.removeEventListener('keydown',escape)};
 },[selected]);
 const invoice=detail?.invoice;
 return <section className="content-panel">
  <div className="toolbar"><h3 style={{margin:0}}>Lịch sử hóa đơn</h3><span className="count">{history?.totalElements??0} hóa đơn</span><button className="secondary" disabled={busy} onClick={()=>setRefresh(v=>v+1)}>Làm mới</button></div>
  {busy?<p className="empty" role="status">Đang tải hóa đơn…</p>:error?<p className="alert error" role="alert">{error}</p>:!history?.invoices.length?<p className="empty">Chưa có hóa đơn đã thanh toán trong kỳ này.</p>:<>
   <div className="table-scroll"><table><thead><tr><th>Mã hóa đơn</th><th>Thời gian</th><th>Khách hàng</th><th>Tiền sân</th><th>Tiền hàng</th><th>Giảm giá</th><th>Tổng hóa đơn</th><th>Thao tác</th></tr></thead><tbody>{history.invoices.map(i=><tr key={i.id}>
    <td title={i.id}><code>{i.id.slice(0,8)}</code></td><td>{new Date(i.createdAt).toLocaleString('vi-VN')}</td><td>{i.customerName}</td><td>{money(i.courtFee)}</td><td>{money(i.productFee)}</td><td>{money(i.discountAmount)}</td><td><strong>{money(i.totalAmount)}</strong></td><td><button className="secondary" onClick={()=>setSelected(i.id)}>Chi tiết</button></td>
   </tr>)}</tbody></table></div>
   <div className="toolbar"><button className="secondary" disabled={page===0} onClick={()=>setPage(v=>v-1)}>Trang trước</button><span>Trang {page+1} / {history.totalPages}</span><button className="secondary" disabled={page+1>=history.totalPages} onClick={()=>setPage(v=>v+1)}>Trang sau</button></div>
  </>}
  {selected&&<div className="overlay"><section className="dialog invoice-report" role="dialog" aria-modal="true" aria-labelledby="invoice-report-title" tabIndex={-1} ref={e=>{if(e&&!e.contains(document.activeElement))e.focus()}}>
   <div className="dialog-head"><h2 id="invoice-report-title">Chi tiết hóa đơn</h2><button aria-label="Đóng chi tiết hóa đơn" onClick={()=>setSelected(null)}>✕</button></div>
   {detailError?<p className="alert error" role="alert">{detailError}</p>:!detail?<p role="status">Đang tải chi tiết…</p>:<>
    <p className="invoice-reference">Hóa đơn: <code>{invoice.id}</code></p><p>Khách hàng: <strong>{invoice.customerName}</strong></p><p>Thời gian: {new Date(invoice.createdAt).toLocaleString('vi-VN')} · {methods[invoice.paymentMethod]||invoice.paymentMethod}</p><p className="invoice-reference">{invoice.bookingId?<>Đơn đặt sân: <code>{invoice.bookingId}</code></>:'Khách mua hàng riêng · Không kèm sân'}</p>
    <h3>Tiền sân</h3>{detail.courts.length?<div className="table-scroll"><table><thead><tr><th>Sân</th><th>Ngày</th><th>Khung giờ</th><th>Giá đã lưu</th></tr></thead><tbody>{detail.courts.map((c,i)=><tr key={i}><td>{c.name}</td><td>{c.date}</td><td>{c.startTime.slice(0,5)}–{c.endTime.slice(0,5)}</td><td>{money(c.price)}</td></tr>)}</tbody></table></div>:<p className="muted">{invoice.bookingId?'Không có chi tiết khung giờ được lưu.':'Không thuê sân.'}</p>}
    <h3>Hàng hóa & dịch vụ</h3>{detail.items.length?<div className="table-scroll"><table><thead><tr><th>Mặt hàng</th><th>SL</th><th>Đơn giá</th><th>Giảm giá</th><th>Thành tiền</th></tr></thead><tbody>{detail.items.map((l,i)=><tr key={i}><td>{l.name}{l.promotionName&&<small className="promotion-applied">{l.promotionName}</small>}</td><td>{l.quantity}</td><td>{money(l.unitPrice)}</td><td>{money(l.discountAmount)}</td><td>{money(l.totalAmount)}</td></tr>)}</tbody></table></div>:<p className="muted">Không có mặt hàng kèm theo.</p>}
    <div className="invoice-row"><span>Tiền sân:</span><strong>{money(invoice.courtFee)}</strong></div><div className="invoice-row"><span>Tiền hàng:</span><strong>{money(invoice.productFee)}</strong></div><div className="invoice-row"><span>Tổng giảm giá:</span><strong>−{money(invoice.discountAmount)}</strong></div><div className="pos-summary-total"><span>Tổng hóa đơn ghi nhận doanh thu:</span><strong>{money(invoice.totalAmount)}</strong></div>
   </>}
   <div className="dialog-foot"><button className="secondary" onClick={()=>setSelected(null)}>Đóng</button></div>
  </section></div>}
 </section>;
}
