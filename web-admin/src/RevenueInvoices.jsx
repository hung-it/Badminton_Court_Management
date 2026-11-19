import {InlinePanel,MainSubArea,rowProps} from './MainSub.jsx';
import React,{useEffect,useState} from 'react';
const money=n=>new Intl.NumberFormat('vi-VN',{style:'currency',currency:'VND'}).format(n||0);
const methods={CASH:'Tiền mặt',VNPAY:'VNPay'};

export function formatPaymentMethod(receiptsOrMethods, fallbackMethod) {
 const set = new Set();
 if (Array.isArray(receiptsOrMethods)) {
  receiptsOrMethods.forEach(r => {
   if (typeof r === 'string') {
    const up = r.toUpperCase();
    if (up === 'VNPAY' || up === 'CASH') set.add(up);
   } else if (r && typeof r === 'object') {
    if (r.status === 'SUCCESS' && (r.amount === null || r.amount === undefined || Number(r.amount) > 0)) {
     const up = (r.method || '').toUpperCase();
     if (up === 'VNPAY' || up === 'CASH') set.add(up);
    }
   }
  });
 }
 if (set.size === 0 && fallbackMethod) {
  const up = String(fallbackMethod).toUpperCase();
  if (up === 'VNPAY' || up === 'CASH') set.add(up);
 }
 const hasVnPay = set.has('VNPAY');
 const hasCash = set.has('CASH');
 if (hasVnPay && hasCash) return 'VNPay + Tiền mặt';
 if (hasVnPay) return 'VNPay';
 if (hasCash) return 'Tiền mặt';
 return '—';
}

const invoiceStatuses = {
 PAID: { label: 'Đã thanh toán', badge: 'AVAILABLE' },
 DRAFT: { label: 'Chờ thanh toán', badge: 'MAINTENANCE' },
 CANCELLED: { label: 'Đã hủy', badge: 'CLOSED' },
 REFUNDED: { label: 'Đã hoàn tiền', badge: 'neutral' }
};

function resolveCourtLines(detail) {
 if (!detail || !detail.courts || !detail.courts.length) return [];
 const invoice = detail.invoice || {};
 const courts = detail.courts;

 const hasExplicitAmounts = courts.every(
  c => c.discountAmount !== null && c.discountAmount !== undefined &&
       c.totalAmount !== null && c.totalAmount !== undefined
 );
 if (hasExplicitAmounts) {
  return courts.map(c => ({
   ...c,
   price: Number(c.price) || 0,
   discountAmount: Number(c.discountAmount) || 0,
   totalAmount: Number(c.totalAmount) || 0
  }));
 }

 const prices = courts.map(c => Number(c.price) || 0);
 const totalBase = prices.reduce((sum, p) => sum + p, 0);
 if (totalBase <= 0) {
  return courts.map(c => ({
   ...c,
   price: Number(c.price) || 0,
   discountAmount: 0,
   totalAmount: Number(c.price) || 0
  }));
 }

 const itemsNet = (detail.items || []).reduce((sum, it) => {
  if (it.totalAmount !== null && it.totalAmount !== undefined) {
   return sum + (Number(it.totalAmount) || 0);
  }
  const gross = (Number(it.quantity) || 0) * (Number(it.unitPrice) || 0);
  return sum + (gross - (Number(it.discountAmount) || 0));
 }, 0);

 let totalCourtDiscount = 0;
 if (invoice.courtDiscount !== null && invoice.courtDiscount !== undefined) {
  totalCourtDiscount = Number(invoice.courtDiscount) || 0;
 } else if (invoice.totalAmount !== null && invoice.totalAmount !== undefined) {
  const invoiceTotal = Number(invoice.totalAmount) || 0;
  const courtFinal = invoiceTotal - itemsNet;
  const calculatedCourtDiscount = totalBase - courtFinal;
  totalCourtDiscount = Math.max(0, Math.min(totalBase, Math.round(calculatedCourtDiscount)));
 }

 if (totalCourtDiscount <= 0) {
  return courts.map(c => ({
   ...c,
   price: Number(c.price) || 0,
   discountAmount: 0,
   totalAmount: Number(c.price) || 0
  }));
 }

 let accumulatedDiscount = 0;
 return courts.map((c, i) => {
  const price = prices[i];
  let discount = 0;
  if (i === courts.length - 1) {
   discount = totalCourtDiscount - accumulatedDiscount;
  } else {
   discount = Math.round((totalCourtDiscount * price) / totalBase);
   accumulatedDiscount += discount;
  }
  const total = price - discount;
  return {
   ...c,
   price,
   discountAmount: discount,
   totalAmount: total
  };
 });
}

