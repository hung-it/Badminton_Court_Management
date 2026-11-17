import React,{useEffect,useState} from 'react';
const money=n=>new Intl.NumberFormat('vi-VN',{style:'currency',currency:'VND'}).format(n||0);
const methods={CASH:'Tiền mặt',VNPAY:'VNPay'};

const invoiceStatuses = {
 PAID: { label: 'Đã thanh toán', badge: 'AVAILABLE' },
 DRAFT: { label: 'Chờ thanh toán', badge: 'MAINTENANCE' },
 CANCELLED: { label: 'Đã hủy', badge: 'CLOSED' },
 REFUNDED: { label: 'Đã hoàn tiền', badge: 'neutral' }
};

const txnStatuses = {
 SUCCESS: { label: 'Thành công', badge: 'AVAILABLE' },
 PENDING: { label: 'Đang xử lý', badge: 'MAINTENANCE' },
 FAILED: { label: 'Thất bại', badge: 'CLOSED' },
 REFUNDED: { label: 'Đã hoàn tiền', badge: 'neutral' }
};

const bookingStatusLabels = {
 PENDING: 'Chờ thanh toán',
 PAID: 'Đã đặt',
 COMPLETED: 'Đã hoàn thành',
 CANCELLED: 'Đã hủy',
 EXPIRED: 'Hết hạn'
};

const receiptTargets = {
 COURT_PREPAYMENT: 'Tiền sân',
 CHECKOUT_OR_POS: 'Dịch vụ'
};

function formatReceiptSource(target) {
 return receiptTargets[target] || (target === 'COURT_PREPAYMENT' ? 'Tiền sân' : 'Dịch vụ');
}

export default function RevenueInvoices({api,params="",openId,customer,onClearCustomer}){
 const [search,setSearch]=useState(''),[method,setMethod]=useState(''),[status,setStatus]=useState(''),[from,setFrom]=useState(''),[to,setTo]=useState('');
 const [page,setPage]=useState(0),[refresh,setRefresh]=useState(0),[history,setHistory]=useState(null),[busy,setBusy]=useState(true),[error,setError]=useState('');
 const [selected,setSelected]=useState(openId||null),[detail,setDetail]=useState(null),[detailError,setDetailError]=useState('');
 useEffect(()=>{let active=true;setBusy(true);setError('');setHistory(null);
  const queryParams = new URLSearchParams({page,search,method,...(status?{status}:{}),...(customer?.id?{customerId:customer.id}:{})});
  api('/analytics/invoices?'+(from&&to?'from='+from+'&to='+to:params)+'&'+queryParams).then(v=>{if(active)setHistory(v)}).catch(e=>{if(active)setError(e.message)}).finally(()=>{if(active)setBusy(false)});
  return()=>{active=false};
 },[params,page,refresh,search,method,status,from,to,customer?.id]);
 useEffect(()=>{let active=true;setDetail(null);setDetailError('');if(!selected)return;
  api('/analytics/invoices/'+selected).then(v=>{if(active)setDetail(v)}).catch(e=>{if(active)setDetailError(e.message)});
  const escape=e=>{if(e.key==='Escape')setSelected(null)};document.addEventListener('keydown',escape);
  return()=>{active=false;document.removeEventListener('keydown',escape)};
 },[selected]);
 const invoice=detail?.invoice;
 return <section className="content-panel admin-records invoices-panel">
  <div className="toolbar record-heading"><h3>Lịch sử hóa đơn</h3><span className="count">{history?.totalElements??0} hóa đơn</span><button className="secondary" disabled={busy} onClick={()=>setRefresh(v=>v+1)}>Làm mới</button></div>
  {customer&&<div className="toolbar" role="status"><span>Hóa đơn đã thanh toán · {customer.name}</span><button className="secondary" onClick={onClearCustomer}>Bỏ lọc khách hàng</button></div>}
  <div className="toolbar record-filters">
   <label className="field">Từ ngày<input aria-label="Từ ngày" type="date" value={from} onChange={e=>{setFrom(e.target.value);setPage(0)}}/></label>
   <label className="field">Đến ngày<input aria-label="Đến ngày" type="date" min={from} value={to} onChange={e=>{setTo(e.target.value);setPage(0)}}/></label>
   <label className="field">Tìm hóa đơn<input aria-label="Tìm hóa đơn" placeholder="Mã hóa đơn, khách, booking" value={search} onChange={e=>{setSearch(e.target.value);setPage(0)}}/></label>
   <label className="field">Phương thức<select aria-label="Phương thức" value={method} onChange={e=>{setMethod(e.target.value);setPage(0)}}><option value="">Mọi phương thức</option>{Object.entries(methods).map(([k,v])=><option key={k} value={k}>{v}</option>)}</select></label>
   <label className="field">Trạng thái<select aria-label="Trạng thái" value={status} onChange={e=>{setStatus(e.target.value);setPage(0)}}><option value="">Tất cả trạng thái</option><option value="PAID">Đã thanh toán</option><option value="DRAFT">Chờ thanh toán</option><option value="CANCELLED">Đã hủy</option></select></label>
  </div>
  {busy?<p className="empty" role="status">Đang tải hóa đơn…</p>:error?<p className="alert error" role="alert">{error}</p>:!history?.invoices.length?<p className="empty">Chưa có hóa đơn đã thanh toán trong kỳ này.</p>:<>
   <div className="table-scroll"><table><thead><tr><th>Mã hóa đơn</th><th>Thời gian</th><th>Mã khách hàng</th><th>Booking</th><th>Loại</th><th>Thanh toán</th><th>Trạng thái</th><th>Tổng hóa đơn</th><th>Thao tác</th></tr></thead><tbody>{history.invoices.map(i=>{
    const st = invoiceStatuses[i.invoiceStatus] || { label: i.invoiceStatus || '—', badge: 'neutral' };
    return <tr key={i.id}>
     <td title={i.id}><code>{i.id.slice(0,8)}</code></td><td>{new Date(i.createdAt).toLocaleString('vi-VN')}</td><td className="record-wrap"><code>{i.customerId||'—'}</code></td><td title={i.bookingId||''}><code>{i.bookingId?.slice(0,8)||"—"}</code></td><td>{i.bookingId?"Đặt sân":"Bán lẻ"}</td><td>{i.paymentMethods?.map(m=>methods[m]||m).join(' + ')||'—'}</td><td><span className={`badge ${st.badge}`}>{st.label}</span></td><td className="record-number"><strong>{money(i.totalAmount)}</strong></td><td><button className="secondary" onClick={()=>setSelected(i.id)}>Chi tiết</button></td>
    </tr>;
   })}</tbody></table></div>
   <div className="toolbar"><button className="secondary" disabled={page===0} onClick={()=>setPage(v=>v-1)}>Trang trước</button><span>Trang {page+1} / {history.totalPages}</span><button className="secondary" disabled={page+1>=history.totalPages} onClick={()=>setPage(v=>v+1)}>Trang sau</button></div>
  </>}
  {selected&&<div className="overlay"><section className="dialog invoice-report invoice-detail" role="dialog" aria-modal="true" aria-labelledby="invoice-report-title" tabIndex={-1} ref={e=>{if(e&&!e.contains(document.activeElement))e.focus()}}>
   <div className="dialog-head"><h2 id="invoice-report-title">Chi tiết hóa đơn</h2><button aria-label="Đóng chi tiết hóa đơn" onClick={()=>setSelected(null)}>✕</button></div>
   {detailError?<p className="alert error" role="alert">{detailError}</p>:!detail?<p role="status">Đang tải chi tiết…</p>:<>
    <dl className="invoice-detail-meta">
     <div className="invoice-meta-col">
      <div><dt>Mã hóa đơn</dt><dd><div className="invoice-code-row"><code className="clickable-code" title={`Sao chép UUID đầy đủ: ${invoice.id}`} onClick={()=>navigator.clipboard?.writeText(invoice.id)}>#{invoice.id.slice(0,8)}</code><span className="copy-hint" title={invoice.id} onClick={()=>navigator.clipboard?.writeText(invoice.id)}>(Nhấp để sao chép)</span></div></dd></div>
      <div><dt>Thời gian</dt><dd>{new Date(invoice.createdAt).toLocaleString('vi-VN')}</dd></div>
      <div><dt>Đơn đặt sân</dt><dd>{invoice.bookingId?<div className="invoice-code-row"><code className="clickable-code" title={`Sao chép UUID đầy đủ: ${invoice.bookingId}`} onClick={()=>navigator.clipboard?.writeText(invoice.bookingId)}>#{invoice.bookingId.slice(0,8)}</code><span className="copy-hint" title={invoice.bookingId} onClick={()=>navigator.clipboard?.writeText(invoice.bookingId)}>(Nhấp để sao chép)</span></div>:'Khách mua hàng riêng · Không kèm sân'}</dd></div>
      <div className="invoice-detail-actor"><dt>Người lập hóa đơn</dt><dd><strong>{detail.creatorName||(detail.createdBy?'Nhân viên hệ thống':'Tự động / Đặt sân online')}</strong>{detail.createdBy?<small title={`Mã NV đầy đủ: ${detail.createdBy}`}>Mã NV: <code>{detail.createdBy.slice(0,8)}</code></small>:null}</dd></div>
     </div>
     <div className="invoice-meta-col">
      <div className="invoice-detail-customer"><dt>Khách hàng</dt><dd><strong>{invoice.customerName}</strong><span className="invoice-detail-phone">{invoice.customerPhone?`SĐT: ${invoice.customerPhone}`:''}</span><span className="invoice-detail-email">{invoice.customerEmail?`Email: ${invoice.customerEmail}`:'Chưa có email'}</span></dd></div>
      <div><dt>Phương thức thanh toán</dt><dd>{invoice.paymentMethods?.map(m=>methods[m]||m).join(' + ')||'—'}</dd></div>
      <div><dt>Trạng thái</dt><dd>{(() => {
       const st = invoiceStatuses[invoice.invoiceStatus] || { label: invoice.invoiceStatus || '—', badge: 'neutral' };
       return <><span className={`badge ${st.badge}`}>{st.label}</span>{invoice.bookingId&&<small>Booking: {bookingStatusLabels[invoice.bookingStatus]||invoice.bookingStatus||'Không có dữ liệu'}</small>}</>;
      })()}</dd></div>
     </div>
    </dl>
    <h3>Các khoản thanh toán</h3>{detail.receipts?.length?<div className="table-scroll invoice-table-card"><table className="invoice-receipt-table"><thead><tr><th>Nguồn</th><th>Phương thức</th><th>Trạng thái giao dịch</th><th className="invoice-detail-money">Số tiền</th><th>Người xác nhận</th><th>Thời điểm</th></tr></thead><tbody>{detail.receipts.map(p=>{
     const tSt = txnStatuses[p.status] || { label: p.status, badge: 'neutral' };
     return <tr key={p.id}>
      <td>{formatReceiptSource(p.target)}</td>
      <td>{methods[p.method]||p.method}</td>
      <td><span className={`badge ${tSt.badge}`}>{tSt.label}</span></td>
      <td className="invoice-detail-money">{money(p.amount)}</td>
      <td><strong>{p.actorName}</strong>{p.confirmedBy?<small title={`Mã NV đầy đủ: ${p.confirmedBy}`}>Mã NV: <code>{p.confirmedBy.slice(0,8)}</code></small>:null}</td>
      <td>{p.confirmedAt?new Date(p.confirmedAt).toLocaleString('vi-VN'):'—'}</td>
     </tr>;
    })}</tbody></table></div>:<p className="muted">Không có khoản thu (hóa đơn giá trị 0) hoặc chưa phát sinh thanh toán.</p>}
    <h3>Tiền sân</h3>{detail.courts.length?<div className="table-scroll invoice-table-card"><table className="invoice-court-table"><thead><tr><th>Sân</th><th>Ngày</th><th>Khung giờ</th><th className="invoice-detail-money">Giá đã lưu</th></tr></thead><tbody>{detail.courts.map((c,i)=><tr key={i}><td>{c.name}</td><td>{c.date}</td><td>{c.startTime.slice(0,5)}–{c.endTime.slice(0,5)}</td><td className="invoice-detail-money">{money(c.price)}</td></tr>)}</tbody></table></div>:<p className="muted">{invoice.bookingId?'Không có chi tiết khung giờ được lưu.':'Không thuê sân.'}</p>}
    <h3>Hàng hóa & dịch vụ</h3>{detail.items.length?<div className="table-scroll invoice-table-card"><table className="invoice-item-table"><thead><tr><th>Mặt hàng</th><th className="invoice-detail-qty">SL</th><th className="invoice-detail-money">Đơn giá</th><th className="invoice-detail-money">Giảm giá</th><th className="invoice-detail-money">Thành tiền</th></tr></thead><tbody>{detail.items.map((l,i)=><tr key={i}><td>{l.name}{l.promotionName&&<small className="promotion-applied">{l.promotionName}</small>}</td><td className="invoice-detail-qty">{l.quantity}</td><td className="invoice-detail-money">{money(l.unitPrice)}</td><td className="invoice-detail-money">{money(l.discountAmount)}</td><td className="invoice-detail-money">{money(l.totalAmount)}</td></tr>)}</tbody></table></div>:<p className="muted">Không có mặt hàng kèm theo.</p>}
    <div className="invoice-summary-block"><div className="invoice-row"><span>Tiền sân:</span><strong>{money(invoice.courtFee)}</strong></div><div className="invoice-row"><span>Tiền hàng:</span><strong>{money(invoice.productFee)}</strong></div><div className="invoice-row"><span>Giảm giá</span><strong>−{money(invoice.discountAmount)}</strong></div><div className="pos-summary-total"><span>Tổng hóa đơn</span><strong>{money(invoice.totalAmount)}</strong></div></div>
   </>}
   <div className="dialog-foot"><button className="secondary" onClick={()=>setSelected(null)}>Đóng</button></div>
  </section></div>}
 </section>;
}