export default function RevenueInvoices({api,params="",openId,customer,onClearCustomer}){
 const [search,setSearch]=useState(''),[method,setMethod]=useState(''),[status,setStatus]=useState(''),[from,setFrom]=useState(''),[to,setTo]=useState('');
 const [page,setPage]=useState(0),[refresh,setRefresh]=useState(0),[history,setHistory]=useState(null),[busy,setBusy]=useState(true),[error,setError]=useState('');
 const [selected,setSelected]=useState(null),[detail,setDetail]=useState(null),[detailError,setDetailError]=useState('');
 useEffect(()=>{let active=true;setBusy(true);setError('');setHistory(null);
  const invoiceStatus = customer?.id ? 'PAID' : status;
  const queryParams = new URLSearchParams({page,search,method,...(invoiceStatus?{status:invoiceStatus}:{}),...(customer?.id?{customerId:customer.id}:{})});
  api('/analytics/invoices?'+(from&&to?'from='+from+'&to='+to:params)+'&'+queryParams).then(v=>{if(active)setHistory(v)}).catch(e=>{if(active)setError(e.message)}).finally(()=>{if(active)setBusy(false)});
  return()=>{active=false};
 },[params,page,refresh,search,method,status,from,to,customer?.id]);
 useEffect(()=>{let active=true;setDetail(null);setDetailError('');if(!selected)return;
  api('/analytics/invoices/'+selected).then(v=>{if(active)setDetail(v)}).catch(e=>{if(active)setDetailError(e.message)});
  const escape=e=>{if(e.key==='Escape')setSelected(null)};document.addEventListener('keydown',escape);
  return()=>{active=false;document.removeEventListener('keydown',escape)};
 },[selected]);
 const invoice=detail?.invoice;
 return <div className="invoices-page">
  <div className="toolbar record-heading"><h3>Lịch sử hóa đơn</h3><span className="count">{history?.totalElements??0} hóa đơn</span><button className="secondary" disabled={busy} onClick={()=>setRefresh(v=>v+1)}>Làm mới</button></div>
  {customer&&<div className="toolbar" role="status"><span>Hóa đơn đã thanh toán · {customer.name}</span><button className="secondary" onClick={onClearCustomer}>Bỏ lọc khách hàng</button></div>}
  <div className="toolbar record-filters">
   <label className="field">Từ ngày<input aria-label="Từ ngày" type="date" value={from} onChange={e=>{setFrom(e.target.value);setPage(0)}}/></label>
   <label className="field">Đến ngày<input aria-label="Đến ngày" type="date" min={from} value={to} onChange={e=>{setTo(e.target.value);setPage(0)}}/></label>
   <label className="field">Tìm hóa đơn<input aria-label="Tìm hóa đơn" placeholder="Mã hóa đơn, khách, booking" value={search} onChange={e=>{setSearch(e.target.value);setPage(0)}}/></label>
   <label className="field">Phương thức<select aria-label="Phương thức" value={method} onChange={e=>{setMethod(e.target.value);setPage(0)}}><option value="">Mọi phương thức</option>{Object.entries(methods).map(([k,v])=><option key={k} value={k}>{v}</option>)}</select></label>
   <label className="field">Trạng thái<select aria-label="Trạng thái" disabled={!!customer?.id} value={customer?.id?'PAID':status} onChange={e=>{setStatus(e.target.value);setPage(0)}}><option value="">Tất cả trạng thái</option><option value="PAID">Đã thanh toán</option><option value="DRAFT">Chờ thanh toán</option><option value="CANCELLED">Đã hủy</option></select></label>
  </div>
  <MainSubArea>
   <section className="content-panel admin-records invoices-panel">
    {busy?<p className="empty" role="status">Đang tải hóa đơn…</p>:error?<p className="alert error" role="alert">{error}</p>:!history?.invoices.length?<p className="empty">Không có hóa đơn phù hợp.</p>:<>
     <div className="table-scroll"><table><thead><tr><th>Mã hóa đơn</th><th>Thời gian</th><th>Thanh toán</th><th>Trạng thái</th><th>Giảm giá cho hóa đơn</th><th>Tổng hóa đơn</th></tr></thead><tbody>{history.invoices.map(i=>{
      const st = invoiceStatuses[i.invoiceStatus] || { label: i.invoiceStatus || '—', badge: 'neutral' };
      return <tr key={i.id} {...rowProps(selected===i.id,()=>setSelected(i.id))}>
       <td title={i.id}><code>{i.id.slice(0,8)}</code></td><td>{new Date(i.createdAt).toLocaleString('vi-VN')}</td><td>{formatPaymentMethod(i.paymentMethods, i.paymentMethod)}</td><td><span className={`badge ${st.badge}`}>{st.label}</span></td><td className="record-number">{money(i.discountAmount)}</td><td className="record-number"><strong>{money(i.totalAmount)}</strong></td>
      </tr>;
     })}</tbody></table></div>
     <div className="toolbar"><button className="secondary" disabled={page===0} onClick={()=>setPage(v=>v-1)}>Trang trước</button><span>Trang {page+1} / {history.totalPages}</span><button className="secondary" disabled={page+1>=history.totalPages} onClick={()=>setPage(v=>v+1)}>Trang sau</button></div>
    </>}
   </section>
  </MainSubArea>
  {selected&&<InlinePanel onClose={()=>setSelected(null)} valid={!history||history.invoices.some(i=>i.id===selected)} panelKey={selected}><section className="dialog invoice-report invoice-detail" role="region" aria-labelledby="invoice-report-title" tabIndex={-1}>
   <div className="dialog-head"><h2 id="invoice-report-title">Chi tiết hóa đơn</h2><button aria-label="Đóng chi tiết hóa đơn" onClick={()=>setSelected(null)}>✕</button></div>
   {detailError?<p className="alert error" role="alert">{detailError}</p>:!detail?<p role="status">Đang tải chi tiết…</p>:(() => {
    const resolvedCourts = resolveCourtLines(detail);
    return <>
     <h3>Tiền sân</h3>{resolvedCourts.length?<div className="table-scroll invoice-table-card"><table className="invoice-court-table"><thead><tr><th>Sân</th><th>Khung giờ</th><th>Ngày</th><th>Đơn giá</th><th>Giảm giá</th><th>Thành tiền</th></tr></thead><tbody>{resolvedCourts.map((c,i)=><tr key={i}><td>{c.name}</td><td>{c.startTime.slice(0,5)}–{c.endTime.slice(0,5)}</td><td>{c.date}</td><td className="invoice-detail-money">{money(c.price)}</td><td className="invoice-detail-money">{money(c.discountAmount)}</td><td className="invoice-detail-money">{money(c.totalAmount)}</td></tr>)}</tbody></table></div>:<p className="muted">{invoice.bookingId?'Không có chi tiết khung giờ được lưu.':'Không thuê sân.'}</p>}
     <h3>Hàng hóa & dịch vụ</h3>{detail.items.length?<div className="table-scroll invoice-table-card"><table className="invoice-item-table"><thead><tr><th>Mặt hàng</th><th className="invoice-detail-qty">SL</th><th className="invoice-detail-money">Đơn giá</th><th className="invoice-detail-money">Giảm giá</th><th className="invoice-detail-money">Thành tiền</th></tr></thead><tbody>{detail.items.map((l,i)=><tr key={i}><td>{l.name}{l.promotionName&&<small className="promotion-applied">{l.promotionName}</small>}</td><td className="invoice-detail-qty">{l.quantity}</td><td className="invoice-detail-money">{money(l.unitPrice)}</td><td className="invoice-detail-money">{money(l.discountAmount)}</td><td className="invoice-detail-money">{money(l.totalAmount)}</td></tr>)}</tbody></table></div>:<p className="muted">Không có mặt hàng kèm theo.</p>}
     <h3>Thông tin chung</h3>
     <dl className="invoice-detail-meta">
      <div className="invoice-meta-col">
       <div><dt>Mã hóa đơn</dt><dd><div className="invoice-code-row"><code className="clickable-code" title={`Sao chép UUID đầy đủ: ${invoice.id}`} onClick={()=>navigator.clipboard?.writeText(invoice.id)}>#{invoice.id.slice(0,8)}</code><span className="copy-hint" title={invoice.id} onClick={()=>navigator.clipboard?.writeText(invoice.id)}>(Sao chép)</span></div></dd></div>
       <div><dt>Thời gian</dt><dd>{new Date(invoice.createdAt).toLocaleString('vi-VN')}</dd></div>
       <div><dt>Đơn đặt sân</dt><dd>{invoice.bookingId?<div className="invoice-code-row"><code className="clickable-code" title={`Sao chép UUID đầy đủ: ${invoice.bookingId}`} onClick={()=>navigator.clipboard?.writeText(invoice.bookingId)}>#{invoice.bookingId.slice(0,8)}</code><span className="copy-hint" title={invoice.bookingId} onClick={()=>navigator.clipboard?.writeText(invoice.bookingId)}>(Sao chép)</span></div>:'Khách mua hàng riêng · Không kèm sân'}</dd></div>
       <div className="invoice-detail-actor"><dt>Người lập hóa đơn</dt><dd><strong>{detail.creatorName||(detail.createdBy?'Nhân viên hệ thống':'Tự động / Đặt sân online')}</strong>{detail.createdBy?<small title={`Mã NV đầy đủ: ${detail.createdBy}`}>Mã NV: <code>#{detail.createdBy.slice(0,8)}</code></small>:null}</dd></div>
      </div>
      <div className="invoice-meta-col">
       <div className="invoice-detail-customer"><dt>Khách hàng</dt><dd><strong>{invoice.customerName}</strong><span className="invoice-detail-phone">{invoice.customerPhone?`SĐT: ${invoice.customerPhone}`:''}</span><span className="invoice-detail-email">{invoice.customerEmail?`Email: ${invoice.customerEmail}`:'Chưa có email'}</span></dd></div>
       <div><dt>Phương thức thanh toán</dt><dd>{formatPaymentMethod(detail.receipts?.length ? detail.receipts : invoice.paymentMethods, invoice.paymentMethod)}</dd></div>
       <div><dt>Trạng thái</dt><dd>{(() => {
        const st = invoiceStatuses[invoice.invoiceStatus] || { label: invoice.invoiceStatus || '—', badge: 'neutral' };
        return <span className={`badge ${st.badge}`}>{st.label}</span>;
       })()}</dd></div>
      </div>
     </dl>
    </>;
   })()}

  </section></InlinePanel>}
 </div>;
}
